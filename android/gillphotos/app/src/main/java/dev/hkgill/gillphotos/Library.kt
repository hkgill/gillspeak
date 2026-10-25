package dev.hkgill.gillphotos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** The phone's photos, through MediaStore: which there are, and pictures of them at the sizes the models want. */
object Library {
    class Photo(val id: Long, val taken: Long, val modified: Long, val screenshot: Boolean)

    enum class Access { ALL, SOME, NONE }

    private val COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun uri(id: Long): Uri = ContentUris.withAppendedId(COLLECTION, id)

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** All photos, some (Android 14+ "Select photos"), or none. */
    fun access(context: Context): Access {
        fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            has(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.ALL
            Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.SOME
            else -> Access.NONE
        }
    }

    /** Every photo gillphotos may see, newest first. */
    fun all(context: Context): List<Photo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.RELATIVE_PATH,
        )
        val photos = ArrayList<Photo>()
        context.contentResolver.query(COLLECTION, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                val path = c.getString(4) ?: ""
                photos += Photo(c.getLong(0), taken, c.getLong(3), path.contains("screenshot", ignoreCase = true))
            }
        }
        return photos.sortedByDescending { it.taken }
    }

    /** The photo the right way up, at most [max] pixels on its longer side, in memory the CPU can read. */
    fun picture(context: Context, id: Long, max: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri(id))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = max.toFloat() / maxOf(w, h)
            if (scale < 1) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }

    fun thumbnail(context: Context, id: Long, px: Int): Bitmap = context.contentResolver.loadThumbnail(uri(id), Size(px, px), null)

    fun argb(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    fun jpeg(bitmap: Bitmap, quality: Int = 90): ByteArray =
        ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }
}
