package dev.atp.pet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.IkChainSpec
import dev.atp.pet.engine.skeleton.Skeleton
import dev.atp.pet.engine.skeleton.TwoBoneIK
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** A draggable end: either the end of a two-bone IK chain, or a bone aimed directly. */
private class Handle(val bone: Bone, val chain: IkChainSpec?)

/**
 * The rigging test bench: draws a character's skeleton over its canvas and lets a finger
 * drag the joints.
 *
 * This exists to prove the engine on a real device, where the reference implementation in
 * tools/skeleton_tool.py cannot reach. Drag the hand of a chain and the elbow is solved,
 * not guessed; drag any other tip and the bone simply aims at the finger.
 */
class SkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var spec: CharacterSpec? = null
    private var skeleton: Skeleton? = null
    private val handles = mutableListOf<Handle>()

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var active: Handle? = null
    private var lastTapAt = 0L

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private val springPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(9f * density, 7f * density), 0f
        )
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33000000
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = 0xCC222233.toInt()
    }

    /** Called with a one-line description of what just happened, for a status strip. */
    var onInfo: ((String) -> Unit)? = null

    /** Load a character package from assets, e.g. "characters/female_base/character.json". */
    fun load(assetPath: String) {
        val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        val parsed = CharacterSpec.parse(text)
        val built = parsed.buildSkeleton()
        built.update()

        spec = parsed
        skeleton = built

        handles.clear()
        val chainByLower = parsed.ikChains.associateBy { it.lower }
        for (b in built.bones) {
            if (b.springy) continue
            handles.add(Handle(b, chainByLower[b.name]))
        }

        requestLayout()
        invalidate()
        onInfo?.invoke(
            parsed.id + "  ·  " + parsed.bones.size + " bones  ·  " +
                parsed.ikChains.size + " IK chains  ·  drag a joint"
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = spec ?: return
        val pad = 8f * density
        scale = min(
            (w - pad * 2) / s.canvasWidth,
            (h - pad * 2) / s.canvasHeight
        )
        offsetX = (w - s.canvasWidth * scale) / 2f
        offsetY = (h - s.canvasHeight * scale) / 2f
    }

    private fun vx(p: Vec2) = offsetX + p.x * scale
    private fun vy(p: Vec2) = offsetY + p.y * scale

    private fun toCanvas(x: Float, y: Float) = Vec2((x - offsetX) / scale, (y - offsetY) / scale)

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val sk = skeleton ?: return

        canvas.drawRect(
            offsetX, offsetY,
            offsetX + s.canvasWidth * scale, offsetY + s.canvasHeight * scale,
            framePaint
        )

        // Centre line, so it is obvious where the authoring origin sits.
        canvas.drawLine(
            vx(Vec2(s.canvasWidth / 2f, 0f)), offsetY,
            vx(Vec2(s.canvasWidth / 2f, 0f)), offsetY + s.canvasHeight * scale,
            framePaint
        )

        for (b in sk.bones) {
            val hx = vx(b.worldPosition)
            val hy = vy(b.worldPosition)
            val tip = b.tipPosition()
            val tx = vx(tip)
            val ty = vy(tip)
            val paint = if (b.springy) springPaint else linePaint
            paint.color = colourFor(b.name, b.springy)
            canvas.drawLine(hx, hy, tx, ty, paint)

            jointPaint.color = paint.color
            canvas.drawCircle(hx, hy, 4.5f * density, jointPaint)
            canvas.drawCircle(hx, hy, 2f * density, jointPaint)
        }

        for (h in handles) {
            val tip = h.bone.tipPosition()
            handlePaint.color = if (h === active) 0xFF000000.toInt() else 0x88000000.toInt()
            val r = (if (h.chain != null) 7f else 5f) * density
            canvas.drawCircle(vx(tip), vy(tip), r, handlePaint)
        }

        canvas.drawText(
            "drag a joint  ·  double-tap to reset",
            10f * density, 16f * density, textPaint
        )
    }

    private fun colourFor(name: String, springy: Boolean): Int = when {
        springy -> 0xFF1AA8A0.toInt()
        name.startsWith("shoulder") || name.startsWith("upperarm") ||
            name.startsWith("forearm") || name.startsWith("hand") ->
            if (name.endsWith("_L")) 0xFFE25A8C.toInt() else 0xFFE88C3C.toInt()
        name.startsWith("thigh") || name.startsWith("shin") || name.startsWith("foot") ->
            if (name.endsWith("_L")) 0xFF46AA6E.toInt() else 0xFF3C96B4.toInt()
        else -> 0xFF7C5CE0.toInt()
    }

    private fun pick(x: Float, y: Float): Handle? {
        var best: Handle? = null
        var bestDist = 34f * density
        for (h in handles) {
            val tip = h.bone.tipPosition()
            val d = hypot(vx(tip) - x, vy(tip) - y)
            if (d < bestDist) {
                bestDist = d
                best = h
            }
        }
        return best
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sk = skeleton ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastTapAt < 300) {
                    sk.reset()
                    active = null
                    invalidate()
                    onInfo?.invoke("reset to rest pose")
                    lastTapAt = 0L
                    return true
                }
                lastTapAt = now
                active = pick(event.x, event.y)
                return active != null
            }

            MotionEvent.ACTION_MOVE -> {
                val h = active ?: return false
                val target = toCanvas(event.x, event.y)
                val chain = h.chain
                if (chain != null) {
                    TwoBoneIK.drag(
                        sk,
                        sk.require(chain.upper),
                        sk.require(chain.lower),
                        target,
                        chain.bend,
                    )
                } else {
                    h.bone.aimAt(target)
                    sk.update()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
