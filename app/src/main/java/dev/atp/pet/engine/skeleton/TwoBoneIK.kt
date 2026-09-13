package dev.atp.pet.engine.skeleton

import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.math.normalizeAngle
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Drag-driven posing.
 *
 * The user grabs a joint; the whole chain above it has to bend so that joint reaches the
 * finger. For the two-segment limbs of a table pet that has an exact closed form -- the
 * law of cosines -- so there is no solver to tune and nothing to jitter.
 *
 * The maths here mirrors tools/skeleton_tool.py, which carries the tests. If the two ever
 * disagree, the Python one is right.
 */
object TwoBoneIK {

    /**
     * Pose a two-segment chain so its tip lands on [target].
     *
     * The forearm is aimed from the *actual* elbow rather than from a precomputed angle,
     * which is what makes joint limits behave: when the shoulder clamps and the chain can
     * no longer reach, the forearm still points the right way instead of snapping.
     */
    fun solve(
        skeleton: Skeleton,
        upper: Bone,
        lower: Bone,
        target: Vec2,
        bend: Float,
    ) {
        val lu = upper.length
        val ll = lower.length

        val tx = target.x - upper.worldPosition.x
        val ty = target.y - upper.worldPosition.y
        var d = hypot(tx, ty)
        if (d < 1e-6f) d = 1e-6f
        val dClamped = max(abs(lu - ll) * 1.0001f, min((lu + ll) * 0.99995f, d))

        val base = atan2(ty, tx)
        val cosA = ((lu * lu + dClamped * dClamped - ll * ll) / (2f * lu * dClamped))
            .coerceIn(-1f, 1f)
        val upperWorld = base + bend * acos(cosA)

        val parentRotation = upper.parent?.worldRotation ?: 0f
        upper.rotation = normalizeAngle(upperWorld - parentRotation - upper.restRotation)
        skeleton.update()

        val elbow = upper.tipPosition()
        val lowerWorld = atan2(target.y - elbow.y, target.x - elbow.x)
        lower.rotation = normalizeAngle(lowerWorld - upper.worldRotation - lower.restRotation)
        skeleton.update()
    }

    /** Which side of the root-to-target line the elbow currently sits on: +1, -1 or 0. */
    fun elbowSide(upper: Bone, target: Vec2): Float {
        val root = upper.worldPosition
        val elbow = upper.tipPosition()
        val cross = (elbow.x - root.x) * (target.y - root.y) -
            (elbow.y - root.y) * (target.x - root.x)
        if (abs(cross) < 1e-6f) return 0f
        return if (cross > 0f) 1f else -1f
    }

    /**
     * What a drag actually does.
     *
     * Two-bone IK has TWO solutions putting the tip on the same point -- elbow one way or
     * the other. A fixed sign makes a dragged arm flip its elbow through the body. Since
     * the side is fully determined by the bend sign, read the side the elbow is on now and
     * keep it.
     *
     * The sign is inverted on purpose: bend = +1 places the elbow at base + alpha, which
     * puts it at a NEGATIVE cross product, so preserving side s means solving with -s.
     * Getting this backwards still lands the tip on the target -- both mirrored solutions
     * do -- it just flips the elbow, and the shoulder must then swing past its limit.
     */
    fun drag(
        skeleton: Skeleton,
        upper: Bone,
        lower: Bone,
        target: Vec2,
        defaultBend: Float,
    ) {
        val side = elbowSide(upper, target)
        solve(skeleton, upper, lower, target, if (side == 0f) defaultBend else -side)
    }
}
