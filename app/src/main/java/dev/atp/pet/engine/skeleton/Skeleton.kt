package dev.atp.pet.engine.skeleton

import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.abs
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
    /**
     * 节点上挂的道具**应该在哪儿**（1.33.0）：节点那个点，加上按这一节骨头的方向旋转过的
     * [NodeSpec.propX]/[NodeSpec.propY]。
     *
     * 旋转用的是**骨头当前指的方向**（头 → 尾），而不是另存一个角度：这样"手一转，剑跟着转"
     * 是白送的，而且和画面上看到的骨头方向永远是同一个（另存一个角度就会有一天对不上）。
     */
    fun nodePropPoint(node: NodeSpec): Vec2 {
        val at = nodePoint(node)
        if (node.propX == 0f && node.propY == 0f) return at
        return Vec2(
            at.x + node.propX * axisCos(node) - node.propY * axisSin(node),
            at.y + node.propX * axisSin(node) + node.propY * axisCos(node),
        )
    }

    /** 反过来：世界坐标里的一点，换成这一节骨头坐标系里的偏移（拖的时候用）。 */
    fun propOffsetOf(node: NodeSpec, world: Vec2): Vec2 {
        val at = nodePoint(node)
        val c = axisCos(node)
        val s = axisSin(node)
        val dx = world.x - at.x
        val dy = world.y - at.y
        // 反向旋转（转置）。
        return Vec2(dx * c + dy * s, -dx * s + dy * c)
    }

    /** 这一节骨头现在指的方向（单位向量）。骨头不存在或者长度为 0 时给 (1, 0)。 */
    private fun axisCos(node: NodeSpec): Float {
        val b = byName[node.bone] ?: return 1f
        val head = b.worldPosition
        val tip = b.tipPosition()
        val len = hypot(tip.x - head.x, tip.y - head.y)
        return if (len < 1e-3f) 1f else (tip.x - head.x) / len
    }

    private fun axisSin(node: NodeSpec): Float {
        val b = byName[node.bone] ?: return 0f
        val head = b.worldPosition
        val tip = b.tipPosition()
        val len = hypot(tip.x - head.x, tip.y - head.y)
        return if (len < 1e-3f) 0f else (tip.y - head.y) / len
    }

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
     * Where a NAME is on this rig: a bone, or a node sitting on one.
     *
     * Bones and nodes are the same kind of thing to a rule -- the 逻辑管理 panel offers both
     * as 部位, an event's `part` can be either, and 「被点一下 · 指尖」 is written exactly like
     * 「被点一下 · hand_L」. So everything that turns a name into a PLACE asks here, and this
     * is the only place that knows a node is not a bone: [find] is a bone lookup, and code
     * that used it directly got null for every node name and quietly fell back to "somewhere
     * near the pet".
     *
     * A bone is asked first. RigEdit keeps node names unique against bone names as well, so
     * today the order cannot matter -- it is written down because "which one wins" is not
     * something a reader should have to guess from a map lookup.
     */
    /**
     * 一条射线打到哪一根骨头（1.34.0，狙击镜用）：最近的那一节，或者 null（没打中）。
     *
     * 一枪是**立刻**结算的（hitscan）：从枪口沿瞄准方向打一条射线，命中第一根离得够近的
     * 骨头（骨头是一段线段，判据是"射线到这段线段的距离"），所以"打中了"和"看到准星压在
     * 它身上"是同一件事 —— 没有飞行中的子弹要在半路上再算一遍碰撞。
     *
     * 纯几何、没有 Android，所以能整段镜像到 tools/scope_check.py 里逐点测。返回**骨头名 +
     * 命中点**：名字给规则（"打到手了"），命中点是给以后的弹孔贴在身上的位置。
     *
     * [reach] 之外不算命中（准星压着画面边上但宠物在很远的地方，不该算打中）；[slack] 是
     * "擦过去也算"的宽容度，取这一节骨头的粗细（骨头是线段，但它画出来是有宽度的）。
     */
    fun rayHit(from: Vec2, dir: Vec2, reach: Float, slack: Float): Pair<String, Vec2>? {
        val len = hypot(dir.x, dir.y)
        if (len < 1e-4f || reach <= 0f) return null
        val ux = dir.x / len
        val uy = dir.y / len
        var best: String? = null
        var bestAt = 0f
        var bestDist = Float.MAX_VALUE
        for (b in bones) {
            // 骨头在世界里的位置：这一版的骨架已经算好 worldPosition，两段端点沿骨头方向。
            val ax = b.worldPosition.x
            val ay = b.worldPosition.y
            // 骨头的方向 = 它的世界变换里的旋转（骨头自己的 x 轴就是它指的方向）。
            val ang = b.worldTransform.rotation
            val bx = ax + cos(ang) * b.length
            val by = ay + sin(ang) * b.length
            // 射线与这一段线段的最近距离，取射线上的参数 t 与线段上的参数 s。
            val ex = bx - ax
            val ey = by - ay
            val denom = ux * ey - uy * ex
            val s: Float
            val t: Float
            if (abs(denom) < 1e-6f) {
                // 平行：取骨头一端的投影
                s = 0f
                t = (ax - from.x) * ux + (ay - from.y) * uy
            } else {
                val px = ax - from.x
                val py = ay - from.y
                t = (px * ey - py * ex) / denom
                s = (px * uy - py * ux) / -denom
            }
            if (t < 0f || t > reach) continue
            if (s < 0f || s > 1f) continue
            val hx = from.x + ux * t
            val hy = from.y + uy * t
            val cx = ax + ex * s
            val cy = ay + ey * s
            val d = hypot(hx - cx, hy - cy)
            if (d <= slack && t < bestDist) {
                bestDist = t
                best = b.name
                bestAt = t
            }
        }
        val name = best ?: return null
        return name to Vec2(from.x + ux * bestAt, from.y + uy * bestAt)
    }

    fun place(name: String): Vec2? {
        if (name.isEmpty()) return null
        byName[name]?.let { return it.worldPosition }
        val node = nodes.firstOrNull { it.name == name } ?: return null
        return nodePoint(node)
    }

    /**
     * The BONE a name belongs to: itself when it names a bone, the one it sits on when it names
     * a node. Null when the rig has no such name.
     *
     * [place] answers "where", this answers "which limb" -- and the callers that need a bone
     * rather than a point (an impulse, tearing a piece off) are exactly the ones that cannot do
     * anything sensible with a point. Before this they asked [find], got null for every node
     * name, and either did nothing at all or aimed at the pet's ROOT: 「推一下 · 指尖」 pushed
     * the whole character.
     */
    fun boneFor(name: String): Bone? {
        byName[name]?.let { return it }
        val node = nodes.firstOrNull { it.name == name } ?: return null
        return byName[node.bone]
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
