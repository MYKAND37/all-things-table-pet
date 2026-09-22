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
    /**
     * Bones a RULE has pulled forward or pushed back, in the order it did it. Not the file.
     *
     * The 改变部位深度 action: 「这一截现在该在前面」是很多动作要的效果，而图层深度是文件里的
     * 一行 —— 规则改不了它，也不该改（那是用户画的顺序）。所以这是**这一次会话里**的覆盖：
     * 动了它只影响怎么画，收回测试场、换骨骼套、重新加载都会回到文件里的顺序。
     */
    private val raised = LinkedHashSet<String>()
    private val lowered = LinkedHashSet<String>()

    /** 改变部位深度：把那根骨头拉到最前 / 压到最后。 */
    fun setDepth(bone: String, toFront: Boolean) {
        if (bone.isEmpty()) return
        if (toFront) {
            lowered.remove(bone)
            raised.remove(bone)
            raised.add(bone)
        } else {
            raised.remove(bone)
            lowered.remove(bone)
            lowered.add(bone)
        }
        invalidateOrder()
    }

    /** Forget every runtime depth: back to the file's order. */
    fun clearDepth() {
        if (raised.isEmpty() && lowered.isEmpty()) return
        raised.clear()
        lowered.clear()
        invalidateOrder()
    }

    private fun invalidateOrder() {
        order.clear()
        lastTriggered = emptyList()
    }

    private fun currentOrder(): List<LayerSpec> {
        val withRuntime = applyRuntimeDepth()
        if (swaps.isEmpty()) return withRuntime

        var changed = lastTriggered.size != swaps.size
        val triggered = ArrayList<Boolean>(swaps.size)
        for ((index, rule) in swaps.withIndex()) {
            val on = isTriggered(rule)
            triggered.add(on)
            if (index >= lastTriggered.size || lastTriggered[index] != on) changed = true
        }
        if (!changed && order.size == baseOrder.size) return order
        lastTriggered = triggered

        val list = ArrayList(withRuntime)
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

    /**
     * The file's order with the runtime overrides applied.
     *
     * "Front" means drawn LAST (see the layer list: the top of the list covers the rest), so a
     * raised bone goes to the end and a lowered one to the start. Both keep their own order of
     * arrival, so raising two parts in a row leaves the second one in front -- which is what
     * somebody who just raised it expects.
     */
    private fun applyRuntimeDepth(): List<LayerSpec> {
        if (raised.isEmpty() && lowered.isEmpty()) return baseOrder
        val out = ArrayList<LayerSpec>(baseOrder.size)
        out.addAll(baseOrder.filter { it.bone in lowered })
        out.addAll(baseOrder.filter { it.bone !in lowered && it.bone !in raised })
        out.addAll(baseOrder.filter { it.bone in raised })
        return out
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
     * Where a bone's artwork was authored: the joint it hangs from, in canvas coordinates.
     *
     * This is the point a piece that has come off turns about. See [drawMoved].
     */
    fun restPosition(bone: String): Vec2? = restWorld[bone]?.position

    /**
     * One bone's artwork under a rigid motion of its own, instead of under the skeleton's.
     *
     * [move] is the motion of the WHOLE piece -- the same transform for every bone that left
     * together -- so an arm that came off at the shoulder keeps its elbow and its hand where
     * they were relative to each other. See PhysicsSandboxView.Debris, which builds it from
     * the piece's rest position and the spin it has picked up.
     */
    fun drawMoved(canvas: Canvas, bone: String, move: Transform) {
        for (layer in baseOrder) {
            if (layer.bone != bone) continue
            if (!layer.visible(states)) continue
            val part = library.parts[layer.artKey] ?: continue
            paintAt(canvas, part, move)
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