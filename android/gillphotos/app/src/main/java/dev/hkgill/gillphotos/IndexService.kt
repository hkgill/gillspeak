package dev.hkgill.gillphotos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.hkgill.gillphotos.core.People
import dev.hkgill.gillphotos.core.VectorIndex
import dev.hkgill.gillphotos.core.YuNet
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * Indexes the library in the foreground, with a progress notification, so it carries on with the screen off: each
 * photo's EmbeddingGemma vector and its faces, newest first, then groups the faces into people. It stops when done,
 * when asked, or at Android's six-hour limit for this kind of service; what's done stays done. It also measures:
 * the milliseconds per photo for decoding, EmbeddingGemma and faces are the numbers the research needs.
 */
class IndexService : Service() {
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val decodeMs: Long = 0,
        val embedMs: Long = 0,
        val faceMs: Long = 0,
        val placeMs: Long = 0,
        val note: String = "",
    ) {
        fun timing() = if (done == 0) "" else
            "${(decodeMs + embedMs + faceMs + placeMs) / done} ms a photo (picture ${decodeMs / done}, meaning ${embedMs / done}, " +
                "faces ${faceMs / done}, place ${placeMs / done})"
    }

    @Volatile private var stopping = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTE, notification("Getting ready…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker == null) worker = thread(name = "index") { work() }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
    }

    override fun onDestroy() {
        stopping = true
        super.onDestroy()
    }

    private fun work() {
        val store = Store.get(this)
        var p = Progress(running = true, note = "Reading the library…")
        publish(p)
        try {
            store.sync(Library.all(this))
            var vectors = Embedder.isReady(this)
            val locating = Library.canReadLocation(this)
            val todo = store.pending(vectors, locating)
            p = p.copy(total = todo.size, note = "")
            val faces = FaceKit.get(this)
            val places = if (locating) PlaceKit.get(this) else null
            val power = getSystemService(PowerManager::class.java)
            var embedFailures = 0
            var lastNote = 0L
            for (id in todo) {
                if (stopping) break
                while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE && !stopping) {
                    publish(p.copy(note = "Paused: the phone is hot"))
                    Thread.sleep(30_000)
                }
                val needs = store.needs(id)
                val needVector = needs.vector
                val needFaces = needs.faces
                try {
                    val tp = SystemClock.uptimeMillis()
                    if (needs.place && places != null) {
                        // EXIF only, no picture: where (nearest named place, offline) and which camera.
                        val exif = runCatching { Library.exif(this, id) }.getOrNull()
                        val near = if (exif?.lat != null && exif.lon != null) places.nearest(exif.lat, exif.lon) else null
                        store.saveDetails(id, exif?.lat, exif?.lon, near?.name, near?.country, exif?.camera)
                    }
                    p = p.copy(placeMs = p.placeMs + SystemClock.uptimeMillis() - tp)
                    if (!(needVector && vectors) && !needFaces) {
                        p = p.copy(done = p.done + 1)
                        continue
                    }
                    val t0 = SystemClock.uptimeMillis()
                    val picture = Library.picture(this, id, YuNet.SIZE)
                    val t1 = SystemClock.uptimeMillis()
                    if (needVector && vectors) {
                        try {
                            val (bytes, scale) = VectorIndex.quantize(Embedder.image(this, Library.jpeg(picture)))
                            store.saveVector(id, bytes, scale)
                            Search.added(id, bytes, scale)
                            embedFailures = 0
                        } catch (e: Exception) {
                            // The model, not the photo: after a few in a row, carry on with faces only.
                            Log.w(TAG, "index: EmbeddingGemma failed on $id", e)
                            if (++embedFailures >= 3) {
                                vectors = false
                                p = p.copy(note = "Search model failed (${e.message}): faces only for now")
                            }
                        }
                    }
                    val t2 = SystemClock.uptimeMillis()
                    if (needFaces) store.saveFaces(id, picture.width, picture.height, faces.find(Library.argb(picture), picture.width, picture.height))
                    val t3 = SystemClock.uptimeMillis()
                    picture.recycle()
                    p = p.copy(done = p.done + 1, decodeMs = p.decodeMs + t1 - t0, embedMs = p.embedMs + t2 - t1, faceMs = p.faceMs + t3 - t2)
                } catch (e: Exception) {
                    Log.w(TAG, "index: skipping photo $id", e)
                    store.fail(id)
                    p = p.copy(done = p.done + 1)
                }
                // People show up while indexing runs, not only at the end of hours of work.
                if (p.done % GROUP_EVERY == 0) {
                    p = p.copy(note = group(store))
                    lastNote = 0
                }
                if (p.done % 100 == 0) Log.i(TAG, "index: ${p.done} of ${p.total}; ${p.timing()}; embedder on ${Embedder.backend}")
                if (SystemClock.uptimeMillis() - lastNote > 1000) {
                    lastNote = SystemClock.uptimeMillis()
                    publish(p)
                }
            }
            Log.i(TAG, "index: ${p.done} photos; ${p.timing()}; embedder on ${Embedder.backend}")
            if (!stopping) {
                publish(p.copy(note = "Grouping faces…"))
                p = p.copy(note = group(store))
                setWanted(this, false) // finished: nothing to pick up again
            }
        } catch (e: Exception) {
            Log.e(TAG, "index failed", e)
            p = p.copy(note = "Indexing stopped: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            publish(p.copy(running = false))
            worker = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTE)
            stopSelf()
        }
    }

    /** Groups every face found so far into people; returns a line for the status. */
    private fun group(store: Store): String {
        val t0 = SystemClock.uptimeMillis()
        val faceList = store.facesToGroup()
        val grouping = People.group(faceList)
        store.saveGrouping(grouping)
        return "${faceList.size} faces in ${grouping.groups.size} groups (${SystemClock.uptimeMillis() - t0} ms)".also { Log.i(TAG, "index: $it") }
    }

    private fun publish(p: Progress) {
        progress = p
        listeners.forEach { it() }
        // Not once stopping: the service is going, and a late notification would stay behind.
        if (p.running && !stopping) getSystemService(NotificationManager::class.java).notify(NOTE, notification(p.note.ifEmpty { "Indexing photos" }, p.done, p.total))
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Indexing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(if (total > 0) "Indexing photos: $done of $total" else "Indexing photos")
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "index"
        private const val NOTE = 1

        @Volatile var progress = Progress()
            private set
        /** Called on the indexing thread when [progress] changes. */
        val listeners = CopyOnWriteArraySet<() -> Unit>()

        private const val GROUP_EVERY = 300

        fun start(context: Context) {
            setWanted(context, true)
            context.startForegroundService(Intent(context, IndexService::class.java))
        }

        /** The user's Stop: unlike an app update or Android's time limit, this one isn't picked up again. */
        fun stop(context: Context) {
            setWanted(context, false)
            context.stopService(Intent(context, IndexService::class.java))
        }

        /** Indexing that was cut short (an app update, Android's limit) and should carry on when the app opens. */
        fun wanted(context: Context) = prefs(context).getBoolean("wanted", false)
        private fun setWanted(context: Context, on: Boolean) = prefs(context).edit().putBoolean("wanted", on).apply()
        private fun prefs(context: Context) = context.getSharedPreferences("index", Context.MODE_PRIVATE)
    }
}
