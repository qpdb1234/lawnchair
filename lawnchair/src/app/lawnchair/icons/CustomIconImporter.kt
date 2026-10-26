package app.lawnchair.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Size
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports an arbitrary image picked by the user (from the gallery, a file
 * manager or any other app that exposes images) and converts it into an
 * [IconPickerItem] backed by a normalized square PNG stored on disk.
 *
 * Decoding goes through Coil, so common raster formats (PNG/JPEG/WebP/...) are
 * supported. Everything is rasterized to a square bitmap so the launcher's icon
 * pipeline can treat custom icons exactly like any other icon.
 */
object CustomIconImporter {

    private const val SOURCE_SIZE = 512
    private const val ICON_SIZE = 384

    suspend fun importImage(context: Context, uri: Uri): IconPickerItem? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            val loader = ImageLoader.Builder(app)
                .build()
            val request = ImageRequest.Builder(app)
                .data(uri)
                .size(Size(SOURCE_SIZE, SOURCE_SIZE))
                .allowHardware(false)
                .build()
            val drawable = loader.execute(request).drawable ?: return@withContext null
            val bitmap = drawable.toSquareBitmap(ICON_SIZE)
            val dir = CustomImageIconPack.iconDir(app)
            val name = UUID.randomUUID().toString() + ".png"
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val label = queryDisplayName(app, uri) ?: name
            IconPickerItem(CustomImageIconPack.PACK_PACKAGE_NAME, name, label, IconType.Custom)
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Draws this drawable centered into a transparent square bitmap, preserving
     * aspect ratio. Falls back to the target size for drawables without
     * intrinsic dimensions (e.g. decoded SVGs).
     */
    private fun Drawable.toSquareBitmap(outSize: Int): Bitmap {
        val iw = if (intrinsicWidth > 0) intrinsicWidth else outSize
        val ih = if (intrinsicHeight > 0) intrinsicHeight else outSize
        val base = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        val baseCanvas = Canvas(base)
        setBounds(0, 0, iw, ih)
        draw(baseCanvas)

        val scale = minOf(outSize.toFloat() / iw, outSize.toFloat() / ih)
        val w = (iw * scale).roundToInt().coerceAtLeast(1)
        val h = (ih * scale).roundToInt().coerceAtLeast(1)

        val result = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val left = (outSize - w) / 2
        val top = (outSize - h) / 2
        canvas.drawBitmap(base, Rect(0, 0, iw, ih), Rect(left, top, left + w, top + h), paint)
        if (base != result) base.recycle()
        return result
    }
}
