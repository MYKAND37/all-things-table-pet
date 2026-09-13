package dev.atp.pet.engine.physics

import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.math.normalizeAngle
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.Skeleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Articulated-body physics for one character.
 *
 * Every joint carries its own angle and angular momentum, so lifting the figure by one
 * hand lets the rest of it hang: each bone takes a gravity torque summed over its whole
 * subtree, resisted by an angular spring towards a target pose. That spring constant is
 * the dial between the two behaviours the project wants — at zero the figure is a limp
 * ragdoll, cranked up it holds a pose.
 *
 * The maths mirrors tools/ragdoll.py, which carries the tests and the full history of the
 * three approaches that did not work. Read that file before changing anything here.
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
    private val wallRight = spec.worldWidth
    private val restitution = 0.2f
    private val groundFriction = 2.5f

    private val children = HashMap<String, MutableList<Bone>>()
    private val subtrees = HashMap<String, List<Bone>>()
    private val colliderType = HashMap<String, String>()
    private val colliderRadius = HashMap<String, Float>()
    private val mass = HashMap<String, Float>()
    private val target = HashMap<String, Float>()

    private val defaultRadius = spec.headHeight * 0.18f
    private val random = Random(20260913)

    private val rootHome: Vec2
    var rootPos: Vec2
        private set
    private var rootVel = Vec2.ZERO

    private val angle = HashMap<String, Float>()
    private val anglePrev = HashMap<String, Float>()

    var grounded = false
        private set

    /** Height of the figure in its rest pose, used to tell "still up" from "already down". */
    private val standingSpan: Float

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
            if (!byName.containsKey(s.name)) continue
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
        standingSpan = spanOfFigure()
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

    private fun spanOfFigure(): Float {
        var low = Float.NEGATIVE_INFINITY
        var high = Float.MAX_VALUE
        for (b in bones) {
            low = max(low, colliderLow(b))
            high = kotlin.math.min(high, b.worldPosition.y)
        }
        return low - high
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

        val noise = ANGULAR_NOISE * (1f - stiffness)
        if (noise > 0f) {
            for (b in bones) {
                angle[b.name] = (angle[b.name] ?: 0f) + (random.nextFloat() * 2f - 1f) * noise
            }
        }

        // A limp figure that has come to rest gives way instead of standing there. It
        // stops giving once it is already down, or it would never actually come to rest.
        val slow = abs(rootVel.x) < 30f && abs(rootVel.y) < 30f
        val giving = stiffness < 0.25f && grounded && slow &&
            spanOfFigure() > 0.55f * standingSpan
        val give = if (giving) COLLAPSE_GAIN * (1f - stiffness) else 0f

        for (b in bones) {
            val name = b.name
            val theta = angle[name] ?: 0f
            val prev = anglePrev[name] ?: 0f
            var omega = theta - prev
            // MAX_OMEGA is rad/s; omega here is a per-step difference.
            val cap = MAX_OMEGA * dt
            omega = omega.coerceIn(-cap, cap)

            var a = alpha[name] ?: 0f
            if (giving) {
                // Positive feedback on the joint's own deviation. A joint with no static
                // friction cannot hold an angle, so whatever it has already given, it
                // gives more of. Without this a limp figure that lands on its feet is a
                // rigid statue balanced on a support polygon: every joint has found its
                // zero-torque angle and nothing ever disturbs it.
                a += theta * give
            }
            val k = stiffness * K_MAX
            if (k > 0f) {
                val c = 2f * sqrt(k) * SPRING_ZETA
                a += -k * (theta - (target[name] ?: 0f)) - c * (omega / dt)
            }

            var next = theta + omega * (1f - ANGULAR_DAMP * dt) + a * dt * dt
            next = next.coerceIn(b.minAngle, b.maxAngle)

            // Keep the angle we came FROM. Verlet reads the difference between the two
            // stored angles as the velocity, so writing anything else freezes the velocity
            // and the integrator diverges.
            anglePrev[name] = theta
            angle[name] = next
        }

        // Root.
        if (noise > 0f) {
            rootVel = Vec2(rootVel.x + (random.nextFloat() * 2f - 1f) * LINEAR_NOISE * dt, rootVel.y)
        }
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

    /**
     * Resolve the floor per bone, not just for the whole figure.
     *
     * Lifting only the root makes the body perch on whichever part happened to touch
     * first and keeps its shape from there on, which is what "limp still feels stiff"
     * actually was: the limbs never get to rest ON the floor, and every other part stays
     * as rigid as the moment it landed.
     */
    private fun ground(dt: Float) {
        var settled = false
        for (pass in 0 until GROUND_PASSES) {
            var deepest = 0f
            var target: Bone? = null
            for (b in bones) {
                val pen = colliderLow(b) - floor
                if (pen > deepest) {
                    deepest = pen
                    target = b
                }
            }
            if (target == null || deepest < 0.05f) {
                settled = true
                break
            }
            if (!turnOut(target, deepest)) lift(deepest, dt)
        }

        if (!settled) {
            // Ran out of passes with something still under the floor: the root carries it.
            var worst = 0f
            for (b in bones) worst = max(worst, colliderLow(b) - floor)
            if (worst > 0.05f) lift(worst, dt)
        }

        walls()
    }

    /** Rotate a bone about its own joint until the part of it in the floor comes out. */
    private fun turnOut(bone: Bone, pen: Float): Boolean {
        val h = bone.worldPosition
        val contact = if (colliderType[bone.name] == "circle") {
            com(bone)
        } else {
            val t = bone.tipPosition()
            if (t.y >= h.y) t else h
        }
        val dx = contact.x - h.x
        // Rotating about the head raises the contact at dx per radian, so a contact
        // directly under the joint cannot be helped by turning and the root has to do it.
        if (abs(dx) < 1f) return false
        val step = (pen / dx).coerceIn(-MAX_TURN_STEP, MAX_TURN_STEP)
        val before = bone.rotation
        val after = (before + step).coerceIn(bone.minAngle, bone.maxAngle)
        if (after == before) return false
        bone.rotation = after
        angle[bone.name] = after
        // Absorb it into the integrator history: a contact that is already resting must
        // not feed the correction back in as velocity and bounce.
        anglePrev[bone.name] = (anglePrev[bone.name] ?: 0f) + (after - before)
        skeleton.update()
        return true
    }

    private fun lift(amount: Float, dt: Float) {
        rootPos = Vec2(rootPos.x, rootPos.y - amount)
        if (rootVel.y > 0f) rootVel = Vec2(rootVel.x, -rootVel.y * restitution)
        rootVel = Vec2(rootVel.x * (1f - groundFriction * dt), rootVel.y)
        applyAngles()
    }

    private fun walls() {
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
        var lowest = Float.NEGATIVE_INFINITY
        for (b in bones) lowest = max(lowest, colliderLow(b))
        grounded = lowest > floor - 6f
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

    /**
     * Hold a saved pose.
     *
     * Setting the spring targets is all it takes: the joints are already pulled towards
     * [target], so a pose is just a different set of angles to be pulled towards. Dragging
     * still works — the pin moves the whole figure, and a dragged joint springs back to
     * the pose it is supposed to be holding.
     */
    fun applyPose(angles: Map<String, Float>, hold: Float = 0.85f) {
        for (b in bones) target[b.name] = angles[b.name] ?: 0f
        stiffness = hold
    }

    /** Drop the pose: the joints go back to hanging limp. */
    fun clearPose() {
        for (b in bones) target[b.name] = 0f
    }

    /**
     * Put the figure back on its feet where it started, keeping the joints as they are.
     *
     * Picking an action off the list has to be visible, and a pet that was thrown into a
     * corner would otherwise strike the pose lying on its side where nobody can see it.
     */
    fun home() {
        rootPos = rootHome
        rootVel = Vec2.ZERO
        pinLast = null
        pinVel = Vec2.ZERO
    }

    /** Let go: keep whatever speed the drag had. */
    fun release() {
        rootVel = pinVel
        pinLast = null
        pinVel = Vec2.ZERO
    }

    fun reset() {
        home()
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

        /**
         * Per second, on every joint. This was 1.4 and made the whole figure feel like it
         * was moving through syrup, which read as "stiff" even with the springs at zero.
         */
        const val ANGULAR_DAMP = 0.9f

        const val MAX_SPEED = 20000f
        const val MAX_OMEGA = 40f

        /** How hard the grab pulls the chain before the root starts sliding. */
        const val PIN_IK_ITERATIONS = 6

        /** Floor resolution passes per step, and the largest turn one pass may apply. */
        const val GROUND_PASSES = 4
        const val MAX_TURN_STEP = 0.35f

        /** A whisper of noise, so an exactly symmetrical pose can break on its own. */
        const val ANGULAR_NOISE = 2.0e-5f
        const val LINEAR_NOISE = 0.6f

        /** How strongly a resting limp joint amplifies its own deviation, in 1/s^2. */
        const val COLLAPSE_GAIN = 6f
    }
}