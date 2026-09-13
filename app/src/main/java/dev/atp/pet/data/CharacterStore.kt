package dev.atp.pet.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

/** One character package on disk. */
class CharacterFolder(val dir: File) {
    val id: String get() = dir.name
    val specFile: File get() = File(dir, "character.json")
    val partsDir: File get() = File(dir, "parts")

    fun partFile(bone: String): File = File(partsDir, bone + ".png")

    fun specText(): String = specFile.readText()

    fun version(): Int = try {
        JSONObject(specText()).optInt("version", 0)
    } catch (e: Exception) {
        0
    }

    /** How many bones actually have artwork on disk. */
    fun partCount(bones: List<String>): Int = bones.count { partFile(it).isFile }
}

/**
 * Where character packages live.
 *
 * They used to be read straight out of assets, which is fine until the user wants to
 * import a drawing of their own — assets are read only. So the app now owns a real
 * directory in its private storage, seeded from assets the first time it runs, and every
 * read and write goes there. One code path, and the packages are files the user's own
 * imports land in.
 */
class CharacterStore(private val context: Context) {

    val root: File get() = File(context.filesDir, "characters")

    /** Copy any bundled package that is missing, and refresh specs that have changed. */
    fun ensureSeeded(): List<String> {
        val bundled = try {
            context.assets.list(BUNDLED_ROOT)?.toList() ?: emptyList()
        } catch (e: IOException) {
            emptyList()
        }
        val seeded = mutableListOf<String>()
        for (id in bundled) {
            val assetPath = BUNDLED_ROOT + "/" + id
            // A directory in assets shows up as a listing with entries.
            val children = context.assets.list(assetPath) ?: continue
            if (children.isEmpty()) continue

            val target = File(root, id)
            val spec = File(target, "character.json")
            val bundledVersion = bundledVersion(assetPath)

            if (!spec.isFile) {
                target.mkdirs()
                copyAssetTree(assetPath, target)
                seeded.add(id)
            } else if (bundledVersion > versionOf(spec)) {
                // The rig changed in a newer build. Take the new skeleton but never
                // touch parts/ — that directory is the user's work.
                copyAsset(assetPath + "/character.json", spec)
                seeded.add(id)
            }
        }
        return seeded
    }

    fun list(): List<CharacterFolder> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.filter { File(it, "character.json").isFile }
            .sortedBy { it.name }
            .map { CharacterFolder(it) }
    }

    fun folder(id: String): CharacterFolder? {
        val dir = File(root, id)
        return if (File(dir, "character.json").isFile) CharacterFolder(dir) else null
    }

    /**
     * Write an imported image as the artwork for one bone.
     *
     * The name is decided by the caller from the bone, never from the picked file: the
     * bone name IS the contract that makes assembly work, so a file called holiday.jpg
     * still has to land as upperarm_L.png.
     */
    fun importPart(id: String, bone: String, open: () -> InputStream?): Boolean {
        val folder = folder(id) ?: return false
        val stream = open() ?: return false
        val target = folder.partFile(bone)
        target.parentFile?.mkdirs()
        return try {
            stream.use { input ->
                // Write to a neighbour first: a half-copied PNG would render as a broken
                // part with no way to tell what went wrong.
                val temp = File(target.parentFile, bone + ".png.part")
                temp.outputStream().use { out -> input.copyTo(out) }
                if (target.exists()) target.delete()
                temp.renameTo(target)
            }
        } catch (e: IOException) {
            false
        }
    }

    fun clearPart(id: String, bone: String): Boolean {
        val folder = folder(id) ?: return false
        return folder.partFile(bone).delete()
    }

    private fun versionOf(spec: File): Int = try {
        JSONObject(spec.readText()).optInt("version", 0)
    } catch (e: Exception) {
        0
    }

    private fun bundledVersion(assetPath: String): Int = try {
        context.assets.open(assetPath + "/character.json").use { stream ->
            JSONObject(stream.bufferedReader().readText()).optInt("version", 0)
        }
    } catch (e: IOException) {
        0
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            copyAsset(assetPath, target)
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetTree(assetPath + "/" + child, File(target, child))
        }
    }

    private fun copyAsset(assetPath: String, target: File) {
        try {
            context.assets.open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (e: IOException) {
            // A missing bundled file is not fatal: the part simply will not exist.
        }
    }

    private companion object {
        const val BUNDLED_ROOT = "characters"
    }
}
