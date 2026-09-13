package dev.atp.pet.data

import android.content.Context
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.skeleton.SwapRuleSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

    /**
     * Write an already-composited part — the result of the alignment screen, which puts
     * the picked image onto a full-canvas transparent bitmap at the position and scale
     * the user chose.
     */
    fun savePart(id: String, bone: String, bitmap: android.graphics.Bitmap): Boolean {
        val folder = folder(id) ?: return false
        val target = folder.partFile(bone)
        target.parentFile?.mkdirs()
        return try {
            val temp = File(target.parentFile, bone + ".png.part")
            FileOutputStream(temp).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Write back the depth of every part, and the rules that change it.
     *
     * [backToFront] is the draw order: index 0 is drawn first and therefore ends up
     * furthest back. Spacing the written values by ten rather than one leaves room to
     * hand-edit a single part in between later without renumbering everything.
     */
    fun saveDepth(
        id: String,
        backToFront: List<String>,
        swaps: List<SwapRuleSpec>,
    ): Boolean {
        val folder = folder(id) ?: return false
        return try {
            val root = JSONObject(folder.specText())

            val layers = JSONArray()
            backToFront.forEachIndexed { index, bone ->
                layers.put(JSONObject().put("bone", bone).put("z", 10 + index * 10))
            }
            root.put("layers", layers)

            val rules = JSONArray()
            for (s in swaps) {
                rules.put(
                    JSONObject()
                        .put("parts", JSONArray(s.parts))
                        .put("behind", JSONArray(s.behind))
                        .put("to", if (s.toFront) "front" else "behind")
                        .put(
                            "trigger",
                            JSONObject()
                                .put("type", s.triggerType)
                                .put("bone", s.triggerBone)
                                .put("reference", s.referenceBone),
                        )
                )
            }
            root.put("layerSwaps", rules)

            val temp = File(folder.dir, "character.json.tmp")
            temp.writeText(root.toString(2))
            if (folder.specFile.exists()) folder.specFile.delete()
            temp.renameTo(folder.specFile)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Write back the rig geometry after the bone editor moved it.
     *
     * [geometry] maps a bone name to its head and tail in canvas coordinates. The version
     * is bumped so the seeding pass — which refreshes the bundled spec whenever the app
     * ships a newer one — leaves these edits alone instead of overwriting them on the
     * next launch.
     */
    fun saveBones(id: String, geometry: Map<String, Pair<Vec2, Vec2>>): Boolean {
        val folder = folder(id) ?: return false
        return try {
            val root = JSONObject(folder.specText())
            val arr = root.getJSONArray("bones")
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val g = geometry[b.getString("name")] ?: continue
                b.put("head", JSONArray(listOf(g.first.x.toDouble(), g.first.y.toDouble())))
                b.put("tail", JSONArray(listOf(g.second.x.toDouble(), g.second.y.toDouble())))
            }
            root.put("version", root.optInt("version", 0) + 1)
            writeSpec(folder, root)
        } catch (e: Exception) {
            false
        }
    }

    // ── 动作预设 ────────────────────────────────────────────────────────────

    /** A saved pose: the joint angles the character holds while it is "doing" this action. */
    data class Pose(val name: String, val angles: Map<String, Float>)

    fun loadPoses(id: String): List<Pose> {
        val folder = folder(id) ?: return emptyList()
        val file = File(folder.dir, POSES_FILE)
        if (!file.isFile) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val angles = o.getJSONObject("angles")
                Pose(
                    o.getString("name"),
                    angles.keys().asSequence().associateWith { angles.getDouble(it).toFloat() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePose(id: String, name: String, angles: Map<String, Float>): Boolean {
        val folder = folder(id) ?: return false
        return try {
            val kept = loadPoses(id).filter { it.name != name }
            val arr = JSONArray()
            for (p in kept + Pose(name, angles)) {
                val a = JSONObject()
                for ((bone, value) in p.angles) a.put(bone, value.toDouble())
                arr.put(JSONObject().put("name", p.name).put("angles", a))
            }
            val temp = File(folder.dir, POSES_FILE + ".tmp")
            temp.writeText(arr.toString(2))
            val target = File(folder.dir, POSES_FILE)
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (e: Exception) {
            false
        }
    }

    fun deletePose(id: String, name: String): Boolean {
        val folder = folder(id) ?: return false
        return try {
            val arr = JSONArray()
            for (p in loadPoses(id)) {
                if (p.name == name) continue
                val a = JSONObject()
                for ((bone, value) in p.angles) a.put(bone, value.toDouble())
                arr.put(JSONObject().put("name", p.name).put("angles", a))
            }
            val temp = File(folder.dir, POSES_FILE + ".tmp")
            temp.writeText(arr.toString(2))
            val target = File(folder.dir, POSES_FILE)
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (e: Exception) {
            false
        }
    }

    private fun writeSpec(folder: CharacterFolder, root: JSONObject): Boolean {
        val temp = File(folder.dir, "character.json.tmp")
        temp.writeText(root.toString(2))
        if (folder.specFile.exists()) folder.specFile.delete()
        return temp.renameTo(folder.specFile)
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

        /** Saved poses live beside the spec, not inside it: they are the user's, not the package's. */
        const val POSES_FILE = "poses.json"
    }
}