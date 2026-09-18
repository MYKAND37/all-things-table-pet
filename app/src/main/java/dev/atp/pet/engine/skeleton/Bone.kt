package dev.atp.pet.engine.skeleton

import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.math.normalizeAngle
import kotlin.math.atan2

private const val FULL_TURN = 3.1415927f

/**
 * One joint.
 *
 * Authored as a head/tail pair in canvas coordinates and baked into this local form at
 * load: [restPosition] is the head offset inside the parent's frame, [restRotation] is
 * the bone's angle relative to the parent at rest. Authoring stays a flat list of joint
 * positions while the runtime still rotates as a tree.
 *
 * The single source of truth for placement is
 *
 *     world = world(parent) composed with local(restPosition, restRotation + rotation)
 *
 * [rotation] is an OFFSET from rest -- the only quantity dragging and physics may write.
 */
class Bone(
    val name: String,
    val restPosition: Vec2,
    val restRotation: Float,
    val length: Float,
    val minAngle: Float = -FULL_TURN,
    val maxAngle: Float = FULL_TURN,
    val springy: Boolean = false,
    val stiffness: Float = 0.35f,
    val damping: Float = 0.86f,
    val gravity: Float = 0f,
) {
    /**
     * Whether writes to [rotation] skip the joint's own limits.
     *
     * Off everywhere the pet is alive: the limits ARE the joint, and the solver clamps to
     * them in its integrator whether this is on or not. It exists for the rig editor, which
     * has to be able to drag a knee PAST where the knee currently stops — that is how a
     * range gets widened, and without it 「设为最大」 could only ever make the range smaller
     * than it already was. The editor's skeleton is a throwaway copy built from the file, so
     * turning this on there cannot reach the character on the bench.
     */
    var ignoreLimits = false

    var rotation: Float = 0f
        set(value) {
            // The FULL_TURN clamp is the hard one and never moves: past half a turn a joint's
            // offset stops meaning anything a person can type into a dialog.
            field = if (ignoreLimits) value.coerceIn(-FULL_TURN, FULL_TURN)
            else value.coerceIn(minAngle, maxAngle)
        }

    val localRotation: Float get() = restRotation + rotation

    var parent: Bone? = null
        internal set

    val children: MutableList<Bone> = mutableListOf()

    val localTransform: Transform
        get() = Transform(position = restPosition, rotation = localRotation)

    /** Written only by [Skeleton.update] and [refreshWorld] — never invented elsewhere. */
    var worldTransform: Transform = Transform.IDENTITY
        internal set

    val worldPosition: Vec2 get() = worldTransform.position
    val worldRotation: Float get() = worldTransform.rotation

    /** Recompute from a parent that is already up to date. */
    fun refreshWorld() {
        worldTransform = parent?.let { it.worldTransform.compose(localTransform) } ?: localTransform
    }

    /** The bone's tail in world space: the child joint, or the tip of a leaf. */
    fun tipPosition(): Vec2 = worldTransform.apply(Vec2(length, 0f))

    /**
     * Rotate so this bone's own +x axis points at [target].
     *
     * The whole difference is normalised as one quantity. Normalising (desired - parent)
     * first and subtracting the rest angle afterwards only stays in range while rest is
     * small; a bone authored pointing backwards (rest = 180 degrees, like the thighs)
     * lands outside its limits and gets silently clamped.
     */
    fun aimAt(target: Vec2) {
        val parentRotation = parent?.worldRotation ?: 0f
        val desired = atan2(target.y - worldPosition.y, target.x - worldPosition.x)
        rotation = normalizeAngle(desired - parentRotation - restRotation)
    }

    /** Turn this bone (and its subtree) so it faces [target] while keeping its children rigid. */
    fun reset(recursive: Boolean = true) {
        rotation = 0f
        if (recursive) children.forEach { it.reset(true) }
    }

    internal fun attach(child: Bone) {
        child.parent = this
        children.add(child)
    }
}
