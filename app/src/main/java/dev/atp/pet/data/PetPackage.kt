package dev.atp.pet.data

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * One pet in one file: the whole folder zipped, with a small manifest at the top.
 *
 * A pet is already a folder with everything in it, so "export" is not a new format — it is that
 * folder, written down as one file somebody can send to a friend. What the manifest adds is the
 * two things a folder cannot say about itself: **which format this is** (so a zip that happens
 * to have a character.json in it is not mistaken for one of ours) and **what the pet was
 * called** (so importing it does not have to invent a name).
 *
 * Everything here is written for a file that came from somewhere else. The rules are the dull
 * ones, and each of them is a thing that has actually happened to somebody:
 *
 * - an entry called `../../databases/x` must never be written anywhere (a zip is a list of
 *   paths, and half the entries in a hostile one are the way out of the folder);
 * - an entry that unpacks to a gigabyte must not fill the phone (a zip bomb is small to send);
 * - a package with no `character.json` is not a pet, whatever else is in it.
 */
object PetPackage {

    /** What is inside this file, and it is not negotiable: see [read]. */
    const val FORMAT = 1

    /** The one entry that is not part of the pet folder. */
    const val MANIFEST = "pack.json"

    /** What a package is called on disk. Not enforced on the way in: a picker may rename it. */
    const val EXTENSION = "atppet"

    /** A pet is a few hundred files; a bomb is a few hundred thousand. */
    const val MAX_ENTRIES = 4000

    /** Uncompressed. The bundled pet is ~6 MB, and a rig with 4k drawings is nowhere near this. */
    const val MAX_BYTES = 512L * 1024 * 1024

    /**
     * The path an entry is allowed to become, relative to the pet folder, or null.
     *
     * Null is the answer for anything that is not simply a name inside the folder: absolute
     * paths, drive letters, `..` anywhere in the name, and names long enough to be somebody
     * probing the filesystem rather than naming a file.
     */
    fun safeEntryPath(name: String): String? {
        val clean = name.replace('\\', '/').trim()
        if (clean.isEmpty() || clean.length > 240) return null
        if (clean.startsWith("/")) return null
        if (clean.contains("..")) return null
        if (clean.contains(':')) return null
        if (clean.contains('\u0000')) return null
        return clean.trimStart('/')
    }

    /**
     * Write one pet folder as a package.
     *
     * The manifest goes first, so that a reader can stop early on a file that is not ours
     * instead of unpacking it to find out.
     */
    fun write(petDir: File, id: String, out: OutputStream): Boolean {
        if (!petDir.isDirectory) return false
        val files = petDir.walkTopDown().filter { it.isFile }.toList()
        return try {
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                val manifest = JSONObject()
                    .put("format", FORMAT)
                    .put("kind", "table-pet")
                    .put("id", id)
                    .put("files", files.size)
                    .put("bytes", files.sumOf { it.length() })
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest.toString(2).toByteArray())
                zip.closeEntry()

                for (f in files) {
                    val rel = f.relativeTo(petDir).path.replace(File.separatorChar, '/')
                    val path = safeEntryPath(rel) ?: continue
                    zip.putNextEntry(ZipEntry(path))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Unpack [zipFile] into `root/<名字>`, and answer the name that was used, or null.
     *
     * [freeId] is asked for a name that is not taken, because a package must never be unpacked
     * over a pet that is already there. The result is a new folder under [root]; a package that
     * turns out not to be one leaves nothing behind.
     */
    fun read(zipFile: File, root: File, freeId: (String) -> String): String? {
        var target: File? = null
        return try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().toList()
                if (entries.size > MAX_ENTRIES) return null
                val manifest = manifestOf(zip, entries) ?: return null
                if (manifest.optInt("format", 0) != FORMAT) return null
                if (manifest.optString("kind", "") != "table-pet") return null

                // Where the pet's own files start. A package written by us has them at the top;
                // one that somebody unzipped and re-zipped has them under a folder, and that is
                // a difference no user can see or fix.
                val prefix = commonFolder(entries)
                val id = freeId(manifest.optString("id", ""))
                val dir = File(root, id)
                if (dir.exists()) return null
                target = dir
                if (!dir.mkdirs()) return null

                var total = 0L
                var count = 0
                for (e in entries) {
                    if (e.isDirectory) continue
                    val path = safeEntryPath(e.name) ?: continue
                    if (path == MANIFEST) continue
                    val rel = if (prefix.isEmpty()) path else path.removePrefix(prefix)
                    if (rel.isEmpty()) continue
                    val safe = safeEntryPath(rel) ?: continue
                    count++
                    if (count > MAX_ENTRIES) return null
                    val file = File(dir, safe)
                    file.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> file.outputStream().use { out -> total += copy(input, out) } }
                    if (total > MAX_BYTES) return null
                }

                // A folder with no spec in it is not a pet, whatever else arrived.
                if (!File(dir, CharacterFolder.SPEC_FILE).isFile) return null
                id
            }
        } catch (e: Exception) {
            null
        } finally {
            // Anything that ended in null (or threw) leaves the folder behind: half a pet in
            // the list is worse than a package that did not import.
            val made = target
            if (made != null && !File(made, CharacterFolder.SPEC_FILE).isFile) {
                made.deleteRecursively()
            }
        }
    }

    /** The manifest, from the top of the archive or from inside its one folder. */
    private fun manifestOf(zip: ZipFile, entries: List<ZipEntry>): JSONObject? {
        val entry = entries.firstOrNull { safeEntryPath(it.name)?.endsWith(MANIFEST) == true }
            ?: return null
        return try {
            zip.getInputStream(entry).use { input ->
                JSONObject(input.readBytes().toString(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The one folder everything is inside, with a trailing slash, or "".
     *
     * Only when there IS one: a package whose files are split between the top and a folder is a
     * package somebody assembled by hand, and guessing which half is the pet would be guessing.
     */
    private fun commonFolder(entries: List<ZipEntry>): String {
        val names = entries.filter { !it.isDirectory }
            .mapNotNull { safeEntryPath(it.name) }
            .filter { it != MANIFEST }
        if (names.isEmpty()) return ""
        // The longest DIRECTORY every entry shares. Not just the first segment: a package that
        // was unzipped and zipped again lands one or two levels down (`pets/小白/parts/…`), and
        // taking only `pets/` would leave the spec at `小白/character.json` -- which reads as
        // "no pet in this package" on a package that is nothing but a pet.
        val first = names.first()
        var cut = first.lastIndexOf('/')
        var prefix = if (cut < 0) "" else first.substring(0, cut + 1)
        while (prefix.isNotEmpty() && !names.all { it.startsWith(prefix) }) {
            cut = prefix.dropLast(1).lastIndexOf('/')
            prefix = if (cut < 0) "" else prefix.substring(0, cut + 1)
        }
        return prefix
    }

    /** Copy, counting what actually arrived: an entry can lie about its own size. */
    private fun copy(input: InputStream, out: OutputStream): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return total
    }
}
