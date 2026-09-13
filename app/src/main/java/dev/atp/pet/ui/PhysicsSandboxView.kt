package dev.atp.pet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.physics.Bounds
import dev.atp.pet.engine.physics.PhysicsBody
import dev.atp.pet.engine.physics.PhysicsWorld
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.Skeleton
import kotlin.math.hypot
import kotlin.math.min

/**
 * The physics bench: the character is dropped into a box with gravity and a floor, and
 * a finger can pick it up and throw it.
 *
 * This is the runtime half of the split the project settled on — posing lives in 桌宠管理,
 * motion lives here. Nothing in this view knows about bones beyond drawing them; the
 * skeleton is placed by whatever the body says its position is.
 */
class PhysicsSandboxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var spec: CharacterSpec? = null
    private var skeleton: Skeleton? = null
    private var world: PhysicsWorld? = null
    private var body: PhysicsBody? = null

    /** Where the collider sits inside the character's own canvas coordinates. */
    private var canvasCentre = Vec2.ZERO

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var grabOffset = Vec2.ZERO
    private var lastTapAt = 0L
    private var lastFrameNs = 0L
    private var flingTracker: Vec2? = null

    private val density = resources.displayMetrics.density

    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x55000000
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x14000000
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33C04000
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
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

    fun load(assetPath: String) {
        val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        val parsed = CharacterSpec.parse(text)
        val built = parsed.buildSkeleton()
        built.update()
        spec = parsed
        skeleton = built

        // Collider = the body only. Arms are left out so a raised arm does not lift the
        // character off the floor.
        val height = parsed.totalHeight
        val width = parsed.bodyWidth
        val top = parsed.headTop
        canvasCentre = Vec2(parsed.centreX, top + height / 2f)

        world = PhysicsWorld(
            gravity = parsed.gravity,
            bounds = Bounds(0f, 0f, parsed.canvasWidth, parsed.floorY),
        )
        body = world!!.add(
            PhysicsBody(
                position = Vec2(canvasCentre.x, 360f),
                halfExtents = Vec2(width / 2f, height / 2f),
            )
        )
        lastFrameNs = System.nanoTime()
        onInfo?.invoke("drop · drag to throw · double-tap to reset")
        invalidate()
    }

    private fun reset() {
        val b = body ?: return
        b.position = Vec2(canvasCentre.x, 360f)
        b.stop()
        b.held = false
        b.resting = false
        world?.clearMotion()
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
        val w = world ?: return
        val b = body ?: return

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else (now - lastFrameNs) / 1_000_000_000f
        lastFrameNs = now
        w.step(dt)

        // The body drives the skeleton; nothing else moves the character.
        val offset = b.position - canvasCentre
        sk.rootTransform = Transform(position = offset)
        sk.update()

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

        canvas.drawRect(
            vx(b.left), vy(b.top), vx(b.right), vy(b.bottom), boxPaint
        )

        // ---- character ----
        for (bone in sk.bones) {
            val h = bone.worldPosition
            val t = bone.tipPosition()
            bonePaint.color = colourFor(bone.name)
            canvas.drawLine(vx(h.x), vy(h.y), vx(t.x), vy(t.y), bonePaint)
            jointPaint.color = bonePaint.color
            canvas.drawCircle(vx(h.x), vy(h.y), 4f * density, jointPaint)
        }

        val state = when {
            b.held -> "held"
            b.resting -> "at rest"
            b.grounded -> "on the ground"
            else -> "falling"
        }
        canvas.drawText(
            "%s · vy=%.0f · vx=%.0f · %s".format(state, b.velocity.y, b.velocity.x, if (b.grounded) "grounded" else "air"),
            10f * density, 16f * density, textPaint
        )
        // Deliberately no onInfo call here: this runs every frame, and pushing a fresh
        // string into a TextView sixty times a second is pure waste. The canvas text
        // above is the live readout; onInfo is for one-off messages only.

        // Keep animating only while something can still change.
        if (!b.resting || b.held) postInvalidateOnAnimation()
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
        val b = body ?: return false
        val sk = skeleton ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastTapAt < 300) {
                    reset()
                    lastTapAt = 0L
                    postInvalidateOnAnimation()
                    return true
                }
                lastTapAt = now

                val p = toWorld(event.x, event.y)
                val inside = p.x >= b.left && p.x <= b.right && p.y >= b.top && p.y <= b.bottom
                if (!inside) return false
                grabOffset = p - b.position
                b.held = true
                b.wake()
                flingTracker = b.position
                lastFrameNs = System.nanoTime()
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!b.held) return false
                val p = toWorld(event.x, event.y)
                val target = p - grabOffset
                // Track speed from the finger so a release throws rather than drops.
                val prev = b.position
                b.position = target
                val dt = 1f / 60f
                b.velocity = Vec2((target.x - prev.x) / dt, (target.y - prev.y) / dt)
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!b.held) return false
                b.held = false
                // Cap the throw so a fast flick does not launch it off-screen.
                val v = b.velocity
                val speed = hypot(v.x, v.y)
                val cap = 4000f
                if (speed > cap) {
                    b.velocity = Vec2(v.x / speed * cap, v.y / speed * cap)
                }
                b.wake()
                lastFrameNs = System.nanoTime()
                postInvalidateOnAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
