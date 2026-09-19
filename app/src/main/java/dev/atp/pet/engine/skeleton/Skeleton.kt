package dev.atp.pet.engine.skeleton

import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.math.Vec2
import kotlin.math.hypot

/**
 * A bone tree plus the forward-kinematics pass.
 *
 * [update] walks parents before children and recomputes every world transform from
 * [rootTransform] down. Run it once per frame after anything has changed a rotation —
 * a drag, the physics pass, a pose reset — and before anything measures or draws. All
 * reads of [Bone.worldTransform] are only meaningful between one update and the next.
 */
class Skeleton(val root: Bone) {

    private val byName = LinkedHashMap<String, Bone>()

    init {
        index(root)
    }

    private fun index(bone: Bone) {
        byName[bone.name] = bone
        bone.children.forEach { index(it) }
    }

    val bones: List<Bone> get() = byName.values.toList()

    fun find(name: String): Bone? = byName[name]

    fun require(name: String): Bone =
        byName[name] ?: error("no bone named '$name'; have ${byName.keys}")

    /** Where the whole figure sits in the world. */
    var rootTransform: Transform = Transform.IDENTITY

    fun update() {
        root.worldTransform = rootTransform.compose(root.localTransform)
        val stack = ArrayDeque<Bone>()
        root.children.asReversed().forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val bone = stack.removeLast()
            bone.refreshWorld()
            bone.children.asReversed().forEach { stack.addLast(it) }
        }
    }

    fun reset() {
        root.reset(recursive = true)
        update()
    }

    /** Bones driven by physics rather than by direct manipulation, roots first. */
    val springRoots: List<Bone> get() = bones.filter { it.springy }

    /**
     * The named points on this rig. Set by [CharacterSpec.buildSkeleton], because a node only
     * means anything next to the bones it is measured against.
     */
    var nodes: List<NodeSpec> = emptyList()

    /**
     * Where a node is right now.
     *
     * Interpolated along the bone's CURRENT head-to-tip line rather than rotated by the bone's
     * angle: the two are the same point, and this one costs no trigonometry and cannot go
     * wrong when a bone is drawn with a length that no longer matches its rest length.
     *
     * [NodeSpec.at] is a distance, so a bone that is resized afterwards takes its tip node
     * with it instead of leaving it hanging where the old tip used to be -- which is why the
     * clamp is here and not in the editor.
     */
    fun nodePoint(node: NodeSpec): Vec2 {
        val b = byName[node.bone] ?: return Vec2.ZERO
        val head = b.worldPosition
        val tip = b.tipPosition()
        val dx = tip.x - head.x
        val dy = tip.y - head.y
        val len = hypot(dx, dy)
        if (len < 1e-3f) return head
        val t = (node.at / len).coerceIn(0f, 1f)
        return Vec2(head.x + dx * t, head.y + dy * t)
    }

    /**
     * The node under a point, or null. Nearest centre wins, the same rule the props use, and a
     * node is asked before the bone it sits on: a fingertip that has been given a name is a
     * more precise answer than the whole finger, and the finger is still there underneath.
     */
    fun nodeAt(p: Vec2): NodeSpec? {
        var best: NodeSpec? = null
        var bestDistance = Float.MAX_VALUE
        for (n in nodes) {
            val q = nodePoint(n)
            val d = hypot(p.x - q.x, p.y - q.y) - n.radius
            if (d < bestDistance) {
                bestDistance = d
                best = n
            }
        }
        return best
    }
}
