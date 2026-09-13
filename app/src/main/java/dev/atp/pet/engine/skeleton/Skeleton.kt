package dev.atp.pet.engine.skeleton

import dev.atp.pet.engine.math.Transform

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
}
