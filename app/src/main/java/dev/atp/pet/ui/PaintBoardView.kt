package dev.atp.pet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * A small square to draw in.
 *
 * It exists because two things in the app are shapes the user has to make rather than pick: the
 * pattern a trail is stamped from, and the shape a particle is. Both are tiny — a brush mark, a
 * star, a leaf — and both were the one thing a phone drawing of them could not survive: an
 * imported PNG from a camera roll is 4000 pixels wide and mostly somebody's kitchen table.
 *
 * What is drawn goes into a [SIZE] × [SIZE] bitmap with a TRANSPARENT background, because both
 * consumers composite it: a particle is stamped over the world, and a trail is stamped over
 * itself, so an opaque square would be a square drawn on the table.
 *
 * The board owns no UI. The tools, the colours and the save button are the caller's business —
 * this is the surface and the strokes, and the undo stack that keeps a stray finger from
 * meaning "start again".
 */
class PaintBoardView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** What is being drawn: the canvas, and the strokes that got it there. */
    private var bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val undoStack = ArrayDeque<Bitmap>()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x33807A6E
    }
    private val backgroundPaint = Paint().apply { color = 0xFFF3F0EA.toInt() }

    /** The colour the brush paints in. The eraser ignores it. */
    var colour: Int = 0xFF2B2A33.toInt()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Brush thickness in BITMAP pixels, so a stroke is the same size however the view is scaled.
     *
     * Deliberately not called `width`: a View already has one of those, it is an Int, and it is
     * the number this file needs for laying the square out.
     */
    var brushWidth: Float = 6f
        set(value) {
            field = value
            invalidate()
        }

    var erasing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var drewAnything = false

    /** Has anything been painted? An empty board means "no shape", not "a blank shape". */
    val isEmpty: Boolean get() = !drewAnything

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        bitmap.recycle()
        bitmap = previous
        // Undoing past the first stroke is not the same as an empty board: what is left is
        // whatever the earlier strokes put there, and only the board's owner knows what that
        // was. So emptiness is only ever cleared by [clear].
        invalidate()
    }

    fun clear() {
        pushUndo()
        bitmap.eraseColor(0)
        drewAnything = false
        invalidate()
    }

    /** Start from a file, if there is one. False means "nothing to load", not "failed". */
    fun load(file: File?): Boolean {
        if (file == null || !file.isFile) return false
        val loaded = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        bitmap.recycle()
        bitmap = Bitmap.createScaledBitmap(loaded, SIZE, SIZE, true)
        if (loaded != bitmap) loaded.recycle()
        drewAnything = true
        undoStack.clear()
        invalidate()
        return true
    }

    /** The picture, as PNG bytes. Transparent where nothing was painted. */
    fun toPng(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    fun saveTo(file: File): Boolean = try {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(toPng()) }
        true
    } catch (e: Exception) {
        false
    }

    private fun pushUndo() {
        undoStack.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, true))
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst().recycle()
    }

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val box = RectF(left, top, left + side, top + side)
        // A chequerboard would say "transparent" better, but it also says "this is a photo
        // editor". A pane of the app's own background does the job: what you see is what the
        // particle will look like, over the table.
        canvas.drawRect(box, backgroundPaint)
        canvas.drawBitmap(bitmap, Rect(0, 0, SIZE, SIZE), box, null)
        canvas.drawRect(box, frame)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val side = min(width, height).toFloat()
        if (side <= 0f) return false
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val x = (event.x - left) / side * SIZE
        val y = (event.y - top) / side * SIZE
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pushUndo()
                lastX = x
                lastY = y
                // A tap is a dot: without this a single tap draws nothing at all, which reads
                // as a board that does not work.
                dot(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!lastX.isNaN()) {
                    line(lastX, lastY, x, y)
                    invalidate()
                }
                lastX = x
                lastY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastX = Float.NaN
                lastY = Float.NaN
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun brushFor(erase: Boolean): Paint {
        val p = if (erase) brush else stroke
        p.color = if (erase) 0 else colour
        p.strokeWidth = brushWidth
        p.xfermode = if (erase) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
        return p
    }

    private fun dot(x: Float, y: Float) {
        val p = brushFor(erasing)
        p.style = Paint.Style.FILL
        Canvas(bitmap).drawCircle(x, y, brushWidth / 2f, p)
        p.style = Paint.Style.STROKE
        drewAnything = drewAnything || !erasing
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
        val p = brushFor(erasing)
        val canvas = Canvas(bitmap)
        // A stroke is drawn as one short segment per move event, and segments meet at a point
        // with a round cap on each. A Path would be one draw call and one join, but the whole
        // picture is 128 pixels wide: the difference is invisible and this way an eraser with
        // PorterDuff.CLEAR and a brush with a colour are the same code.
        canvas.drawLine(x0, y0, x1, y1, p)
        drewAnything = drewAnything || !erasing
    }

    companion object {
        /** The bitmap's side, in pixels. Big enough for a brush mark, small enough to be flat. */
        const val SIZE = 128
        /** How many strokes can be taken back. Each one is a [SIZE]² copy of the canvas. */
        private const val MAX_UNDO = 24
    }
}
