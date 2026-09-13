package dev.atp.pet.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.IOException

/**
 * One part image, plus where its cropped region sits inside the character's canvas.
 *
 * Parts are authored full-canvas — that is what makes assembly need no calibration — but
 * keeping a 1024x2048 bitmap per bone would mean drawing tens of megabytes of mostly
 * transparent pixels every frame. They are cropped on load and the offset remembered.
 */
class Part(val bitmap: Bitmap, val offsetX: Float, val offsetY: Float)

/**
 * Every part image a character package ships, keyed by bone name.
 *
 * The naming convention is the whole contract: a file called upperarm_L.png is the
 * artwork for the bone called upperarm_L. A bone with no file simply draws nothing,
 * which is what lets an artist draw one limb at a time and still see it working.
 */
class PartLibrary(val parts: Map<String, Part>) {

    val isEmpty: Boolean get() = parts.isEmpty()
    val size: Int get() = parts.size

    companion object {
        /** Threshold below which a pixel counts as empty; avoids halos from soft edges. */
        private const val ALPHA_CUTOFF = 8

        fun load(context: Context, assetDir: String, boneNames: List<String>): PartLibrary {
            val out = LinkedHashMap<String, Part>()
            for (name in boneNames) {
                val path = assetDir + "/parts/" + name + ".png"
                val source = open(context, path) ?: continue
                val bounds = alphaBounds(source)
                if (bounds == null) {
                    source.recycle()
                    continue
                }
                val (x, y, w, h) = bounds
                val cropped = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(cropped).drawBitmap(source, -x.toFloat(), -y.toFloat(), null)
                source.recycle()
                out[name] = Part(cropped, x.toFloat(), y.toFloat())
            }
            return PartLibrary(out)
        }

        private fun open(context: Context, path: String): Bitmap? {
            return try {
                context.assets.open(path).use { stream ->
                    val options = BitmapFactory.Options().apply {
                        // Assets are not density-scaled; ask for the pixels as authored.
                        inScaled = false
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (e: IOException) {
                null
            }
        }

        /** Tight bounding box of everything not (nearly) transparent, or null if empty. */
        private fun alphaBounds(bitmap: Bitmap): IntArray? {
            val w = bitmap.width
            val h = bitmap.height
            val row = IntArray(w)
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                // Cheap reject first: most rows of a limb part are entirely empty.
                var rowHasInk = false
                for (x in 0 until w) {
                    if ((row[x] ushr 24) > ALPHA_CUTOFF) {
                        rowHasInk = true
                        break
                    }
                }
                if (!rowHasInk) continue
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (x in 0 until w) {
                    if ((row[x] ushr 24) > ALPHA_CUTOFF) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                    }
                }
            }
            if (maxX < 0) return null
            return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
        }
    }
}
