package dev.atp.pet.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import dev.atp.pet.data.VARIANT_SEPARATOR
import java.io.File
import java.io.IOException

/**
 * One part image, plus where its cropped region sits inside the character's canvas.
 *
 * Parts are authored full-canvas — that is what makes assembly need no calibration — but
 * keeping a 1024x2048 bitmap per bone would mean drawing tens of megabytes of mostly
 * transparent pixels every frame. They are cropped on load and the offset remembered.
 */
class Part(
    val bitmap: Bitmap,
    val offsetX: Float,
    val offsetY: Float,
    /**
     * 动图（1.36.0）：这一节的图如果是 GIF，这里就是那个会自己走的 drawable；PNG 是 null。
     *
     * 两条路都留着，而不是"统一成 drawable"：PNG 那条路上有**裁剪**（只画非透明的那一块，
     * 见 `alphaBounds`），那份偏移被画笔那边手算过（`paintAt` 里的 c/s/tx/ty），换成 drawable
     * 就得把那套算法再写一遍。GIF 不裁剪（整画布画），所以它不需要那份偏移 —— 两条路各自简单。
     */
    val drawable: android.graphics.drawable.Drawable? = null,
)

/**
 * Every part image a character folder holds, keyed by bone name.
 *
 * The naming convention is the whole contract: a file called upperarm_L.png is the
 * artwork for the bone called upperarm_L. A bone with no file simply draws nothing,
 * which is what lets an artist draw one limb at a time and still see it working.
 */
/**
 * 哪些文件算"一张部位图"（1.36.0 加上了 GIF）。
 *
 * **只有这一处判断。** 问这句话的地方有三处 —— 扫描 parts 目录（这里）、部位页列出这一节
 * 有哪些图（`CharacterStore.partDrawings`）、以及变体文件名的解析（`<骨头>__<状态>`）——
 * 三处各写一遍，就是下一次加格式时漏掉其中一处，而症状是"导入了但是列表里没有"。
 */
object PartFiles {
    /** 认识的图片后缀。顺序无关，但**只此一份**。 */
    val EXTENSIONS = listOf("png", "gif")

    fun isPartFile(f: File): Boolean =
        f.isFile && EXTENSIONS.any { f.name.endsWith("." + it, ignoreCase = true) }

    /** 文件名去掉后缀：`upperarm_L__机械臂.gif` → `upperarm_L__机械臂`。 */
    fun stem(f: File): String = f.name.substringBeforeLast('.')

    /**
     * 换掉"名字"那一半，**后缀原样留着**（改骨骼名时用）：`old__机械臂.gif` 改成
     * `new__机械臂.gif`。
     *
     * 这个名字得从**原来那个文件**上取，不能按 ".png" 拼出来 —— 改骨骼名那一段原来就是
     * 自己拼的，于是它扫不到 GIF（`endsWith(".png")`），一个 GIF 的变体在改名之后就成了
     * 孤儿：文件还在老名字下，图层却跟着骨头改了名。那正是 1.33.2 修过的那一类"图在、层
     * 找不到它"。
     */
    fun withStem(f: File, stem: String): String = stem + "." + f.name.substringAfterLast('.')
}

class PartLibrary(val parts: Map<String, Part>) {

    val isEmpty: Boolean get() = parts.isEmpty()
    val size: Int get() = parts.size

    fun release() {
        parts.values.forEach { it.bitmap.recycle() }
    }

    companion object {
        /** Threshold below which a pixel counts as empty; avoids halos from soft edges. */
        private const val ALPHA_CUTOFF = 8

        fun load(
            partsDir: File,
            boneNames: List<String>,
            /** 动图的每一帧要靠它重画（`View` 自己就是 `Drawable.Callback`）。没有就只画第一帧。 */
            callback: android.graphics.drawable.Drawable.Callback? = null,
        ): PartLibrary {
            val out = LinkedHashMap<String, Part>()
            val names = boneNames.toHashSet()
            val files = partsDir.listFiles { f -> PartFiles.isPartFile(f) }
                ?: return PartLibrary(out)
            for (file in files.sortedBy { it.name }) {
                val stem = PartFiles.stem(file)
                // The bone's own drawing, and the drawings of its STATES.
                //
                // A state drawing is a second file for the same bone — <bone>__<state>, see
                // CharacterStore.VARIANT_SEPARATOR — and the key a layer names for it is that
                // whole stem, not the bone name. Loading only the bone names therefore left
                // every variant OUT of the library, and PartRenderer drops any layer whose
                // art the library does not have: the file was on disk, the parts folder listed
                // it, the layer pointed at it, and the pet never wore it. It looked exactly
                // like importing a picture that goes nowhere, because that is what it was.
                val belongs = stem in names || stem.substringBefore(VARIANT_SEPARATOR) in names
                if (!belongs) continue
                // 动图先试（1.36.0）：成了就是"会自己走的那一张"，整画布画、不裁剪；
                // 不成（PNG / 老系统 / 坏文件）就退回老路 —— 第一帧 + 裁剪。
                val animated = openAnimated(file, callback)
                if (animated != null) {
                    val first = open(file) ?: continue
                    out[stem] = Part(first, 0f, 0f, animated)
                    continue
                }
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
        fun loadFree(
            dir: File,
            callback: android.graphics.drawable.Drawable.Callback? = null,
        ): PartLibrary {
            val out = LinkedHashMap<String, Part>()
            val files = dir.listFiles { f -> PartFiles.isPartFile(f) } ?: return PartLibrary(out)
            for (file in files.sortedBy { it.name }) {
                val stem = PartFiles.stem(file)
                val animated = openAnimated(file, callback)
                if (animated != null) {
                    val first = open(file) ?: continue
                    out[stem] = Part(first, 0f, 0f, animated)
                    continue
                }
                val source = open(file) ?: continue
                val part = crop(source)
                if (part != null) out[stem] = part
            }
            return PartLibrary(out)
        }

        /**
         * 一张 GIF：能走就走，不能走回 null（调用方退回第一帧）。
         *
         * `AnimatedImageDrawable` 是 API 28 的东西，而这个应用的 minSdk 是 26 —— 26/27 上
         * 退回静态的第一帧，不是"图不显示"（`BitmapFactory` 本来就会解出 GIF 的第一帧）。
         * 动画要靠 [callback] 重画：drawable 自己不会去 invalidate 一个 View。
         */
        private fun openAnimated(
            file: File,
            callback: android.graphics.drawable.Drawable.Callback?,
        ): android.graphics.drawable.Drawable? {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
            if (!file.name.endsWith(".gif", ignoreCase = true)) return null
            return try {
                val source = android.graphics.ImageDecoder.createSource(file)
                val decoded = android.graphics.ImageDecoder.decodeDrawable(source)
                val animated = decoded as? android.graphics.drawable.AnimatedImageDrawable
                    ?: return null
                animated.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                animated.callback = callback
                animated.start()
                animated
            } catch (e: Exception) {
                // 坏 GIF、解码器不支持、内存不够 —— 都退回第一帧。一张图坏了不该让宠物消失。
                null
            }
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
