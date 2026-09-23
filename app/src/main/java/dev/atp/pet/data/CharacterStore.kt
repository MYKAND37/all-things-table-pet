package dev.atp.pet.data

import android.content.Context
import dev.atp.pet.engine.anim.AnimFrame
import dev.atp.pet.engine.anim.AnimationSpec
import dev.atp.pet.engine.logic.LogicSpec
import dev.atp.pet.engine.logic.Subjects
import dev.atp.pet.engine.prop.PropSpec
import dev.atp.pet.engine.prop.PropSpecs
import dev.atp.pet.engine.skeleton.BoneSpec
import dev.atp.pet.engine.skeleton.LayerSpec
import dev.atp.pet.engine.skeleton.NodeSpec
import dev.atp.pet.engine.skeleton.RigEdit
import dev.atp.pet.engine.skeleton.SwapRuleSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * How a drawing for a STATE is named: upperarm_L__mech.png for the mech state.
 *
 * Top level and public rather than tucked into the store's private companion, because it is a
 * contract between two halves of the app, not a detail of one: the store names the files this
 * way, and the renderer's loader has to recognise the same names. It lived in a
 * ~private companion~, the loader reached for it, and nothing local noticed until CI compiled
 * it — which is the one kind of mistake this project's checks could not see, and now can.
 */
const val VARIANT_SEPARATOR = "__"

/**
 * One character package on disk — and WHICH of its rigs.
 *
 * A pet can have more than one 骨骼套: a different skeleton with its own artwork, the way a
 * character in a game has forms. The first one is the package itself, exactly where it has
 * always been, so every file written by an older version is already the default rig and
 * nothing has to be migrated. The others live one folder down, in `rigs/<名字>/`, each with
 * its own [specFile] and [partsDir] — the same two things, one level in.
 *
 * Everything the PET owns rather than the rig — its rules and numbers, its particles, its
 * liquids — stays at the top of the folder and is reached through [dir], not through [rigDir].
 * That split is the whole point of the feature: swapping a rig changes what it looks like,
 * not who it is.
 */
class CharacterFolder(val dir: File, val rig: String = "") {
    val id: String get() = dir.name

    /**
     * The folder this rig's own files are in: the package itself for the default rig, and
     * `rigs/<名字>/` for the others.
     *
     * Public because two things outside this class need to say where a rig lives: the store,
     * when it copies or deletes one, and the editor, when it lists them.
     */
    val rigDir: File get() = if (rig.isEmpty()) dir else File(File(dir, RIGS_DIR), rig)

    val specFile: File get() = File(rigDir, SPEC_FILE)
    val partsDir: File get() = File(rigDir, PARTS_DIR)

    fun partFile(bone: String): File = File(partsDir, bone + ".png")

    /**
     * A picture to rig THIS rig against, if the user brought one in.
     *
     * Beside the rig's spec rather than in parts/: it is not artwork for a bone and it must not
     * be mistaken for one -- a file called reference.png next to hand_L.png is a file somebody
     * will wonder about, and it would be drawn on the bench as if it were a part.
     */
    val referenceFile: File get() = File(rigDir, REFERENCE_FILE)

    /**
     * The saved poses, which belong to the RIG and not to the pet: a pose is a set of bone
     * angles, and a rig whose bones are named differently cannot hold the same ones. The
     * default rig's file stays where it always was, so nobody's saved poses move.
     */
    val posesFile: File get() = File(rigDir, POSES_FILE)
    val animationsFile: File get() = File(rigDir, ANIMATIONS_FILE)

    fun specText(): String = specFile.readText()

    /** The same pet, looking at another of its rigs. Empty means the one at the top. */
    fun withRig(name: String): CharacterFolder = CharacterFolder(dir, name)

    /**
     * Every rig this pet has, the default one first.
     *
     * A named folder only counts if it has a spec in it: a half-made rig with no skeleton is
     * not something to offer somebody in a list, and it would fail the moment it was picked.
     */
    fun rigs(): List<String> {
        val named = File(dir, RIGS_DIR).listFiles { f -> f.isDirectory } ?: return listOf("")
        return listOf("") + named.filter { File(it, SPEC_FILE).isFile }.map { it.name }.sorted()
    }

    /**
     * The names of the files INSIDE a character's folder.
     *
     * They live here rather than on CharacterStore because a folder that cannot say what it
     * contains is a folder whose layout is written down in the places that read it -- and the
     * store's own companion is private, so a file name over there is a name a folder cannot
     * even see.
     */
    companion object {
        val SPEC_FILE = "character.json"

        /** Where the drawings are: beside the spec, so a rig is one folder with two things in it. */
        val PARTS_DIR = "parts"

        /** The extra rigs of one pet. See [rigDir]. */
        val RIGS_DIR = "rigs"

        /** The picture a rig is adjusted against. One per rig: it is a picture of that body. */
        val REFERENCE_FILE = "reference.png"

        /** Saved poses live beside the spec, not inside it: they are the user's, not the package's. */
        val POSES_FILE = "poses.json"

        /**
         * 动画，和 poses.json 一个地方、一个理由：帧里写的是**骨头名字**的角度，
         * 换一套身体那些名字就不存在了。
         */
        val ANIMATIONS_FILE = "animations.json"

        /** One folder per KIND of particle (not per drop). See Subjects. */
        val PARTICLES_DIR = "particles"

        /**
         * The user's own drawing of a kind of particle, inside that kind's folder.
         *
         * It is a PNG rather than a description of a shape on purpose: what the user draws is a
         * BRUSH MARK, and the only thing that can reproduce a brush mark is the pixels of it.
         */
        val SHAPE_FILE = "shape.png"
    }

    fun version(): Int = try {
        JSONObject(specText()).optInt("version", 0)
    } catch (e: Exception) {
        0
    }

    /** How many bones actually have artwork on disk. */
    fun partCount(bones: List<String>): Int = bones.count { partFile(it).isFile }

    /** Where a kind of particle keeps its shape and its rules: particles/<粒子>/. */
    fun particleDir(id: String): File = File(File(dir, PARTICLES_DIR), id)

    /**
     * The shape the user painted for a kind of particle.
     *
     * Beside the kind's logic.json rather than in a folder of its own, because it is the same
     * thing: everything the app knows about one kind of particle lives in one folder, and a
     * shape in a second place is a shape that gets left behind when the kind is deleted.
     */
    fun particleArt(id: String): File = File(particleDir(id), SHAPE_FILE)
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
            val spec = File(target, CharacterFolder.SPEC_FILE)
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
        return dirs.filter { File(it, CharacterFolder.SPEC_FILE).isFile }
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
        return if (File(dir, CharacterFolder.SPEC_FILE).isFile) CharacterFolder(dir) else null
    }

    // ── 新建与复制一只桌宠 ───────────────────────────────────────────────────

    /**
     * The first free name of the form `<prefix>`, `<prefix>2`, `<prefix>3` … .
     *
     * Used for new pets and for importing a package whose name is taken: importing must never
     * write over a pet that is already there, and asking the user to invent a name before they
     * have seen what is in the package is asking them to name somebody else's pet.
     */
    fun freeId(prefix: String, taken: Set<String>? = null): String {
        val used = taken ?: list().map { it.id }.toSet()
        val base = RigEdit.sanitise(prefix).ifEmpty { "pet" }
        if (base !in used) return base
        var n = 2
        while (base + n in used) n++
        return base + n
    }

    /**
     * A new pet: one copied from the bundled one, or a copy of an existing package.
     *
     * [from] empty means "the one that ships with the app" -- the素体, which is what somebody
     * starting from nothing wants. A copy of an existing pet takes the WHOLE folder: its
     * drawings, its rigs, its rules, its particles, its liquids and its poses, because that is
     * what "the same pet again" means and it is also how somebody makes a variant without
     * risking the original.
     *
     * The ids inside the copied specs are stamped with the new name (see [stampId]): a pet whose
     * character.json still says `female_base` shows that name in the editor and in the 变身
     * check, and a package that lies about its own name is a package nobody can debug.
     */
    fun createCharacter(name: String, from: String = ""): String? {
        val clean = RigEdit.sanitise(name)
        if (clean.isEmpty()) return null
        val target = File(root, clean)
        if (target.exists()) return null
        return try {
            if (from.isEmpty()) {
                val bundled = BUNDLED_ROOT + "/" + BUNDLED_FALLBACK
                if ((context.assets.list(bundled) ?: emptyArray()).isEmpty()) return null
                target.mkdirs()
                copyAssetTree(bundled, target)
            } else {
                val source = folder(from) ?: return null
                copyDir(source.dir, target)
            }
            stampId(target, clean)
            clean
        } catch (e: Exception) {
            target.deleteRecursively()
            null
        }
    }

    /**
     * Every spec inside one pet folder says whose pet it is.
     *
     * The default rig's spec and each `rigs/<套名>/character.json`: all of them belong to the
     * same pet, so all of them carry the same id. A copied file keeps the name of the folder it
     * came from otherwise, and that name is shown to the user (the rig editor's first line, the
     * bench's status line) and compared against by 变身.
     */
    private fun stampId(petDir: File, id: String) {
        val specs = mutableListOf(File(petDir, CharacterFolder.SPEC_FILE))
        File(petDir, CharacterFolder.RIGS_DIR).listFiles()?.forEach {
            if (it.isDirectory) specs.add(File(it, CharacterFolder.SPEC_FILE))
        }
        for (spec in specs) {
            if (!spec.isFile) continue
            try {
                val text = spec.readText()
                val root = JSONObject(text)
                if (root.optString("id", "") == id) continue
                root.put("id", id)
                writeText(spec, root.toString(2))
            } catch (e: Exception) {
                // A spec that will not parse is left exactly as it is: rewriting a broken file
                // from a parse that failed would turn "unreadable" into "empty".
            }
        }
    }

    // ── 骨骼套 ──────────────────────────────────────────────────────────────

    /**
     * Another rig for one pet: a different skeleton, with its own artwork.
     *
     * [from] is the rig to copy, or empty for "the default one"; [art] says whether the
     * drawings come along. Both answers are things somebody actually wants: 「同一个身体的机械
     * 形态」 is a copy of everything, and 「骨架先搭好，图我自己再画」 is the skeleton alone.
     *
     * The name becomes a folder name, so it goes through the same filter bone names do -- a
     * rig is a folder, and a folder with a slash in its name cannot exist. Returns the name
     * that was actually used, or null when there is already a rig with it.
     */
    fun addRig(id: String, name: String, from: String = "", art: Boolean = true): String? {
        val pet = folder(id) ?: return null
        val clean = RigEdit.sanitise(name)
        if (clean.isEmpty()) return null
        val source = pet.withRig(from)
        if (!source.specFile.isFile) return null
        val target = pet.withRig(clean)
        if (target.rigDir.exists()) return null
        return try {
            target.rigDir.mkdirs()
            source.specFile.copyTo(target.specFile, overwrite = true)
            if (art) copyDir(source.partsDir, target.partsDir)
            clean
        } catch (e: Exception) {
            null
        }
    }

    /** Forget a rig: its skeleton, its drawings and its poses. The default one is not a folder. */
    fun deleteRig(id: String, name: String): Boolean {
        if (name.isEmpty()) return false
        val pet = folder(id) ?: return false
        val dir = pet.withRig(name).rigDir
        if (!dir.isDirectory) return false
        return dir.deleteRecursively()
    }

    /**
     * Put ONE rig's skeleton back to the one that ships with the app.
     *
     * The report was 「搞一个重置骨骼的功能」, and the thing that has no way back is the rig
     * itself: dragging joints in 改骨骼 moves them for good, and 清空 leaves one bone and a
     * blank canvas. This restores the spec -- bones, layers, chains, physics numbers -- while
     * leaving everything that is the pet's or the user's: the drawings in this rig's `parts/`
     * stay (a bone with the same name keeps its picture), the poses stay, the rules, the
     * particles, the liquids and the OTHER rigs are not touched at all.
     *
     * The spec that comes back says `female_base` inside it, so its id is stamped with the pet's
     * own name on the way in (see [stampId]).
     */
    fun resetRig(folder: CharacterFolder): Boolean {
        val asset = BUNDLED_ROOT + "/" + BUNDLED_FALLBACK + "/" + CharacterFolder.SPEC_FILE
        return try {
            folder.rigDir.mkdirs()
            val temp = File(folder.rigDir, CharacterFolder.SPEC_FILE + ".part")
            context.assets.open(asset).use { input ->
                temp.outputStream().use { out -> input.copyTo(out) }
            }
            if (temp.length() <= 0L) return false
            if (folder.specFile.exists()) folder.specFile.delete()
            if (!temp.renameTo(folder.specFile)) return false
            stampId(folder.dir, folder.id)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Rename a rig: the whole folder moves, so the skeleton, the art and the poses move with it. */
    fun renameRig(id: String, from: String, to: String): String? {
        if (from.isEmpty()) return null
        val pet = folder(id) ?: return null
        val clean = RigEdit.sanitise(to)
        if (clean.isEmpty() || clean == from) return null
        val source = pet.withRig(from).rigDir
        val target = pet.withRig(clean).rigDir
        if (!source.isDirectory || target.exists()) return null
        return if (source.renameTo(target)) clean else null
    }

    /** One folder's files copied into another, all the way down. */
    private fun copyDir(from: File, to: File) {
        if (!from.isDirectory) return
        to.mkdirs()
        for (f in from.listFiles() ?: return) {
            if (f.isDirectory) copyDir(f, File(to, f.name)) else f.copyTo(File(to, f.name), overwrite = true)
        }
    }

    /**
     * Keep a picked image as this rig's reference, or drop the one it has.
     *
     * Written to a neighbour first and renamed, like every other file this app owns: a
     * half-copied picture would show up as a reference that is half there.
     */
    fun saveReference(folder: CharacterFolder, open: () -> InputStream?): Boolean {
        val stream = open() ?: return false
        val target = folder.referenceFile
        return try {
            folder.rigDir.mkdirs()
            val temp = File(folder.rigDir, CharacterFolder.REFERENCE_FILE + ".part")
            stream.use { input -> temp.outputStream().use { out -> input.copyTo(out) } }
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (e: IOException) {
            false
        }
    }

    fun clearReference(folder: CharacterFolder): Boolean =
        try {
            folder.referenceFile.delete()
        } catch (e: Exception) {
            false
        }

    /**
     * Write an imported image as the artwork for one bone.
     *
     * The name is decided by the caller from the bone, never from the picked file: the
     * bone name IS the contract that makes assembly work, so a file called holiday.jpg
     * still has to land as upperarm_L.png.
     */
    fun importPart(folder: CharacterFolder, bone: String, open: () -> InputStream?): Boolean {
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
    fun savePart(folder: CharacterFolder, bone: String, bitmap: android.graphics.Bitmap): Boolean {
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
    fun addVariant(folder: CharacterFolder, bone: String, state: String): Boolean {
        // Which LEVEL this state belongs to, decided by who declares it: a state the PART
        // declares is tagged with the bone ("hand_L:sweat"), a global one is just its name.
        // The two levels may share a name -- a hand that sweats and a character that sweats are
        // two different switches -- so a drawing has to say which one it is for. See
        // Subjects.stateTag, and LayerSpec.visible, which looks the tag up as a key.
        val local = loadObjectLogic()[Subjects.part(bone)]
            ?.states?.any { it.id == state } == true
        val tag = if (local) Subjects.stateTag(bone, state) else state
        return try {
            val root = JSONObject(folder.specText())
            val arr = root.optJSONArray("layers") ?: JSONArray()
            var top = 0
            for (i in 0 until arr.length()) {
                val l = arr.getJSONObject(i)
                top = maxOf(top, l.optInt("z", 0))
                if (l.getString("bone") == bone && l.optString("state", "").isEmpty()) {
                    l.put("state", "!" + tag)
                }
            }
            arr.put(
                JSONObject()
                    .put("bone", bone).put("z", top + 10)
                    .put("state", tag).put("art", bone + VARIANT_SEPARATOR + state)
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
        folder: CharacterFolder,
        backToFront: List<LayerSpec>,
        swaps: List<SwapRuleSpec>,
    ): Boolean {
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

            // The temp file goes next to its TARGET, not next to the pet: with more than one
            // rig the spec lives one folder in, and a temp file written at the top while the
            // target is below it is a rename across two different directories that happens to
            // work on the same filesystem -- until it does not.
            folder.rigDir.mkdirs()
            val temp = File(folder.rigDir, CharacterFolder.SPEC_FILE + ".tmp")
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
    /**
     * Take a kind of particle's shape off the disk.
     *
     * Deleting the file rather than blanking it: "no shape" and "a shape that is entirely
     * transparent" are the same drawing and different intentions, and the one the user means
     * when they press 去掉图案 is the first.
     */
    fun clearParticleArt(folder: CharacterFolder, id: String): Boolean = try {
        val file = folder.particleArt(id)
        !file.exists() || file.delete()
    } catch (e: Exception) {
        false
    }

    /** Where a prop's own files live: its rules, and the pattern it drags behind it. */
    fun propTrail(id: String): File =
        File(File(propsDir, id), dev.atp.pet.engine.prop.PropSpecs.TRAIL_FILE)

    /** The drawing of one piece of a rope prop. See PropSpecs.ROPE_FILE. */
    fun propRope(id: String): File =
        File(File(propsDir, id), dev.atp.pet.engine.prop.PropSpecs.ROPE_FILE)

    fun saveRig(
        folder: CharacterFolder,
        bones: List<BoneSpec>,
        backToFront: List<String>,
        renames: Map<String, String> = emptyMap(),
        nodes: List<NodeSpec> = emptyList(),
    ): Boolean {
        if (bones.isEmpty()) return false
        if (RigEdit.problem(bones) != null) return false
        if (RigEdit.nodeProblem(bones, nodes) != null) return false
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
                // What the joint is allowed to do, what it collides as, and whether the world
                // and a finger can feel it at all. Written every time rather than only for a
                // new bone, because these are the fields the attribute editor changes — and
                // they are the ones that actually reach the solver and the world. A flag left
                // out here is a switch that silently flips back the next time the rig is saved,
                // which is exactly the shape of the bug tools/store_check.py exists for.
                o.put("limits", JSONArray(listOf(b.minAngle.toDouble(), b.maxAngle.toDouble())))
                o.put("collider", JSONObject()
                    .put("type", b.colliderType)
                    .put("radius", b.colliderRadius.toDouble()))
                o.put("collides", b.collides)
                o.put("grabbable", b.grabbable)
                // 刚度 ×: this joint's own spring, as a multiple of the character's. THIS IS THE
                // FIELD THE ATTRIBUTE EDITOR CHANGES AND THE ONE THAT WAS MISSING HERE -- a
                // stiffness somebody tuned came back as whatever the file already said (or 1.0
                // for a new bone), silently, the moment they pressed 保存骨骼. It is written
                // only when it is not 1.0, because "not there" already means 1.0 and a file
                // should not grow a physics object on all nineteen bones the first time it is
                // saved; going back to 1.0 takes the key away again.
                val phys = o.optJSONObject("physics") ?: JSONObject()
                if (b.stiffness != 1f) {
                    phys.put("stiffness", b.stiffness.toDouble())
                } else {
                    phys.remove("stiffness")
                }
                if (phys.length() == 0) o.remove("physics") else o.put("physics", phys)
                arr.put(o)
            }
            root.put("bones", arr)

            // Nodes are written WHOLE rather than carried over field by field like a bone:
            // there is nothing in the file a node has that the editor does not show, so a
            // node that is not in the list is a node somebody deleted -- and the list is the
            // only place that can say that.
            val nodesArr = JSONArray()
            for (n in nodes) {
                nodesArr.put(
                    JSONObject()
                        .put("name", n.name)
                        .put("bone", n.bone)
                        .put("at", n.at.toDouble())
                        .put("radius", n.radius.toDouble())
                        .put("prop", n.prop)
                )
            }
            root.put("nodes", nodesArr)

            val live = bones.map { it.name }.toSet()
            // **Distinct**, and this is a real bug rather than tidiness.
            //
            // [backToFront] comes from the editor's `rigLayers()`, which names one bone per
            // LAYER — a bone that has a drawing for a state owns two layers and therefore
            // appears twice, and three times for two states. The loop below means "write this
            // bone's layers NOW", so every occurrence wrote ALL of that bone's layers again:
            // two became four, four became sixteen. The report was 「保存骨骼两次就开始掉帧」,
            // and the file had been growing quadratically under every save.
            val order = (backToFront.filter { it in live } +
                bones.map { it.name }.filter { it !in backToFront }).distinct()

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
            val layerSeen = HashSet<String>()
            for (i in 0 until (oldLayers?.length() ?: 0)) {
                val l = oldLayers!!.getJSONObject(i)
                val name = l.getString("bone")
                val now = renames[name] ?: name
                // A renamed bone keeps its layers, and they have to say the new name.
                l.put("bone", now)
                // The ART key names a file, so it follows the bone as well. Without this a
                // renamed bone still draws its drawing for a state -- the old file name is
                // still what the layer says -- but the file is no longer recognised as
                // belonging to the bone, so the part's folder stops showing it and deleting
                // the drawing stops finding it.
                val artKey = l.optString("art", "")
                if (artKey.isNotEmpty()) {
                    for ((from, to) in renames) {
                        if (artKey == from || artKey.startsWith(from + VARIANT_SEPARATOR)) {
                            l.put("art", to + artKey.removePrefix(from))
                            break
                        }
                    }
                }
                // 层一模一样的重复：同一根骨头、同一张图、同一个状态。旧版本的保存把它们
                // 平方过（2 → 4 → 16），所以老文件里可能堆着上百层 —— 折叠在这里做，因为
                // 这是唯一知道"什么叫重复"的地方。它们画的是同一个东西、顺序也一样，
                // 留第一层就是它们全部的意思。
                val same = now + "|" + l.optString("art", "") + "|" + l.optString("state", "")
                if (!layerSeen.add(same)) continue
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
            // And the drawings for its states, which live beside it as from__state.png.
            val parts = folder.partsDir.listFiles() ?: emptyArray()
            for (f in parts) {
                if (!f.isFile || !f.name.endsWith(".png")) continue
                val stem = f.name.removeSuffix(".png")
                if (!stem.startsWith(from + VARIANT_SEPARATOR)) continue
                val state = stem.removePrefix(from + VARIANT_SEPARATOR)
                f.renameTo(File(folder.partsDir, to + VARIANT_SEPARATOR + state + ".png"))
            }
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

    // ── 桌宠包 ──────────────────────────────────────────────────────────────

    /**
     * Write one pet out as a single file.
     *
     * Everything in the folder goes in -- the rig, the drawings, the other rigs, the rules, the
     * particles, the liquids, the poses, the reference pictures -- because a package that leaves
     * something behind is a package that restores a pet somebody does not recognise. [out] is
     * whatever the system file picker handed back, so this writes and closes and owns nothing.
     */
    fun exportPackage(folder: CharacterFolder, out: OutputStream): Boolean =
        PetPackage.write(folder.dir, folder.id, out)

    /**
     * Read a package in as a NEW pet, and answer its name.
     *
     * Never over a pet that is already there: the name in the package is what the sender called
     * it, and two people naming their pet 小白 is not a reason to lose one of them. A name that
     * is taken gets a number (see [freeId]).
     */
    fun importPackage(fallback: String = "pet", open: () -> InputStream?): String? {
        val stream = open() ?: return null
        root.mkdirs()
        // Copied to a file first so it can be read twice: the manifest says what is inside, and
        // a stream cannot be rewound. It also means a half-downloaded package never gets
        // half-extracted.
        val temp = File(root, ".import-" + System.nanoTime() + ".part")
        return try {
            stream.use { input -> temp.outputStream().use { out -> input.copyTo(out) } }
            PetPackage.read(temp, root) { wanted -> freeId(wanted.ifEmpty { fallback }) }
        } catch (e: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    // ── 动作预设 ────────────────────────────────────────────────────────────

    /** A saved pose: the joint angles the character holds while it is "doing" this action. */
    data class Pose(val name: String, val angles: Map<String, Float>)

    /**
     * The poses of ONE RIG of one pet. The folder says which, so the caller cannot forget:
     * poses are bone angles, and asking for them by pet id alone was a question that no
     * longer has one answer.
     */
    fun loadPoses(folder: CharacterFolder): List<Pose> {
        val file = folder.posesFile
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

    fun savePose(folder: CharacterFolder, name: String, angles: Map<String, Float>): Boolean {
        return try {
            val kept = loadPoses(folder).filter { it.name != name }
            val arr = JSONArray()
            for (p in kept + Pose(name, angles)) {
                val a = JSONObject()
                for ((bone, value) in p.angles) a.put(bone, value.toDouble())
                arr.put(JSONObject().put("name", p.name).put("angles", a))
            }
            folder.rigDir.mkdirs()
            val temp = File(folder.rigDir, CharacterFolder.POSES_FILE + ".tmp")
            temp.writeText(arr.toString(2))
            val target = folder.posesFile
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (e: Exception) {
            false
        }
    }

    fun deletePose(folder: CharacterFolder, name: String): Boolean {
        return try {
            val arr = JSONArray()
            for (p in loadPoses(folder)) {
                if (p.name == name) continue
                val a = JSONObject()
                for ((bone, value) in p.angles) a.put(bone, value.toDouble())
                arr.put(JSONObject().put("name", p.name).put("angles", a))
            }
            val temp = File(folder.rigDir, CharacterFolder.POSES_FILE + ".tmp")
            temp.writeText(arr.toString(2))
            val target = folder.posesFile
            if (target.exists()) target.delete()
            temp.renameTo(target)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 这个骨骼套的动画。
     *
     * 和 [loadPoses] 一样按**骨骼套**读：一帧里是骨头名字，换一套身体就一个都对不上。
     * 读坏了(半截文件、手改错了)就当没有 —— 一个动画读不出来不该让整个测试场空掉，和
     * character.json 那边同一条规矩：坏文件降级，不传染。
     */
    fun loadAnimations(folder: CharacterFolder): List<AnimationSpec> {
        val file = folder.animationsFile
        if (!file.isFile) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isEmpty()) return@mapNotNull null
                val frameArr = o.optJSONArray("frames") ?: JSONArray()
                val frames = (0 until frameArr.length()).mapNotNull { j ->
                    val f = frameArr.optJSONObject(j) ?: return@mapNotNull null
                    val angleObj = f.optJSONObject("angles")
                    val angles = LinkedHashMap<String, Float>()
                    if (angleObj != null) {
                        for (key in angleObj.keys()) {
                            angles[key] = angleObj.optDouble(key, 0.0).toFloat()
                        }
                    }
                    AnimFrame(
                        angles = angles,
                        state = f.optString("state", ""),
                        seconds = f.optDouble("seconds", 0.4).toFloat(),
                    )
                }
                AnimationSpec(
                    id = id,
                    name = o.optString("name", id),
                    frames = frames,
                    // 速度原样读进来，夹的动作在 Anim.speedOf（播放那一刻）—— 那里是唯一
                    // 一次"这个数会变成时间"的地方，手改出来的 0 或者负数在那里被挡住，
                    // 而不是在这里被悄悄改掉、让用户以为文件里写的就是 1。
                    speed = o.optDouble("speed", 1.0).toFloat(),
                    loop = o.optBoolean("loop", true),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 写一段动画（同 id 覆盖），和 poses.json 一样走临时文件 + 改名。 */
    fun saveAnimation(folder: CharacterFolder, spec: AnimationSpec): Boolean {
        return try {
            val kept = loadAnimations(folder).filter { it.id != spec.id }
            writeAnimations(folder, kept + spec)
        } catch (e: Exception) {
            false
        }
    }

    fun deleteAnimation(folder: CharacterFolder, id: String): Boolean {
        return try {
            writeAnimations(folder, loadAnimations(folder).filter { it.id != id })
        } catch (e: Exception) {
            false
        }
    }

    private fun writeAnimations(folder: CharacterFolder, all: List<AnimationSpec>): Boolean {
        val arr = JSONArray()
        for (a in all) {
            val frames = JSONArray()
            for (f in a.frames) {
                val angles = JSONObject()
                for ((bone, value) in f.angles) angles.put(bone, value.toDouble())
                frames.put(
                    JSONObject()
                        .put("angles", angles)
                        .put("state", f.state)
                        .put("seconds", f.seconds.toDouble())
                )
            }
            arr.put(
                JSONObject()
                    .put("id", a.id).put("name", a.name)
                    .put("speed", a.speed.toDouble()).put("loop", a.loop)
                    .put("frames", frames)
            )
        }
        folder.rigDir.mkdirs()
        val temp = File(folder.rigDir, ANIMATIONS_FILE + ".tmp")
        temp.writeText(arr.toString(2))
        val target = folder.animationsFile
        if (target.exists()) target.delete()
        temp.renameTo(target)
        return true
    }

    // ── 逻辑（每个角色一份）────────────────────────────────────────────────

    /**
     * A character's rules and numbers.
     *
     * A character that has never been edited simply runs the built-in defaults, so the file
     * is not written until the user changes something. That is deliberate: once it exists
     * it is theirs, and nothing the app ships will ever overwrite a rule somebody wrote.
     */
    /**
     * A character's rules.
     *
     * The shipped defaults stand in for a file that is NOT THERE -- never for one that is
     * there and empty. Delete every stat and every rule and the character keeps having none;
     * quietly handing back the default set would be the app putting rules back that somebody
     * deliberately removed, which is the most confusing thing a file format can do.
     */
    fun loadLogic(id: String): LogicSpec {
        val file = folder(id)?.let { File(it.dir, LOGIC_FILE) }
            ?: return LogicSpec.parse(LogicSpec.DEFAULT)
        if (!file.isFile) return LogicSpec.parse(LogicSpec.DEFAULT)
        return try {
            LogicSpec.parseObject(file.readText())
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
    /**
     * Where a subject's rules live: one folder per thing that can hold logic.
     *
     * ```
     * props/<道具>/logic.json                        一个道具的规则（所有角色共用）
     * characters/<角色>/liquids/<液体>/logic.json      一种液体的规则
     * characters/<角色>/parts/<骨骼>/logic.json        一个部件的规则
     * props/object-logic.json                          旧的：全挤在一个文件里（只读）
     * ```
     *
     * A subject that is not one of those three has no file, and the old one is where its rules
     * will be found instead. See [loadObjectLogic] for the reading rule.
     */
    fun objectLogicFile(folder: CharacterFolder?, subject: String): File? = when {
        Subjects.isProp(subject) ->
            File(File(propsDir, Subjects.propId(subject)), LOGIC_FILE)
        Subjects.isLiquid(subject) && folder != null ->
            File(File(File(folder.dir, LIQUIDS_DIR), Subjects.liquidId(subject)), LOGIC_FILE)
        // A part's folder sits INSIDE parts/, beside the drawings: parts/hand_L/ next to
        // parts/hand_L.png and parts/hand_L__sweat.png.
        Subjects.isPart(subject) && folder != null ->
            File(File(folder.partsDir, Subjects.partId(subject)), LOGIC_FILE)
        // Same shape as a liquid's: the character DECLARES the particle in its own logic.json,
        // and the kind's rules live in a folder of their own beside the other kinds'.
        Subjects.isParticle(subject) && folder != null ->
            File(
                File(File(folder.dir, CharacterFolder.PARTICLES_DIR), Subjects.particleId(subject)),
                LOGIC_FILE,
            )
        else -> null
    }

    /**
     * Every subject's rules, from wherever they live now.
     *
     * The new locations are read first, and the old single file is the FALLBACK: rules written
     * before the folders existed are the user's, and an upgrade must not lose them. Nothing is
     * ever written back to the old file, so it retires by itself once every subject has been
     * saved once.
     *
     * The one rule that is easy to get wrong, and has been got wrong here before: a new file
     * that EXISTS AND IS EMPTY is not a file that is missing. Empty means "this thing has no
     * rules"; falling back would be the app putting back rules somebody deleted.
     */
    fun loadObjectLogic(folder: CharacterFolder? = null): Map<String, LogicSpec> {
        val out = LinkedHashMap<String, LogicSpec>()
        for ((subject, spec) in readObjectLogicFile(File(propsDir, OBJECT_LOGIC_FILE))) {
            out[subject] = spec
        }
        // The new locations win, including when they say "nothing".
        for (file in walkLogicFiles(propsDir)) {
            val id = file.parentFile?.name ?: continue
            out[Subjects.prop(id)] = readOneObjectLogic(file) ?: continue
        }
        if (folder != null) {
            for (file in walkLogicFiles(File(folder.dir, LIQUIDS_DIR))) {
                val id = file.parentFile?.name ?: continue
                out[Subjects.liquid(id)] = readOneObjectLogic(file) ?: continue
            }
            for (file in walkLogicFiles(folder.partsDir)) {
                val id = file.parentFile?.name ?: continue
                out[Subjects.part(id)] = readOneObjectLogic(file) ?: continue
            }
            for (file in walkLogicFiles(File(folder.dir, CharacterFolder.PARTICLES_DIR))) {
                val id = file.parentFile?.name ?: continue
                out[Subjects.particle(id)] = readOneObjectLogic(file) ?: continue
            }
        }
        return out
    }

    /** Every logic.json exactly one folder down from [dir], in a stable order. */
    private fun walkLogicFiles(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { File(it, LOGIC_FILE) }
            .filter { it.isFile }

    private fun readOneObjectLogic(file: File): LogicSpec? = try {
        LogicSpec.parseObject(file.readText())
    } catch (e: Exception) {
        null
    }

    private fun readObjectLogicFile(file: File): Map<String, LogicSpec> {
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

    fun saveObjectLogic(folder: CharacterFolder?, subject: String, spec: LogicSpec): Boolean {
        val file = objectLogicFile(folder, subject) ?: return false
        return writeText(file, LogicSpec.toJson(spec))
    }

    /** Forget a subject's rules outright: delete the file that holds them. */
    fun forgetObjectLogic(folder: CharacterFolder?, subject: String): Boolean {
        val file = objectLogicFile(folder, subject) ?: return false
        return try {
            file.delete()
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
        folder.rigDir.mkdirs()
        val temp = File(folder.rigDir, CharacterFolder.SPEC_FILE + ".tmp")
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
    fun partDrawings(folder: CharacterFolder, bone: String): List<PartDrawing> {
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
    fun deleteDrawing(folder: CharacterFolder, bone: String, artKey: String): Boolean {
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

    fun clearPart(folder: CharacterFolder, bone: String): Boolean {
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

        /** What "a new pet" is copied from when nothing else is named. The素体. */
        const val BUNDLED_FALLBACK = "female_base"

        /** Rules and numbers are the user's too, for the same reason. */
        const val LOGIC_FILE = "logic.json"

        private val RULE_LISTS = listOf("parts", "behind")

        /** Props are shared by every character, so they sit at the top level. */
        const val PROPS_DIR = "props"

        /** The logic of every prop and every liquid, keyed by subject. See Subjects. */
        const val OBJECT_LOGIC_FILE = "object-logic.json"

        /** One folder per thing that holds logic: characters/<角色>/liquids/<液体>/logic.json. */
        const val LIQUIDS_DIR = "liquids"

        const val PROPS_FILE = "props.json"
    }
}