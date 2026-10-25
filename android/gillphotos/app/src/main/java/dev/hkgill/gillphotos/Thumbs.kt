package dev.hkgill.gillphotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Loads photo thumbnails and face crops off the main thread, with a memory cache. */
class Thumbs(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun photo(view: ImageView, id: Long, px: Int) = load(view, "p$id") { Library.thumbnail(context, id, px) }

    /** A square crop round [face], with room for hair and chin. */
    fun face(view: ImageView, face: Store.FaceRow, px: Int) = load(view, "f${face.id}") {
        val picture = Library.picture(context, face.photo, 640)
        val w = picture.width
        val h = picture.height
        val side = (max(face.w * w, face.h * h) * 1.6f).toInt().coerceIn(1, min(w, h))
        val left = ((face.x + face.w / 2) * w - side / 2).toInt().coerceIn(0, w - side)
        val top = ((face.y + face.h / 2) * h - side / 2).toInt().coerceIn(0, h - side)
        Bitmap.createScaledBitmap(Bitmap.createBitmap(picture, left, top, side, side), px, px, true)
    }

    private fun load(view: ImageView, key: String, make: () -> Bitmap) {
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        pool.execute {
            if (view.tag != key) return@execute // scrolled away
            val bitmap = runCatching(make).getOrNull() ?: return@execute
            cache.put(key, bitmap)
            main.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    fun close() {
        pool.shutdownNow()
    }
}

/** A grid cell: as tall as it is wide. */
class SquareImage(context: Context) : ImageView(context) {
    init {
        scaleType = ScaleType.CENTER_CROP
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}

/** Clips a view to a circle. */
fun View.round() {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
    }
    clipToOutline = true
}
