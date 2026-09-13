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
import kotlin.math.hypot
import kotlin.math.max

/**
 * The physics bench.
 *
 * The play area is wider than the artwork: a pet confined to the width of its own drawing
 * hits a wall the moment it is thrown anywhere. The view is a window onto that area —
 * pinch to zoom, drag the background to pan, and it slides by itself to keep the pet in
 * sight. Dragging the pet throws it.
 *
 * Posing lives in 桌宠管理; motion lives here.
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

    /** world -> view, and the world coordinate sitting at the viewport's top-left. */
    private var viewScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var defaultScale = 1f
    private var framed = false

    private var heldBone: String? = null
    private var pinTarget = Vec2.ZERO
    private var panning = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var lastTapAt = 0L
    private var lastFrameNs = 0L

    private var pinchSpan = 0f

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
    private val canvasPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33C04000
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * density, 8f * density), 0f)
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

    /** null clears back to limp. */
    fun applyPose(angles: Map<String, Float>?) {
        val rag = ragdoll ?: return
        if (angles == null) {
            rag.clearPose()
        } else {
            // Choosing an action is a request to watch it. A pet that has been thrown into
            // a corner would otherwise strike the pose lying on its side, off screen.
            rag.home()
            rag.applyPose(angles)
            framePet()
        }
        stiffness = rag.stiffness
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
            parsed.swaps,
        )

        ragdoll = Ragdoll(built, parsed, stiffness)
        heldBone = null
        framed = false
        lastFrameNs = System.nanoTime()
        onInfo?.invoke(folder.id + " · " + loaded.size + " parts · 拖角色，拖背景平移")
        invalidate()
    }

    // -- viewport -----------------------------------------------------------

    private fun viewWidth(): Float = if (viewScale <= 0f) 0f else width / viewScale
    private fun viewHeight(): Float = if (viewScale <= 0f) 0f else height / viewScale

    private fun clampPan() {
        val s = spec ?: return
        val vw = viewWidth()
        val vh = viewHeight()
        val maxX = max(0f, s.worldWidth - vw)
        val maxY = max(0f, s.floorY - vh)
        panX = panX.coerceIn(0f, maxX)
        panY = panY.coerceIn(0f, maxY)
    }

    /** Centre the window on the pet, at the given zoom. */
    private fun framePet() {
        val s = spec ?: return
        val rag = ragdoll ?: return
        val vw = viewWidth()
        val vh = viewHeight()
        panX = rag.rootPos.x - vw / 2f
        panY = if (vh >= s.floorY) 0f else s.floorY - vh
        clampPan()
        framed = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = spec ?: return
        val pad = 4f * density
        // Fit the height: the pet is tall, and cropping it vertically to fill a phone
        // screen sideways would be a strange default. Horizontal room is the point, and
        // that is what the panning is for.
        defaultScale = (h - pad * 2) / s.floorY
        if (!framed) {
            viewScale = defaultScale
            framePet()
        } else {
            viewScale = viewScale.coerceIn(defaultScale * 0.25f, defaultScale * 6f)
            clampPan()
        }
    }

    private fun vx(worldX: Float) = (worldX - panX) * viewScale
    private fun vy(worldY: Float) = (worldY - panY) * viewScale
    private fun toWorld(x: Float, y: Float) = Vec2(panX + x / viewScale, panY + y / viewScale)

    /** Slide the window only far enough to keep the pet on screen; never fight a pan. */
    private fun follow() {
        val rag = ragdoll ?: return
        val vw = viewWidth()
        if (vw <= 0f) return
        val petX = rag.rootPos.x
        val left = panX + vw * 0.12f
        val right = panX + vw * 0.88f
        when {
            petX < left -> panX -= (left - petX) * 0.12f
            petX > right -> panX += (petX - right) * 0.12f
            else -> return
        }
        clampPan()
    }

    // -- drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val sk = skeleton ?: return
        val rag = ragdoll ?: return

        if (viewScale <= 0f) return
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else (now - lastFrameNs) / 1_000_000_000f
        lastFrameNs = now
        rag.step(dt, heldBone, pinTarget)
        if (!panning) follow()

        val step = 256f
        var gx = 0f
        while (gx <= s.worldWidth) {
            if (vx(gx) >= -2f && vx(gx) <= width + 2f) {
                canvas.drawLine(vx(gx), 0f, vx(gx), vy(s.floorY), gridPaint)
            }
            gx += step
        }
        var gy = 0f
        while (gy <= s.floorY) {
            if (vy(gy) >= -2f && vy(gy) <= height + 2f) {
                canvas.drawLine(0f, vy(gy), width.toFloat(), vy(gy), gridPaint)
            }
            gy += step
        }
        canvas.drawLine(0f, vy(s.floorY), width.toFloat(), vy(s.floorY), groundPaint)

        // Where the artwork's own canvas sits, so the pet's home patch is findable.
        canvas.drawRect(
            vx(0f), vy(0f), vx(s.canvasWidth), vy(s.floorY), canvasPaint
        )

        val art = renderer
        if (art != null) {
            canvas.save()
            canvas.translate(-panX * viewScale, -panY * viewScale)
            canvas.scale(viewScale, viewScale)
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
        val state = if (heldBone != null) "抓住" else if (rag.grounded) "着地" else "空中"
        canvas.drawText(
            "刚度 $mode · $state · 缩放 " + (viewScale / defaultScale * 100).toInt() + "%",
            10f * density, 16f * density, textPaint
        )

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

    // -- input --------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rag = ragdoll ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastTapAt < 300) {
                    rag.reset()
                    heldBone = null
                    framePet()
                    lastTapAt = 0L
                    postInvalidateOnAnimation()
                    return true
                }
                lastTapAt = now

                val p = toWorld(event.x, event.y)
                val bone = rag.grabAt(p)
                if (bone != null) {
                    heldBone = bone.name
                    pinTarget = p
                } else {
                    // Nothing under the finger: the finger is moving the window.
                    panning = true
                    lastPanX = event.x
                    lastPanY = event.y
                }
                lastFrameNs = System.nanoTime()
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger always means zoom, whatever the first one was doing.
                heldBone = null
                panning = false
                pinchSpan = span(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val s = span(event)
                    if (pinchSpan > 1f && s > 1f) {
                        val before = toWorld(midX(event), midY(event))
                        viewScale = (viewScale * (s / pinchSpan))
                            .coerceIn(defaultScale * 0.25f, defaultScale * 6f)
                        val after = toWorld(midX(event), midY(event))
                        // Keep the point between the fingers pinned while zooming.
                        panX += before.x - after.x
                        panY += before.y - after.y
                        clampPan()
                    }
                    pinchSpan = s
                    postInvalidateOnAnimation()
                    return true
                }

                if (heldBone != null) {
                    pinTarget = toWorld(event.x, event.y)
                } else if (panning) {
                    panX -= (event.x - lastPanX) / viewScale
                    panY -= (event.y - lastPanY) / viewScale
                    lastPanX = event.x
                    lastPanY = event.y
                    clampPan()
                }
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (heldBone != null) {
                    heldBone = null
                    rag.release()
                }
                panning = false
                pinchSpan = 0f
                lastFrameNs = System.nanoTime()
                postInvalidateOnAnimation()
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
}