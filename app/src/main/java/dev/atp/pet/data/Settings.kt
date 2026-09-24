package dev.atp.pet.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import org.json.JSONObject
import java.io.File

/**
 * The switches and numbers that belong to the APP rather than to a character.
 *
 * Deliberately small and flat. Everything here is something a person sets once and then
 * forgets about, so it lives in one place with one screen. Anything that belongs to a pet,
 * a prop, a liquid or a rule already has a home of its own, and putting it here as well
 * would give it two.
 *
 * READING IS TOLERANT, on purpose. A missing key is the default, a number out of range is
 * clamped, and a file that does not parse at all is the defaults. A settings file is the
 * last thing in an app that should be able to stop it from starting, and it is also the one
 * file most likely to be hand-edited.
 *
 * Mirrored in tools/settings_check.py, which carries the cases that matter: a missing key,
 * a nonsense value, a file full of another app's JSON, and the round trip.
 */
data class Settings(
    /** Multiplies the point of gravity. 1.0 is the character's own idea of how heavy it is. */
    val gravityScale: Float = 1f,
    /** What the bench starts at: 0 is a limp ragdoll, 1 holds a pose. */
    val defaultStiffness: Float = 0f,
    /** The three things the bench draws that are not the pet. */
    val showGrid: Boolean = true,
    val showGround: Boolean = true,
    val showBalance: Boolean = true,
    /** The rig itself, over the artwork. For lining a drawing up against a bone. */
    val showBones: Boolean = false,
    /** Particles and liquid. Off is for a slow phone, and for seeing the physics alone. */
    val particles: Boolean = true,
    val liquid: Boolean = true,
    /** Slide the window to keep the pet on screen while it is being dragged. */
    val followPet: Boolean = true,
    /**
     * 应用背景图：**文件名**，住在 `filesDir/theme/` 里；空串 = 用自带的那层极光。
     *
     * 存名字而不是路径，也不存绝对路径：这个文件是能被手改的（见类注释），而一个手改出来的
     * `"../../.."` 会让"背景图"变成"删掉你手机里任意一个文件"。名字的合法性由
     * [safeBackgroundName] 一处说了算 —— 读进来的时候和存进去的时候走的是同一条规矩。
     */
    val background: String = "",
    /**
     * 背景图之上压多暗（0 = 不压，0.85 = 几乎只剩轮廓）。
     *
     * 不是审美参数：面板是**半透明白**，一张亮照片铺在下面，字就看不清了。所以它默认有点
     * 压暗（0.35），而调它的入口和"浓淡"这个说法，和参考图那边是同一套。
     */
    val backgroundDim: Float = 0.35f,
    /**
     * 用户同意过第几版免责声明（0 = 还没同意过）。
     *
     * 存**版本号**而不是一个 true/false：免责声明是会改的（加了新的法律风险、改了范围），
     * 而"我同意过 1.19 那一版"和"我同意过这一版"不是同一件事。改了文案就把
     * [DISCLAIMER_VERSION] 加一，应用会再问一次 —— 一个只在安装时问过一次的同意书，
     * 在内容变了之后就不再是同意书了。
     */
    val disclaimerAccepted: Int = 0,
) {
    companion object {
        /** What the file is called, and what it is called inside it. */
        const val FILE = "settings.json"
        const val VERSION = 1

        /** Below this a thrown pet hangs; above it, it drops like a stone. */
        const val MIN_GRAVITY = 0.2f
        const val MAX_GRAVITY = 3f

        /**
         * 免责声明的版本。**改了 `disclaimer_gate_text` 就把它加一**：启动时那道门比的是
         * `disclaimerAccepted < 这个数`，所以加一就等于"请重新读一遍并再同意一次"。
         */
        const val DISCLAIMER_VERSION = 1

        /** 背景图住在这个子目录里（在应用自己的 filesDir 下，和别的东西一样）。 */
        const val THEME_DIR = "theme"

        /** 压暗的两头。0.85 之上就等于把图藏起来了，那不如直接去掉它。 */
        const val MIN_DIM = 0f
        const val MAX_DIM = 0.85f

        /** 存下来的背景图，长边最多这么多像素 —— 一张手机照片原样解码是几十 MB。 */
        const val MAX_BACKGROUND_PX = 1600

        /**
         * 一个背景文件名合法吗？不合法就返回空串。
         *
         * 只收**一个纯文件名**：没有路径分隔符、没有上级目录、不是空的、不太长。这是唯一
         * 一处判断，读文件和存文件都走它 —— 两份判断就是两个会分叉的答案，而这里分叉的
         * 代价是"背景图"能指向文件系统里的任何地方。
         */
        fun safeBackgroundName(raw: String): String {
            val name = raw.trim()
            if (name.isEmpty() || name.length > 96) return ""
            if (name.contains('/') || name.contains('\\') || name.contains("..")) return ""
            return name
        }

        val DEFAULT = Settings()

        fun parse(text: String): Settings = try {
            from(JSONObject(text))
        } catch (e: Exception) {
            DEFAULT
        }

        /**
         * Read every key on its own.
         *
         * One line of this file being nonsense costs that one line, not the whole file: a
         * hand-edited "gravityScale": "heavy" should leave the gravity alone, not silently
         * reset the eight switches somebody else spent five minutes setting up.
         */
        private fun from(o: JSONObject) = Settings(
            gravityScale = clamp(num(o, "gravityScale", 1f), MIN_GRAVITY, MAX_GRAVITY),
            defaultStiffness = clamp(num(o, "defaultStiffness", 0f), 0f, 1f),
            showGrid = flag(o, "showGrid", true),
            showGround = flag(o, "showGround", true),
            showBalance = flag(o, "showBalance", true),
            showBones = flag(o, "showBones", false),
            particles = flag(o, "particles", true),
            liquid = flag(o, "liquid", true),
            followPet = flag(o, "followPet", true),
            // 名字过一遍安检：settings.json 是能手改的，而这个名字会被拼成一条路径。
            background = safeBackgroundName(o.optString("background", "")),
            backgroundDim = clamp(num(o, "backgroundDim", 0.35f), MIN_DIM, MAX_DIM),
            // 负数没有意义（比"没同意过"还低），手改成 999 也不该等于同意了未来的某一版：
            // 夹在 0..当前版本之间。
            disclaimerAccepted = num(o, "disclaimerAccepted", 0f).toInt()
                .coerceIn(0, DISCLAIMER_VERSION),
        )

        private fun num(o: JSONObject, key: String, fallback: Float): Float = try {
            o.optDouble(key, fallback.toDouble()).toFloat()
        } catch (e: Exception) {
            fallback
        }

        private fun flag(o: JSONObject, key: String, fallback: Boolean): Boolean = try {
            o.optBoolean(key, fallback)
        } catch (e: Exception) {
            fallback
        }

        fun toJson(s: Settings): String = JSONObject()
            .put("version", VERSION)
            .put("gravityScale", s.gravityScale.toDouble())
            .put("defaultStiffness", s.defaultStiffness.toDouble())
            .put("showGrid", s.showGrid)
            .put("showGround", s.showGround)
            .put("showBalance", s.showBalance)
            .put("showBones", s.showBones)
            .put("particles", s.particles)
            .put("liquid", s.liquid)
            .put("followPet", s.followPet)
            // 写的时候也过一遍：内存里那份可能是从别处来的（导入、旧版本），
            // 而"写进去的必须是安全的"这条规矩不该只在读的那一头。
            .put("background", safeBackgroundName(s.background))
            .put("backgroundDim", s.backgroundDim.toDouble())
            .put("disclaimerAccepted", s.disclaimerAccepted)
            .toString(2)

        private fun clamp(v: Float, lo: Float, hi: Float): Float =
            if (v.isNaN()) lo else v.coerceIn(lo, hi)
    }
}

/** The settings file, beside everything else the app owns. */
class SettingsStore(private val context: Context) {

    val file: File get() = File(context.filesDir, Settings.FILE)

    fun load(): Settings = try {
        if (file.isFile) Settings.parse(file.readText()) else Settings.DEFAULT
    } catch (e: Exception) {
        Settings.DEFAULT
    }

    fun save(settings: Settings): Boolean = try {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(Settings.toJson(settings))
        if (file.exists()) file.delete()
        temp.renameTo(file)
    } catch (e: Exception) {
        false
    }

    fun forget(): Boolean = try {
        file.delete()
    } catch (e: Exception) {
        false
    }

    // ── 应用背景图（主题）──────────────────────────────────────────────────

    /** 背景图住的地方。和别的东西一样，在应用自己的目录里 —— 不需要任何存储权限。 */
    val themeDir: File get() = File(context.filesDir, Settings.THEME_DIR)

    /**
     * 这个文件名对应的图，或者 null（名字不合法、或者文件不在）。
     *
     * The name goes through [Settings.safeBackgroundName] HERE too, and not only when the
     * settings file is read: a caller can hand in a name that came from anywhere.
     */
    fun backgroundFile(name: String): File? {
        val safe = Settings.safeBackgroundName(name)
        if (safe.isEmpty()) return null
        val file = File(themeDir, safe)
        return if (file.isFile) file else null
    }

    /**
     * 把选中的图存成应用背景，返回它的**文件名**（失败给 null）。
     *
     * 两件事值得写下来：
     *
     *  * **它会被缩小再存**：一张 4000×3000 的照片直接当背景，光是解码就要几十 MB，而这个
     *    应用绝大部分时间在手机上跑物理。长边压到 [Settings.MAX_BACKGROUND_PX]，够铺满一块
     *    手机屏幕了；
     *  * **它是先写 `.part` 再改名**，和老规矩一样：中途没电了不该留下一个半张图，
     *    那会让应用每次启动都去解一张坏图。
     */
    fun importBackground(open: () -> InputStream?): String? {
        val stream = open() ?: return null
        val bitmap = try {
            val bytes = stream.use { it.readBytes() }
            downscaled(bytes, Settings.MAX_BACKGROUND_PX)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            themeDir.mkdirs()
            val name = "bg-" + System.currentTimeMillis() + ".png"
            val temp = File(themeDir, name + ".part")
            temp.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            val target = File(themeDir, name)
            if (target.exists()) target.delete()
            temp.renameTo(target)
            bitmap.recycle()
            name
        } catch (e: Exception) {
            bitmap.recycle()
            null
        }
    }

    /** 去掉一张背景图（顺手把它删了）。名字不合法就什么都不做。 */
    fun clearBackground(name: String): Boolean = try {
        backgroundFile(name)?.delete() ?: false
    } catch (e: Exception) {
        false
    }

    /** 除了留下的那一张，把别的背景图都收掉：换背景不该在手机上攒出一堆照片。 */
    fun pruneBackgrounds(keep: String) {
        val files = themeDir.listFiles() ?: return
        for (f in files) if (f.name != keep) runCatching { f.delete() }
    }

    /** 按长边限制解码（先读尺寸再决定采样率，一次读进内存）。 */
    private fun downscaled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
