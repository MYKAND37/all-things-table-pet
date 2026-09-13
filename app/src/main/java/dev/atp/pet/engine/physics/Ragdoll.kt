package dev.atp.pet.engine.physics

import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.math.normalizeAngle
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.Skeleton
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Articulated-body physics for one character.
 *
 * This replaces a single rigid block. Every joint carries its own angle and angular
 * momentum, so lifting the figure by one hand lets the rest of it hang: each bone takes a
 * gravity torque summed over its whole subtree, resisted by an angular spring towards a
 * target pose. That spring constant is the dial between the two behaviours the project
 * wants — at zero the figure is a limp ragdoll, cranked up it holds a pose.
 *
 * The maths mirrors tools/ragdoll.py, which carries the tests. Two dead ends are recorded
 * there in full, because both looked reasonable and both were wrong:
 *
 *   * Verlet integration for the root. A pinned joint teleports the root by the distance
 *     the finger moved, and Verlet reads any positional correction as velocity, adds it
 *     again next step, and squares its way to NaN in under a second.
 *
 *   * Pinning with a FORCE. The equilibrium is degenerate — the force depends only on how
 *     far the grabbed point is from the finger, and "one spring-length short" is true
 *     wherever the rest of the body happens to be. Every gain produced the identical
 *     wrong pose, with the figure drifting into the ceiling and staying there.
 *
 * What works is solving the chain to the finger, then sliding the root only for the part
 * the chain could not cover.
 */
class Ragdoll(
    private val skeleton: Skeleton,
    private val spec: CharacterSpec,
    /** 0 = limp ragdoll, 1 = holds a pose. Live-adjustable. */
    var stiffness: Float = 0f,
) {
    private val bones: List<Bone> = skeleton.bones
    private val byName: Map<String, Bone> = bones.associateBy { it.name }

    private val gravity = spec.gravity
    private val floor = spec.floorY
    private val ceiling = 0f
    private val wallLeft = 0f
    private val wallRight = spec.canvasWidth
    private val restitution = 0.2f
    private val groundFriction = 2.5f

    private val children = HashMap<String, MutableList<Bone>>()
    private val subtrees = HashMap<String, List<Bone>>()
    private val colliderType = HashMap<String, String>()
    private val colliderRadius = HashMap<String, Float>()
    private val mass = HashMap<String, Float>()
    private val target = HashMap<String, Float>()

    private val defaultRadius = spec.headHeight * 0.18f

    /** Root position, and the home position the offset is measured from. */
    private val rootHome: Vec2
    var rootPos: Vec2
        private set
    private var rootVel = Vec2.ZERO

    private val angle = HashMap<String, Float>()
    private val anglePrev = HashMap<String, Float>()

    /** Angular acceleration from gravity alone, kept for readouts. */
    var grounded = false
        private set

    /** Speed of the finger, so releasing throws the figure instead of dropping it. */
    private var pinLast: Vec2? = null
    private var pinVel = Vec2.ZERO

    init {
        for (b in bones) {
            children[b.name] = mutableListOf()
            angle[b.name] = 0f
            anglePrev[b.name] = 0f
            target[b.name] = 0f
        }
        for (b in bones) {
            b.parent?.let { children[it.name]?.add(b) }
        }
        for (b in bones) {
            val out = mutableListOf<Bone>()
            fun walk(x: Bone) {
                out.add(x)
                children[x.name]?.forEach { walk(it) }
            }
            walk(b)
            subtrees[b.name] = out
        }

        for (s in spec.bones) {
            val known = byName.containsKey(s.name)
            if (!known) continue
            colliderType[s.name] = s.colliderType
            colliderRadius[s.name] = if (s.colliderRadius > 0f) s.colliderRadius else defaultRadius
        }
        for (b in bones) {
            val r = colliderRadius[b.name] ?: defaultRadius
            val area = if (colliderType[b.name] == "circle") {
                (Math.PI * r * r).toFloat()
            } else {
                b.length * 2f * r + (Math.PI * r * r).toFloat()
            }
            mass[b.name] = max(area, 1f)
        }

        // Measure the home position first. applyAngles() needs rootHome to compute the
        // offset, so it cannot run before this.
        skeleton.rootTransform = Transform.IDENTITY
        skeleton.update()
        rootHome = bones.first().worldPosition
        rootPos = rootHome
        applyAngles()
    }

    // -- placement ----------------------------------------------------------

    private fun applyAngles() {
        for (b in bones) {
            b.rotation = angle[b.name] ?: 0f
        }
        skeleton.rootTransform = Transform(position = rootPos - rootHome)
        skeleton.update()
    }

    private fun com(b: Bone): Vec2 {
        val h = b.worldPosition
        val t = b.tipPosition()
        return Vec2((h.x + t.x) / 2f, (h.y + t.y) / 2f)
    }

    /** Lowest point of a bone's collider, in canvas space. */
    fun colliderLow(b: Bone): Float {
        val r = colliderRadius[b.name] ?: defaultRadius
        return if (colliderType[b.name] == "circle") {
            com(b).y + r
        } else {
            max(b.worldPosition.y, b.tipPosition().y) + r
        }
    }

    /** The bone whose collider is nearest to [p]; what a finger grabbing the body hits. */
    fun grabAt(p: Vec2): Bone? {
        var best: Bone? = null
        var bestDist = Float.MAX_VALUE
        for (b in bones) {
            val r = colliderRadius[b.name] ?: defaultRadius
            val d = distanceToSegment(p, b.worldPosition, b.tipPosition()) - r
            if (d < bestDist) {
                bestDist = d
                best = b
            }
        }
        // Generous: a finger is much fatter than a bone, and missing the grab entirely is
        // the worst possible outcome.
        return if (bestDist < spec.headHeight * 0.9f) best else null
    }

    private fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq < 1e-6f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + abx * t), p.y - (a.y + aby * t))
    }

    // -- the step -----------------------------------------------------------

    fun step(dt: Float, pinBone: String? = null, pinTarget: Vec2? = null) {
        applyAngles()

        // Gravity torque about each bone's head, summed over everything hanging from it.
        val alpha = HashMap<String, Float>(bones.size)
        for (b in bones) {
            val hx = b.worldPosition.x
            var tau = 0f
            var inertia = 0f
            for (d in subtrees[b.name]!!) {
                val c = com(d)
                val m = mass[d.name] ?: 1f
                tau += m * gravity * (c.x - hx)
                val dx = c.x - hx
                val dy = c.y - b.worldPosition.y
                inertia += m * (dx * dx + dy * dy)
            }
            alpha[b.name] = tau / max(inertia, 1e-6f)
        }

        // Joints.
        for (b in bones) {
            val name = b.name
            val theta = angle[name] ?: 0f
            val prev = anglePrev[name] ?: 0f
            var omega = theta - prev
            // MAX_OMEGA is rad/s; omega here is a per-step difference.
            val cap = MAX_OMEGA * dt
            omega = omega.coerceIn(-cap, cap)

            var a = alpha[name] ?: 0f
            val k = stiffness * K_MAX
            if (k > 0f) {
                val c = 2f * sqrt(k) * SPRING_ZETA
                a += -k * (theta - (target[name] ?: 0f)) - c * (omega / dt)
            }

            var next = theta + omega * (1f - ANGULAR_DAMP * dt) + a * dt * dt
            next = next.coerceIn(b.minAngle, b.maxAngle)

            // Keep the angle we came FROM. Verlet reads the difference between the two
            // stored angles as the velocity, so writing anything else here freezes the
            // velocity and the integrator diverges.
            anglePrev[name] = theta
            angle[name] = next
        }

        // Root.
        val damp = 1f - LINEAR_DAMP * dt
        rootVel = Vec2(rootVel.x * damp, rootVel.y * damp + gravity * dt)
        val speed = rootVel.length()
        if (speed > MAX_SPEED) rootVel = rootVel / speed * MAX_SPEED
        rootPos = rootPos + rootVel * dt

        applyAngles()
        ground(dt)
        applyAngles()

        if (pinBone != null && pinTarget != null) {
            pin(dt, pinBone, pinTarget)
            applyAngles()
        }
    }

    private fun ground(dt: Float) {
        var deepest = 0f
        for (b in bones) {
            val pen = colliderLow(b) - floor
            if (pen > deepest) deepest = pen
        }
        grounded = deepest > 0f
        if (deepest > 0f) {
            rootPos = Vec2(rootPos.x, rootPos.y - deepest)
            if (rootVel.y > 0f) rootVel = Vec2(rootVel.x, -rootVel.y * restitution)
            rootVel = Vec2(rootVel.x * (1f - groundFriction * dt), rootVel.y)
        }

        val r = colliderRadius[bones.first().name] ?: defaultRadius
        if (rootPos.x - r < wallLeft) {
            rootPos = Vec2(wallLeft + r, rootPos.y)
            rootVel = Vec2(0f, rootVel.y)
        } else if (rootPos.x + r > wallRight) {
            rootPos = Vec2(wallRight - r, rootPos.y)
            rootVel = Vec2(0f, rootVel.y)
        }
        if (rootPos.y - r < ceiling) {
            rootPos = Vec2(rootPos.x, ceiling + r)
            if (rootVel.y < 0f) rootVel = Vec2(rootVel.x, 0f)
        }
    }

    /**
     * Hold the grabbed joint under the finger.
     *
     * The chain is solved first, by cyclic coordinate descent over the grabbed bone's
     * whole ancestry, because that is what actually happens when a limp figure is lifted
     * by one hand: the arm swings up and the body hangs off it. Only the leftover the
     * chain cannot reach is taken up by sliding the root — solving the root alone is
     * exact and stable but leaves the arm hanging exactly where it was, so the body ends
     * up balanced above the finger, which a limp arm cannot do.
     */
    private fun pin(dt: Float, boneName: String, target: Vec2) {
        val bone = byName[boneName] ?: return
        val chain = chainToRoot(bone)

        for (iteration in 0 until PIN_IK_ITERATIONS) {
            val end = bone.worldPosition
            if (hypot(target.x - end.x, target.y - end.y) < 0.5f) break
            for (b in chain) {
                val e = bone.worldPosition
                val px = b.worldPosition.x
                val py = b.worldPosition.y
                if (hypot(e.x - px, e.y - py) < 1e-6f) continue
                val current = atan2(e.y - py, e.x - px)
                val wanted = atan2(target.y - py, target.x - px)
                val turn = normalizeAngle(wanted - current)
                val turned = (b.rotation + turn).coerceIn(b.minAngle, b.maxAngle)
                if (turned != b.rotation) {
                    b.rotation = turned
                    angle[b.name] = turned
                    skeleton.update()
                }
            }
        }

        val p = bone.worldPosition
        val dx = target.x - p.x
        val dy = target.y - p.y
        rootPos = Vec2(rootPos.x + dx, rootPos.y + dy)

        pinLast?.let {
            val a = 0.25f
            pinVel = Vec2(
                pinVel.x + (dx / dt - pinVel.x) * a,
                pinVel.y + (dy / dt - pinVel.y) * a,
            )
        }
        pinLast = p
    }

    private fun chainToRoot(bone: Bone): List<Bone> {
        val out = mutableListOf<Bone>()
        var b: Bone? = bone
        while (b != null) {
            out.add(b)
            b = b.parent
        }
        return out
    }

    /** Let go: keep whatever speed the drag had. */
    fun release() {
        rootVel = pinVel
        pinLast = null
        pinVel = Vec2.ZERO
    }

    /** Put the figure back where it started, and settle the angles to rest. */
    fun reset() {
        rootPos = rootHome
        rootVel = Vec2.ZERO
        pinLast = null
        pinVel = Vec2.ZERO
        for (b in bones) {
            angle[b.name] = 0f
            anglePrev[b.name] = 0f
        }
        applyAngles()
    }

    companion object {
        /** Angular spring constant at stiffness = 1.0, in 1/s^2. */
        const val K_MAX = 400f
        const val SPRING_ZETA = 1f
        const val LINEAR_DAMP = 0.6f
        const val ANGULAR_DAMP = 1.4f
        const val MAX_SPEED = 20000f
        const val MAX_OMEGA = 40f

        /** How hard the grab pulls the chain before the root starts sliding. */
        const val PIN_IK_ITERATIONS = 6
    }
}
