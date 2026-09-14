package dev.atp.pet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.Skeleton
import dev.atp.pet.render.PartLibrary
import dev.atp.pet.render.PartRenderer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lining up a freshly picked drawing with the character.
 *
 * Exports are rarely pixel-perfect, and a part that is ten pixels off is a part that
 * never looks right no matter how good the rig is. So the image is placed by hand first,
 * against the rest of the character, and only then baked down.
 *
 * What this confirms is a FULL CANVAS bitmap, whatever the source was — that is the whole
 * reason assembly needs no calibration step anywhere else in the project, and it would be
 * a shame to lose it at the last step.
 */
class PartAlignView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var spec: CharacterSpec? = null
    private var skeleton: Skeleton? = null
    private var library: PartLibrary? = null
    private var renderer: PartRenderer? = null
    private var bone: Bone? = null
    private var source: Bitmap? = null

    /** Where the source image sits in canvas space, and how big. */
    private var imageOffset = Vec2.ZERO
    private var imageScale = 1f

    private var viewScale = 1f
    private var viewOffsetX = 0f
    private var viewOffsetY = 0f

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastMid = Vec2.ZERO

    private val density = resources.displayMetrics.density

    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
        color = 0x33171528
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * density
        color = 0xFFE2653C.toInt()
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x40000000
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF2C7BE5.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = 0xCC222233.toInt()
    }

    var onInfo: ((String) -> Unit)? = null

    fun load(folder: CharacterFolder, boneName: String, bitmap: Bitmap) {
        library?.release()
        val parsed = CharacterSpec.parse(folder.specText())
        val built = parsed.buildSkeleton()
        built.update()
        spec = parsed
        skeleton = built
        bone = built.find(boneName)

        val loaded = PartLibrary.load(folder.partsDir, parsed.bones.map { it.name })
        library = loaded
        renderer = if (loaded.isEmpty) null else PartRenderer(
            built,
            loaded,
            parsed.drawOrder(),
        )

        source = bitmap
        fitToCanvas()
        invalidate()
    }

    /** Treat the source as a full-canvas export: map its width onto the canvas. */
    fun fitToCanvas() {
        val s = spec ?: return
        val b = source ?: return
        val exact = abs(b.width - s.canvasWidth) / s.canvasWidth < 0.02f &&
            abs(b.height - s.canvasHeight) / s.canvasHeight < 0.02f
        imageScale = if (exact) 1f else s.canvasWidth / b.width
        centreOn(Vec2(s.canvasWidth / 2f, s.canvasHeight / 2f))
        report()
    }

    /** Scale the source so its long side matches the target bone, and sit it on the bone. */
    fun fitToBone() {
        val s = spec ?: return
        val b = source ?: return
        val target = bone ?: return
        val long = max(b.width, b.height).toFloat()
        imageScale = if (long < 1f) 1f else (target.length * 1.15f) / long
        centreOn(Vec2((target.worldPosition.x + target.tipPosition().x) / 2f,
                      (target.worldPosition.y + target.tipPosition().y) / 2f))
        report()
    }

    fun nudgeScale(factor: Float) {
        setScale(imageScale * factor, centreOfCanvas())
    }

    fun currentScale(): Float = imageScale

    fun targetBoneName(): String? = bone?.name

    private fun centreOfCanvas(): Vec2 {
        val s = spec ?: return Vec2.ZERO
        return Vec2(s.canvasWidth / 2f, s.canvasHeight / 2f)
    }

    private fun centreOn(p: Vec2) {
        val b = source ?: return
        val w = b.width * imageScale
        val h = b.height * imageScale
        imageOffset = Vec2(p.x - w / 2f, p.y - h / 2f)
    }

    private fun setScale(next: Float, pivot: Vec2) {
        val clamped = next.coerceIn(0.02f, 12f)
        if (clamped == imageScale) return
        // Keep the pivot put on screen while the scale changes, which is what a pinch does.
        val before = Vec2((pivot.x - imageOffset.x) / imageScale, (pivot.y - imageOffset.y) / imageScale)
        imageScale = clamped
        imageOffset = Vec2(pivot.x - before.x * imageScale, pivot.y - before.y * imageScale)
        report()
        invalidate()
    }

    private fun report() {
        val b = source ?: return
        onInfo?.invoke(
            "缩放 " + (imageScale * 100).toInt() + "%  ·  拖动移动，双指缩放" +
                "  ·  原图 " + b.width + "×" + b.height
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = spec ?: return
        val pad = 6f * density
        viewScale = min((w - pad * 2) / s.canvasWidth, (h - pad * 2) / s.floorY)
        viewOffsetX = (w - s.canvasWidth * viewScale) / 2f
        viewOffsetY = (h - s.floorY * viewScale) / 2f
    }

    private fun vx(x: Float) = viewOffsetX + x * viewScale
    private fun vy(y: Float) = viewOffsetY + y * viewScale
    private fun toCanvas(x: Float, y: Float) = Vec2((x - viewOffsetX) / viewScale, (y - viewOffsetY) / viewScale)

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val sk = skeleton ?: return

        canvas.save()
        canvas.translate(viewOffsetX, viewOffsetY)
        canvas.scale(viewScale, viewScale)

        // The rest of the character, faded: enough to line up against, not enough to
        // compete with the thing being placed.
        artPaint.alpha = 90
        renderer?.draw(canvas)
        artPaint.alpha = 255

        for (b in sk.bones) {
            val h = b.worldPosition
            val t = b.tipPosition()
            val paint = if (b === bone) targetPaint else linePaint
            canvas.drawLine(h.x, h.y, t.x, t.y, paint)
        }
        bone?.let {
            val h = it.worldPosition
            val t = it.tipPosition()
            val r = 6f * density / viewScale
            canvas.drawCircle(h.x, h.y, r, targetPaint)
            canvas.drawCircle(t.x, t.y, r, targetPaint)
        }

        source?.let { bmp ->
            val m = Matrix().apply {
                setValues(
                    floatArrayOf(
                        imageScale, 0f, imageOffset.x,
                        0f, imageScale, imageOffset.y,
                        0f, 0f, 1f,
                    )
                )
            }
            canvas.drawBitmap(bmp, m, artPaint)
            // Outline the image so an all-transparent edge is still findable.
            canvas.drawRect(
                imageOffset.x, imageOffset.y,
                imageOffset.x + bmp.width * imageScale,
                imageOffset.y + bmp.height * imageScale,
                boxPaint,
            )
        }

        canvas.drawRect(0f, 0f, s.canvasWidth, s.canvasHeight, framePaint)
        canvas.restore()

        canvas.drawText((bone?.name ?: "?") + "  ·  橙色是目标骨骼", 10f * density, 16f * density, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                lastSpan = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    lastSpan = span(event)
                    lastMid = toCanvas(midX(event), midY(event))
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val s = span(event)
                    val mid = toCanvas(midX(event), midY(event))
                    if (lastSpan > 1f && s > 1f) {
                        setScale(imageScale * (s / lastSpan), lastMid)
                    }
                    imageOffset = Vec2(
                        imageOffset.x + (mid.x - lastMid.x),
                        imageOffset.y + (mid.y - lastMid.y),
                    )
                    lastSpan = s
                    lastMid = mid
                } else {
                    val dx = (event.x - lastX) / viewScale
                    val dy = (event.y - lastY) / viewScale
                    imageOffset = Vec2(imageOffset.x + dx, imageOffset.y + dy)
                    lastX = event.x
                    lastY = event.y
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    private fun midX(e: MotionEvent) = if (e.pointerCount >= 2) (e.getX(0) + e.getX(1)) / 2f else e.x
    private fun midY(e: MotionEvent) = if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2f else e.y

    /** Bake the placement down to a full-canvas part image. */
    fun compose(): Bitmap? {
        val s = spec ?: return null
        val b = source ?: return null
        val out = Bitmap.createBitmap(
            s.canvasWidth.toInt(),
            s.canvasHeight.toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val into = Canvas(out)
        val m = Matrix().apply {
            setValues(
                floatArrayOf(
                    imageScale, 0f, imageOffset.x,
                    0f, imageScale, imageOffset.y,
                    0f, 0f, 1f,
                )
            )
        }
        into.drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun release() {
        library?.release()
        library = null
    }
}
