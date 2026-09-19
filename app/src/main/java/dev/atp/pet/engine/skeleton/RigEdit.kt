package dev.atp.pet.engine.skeleton

import kotlin.math.hypot

/**
 * Rig surgery: the rules that decide whether a rig can be baked at all.
 *
 * They have to hold in two places at once — in the editor, which keeps the rig in memory
 * while it is being changed, and in the store, which writes it back to disk. Baking a bone
 * before its parent is a hard error rather than a wrong picture, so both sides come here
 * instead of each inventing its own list handling.
 */
object RigEdit {

    /**
     * Reorder so every parent comes before its children.
     *
     * [Skeleton] builds each bone the moment it meets it and needs the parent already
     * built, so this is not cosmetic: the authored order IS the build order. Nothing is
     * dropped — a bone whose parent is missing or part of a cycle is kept at the end, where
     * [problem] can see it, rather than quietly disappearing from the figure.
     */
    fun order(bones: List<BoneSpec>): List<BoneSpec> {
        val placed = HashSet<String>()
        val out = ArrayList<BoneSpec>(bones.size)
        var moved = true
        while (moved) {
            moved = false
            for (b in bones) {
                if (b.name in placed) continue
                val parent = b.parentName
                if (parent == null || parent in placed) {
                    out.add(b)
                    placed.add(b.name)
                    moved = true
                }
            }
        }
        for (b in bones) if (b.name !in placed) out.add(b)
        return out
    }

    /** [name] and everything hanging off it. Used to refuse a reparent that makes a loop. */
    fun descendants(bones: List<BoneSpec>, name: String): List<String> {
        val children = HashMap<String, MutableList<String>>()
        for (b in bones) {
            b.parentName?.let { children.getOrPut(it) { mutableListOf() }.add(b.name) }
        }
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(name)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (!seen.add(n)) continue
            out.add(n)
            children[n]?.forEach { stack.addLast(it) }
        }
        return out
    }

    /** Depth in the tree, for indenting the bone list so the hierarchy is visible. */
    fun depths(bones: List<BoneSpec>): Map<String, Int> {
        val parent = HashMap<String, String?>()
        for (b in bones) parent[b.name] = b.parentName
        val out = HashMap<String, Int>()
        for (b in bones) {
            var depth = 0
            var cur = b.parentName
            while (cur != null && depth <= bones.size) {
                depth++
                cur = parent[cur]
            }
            out[b.name] = depth
        }
        return out
    }

    /** null when the rig is bakeable; otherwise why it is not, in words worth showing. */
    fun problem(bones: List<BoneSpec>): String? {
        if (bones.isEmpty()) return "至少要有 1 根骨骼"
        val seen = HashSet<String>()
        for (b in bones) {
            if (b.name.isBlank()) return "有骨骼没有名字"
            if (!seen.add(b.name)) return "骨骼重名：" + b.name
        }
        val byName = bones.associateBy { it.name }
        for (b in bones) {
            val p = b.parentName
            if (p != null && !byName.containsKey(p)) return b.name + " 的父级「" + p + "」不存在"
        }
        // Checked before the root count: a loop always leaves the rig without a root, so
        // asking about roots first would report the symptom and hide the cause.
        if (hasCycle(bones)) return "骨骼的父子关系成环了"
        val roots = bones.count { it.parentName == null }
        if (roots == 0) return "没有根骨骼了：每根骨骼都要挂在别的骨骼下面"
        // Two roots would silently halve the figure: buildSkeleton keeps the last one it
        // sees, and the whole other tree would simply never be drawn.
        if (roots > 1) return "有 " + roots + " 根根骨骼，一棵骨架只能有 1 根"
        for (b in bones) {
            if (hypot(b.tail.x - b.head.x, b.tail.y - b.head.y) < 1f) {
                return b.name + " 的长度是 0，拖动它的橙色端点给个方向"
            }
        }
        return null
    }

    private fun hasCycle(bones: List<BoneSpec>): Boolean {
        val parent = HashMap<String, String?>()
        for (b in bones) parent[b.name] = b.parentName
        for (b in bones) {
            var steps = 0
            var cur = b.parentName
            while (cur != null) {
                if (cur == b.name) return true
                if (++steps > bones.size) return true
                cur = parent[cur]
            }
        }
        return false
    }

    /**
     * A bone name is also a file name: the artwork for a bone lands as parts/<name>.png, so
     * the name is the contract between the rig and the drawings. Anything a filesystem
     * would object to becomes an underscore; spaces go too, because a name with a space in
     * it is a name that gets mistyped.
     */
    fun sanitise(raw: String): String {
        val cleaned = raw.trim().map { c ->
            if (c.isWhitespace() || c in "/\\:*?\"<>|") '_' else c
        }.joinToString("")
        return if (cleaned.all { it == '_' }) "" else cleaned
    }

    /** The first unused bone_1, bone_2 … so a new bone always has a name to start from. */
    fun freeName(bones: List<BoneSpec>, prefix: String = "bone_"): String {
        val taken = bones.map { it.name }.toSet()
        var n = 1
        while ((prefix + n) in taken) n++
        return prefix + n
    }

    /** The same, for a node: unique against the bones as well, because both are names. */
    fun freeNodeName(bones: List<BoneSpec>, nodes: List<NodeSpec>, prefix: String = "node_"): String {
        val taken = bones.map { it.name }.toSet() + nodes.map { it.name }
        var n = 1
        while ((prefix + n) in taken) n++
        return prefix + n
    }

    /**
     * Whether a set of nodes can be written to a file at all.
     *
     * A node is a NAME and a PLACE, and both of them can be wrong in ways that are invisible
     * until a rule does not fire: a node whose bone does not exist is a name nothing can ever
     * touch, and a node sharing a bone's name would make 被碰到 · 手 mean two different things
     * depending on which one the finger found first.
     */
    fun nodeProblem(bones: List<BoneSpec>, nodes: List<NodeSpec>): String? {
        val boneNames = bones.map { it.name }.toSet()
        val seen = HashSet<String>()
        for (n in nodes) {
            if (n.name.isBlank()) return "有节点没有名字"
            if (!seen.add(n.name)) return "节点重名：" + n.name
            if (n.name in boneNames) return "节点「" + n.name + "」和骨骼重名了"
            if (n.bone !in boneNames) return n.name + " 挂在不存在的骨骼「" + n.bone + "」上"
            if (n.radius <= 0f) return n.name + " 的判定半径是 0，永远不会被碰到"
        }
        return null
    }

    /** 关节 / 中间 / 末端 as a distance along the bone, from the joint. */
    fun placeAt(bone: BoneSpec, place: String): Float {
        val len = hypot(bone.tail.x - bone.head.x, bone.tail.y - bone.head.y)
        return when (place) {
            "mid" -> len / 2f
            "tip" -> len
            else -> 0f
        }
    }

    /**
     * Which of the three a node is at, for the editor to light the right chip.
     *
     * A node is stored as a DISTANCE, so this is a comparison rather than a lookup -- and the
     * tolerance is a tenth of the bone because a node dragged along a bone by hand is not
     * going to land on the exact midpoint, and being told it is "somewhere else" is less
     * useful than being told it is at the middle.
     */
    fun placeOf(bone: BoneSpec, at: Float): String {
        val len = hypot(bone.tail.x - bone.head.x, bone.tail.y - bone.head.y)
        val slack = kotlin.math.max(1f, len * 0.1f)
        return when {
            kotlin.math.abs(at - len) <= slack -> "tip"
            kotlin.math.abs(at - len / 2f) <= slack -> "mid"
            else -> "joint"
        }
    }

    /**
     * The two ends of a joint's range, sorted.
     *
     * A range written backwards is not a joint that moves backwards, it is a joint that cannot
     * move at all — `min` above `max` clamps every angle to one value — and that reads as a
     * broken rig rather than as a typo. Both ways of setting a range go through here so that
     * they cannot disagree: the 属性 dialog, where the two ends are typed, and 「设为最小 /
     * 设为最大」, where one end comes from a pose and the other from the file.
     *
     * Mirrored in tools/rig_edit_check.py, which carries the cases (backwards, equal, and a
     * capture that lands inside the range it already had).
     */
    fun limits(a: Float, b: Float): Pair<Float, Float> =
        if (a <= b) (a to b) else (b to a)

}
