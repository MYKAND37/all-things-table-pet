package dev.atp.pet.render

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.skeleton.Skeleton
import dev.atp.pet.engine.skeleton.SwapRuleSpec
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws part images attached to bones.
 *
 * The whole trick is one line of algebra. A part is authored in the character's canvas
 * space, and at rest it must appear exactly where the artist put it. So the transform
 * for a bone is
 *
 *     canvas -> world  =  world(bone now)  composed with  inverse(world(bone at rest))
 *
 * At rest that is the identity, which is why no calibration step exists anywhere in this
 * project. When the bone turns, the part turns with it about the bone's own joint.
 */
class PartRenderer(
    private val skeleton: Skeleton,
    private val library: PartLibrary,
    /** Bone names in back-to-front draw order. */
    drawOrder: List<String>,
    private val swaps: List<SwapRuleSpec> = emptyList(),
) {
    private val restWorld = HashMap<String, Transform>()
    private val baseOrder = drawOrder.filter { library.parts.containsKey(it) }
    private val order = ArrayList<String>(baseOrder.size)
    private var lastTriggered = emptyList<Boolean>()
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        // Capture the rest pose once, before anything has been posed. The skeleton may
        // already be displaced, so pin it to identity for the measurement and put it back.
        val savedRoot = skeleton.rootTransform
        skeleton.rootTransform = Transform.IDENTITY
        skeleton.reset()
        for (bone in skeleton.bones) {
            restWorld[bone.name] = bone.worldTransform
        }
        skeleton.rootTransform = savedRoot
        skeleton.update()
    }

    /**
     * The draw order for the pose right now.
     *
     * Rebuilt only when a rule's trigger actually flips: the answer changes when a limb
     * crosses a threshold, not every frame, and rebuilding a list of twenty bones sixty
     * times a second for nothing is the kind of waste that shows up as battery drain
     * rather than as a bug.
     */
    private fun currentOrder(): List<String> {
        if (swaps.isEmpty()) return baseOrder

        var changed = lastTriggered.size != swaps.size
        val triggered = ArrayList<Boolean>(swaps.size)
        for ((index, rule) in swaps.withIndex()) {
            val on = isTriggered(rule)
            triggered.add(on)
            if (index >= lastTriggered.size || lastTriggered[index] != on) changed = true
        }
        if (!changed && order.size == baseOrder.size) return order
        lastTriggered = triggered

        val list = ArrayList(baseOrder)
        for ((index, rule) in swaps.withIndex()) {
            if (!triggered[index]) continue
            val moved = rule.parts.filter { list.contains(it) }
            if (moved.isEmpty()) continue
            list.removeAll(moved)

            var anchor = -1
            for (i in list.indices) {
                if (rule.behind.contains(list[i])) {
                    if (rule.toFront) {
                        anchor = i + 1
                    } else {
                        anchor = i
                        break
                    }
                }
            }
            if (anchor < 0) {
                // Nothing to swap against is in view; put the parts back where they were.
                list.addAll(moved)
                continue
            }
            list.addAll(anchor, moved)
        }

        order.clear()
        order.addAll(list)
        return order
    }

    private fun isTriggered(rule: SwapRuleSpec): Boolean {
        val bone = skeleton.find(rule.triggerBone) ?: return false
        val reference = skeleton.find(rule.referenceBone) ?: return false
        val tip = bone.tipPosition().y
        val line = reference.worldPosition.y
        return if (rule.triggerType == "tipBelow") tip > line else tip < line
    }

    fun draw(canvas: Canvas) {
        for (name in currentOrder()) {
            val part = library.parts[name] ?: continue
            val bone = skeleton.find(name) ?: continue
            val rest = restWorld[name] ?: continue

            val t = bone.worldTransform.compose(rest.inverse())

            // The crop offset is applied to the bitmap BEFORE the bone transform, so it
            // has to be rotated and scaled into place by hand.
            val c = cos(t.rotation) * t.scale
            val s = sin(t.rotation) * t.scale
            val tx = t.position.x + part.offsetX * c - part.offsetY * s
            val ty = t.position.y + part.offsetX * s + part.offsetY * c

            matrix.setValues(
                floatArrayOf(
                    c, -s, tx,
                    s, c, ty,
                    0f, 0f, 1f,
                )
            )
            canvas.drawBitmap(part.bitmap, matrix, paint)
        }
    }
}