package dev.atp.pet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.physics.Ragdoll
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.Skeleton
import dev.atp.pet.render.PartLibrary
import dev.atp.pet.render.PartRenderer
import kotlin.math.min

/**
 * The physics bench: the character is dropped into a box with gravity and a floor, and a
 * finger can pick it up by any part of it and throw it.
 *
 * Posing lives in 桌宠管理; motion lives here. Nothing in this view knows about bones
 * beyond drawing them — the skeleton is placed entirely by the ragdoll.
 */
class PhysicsSandboxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var spec: CharacterSpec? = null
    private var skeleton: Skeleton? = null
    private var renderer: PartRenderer? = null
    private var library: PartLibrary? = null
    private var ragdoll: Ragdoll? = null

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var heldBone: String? = null
    private var pinTarget = Vec2.ZERO
    private var lastTapAt = 0L
    private var lastFrameNs = 0L

    private val density = resources.displayMetrics.density

    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x55000000
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x12000000
    }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = 0xCC222233.toInt()
    }

    var onInfo: ((String) -> Unit)? = null

    /** 0 = limp ragdoll, 1 = holds a pose. Changing it takes effect immediately. */
    var stiffness: Float = 0f
        set(value) {
            field = value
            ragdoll?.stiffness = value
            invalidate()
        }

    fun load(folder: CharacterFolder) {
        library?.release()
        val parsed = CharacterSpec.parse(folder.specText())
        val built = parsed.buildSkeleton()
        built.update()
        spec = parsed
        skeleton = built

        val loaded = PartLibrary.load(folder.partsDir, parsed.bones.map { it.name })
        library = loaded
        renderer = if (loaded.isEmpty) null else PartRenderer(
            built,
            loaded,
            parsed.layers.sortedBy { it.z }.map { it.bone },
        )

        ragdoll = Ragdoll(built, parsed, stiffness)
        heldBone = null
        lastFrameNs = System.nanoTime()
        onInfo?.invoke(folder.id + " · " + loaded.size + " parts · 抓住任意部位拖动")
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = spec ?: return
        val pad = 6f * density
        scale = min((w - pad * 2) / s.canvasWidth, (h - pad * 2) / s.floorY)
        offsetX = (w - s.canvasWidth * scale) / 2f
        offsetY = (h - s.floorY * scale) / 2f
    }

    private fun vx(x: Float) = offsetX + x * scale
    private fun vy(y: Float) = offsetY + y * scale
    private fun toWorld(x: Float, y: Float) = Vec2((x - offsetX) / scale, (y - offsetY) / scale)

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val sk = skeleton ?: return
        val rag = ragdoll ?: return

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else (now - lastFrameNs) / 1_000_000_000f
        lastFrameNs = now
        rag.step(dt, heldBone, pinTarget)

        // ---- sandbox ----
        val step = 256f
        var gx = 0f
        while (gx <= s.canvasWidth) {
            canvas.drawLine(vx(gx), vy(0f), vx(gx), vy(s.floorY), gridPaint)
            gx += step
        }
        var gy = 0f
        while (gy <= s.floorY) {
            canvas.drawLine(vx(0f), vy(gy), vx(s.canvasWidth), vy(gy), gridPaint)
            gy += step
        }
        canvas.drawLine(vx(0f), vy(s.floorY), vx(s.canvasWidth), vy(s.floorY), groundPaint)

        // ---- character ----
        val art = renderer
        if (art != null) {
            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scale)
            art.draw(canvas)
            canvas.restore()
        } else {
            for (bone in sk.bones) {
                val h = bone.worldPosition
                val t = bone.tipPosition()
                bonePaint.color = colourFor(bone.name)
                canvas.drawLine(vx(h.x), vy(h.y), vx(t.x), vy(t.y), bonePaint)
                jointPaint.color = bonePaint.color
                canvas.drawCircle(vx(h.x), vy(h.y), 4f * density, jointPaint)
            }
        }

        val mode = when {
            stiffness <= 0.01f -> "松垮"
            stiffness >= 0.99f -> "硬挺"
            else -> "半软"
        }
        val state = if (heldBone != null) "抓住 " + heldBone else if (rag.grounded) "着地" else "空中"
        canvas.drawText("刚度 $mode · $state", 10f * density, 16f * density, textPaint)

        postInvalidateOnAnimation()
    }

    private fun colourFor(name: String): Int = when {
        name.startsWith("shoulder") || name.startsWith("upperarm") ||
            name.startsWith("forearm") || name.startsWith("hand") ->
            if (name.endsWith("_L")) 0xFFE25A8C.toInt() else 0xFFE88C3C.toInt()
        name.startsWith("thigh") || name.startsWith("shin") || name.startsWith("foot") ->
            if (name.endsWith("_L")) 0xFF46AA6E.toInt() else 0xFF3C96B4.toInt()
        else -> 0xFF7C5CE0.toInt()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rag = ragdoll ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastTapAt < 300) {
                    rag.reset()
                    heldBone = null
                    lastTapAt = 0L
                    postInvalidateOnAnimation()
                    return true
                }
                lastTapAt = now

                val p = toWorld(event.x, event.y)
                val bone = rag.grabAt(p) ?: return false
                heldBone = bone.name
                pinTarget = p
                lastFrameNs = System.nanoTime()
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (heldBone == null) return false
                pinTarget = toWorld(event.x, event.y)
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (heldBone == null) return false
                heldBone = null
                rag.release()
                lastFrameNs = System.nanoTime()
                postInvalidateOnAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
