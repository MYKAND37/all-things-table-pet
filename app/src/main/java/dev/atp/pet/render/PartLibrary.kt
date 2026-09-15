package dev.atp.pet.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import dev.atp.pet.data.CharacterStore
import java.io.File
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
 * Every part image a character folder holds, keyed by bone name.
 *
 * The naming convention is the whole contract: a file called upperarm_L.png is the
 * artwork for the bone called upperarm_L. A bone with no file simply draws nothing,
 * which is what lets an artist draw one limb at a time and still see it working.
 */
class PartLibrary(val parts: Map<String, Part>) {

    val isEmpty: Boolean get() = parts.isEmpty()
    val size: Int get() = parts.size

    fun release() {
        parts.values.forEach { it.bitmap.recycle() }
    }

    companion object {
        /** Threshold below which a pixel counts as empty; avoids halos from soft edges. */
        private const val ALPHA_CUTOFF = 8

        fun load(partsDir: File, boneNames: List<String>): PartLibrary {
            val out = LinkedHashMap<String, Part>()
            val names = boneNames.toHashSet()
            val files = partsDir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
                ?: return PartLibrary(out)
            for (file in files.sortedBy { it.name }) {
                val stem = file.name.substringBeforeLast(".png")
                // The bone's own drawing, and the drawings of its STATES.
                //
                // A state drawing is a second file for the same bone — <bone>__<state>, see
                // CharacterStore.VARIANT_SEPARATOR — and the key a layer names for it is that
                // whole stem, not the bone name. Loading only the bone names therefore left
                // every variant OUT of the library, and PartRenderer drops any layer whose
                // art the library does not have: the file was on disk, the parts folder listed
                // it, the layer pointed at it, and the pet never wore it. It looked exactly
                // like importing a picture that goes nowhere, because that is what it was.
                val belongs = stem in names ||
                    stem.substringBefore(CharacterStore.VARIANT_SEPARATOR) in names
                if (!belongs) continue
                val source = open(file) ?: continue
                val part = crop(source) ?: continue
                out[stem] = part
            }
            return PartLibrary(out)
        }

        /**
         * Every PNG in a directory, keyed by file name.
         *
         * Props are not attached to bones, so there is no naming contract to satisfy: the
         * file IS the thing, and where its pixels are is where it is drawn.
         */
        fun loadFree(dir: File): PartLibrary {
            val out = LinkedHashMap<String, Part>()
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return PartLibrary(out)
            for (file in files.sortedBy { it.name }) {
                val source = open(file) ?: continue
                val part = crop(source)
                if (part != null) out[file.name.substringBeforeLast(".png")] = part
            }
            return PartLibrary(out)
        }

        /** Crop a decoded part to its ink and remember where that was. Recycles [source]. */
        private fun crop(source: Bitmap): Part? {
            val bounds = alphaBounds(source)
            if (bounds == null) {
                source.recycle()
                return null
            }
            val (x, y, w, h) = bounds
            val cropped = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(cropped).drawBitmap(source, -x.toFloat(), -y.toFloat(), null)
            source.recycle()
            return Part(cropped, x.toFloat(), y.toFloat())
        }

        private fun open(file: File): Bitmap? {
            return try {
                val options = BitmapFactory.Options().apply {
                    // Authored pixels, not density-scaled ones.
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
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
