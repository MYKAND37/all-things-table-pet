package dev.atp.pet.ui

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * One drag, frame by frame, as a CSV that can leave the phone.
 *
 * The buzz has now been diagnosed twice from tools/ragdoll.py and both times the phone
 * disagreed with the reference: the rate is 17-39% in Python and 100% in a real hand. That
 * gap is a thing the reference does not model, and no summary number can say WHICH thing,
 * because a summary has already thrown the frames away. So this writes the frames: one row
 * per step, with the finger, the grip, the four signed phase contributions and the constants
 * that were in force, so the argument about the cause can be had over 7200 rows instead of
 * over two percentages.
 *
 * Three things live here and nothing else does:
 *
 *   * the FILE. Under getExternalFilesDir(null)/diag/ rather than filesDir, because half of
 *     "get the data off the phone" is a path the user can reach with a cable -- and the other
 *     half is the share button on the panel, which hands this same file to whatever app the
 *     user picks. See PhysicsSandboxView.onShareFile.
 *   * the FLUSH POLICY. Every [ROWS_PER_FLUSH] rows, not every row: a flush is a syscall, and
 *     putting disk latency inside the frame loop would put this recorder's own jitter into
 *     the data it exists to record. Half a second is the most a kill can cost.
 *   * the CAP, so a long evening of dragging cannot fill the user's storage. It stops itself
 *     and the panel says so; see PhysicsSandboxView's 导出诊断 block.
 *
 * The row text itself is built by the bench, which is the only thing that knows what a frame
 * contained. This class writes what it is handed and counts it.
 */
class DragDiagRecorder {

    /** The file being written, or the last one written. Null before the first recording. */
    var file: File? = null
        private set

    /** Rows written, header excluded. Also the frame number of the next row, plus one. */
    var rows = 0
        private set

    /** Physics time inside THIS recording, in seconds: the sum of the dt's handed to row(). */
    var seconds = 0f
        private set

    /** True while a recording is open. False after stop(), after the cap, and after a fault. */
    val active: Boolean get() = writer != null

    /** True when the cap is what closed it, as opposed to a finger or a failed write. */
    val full: Boolean get() = rows >= MAX_ROWS

    /** True when a write failed and the recording closed itself. Read once, for the reason. */
    var failed = false
        private set

    private var writer: Writer? = null
    private var unflushed = 0

    /**
     * Open a new file and write its header. False when the file cannot be created at all.
     *
     * Whatever was open is closed first: one recording at a time, so that "the file on the
     * panel" and "the file being written" are never two different files.
     */
    fun start(dir: File, name: String, header: String): Boolean {
        stop()
        failed = false
        if (!dir.isDirectory && !dir.mkdirs()) {
            failed = true
            return false
        }
        return try {
            val f = File(dir, name)
            val w = BufferedWriter(
                OutputStreamWriter(FileOutputStream(f, false), Charsets.UTF_8),
                BUFFER_CHARS,
            )
            w.write(header)
            w.newLine()
            w.flush()
            writer = w
            file = f
            rows = 0
            seconds = 0f
            unflushed = 0
            true
        } catch (e: Exception) {
            // Deliberately broad: this runs on the render thread of a running bench, and a
            // storage full, a card pulled out or a path this app may not write are all the
            // same answer here -- no record, and the panel says so instead of the app dying.
            failed = true
            writer = null
            false
        }
    }

    /**
     * Append one frame. [dt] is the step that frame was given, in seconds, and it is added to
     * [seconds] AFTER the row is written: the t column is when the frame started.
     *
     * False when nothing was written -- no recording open, the cap, or a failed write. The
     * caller reads [full] and [failed] to find out which, on the frame it happens.
     */
    fun row(line: String, dt: Float): Boolean {
        val w = writer ?: return false
        try {
            w.write(line)
            // A bare "\n" rather than Writer.newLine(): that extension is not resolvable with
            // this module's Kotlin setup (CI: "Unresolved reference: newLine" at this line),
            // and the CSV is read by a computer either way. Android writes "\n"; a reader on
            // Windows copes with it, which is more than can be said for the build not existing.
            w.write("\n")
        } catch (e: IOException) {
            failed = true
            stop()
            return false
        }
        rows++
        seconds += dt
        unflushed++
        if (unflushed >= ROWS_PER_FLUSH) {
            try {
                w.flush()
            } catch (e: IOException) {
                failed = true
                stop()
                return false
            }
            unflushed = 0
        }
        // Closed here rather than on the next row: the file has to be complete and readable
        // the moment the panel says it is done, and the panel reads `active` to say that.
        if (rows >= MAX_ROWS) stop()
        return true
    }

    /** Close the file. Safe to call twice, and safe to call when nothing was ever opened. */
    fun stop() {
        val w = writer ?: return
        writer = null
        try {
            w.flush()
            w.close()
        } catch (e: IOException) {
            failed = true
        }
    }

    companion object {
        /**
         * The cap: a minute at 120 Hz, which is a whole drag and then some.
         *
         * Long enough that a full minute of shaking is in the file, short enough that the
         * file is a few megabytes of text rather than a full card, and it ends on its own so
         * that forgetting to stop is not a thing that can happen to somebody's storage.
         */
        const val MAX_ROWS = 7200

        /** How many rows may be buffered before the write is forced out. See the class note. */
        private const val ROWS_PER_FLUSH = 64

        /** One row is about 200 characters; this holds a few hundred of them. */
        private const val BUFFER_CHARS = 1 shl 16
    }
}
