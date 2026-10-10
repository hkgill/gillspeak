package dev.hkgill.gillspeak

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import android.view.KeyEvent

/**
 * What a [Command] turns into on this phone: a card to show, and the intent or system call that carries it out.
 * Everything goes through Android's standard intents (the user's own Clock, Messages and Phone apps do the work),
 * so gillspeak needs no permission to send texts or place calls: those apps open with everything filled in.
 */
class Actions(private val context: Context) {
    /**
     * [confirm] is the button label when the action waits for a tap (texts and calls); null runs it straight away.
     * [run] returns false when nothing on the phone could handle it.
     */
    class Plan(
        val title: String,
        val detail: String,
        val body: String? = null,
        val confirm: String? = null,
        val opensApp: Boolean = false,
        val run: () -> Boolean,
    ) {
        /** The same plan, but waiting for a tap on [label] if it would otherwise run straight away. */
        fun asking(label: String) = Plan(title, detail, body, confirm ?: label, opensApp, run)
    }

    sealed interface Outcome {
        data class Ready(val plan: Plan) : Outcome
        /** Can't go ahead: [title] and [detail] say why. [askContacts] offers to allow contacts access. */
        data class Problem(val title: String, val detail: String, val askContacts: Boolean = false) : Outcome
    }

    data class App(val label: String, val component: ComponentName)
    data class Contact(val name: String, val number: String)

    fun plan(command: Command): Outcome = when (command) {
        is Command.OpenApp -> {
            val apps = apps()
            val i = Commands.bestMatch(command.name, apps.map { it.label })
            if (i == null) Outcome.Problem("No app called “${command.name}”", "Say its name as it appears under its icon")
            else apps[i].let { app ->
                ready(Plan("Open ${app.label}", "App on this phone", opensApp = true) {
                    start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
                        .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                })
            }
        }
        is Command.Timer -> ready(Plan("Timer", "Clock · starts now", body = clock(command.seconds)) {
            start(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        })
        is Command.Alarm -> {
            val (hour, minute) = if (command.exact) command.hour to command.minute
            else java.time.LocalTime.now().let { Commands.nextOccurrence(command.hour, command.minute, it.hour * 60 + it.minute) }
            ready(Plan("Alarm", "Clock", body = timeOfDay(hour, minute)) {
            start(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            })
        }
        is Command.Torch -> ready(Plan(if (command.on) "Torch on" else "Torch off", "Flashlight") { torch(command.on) })
        is Command.Media -> {
            val (title, key) = when (command.action) {
                MediaAction.PLAY -> "Play" to KeyEvent.KEYCODE_MEDIA_PLAY
                MediaAction.PAUSE -> "Pause" to KeyEvent.KEYCODE_MEDIA_PAUSE
                MediaAction.NEXT -> "Next track" to KeyEvent.KEYCODE_MEDIA_NEXT
                MediaAction.PREVIOUS -> "Previous track" to KeyEvent.KEYCODE_MEDIA_PREVIOUS
            }
            ready(Plan(title, "Whatever is playing") { media(key) })
        }
        is Command.Text -> withContacts { contacts ->
            val split = Commands.splitRecipient(command.target) { find(contacts, it) != null }
            val who = split?.let { find(contacts, it.first) }
            when {
                split == null || who == null -> Outcome.Problem("Who should get this?", "No contact matches “${command.target.substringBefore(' ')}”")
                split.second.isBlank() -> Outcome.Problem("What should it say?", "Try “text ${who.name} I'm on my way”")
                else -> ready(Plan("Message ${who.name}", "Messages · ${who.number}", body = split.second, confirm = "Open in Messages", opensApp = true) {
                    start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(who.number))).putExtra("sms_body", split.second))
                })
            }
        }
        is Command.Call -> withContacts { contacts ->
            val who = find(contacts, command.who)
            if (who == null) Outcome.Problem("No contact called “${command.who}”", "Check how they're saved in Contacts")
            else ready(Plan("Call ${who.name}", "Phone · ${who.number}", confirm = "Open in Phone", opensApp = true) {
                start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(who.number))))
            })
        }
        is Command.Navigate -> ready(Plan("Directions", "Maps", body = command.place, opensApp = true) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(command.place)))) ||
                start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(command.place))))
        })
        is Command.Search -> ready(search(command.query))
    }

    /** For speech that isn't a command: offered as a web search, never run without a tap. */
    fun searchInstead(text: String) = search(text).let {
        Plan("Not a command I know", "Tap to search the web for it", body = text, confirm = "Search the web", opensApp = true, run = it.run)
    }

    private fun search(query: String) = Plan("Search the web", "Browser", body = query, opensApp = true) {
        start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
    }

    private fun ready(plan: Plan) = Outcome.Ready(plan)

    // ---- Apps ----

    /** Every app with an icon in the app drawer. Needs the launcher <queries> entry in the manifest (Android 11+). */
    fun apps(): List<App> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { App(it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name)) }
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(Dictation.TAG, "command: nothing handles ${intent.action}")
        false
    } catch (e: SecurityException) {
        Log.w(Dictation.TAG, "command: not allowed to start ${intent.action}", e)
        false
    }

    // ---- System ----

    private fun torch(on: Boolean): Boolean = runCatching {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return false
        cm.setTorchMode(id, on)
        true
    }.getOrElse { Log.w(Dictation.TAG, "command: torch failed", it); false }

    private fun media(key: Int): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
        return true
    }

    // ---- Contacts ----

    fun hasContacts() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun withContacts(then: (List<Contact>) -> Outcome): Outcome =
        if (!hasContacts()) Outcome.Problem("Allow contacts first", "gillspeak looks names up on this phone to text or call them", askContacts = true)
        else then(contacts())

    /** Every contact with a phone number, mobile numbers first. Read on this phone only; nothing is kept. */
    private fun contacts(): List<Contact> {
        val out = mutableListOf<Pair<Contact, Boolean>>()
        context.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.IS_SUPER_PRIMARY), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val preferred = c.getInt(3) != 0 || c.getInt(2) == Phone.TYPE_MOBILE
                out += Contact(name, number) to preferred
            }
        }
        return out.sortedByDescending { it.second }.map { it.first }
    }

    /** A contact whose full name or first name is [spoken]. */
    private fun find(contacts: List<Contact>, spoken: String): Contact? {
        val q = Commands.normalise(spoken)
        if (q.isEmpty()) return null
        return contacts.firstOrNull { Commands.normalise(it.name) == q }
            ?: contacts.firstOrNull { Commands.normalise(it.name).substringBefore(' ') == q }
    }

    companion object {
        fun clock(seconds: Int): String {
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        fun timeOfDay(hour: Int, minute: Int): String {
            val h12 = if (hour % 12 == 0) 12 else hour % 12
            return "%d:%02d %s".format(h12, minute, if (hour < 12) "am" else "pm")
        }
    }
}
