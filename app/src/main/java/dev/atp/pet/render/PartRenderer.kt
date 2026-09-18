package dev.atp.pet.render

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.skeleton.LayerSpec
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
    /**
     * Back to front. A LAYER, not a bone: the same bone can have more than one, which is
     * what lets a state swap the arm for a mechanical one instead of just hiding it.
     */
    layers: List<LayerSpec>,
    private val swaps: List<SwapRuleSpec> = emptyList(),
) {
    /**
     * Bones whose artwork is gone: a broken part, or one the rules removed.
     *
     * The skeleton still has the bone — it still collides, it is still part of the figure —
     * only the drawing is missing. Deleting the bone instead would change the shape of the
     * character the moment it got hurt.
     */
    var hidden: Set<String> = emptySet()

    /** Which states are on right now. A state nobody declares reads as off. */
    var states: Map<String, Boolean> = emptyMap()

    private val restWorld = HashMap<String, Transform>()

    /** Layers whose artwork actually exists on disk. A variant with no file draws nothing. */
    private val baseOrder = layers.filter { library.parts.containsKey(it.artKey) }
    private val order = ArrayList<LayerSpec>(baseOrder.size)
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
    private fun currentOrder(): List<LayerSpec> {
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
            // Rules name BONES; a bone may own several layers, and they travel together.
            val moved = rule.parts.filter { name -> list.any { it.bone == name } }
            if (moved.isEmpty()) continue
            val movedLayers = list.filter { it.bone in moved }
            val rest = list.filter { it.bone !in moved }
            list.clear()
            list.addAll(rest)

            var anchor = -1
            for (i in list.indices) {
                if (rule.behind.contains(list[i].bone)) {
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
                list.addAll(movedLayers)
                continue
            }
            list.addAll(anchor, movedLayers)
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

    /**
     * How many layers actually drew last frame, and how many of them could have.
     *
     * The bench asks these two questions. A character whose drawings are on disk but which
     * drew NOTHING is an empty room with the physics still running, and it has been reported
     * as "the pet has gone" more than once; two numbers turn that into an answer.
     */
    var drawnLastFrame = 0
        private set

    /** Layers whose artwork exists on disk: the most that could ever draw. */
    val drawable: Int get() = baseOrder.size

    fun draw(canvas: Canvas) {
        var drawn = 0
        for (layer in currentOrder()) {
            if (layer.bone in hidden) continue
            if (!layer.visible(states)) continue
            val part = library.parts[layer.artKey] ?: continue
            val bone = skeleton.find(layer.bone) ?: continue
            val rest = restWorld[layer.bone] ?: continue

            paintAt(canvas, library.parts[layer.artKey]!!, bone.worldTransform.compose(rest.inverse()))
            drawn++
        }
        drawnLastFrame = drawn
    }

    /**
     * Draw one bone's artwork as a piece that has come OFF the figure.
     *
     * The same layers, the same crop offsets, one difference: instead of asking the skeleton
     * where the bone is, this says where it would have to be. [at] is where that bone's rest
     * position has been carried to (its joint, in canvas coordinates) and [spin] is how far
     * the piece has turned since it left. See PhysicsSandboxView.Debris for when one exists.
     *
     * The motion is a rigid one about the piece's own rest position, which is what makes a
     * detached arm lie where it fell instead of snapping back to where it was authored.
     */
    fun drawDetached(canvas: Canvas, boneName: String, at: Vec2, spin: Float) {
        val rest = restWorld[boneName] ?: return
        val moved = Transform(at + (Vec2.ZERO - rest.position).rotated(spin), spin)
        for (layer in baseOrder) {
            if (layer.bone != boneName) continue
            if (!layer.visible(states)) continue
            val part = library.parts[layer.artKey] ?: continue
            paintAt(canvas, part, moved)
        }
    }

    /**
     * One part's bitmap at one bone transform.
     *
     * The crop offset is applied to the bitmap BEFORE the bone transform, so it has to be
     * rotated and scaled into place by hand -- which is exactly the arithmetic that must not
     * be written twice, once for the body and once for the pieces that fall off it.
     */
    private fun paintAt(canvas: Canvas, part: Part, t: Transform) {
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