package dev.atp.pet.data

import android.content.Context
import dev.atp.pet.engine.logic.LogicSpec
import dev.atp.pet.engine.prop.PropSpec
import dev.atp.pet.engine.prop.PropSpecs
import dev.atp.pet.engine.skeleton.BoneSpec
import dev.atp.pet.engine.skeleton.LayerSpec
import dev.atp.pet.engine.skeleton.RigEdit
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

    /**
     * Remove a package and everything in it: the rig, the drawings, the rules, the poses.
     *
     * There is no undo and no copy anywhere else. That is why the caller asks twice.
     */
    fun deleteCharacter(id: String): Boolean {
        val dir = File(root, id)
        if (!dir.isDirectory) return false
        return dir.deleteRecursively()
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
     * Add a second artwork for a bone, drawn only while [state] is on.
     *
     * The base layer is put on "not [state]" at the same time. Without that both drawings
     * appear the instant the state turns on, which is the one thing a variant is for.
     */
    fun addVariant(id: String, bone: String, state: String): Boolean {
        val folder = folder(id) ?: return false
        return try {
            val root = JSONObject(folder.specText())
            val arr = root.optJSONArray("layers") ?: JSONArray()
            var top = 0
            for (i in 0 until arr.length()) {
                val l = arr.getJSONObject(i)
                top = maxOf(top, l.optInt("z", 0))
                if (l.getString("bone") == bone && l.optString("state", "").isEmpty()) {
                    l.put("state", "!" + state)
                }
            }
            arr.put(
                JSONObject()
                    .put("bone", bone).put("z", top + 10)
                    .put("state", state).put("art", bone + VARIANT_SEPARATOR + state)
            )
            root.put("layers", arr)
            root.put("version", root.optInt("version", 0) + 1)
            writeSpec(folder, root)
        } catch (e: Exception) {
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
        backToFront: List<LayerSpec>,
        swaps: List<SwapRuleSpec>,
    ): Boolean {
        val folder = folder(id) ?: return false
        return try {
            val root = JSONObject(folder.specText())

            val layers = JSONArray()
            backToFront.forEachIndexed { index, layer ->
                val o = JSONObject().put("bone", layer.bone).put("z", 10 + index * 10)
                if (layer.state.isNotEmpty()) o.put("state", layer.state)
                if (layer.art.isNotEmpty()) o.put("art", layer.art)
                layers.put(o)
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
     * Write the rig back after the bone editor changed it.
     *
     * [bones] is the whole rig rather than a diff, because the editor can add a bone,
     * reparent one and delete one; a bone missing from the list has been deleted. Its
     * artwork is deleted too — but only down at the bottom, after the spec is safely
     * written. Until then a delete is an intention, and a session that is walked away from
     * instead of saved has to leave the drawings alone.
     *
     * A bone the package has never seen gets the fields the runtime always reads, with
     * defaults that mean "work it out from the figure". The version is bumped so the
     * seeding pass — which refreshes the bundled spec whenever the app ships a newer one —
     * leaves these edits alone instead of overwriting them on the next launch.
     */
    fun saveRig(
        id: String,
        bones: List<BoneSpec>,
        backToFront: List<String>,
        renames: Map<String, String> = emptyMap(),
    ): Boolean {
        if (bones.isEmpty()) return false
        if (RigEdit.problem(bones) != null) return false
        val folder = folder(id) ?: return false
        // A bone missing from the list has been deleted — unless it was renamed, in which
        // case the name it used to have is the one that is missing.
        val wasRenamed = renames.values.toSet()
        var orphaned: List<String> = emptyList()
        val ok = try {
            val root = JSONObject(folder.specText())
            val old = root.optJSONArray("bones") ?: JSONArray()
            val alive = bones.map { it.name }.toSet()
            val kept = HashMap<String, JSONObject>()
            val gone = mutableListOf<String>()
            for (i in 0 until old.length()) {
                val b = old.getJSONObject(i)
                val name = b.getString("name")
                val now = renames[name] ?: name
                // A renamed bone keeps everything the file said about it: only the name
                // changed, and rebuilding the entry would throw away the limits and the
                // collider somebody chose for it.
                if (now in alive) kept[now] = b else gone.add(name)
            }
            orphaned = gone.filter { it !in renames.keys }

            val arr = JSONArray()
            for (b in RigEdit.order(bones)) {
                val o = kept[b.name] ?: JSONObject()
                if (b.name in wasRenamed) o.remove("name")
                o.put("name", b.name)
                if (b.parentName == null) o.put("parent", JSONObject.NULL) else o.put("parent", b.parentName)
                o.put("head", JSONArray(listOf(b.head.x.toDouble(), b.head.y.toDouble())))
                o.put("tail", JSONArray(listOf(b.tail.x.toDouble(), b.tail.y.toDouble())))
                if (!kept.containsKey(b.name)) {
                    o.put("limits", JSONArray(listOf(-180.0, 180.0)))
                    o.put("collider", JSONObject().put("type", "capsule").put("radius", 0.0))
                }
                // What the joint is allowed to do, and what it collides as. Written every
                // time rather than only for a new bone, because these are the fields the
                // attribute editor changes — and they are the two that actually reach the
                // solver and the world.
                o.put("limits", JSONArray(listOf(b.minAngle.toDouble(), b.maxAngle.toDouble())))
                o.put("collider", JSONObject()
                    .put("type", b.colliderType)
                    .put("radius", b.colliderRadius.toDouble()))
                arr.put(o)
            }
            root.put("bones", arr)

            val live = bones.map { it.name }.toSet()
            val order = backToFront.filter { it in live } +
                bones.map { it.name }.filter { it !in backToFront }

            // Reordering the layers must not REBUILD them.
            //
            // This used to write one layer per bone, carrying over only that bone's state tag,
            // which quietly threw away everything else a layer can be. A bone with a drawing for
            // a state owns TWO layers -- the plain one and the mechanical one -- so saving the rig
            // from the bone editor deleted the state's drawing outright: the artwork stayed on
            // disk and the pet simply stopped wearing it. Layers are carried over whole and only
            // renumbered now, in the order the editor asked for, with the layers of a bone that is
            // gone dropped.
            val oldLayers = root.optJSONArray("layers")
            val byBone = LinkedHashMap<String, MutableList<JSONObject>>()
            for (i in 0 until (oldLayers?.length() ?: 0)) {
                val l = oldLayers!!.getJSONObject(i)
                val name = l.getString("bone")
                val now = renames[name] ?: name
                // A renamed bone keeps its layers, and they have to say the new name.
                l.put("bone", now)
                byBone.getOrPut(now) { mutableListOf() }.add(l)
            }
            val layers = JSONArray()
            var nextZ = 10
            for (bone in order) {
                val own = byBone[bone]
                if (own.isNullOrEmpty()) {
                    layers.put(JSONObject().put("bone", bone).put("z", nextZ))
                    nextZ += 10
                    continue
                }
                for (l in own) {
                    l.put("z", nextZ)
                    nextZ += 10
                    layers.put(l)
                }
            }
            root.put("layers", layers)

            // A depth rule naming a bone that is gone can never fire again, and an IK chain
            // naming one throws the moment it is dragged.
            // A renamed bone is still the same bone, so the rules and the drag chains that
            // named it follow it. Without this, renaming a bone would quietly delete every
            // rule that mentioned it.
            val rules = JSONArray()
            val oldRules = root.optJSONArray("layerSwaps")
            for (i in 0 until (oldRules?.length() ?: 0)) {
                val r = oldRules!!.getJSONObject(i)
                for (key in RULE_LISTS) {
                    val a = r.optJSONArray(key) ?: continue
                    for (j in 0 until a.length()) {
                        val from = a.optString(j)
                        if (renames.containsKey(from)) a.put(j, renames[from])
                    }
                }
                r.optJSONObject("trigger")?.let { t ->
                    follow(t, renames, "bone")
                    follow(t, renames, "reference")
                }
                if (namesMissing(r, live)) continue
                rules.put(r)
            }
            root.put("layerSwaps", rules)

            val chains = JSONArray()
            val oldChains = root.optJSONArray("ikChains")
            for (i in 0 until (oldChains?.length() ?: 0)) {
                val c = oldChains!!.getJSONObject(i)
                follow(c, renames, "upper")
                follow(c, renames, "lower")
                if (c.optString("upper") !in live || c.optString("lower") !in live) continue
                chains.put(c)
            }
            root.put("ikChains", chains)

            root.put("version", root.optInt("version", 0) + 1)
            writeSpec(folder, root)
        } catch (e: Exception) {
            false
        }
        if (!ok) return false
        for (name in orphaned) folder.partFile(name).delete()
        // The artwork follows the bone: the name is the contract, and a rename that left
        // the file behind would look exactly like a bone whose drawing was never imported.
        for ((from, to) in renames) {
            val art = folder.partFile(from)
            if (art.isFile) art.renameTo(folder.partFile(to))
        }
        return true
    }

    private fun follow(node: JSONObject, renames: Map<String, String>, key: String) {
        val from = node.optString(key, "")
        if (from.isNotEmpty() && renames.containsKey(from)) node.put(key, renames[from])
    }

    /** True when a depth rule refers to a bone that is no longer in the rig. */
    private fun namesMissing(rule: JSONObject, live: Set<String>): Boolean {
        rule.optJSONObject("trigger")?.let {
            if (it.optString("bone") !in live) return true
            if (it.optString("reference") !in live) return true
        }
        for (key in listOf("parts", "behind")) {
            val a = rule.optJSONArray(key) ?: continue
            for (i in 0 until a.length()) if (a.optString(i) !in live) return true
        }
        return false
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

    // ── 逻辑（每个角色一份）────────────────────────────────────────────────

    /**
     * A character's rules and numbers.
     *
     * A character that has never been edited simply runs the built-in defaults, so the file
     * is not written until the user changes something. That is deliberate: once it exists
     * it is theirs, and nothing the app ships will ever overwrite a rule somebody wrote.
     */
    fun loadLogic(id: String): LogicSpec {
        val file = folder(id)?.let { File(it.dir, LOGIC_FILE) } ?: return LogicSpec.parse(LogicSpec.DEFAULT)
        if (!file.isFile) return LogicSpec.parse(LogicSpec.DEFAULT)
        return try {
            LogicSpec.parse(file.readText())
        } catch (e: Exception) {
            LogicSpec.parse(LogicSpec.DEFAULT)
        }
    }

    fun saveLogic(id: String, spec: LogicSpec): Boolean {
        val file = folder(id)?.let { File(it.dir, LOGIC_FILE) } ?: return false
        return writeText(file, LogicSpec.toJson(spec))
    }

    /** Whether the user has ever edited this character's rules. */
    fun hasLogic(id: String): Boolean = folder(id)?.let { File(it.dir, LOGIC_FILE).isFile } ?: false

    fun forgetLogic(id: String): Boolean {
        val file = folder(id)?.let { File(it.dir, LOGIC_FILE) } ?: return false
        return file.delete()
    }

    // ── 道具（所有角色共用）────────────────────────────────────────────────

    /**
     * Props live beside the characters rather than inside one, because a hammer is not a
     * property of the thing being hit. Art is props/<id>.png; the list is props.json.
     */
    val propsDir: File get() = File(context.filesDir, PROPS_DIR)

    fun propArtFile(id: String): File = File(propsDir, id + ".png")

    fun loadProps(): List<PropSpec> {
        val file = File(propsDir, PROPS_FILE)
        if (!file.isFile) return emptyList()
        return try {
            PropSpecs.parse(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveProps(specs: List<PropSpec>): Boolean = writeText(File(propsDir, PROPS_FILE), PropSpecs.toJson(specs))

    // ── 客体逻辑：道具和液体自己的那套规则 ──────────────────────────────────

    /**
     * The logic that belongs to a prop or a liquid, by subject.
     *
     * One file for all of them rather than a directory per object, because a prop is a name
     * and a picture and half a dozen numbers, not a package: half a dozen files for the
     * possibility that a candle wants two rules is a lot of filesystem for not much.
     *
     * Nothing here knows what a subject MEANS. A file that names a prop which has since been
     * deleted loads exactly as well as one that names a prop that exists, and simply has
     * nothing on the bench to run against.
     */
    fun loadObjectLogic(): Map<String, LogicSpec> {
        val file = File(propsDir, OBJECT_LOGIC_FILE)
        if (!file.isFile) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            val out = LinkedHashMap<String, LogicSpec>()
            for (subject in root.keys()) {
                out[subject] = LogicSpec.parseObject(root.getJSONObject(subject).toString())
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveObjectLogic(subject: String, spec: LogicSpec): Boolean {
        propsDir.mkdirs()
        return try {
            val file = File(propsDir, OBJECT_LOGIC_FILE)
            val root = if (file.isFile) JSONObject(file.readText()) else JSONObject()
            root.put(subject, JSONObject(LogicSpec.toJson(spec)))
            writeText(file, root.toString(2))
        } catch (e: Exception) {
            false
        }
    }

    fun forgetObjectLogic(subject: String): Boolean {
        val file = File(propsDir, OBJECT_LOGIC_FILE)
        if (!file.isFile) return false
        return try {
            val root = JSONObject(file.readText())
            root.remove(subject)
            writeText(file, root.toString(2))
        } catch (e: Exception) {
            false
        }
    }

    fun savePropArt(id: String, bitmap: android.graphics.Bitmap): Boolean {
        propsDir.mkdirs()
        val target = propArtFile(id)
        return try {
            val temp = File(propsDir, id + ".png.part")
            FileOutputStream(temp).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (e: IOException) {
            false
        }
    }

    fun deleteProp(id: String): Boolean {
        val ok = saveProps(loadProps().filter { it.id != id })
        propArtFile(id).delete()
        return ok
    }

    /** Write a text file through a neighbour, so a half-written file is never left behind. */
    private fun writeText(file: File, text: String): Boolean = try {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (file.exists()) file.delete()
        temp.renameTo(file)
    } catch (e: Exception) {
        false
    }

    private fun writeSpec(folder: CharacterFolder, root: JSONObject): Boolean {
        val temp = File(folder.dir, "character.json.tmp")
        temp.writeText(root.toString(2))
        if (folder.specFile.exists()) folder.specFile.delete()
        return temp.renameTo(folder.specFile)
    }

    /**
     * Every drawing one bone owns, base first.
     *
     * The files ARE the folder: shin_L.png is the base drawing, shin_L__mech.png is the one
     * that only shows while 机械臂 is on. Reading the directory rather than the layers means
     * a drawing nothing points at still shows up here — which is exactly the file somebody
     * copied into the folder by hand and now wants to assign to a state.
     */
    fun partDrawings(id: String, bone: String): List<PartDrawing> {
        val folder = folder(id) ?: return emptyList()
        val files = folder.partsDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".png") }
            .map { PartDrawing(it.name.removeSuffix(".png"), it) }
            .filter { it.artKey == bone || it.artKey.startsWith(bone + VARIANT_SEPARATOR) }
            .sortedBy { it.artKey }
    }

    /**
     * Delete one drawing, and put the part back the way it was.
     *
     * Two things have to go with the file. A LAYER that pointed at it would draw nothing at
     * all — a hole in the file that nothing can ever fill — and the base drawing of a part
     * that has a variant is usually marked "not while that state is on", so deleting the
     * variant without clearing that marker would leave the plain arm missing whenever the
     * state was on, which reads as a bug in the app rather than as a drawing that was
     * removed.
     */
    fun deleteDrawing(id: String, bone: String, artKey: String): Boolean {
        val folder = folder(id) ?: return false
        return try {
            File(folder.partsDir, artKey + ".png").delete()
            val root = JSONObject(folder.specText())
            val old = root.optJSONArray("layers") ?: JSONArray()
            val state = if (artKey.startsWith(bone + VARIANT_SEPARATOR)) {
                artKey.removePrefix(bone + VARIANT_SEPARATOR)
            } else {
                ""
            }
            val arr = JSONArray()
            for (i in 0 until old.length()) {
                val l = old.getJSONObject(i)
                if (l.optString("bone") == bone && l.optString("art", bone) == artKey) continue
                if (state.isNotEmpty() && l.optString("bone") == bone &&
                    l.optString("state", "") == "!" + state
                ) {
                    l.remove("state")
                }
                arr.put(l)
            }
            root.put("layers", arr)
            root.put("version", root.optInt("version", 0) + 1)
            writeSpec(folder, root)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The artwork key a state's drawing of a bone is stored under.
     *
     * The naming convention is the whole interface between the rig, the drawings and the
     * layers, so it is spelled out in one place rather than assembled out of a constant
     * wherever somebody needs it.
     */
    fun variantKey(bone: String, state: String): String = bone + VARIANT_SEPARATOR + state

    /** One drawing on disk, and the key the layers call it by. */
    class PartDrawing(val artKey: String, val file: File) {
        /** The state this drawing is for, or "" for the base drawing. */
        val state: String
            get() = if (artKey.contains(VARIANT_SEPARATOR)) {
                artKey.substringAfter(VARIANT_SEPARATOR)
            } else {
                ""
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

        /** Saved poses live beside the spec, not inside it: they are the user's, not the package's. */
        const val POSES_FILE = "poses.json"

        /** Rules and numbers are the user's too, for the same reason. */
        const val LOGIC_FILE = "logic.json"

        private val RULE_LISTS = listOf("parts", "behind")

        /** How a variant's file is named: upperarm_L__mech.png for the mech state. */
        const val VARIANT_SEPARATOR = "__"

        /** Props are shared by every character, so they sit at the top level. */
        const val PROPS_DIR = "props"

        /** The logic of every prop and every liquid, keyed by subject. See Subjects. */
        const val OBJECT_LOGIC_FILE = "object-logic.json"
        const val PROPS_FILE = "props.json"
    }
}