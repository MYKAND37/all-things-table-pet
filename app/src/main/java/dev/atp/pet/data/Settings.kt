package dev.atp.pet.data

import android.content.Context
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
) {
    companion object {
        /** What the file is called, and what it is called inside it. */
        const val FILE = "settings.json"
        const val VERSION = 1

        /** Below this a thrown pet hangs; above it, it drops like a stone. */
        const val MIN_GRAVITY = 0.2f
        const val MAX_GRAVITY = 3f

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
}
