package dev.atp.pet.engine.physics

import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.math.normalizeAngle
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.Skeleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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

    /** Where the root bone is in the artwork's own coordinates. See rootHome. */
    private val rigRoot: Vec2
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

    /** The finger positions from last step, and where the fingers are now. */
    private var pinLastTarget: Vec2? = null
    private var pinTargets: List<Vec2> = emptyList()
    private var pinned = false

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

        // Measure the home position first. applyAngles() needs the rig's own origin to compute
        // the offset, so it cannot run before this.
        skeleton.rootTransform = Transform.IDENTITY
        skeleton.update()
        // A rig is authored in the ART canvas' coordinates and the room is deeper than the art
        // canvas, so the figure is stood on the real floor by offsetting it. Two numbers, and
        // confusing them put the pet a body's height above the ground, where it fell out of
        // the world and was never seen again:
        //
        //   rigRoot   where the root bone is in the artwork  (never moves)
        //   rootHome  where the figure STANDS in the room    (rigRoot + the air)
        //
        // The transform handed to the skeleton is measured from rigRoot, so at rest it is
        // exactly the air -- which is the whole of "the artwork's ground line is a body's
        // height above the floor it stands on".
        rigRoot = bones.first().worldPosition
        rootHome = rigRoot + Vec2(0f, spec.standOffset)
        rootPos = rootHome
        applyAngles()
        standingSpan = spanOfFigure()
    }

    // -- placement ----------------------------------------------------------

    private fun applyAngles() {
        for (b in bones) {
            b.rotation = angle[b.name] ?: 0f
        }
        // Measured from the ARTWORK's root, not from where the figure stands: see the note in
        // init. Subtracting rootHome here cancels the air out and puts the pet back in the
        // drawing's coordinates, a body's height above the floor it is supposed to stand on.
        skeleton.rootTransform = Transform(position = rootPos - rigRoot)
        skeleton.update()
    }

    private fun com(b: Bone): Vec2 {
        val h = b.worldPosition
        val t = b.tipPosition()
        return Vec2((h.x + t.x) / 2f, (h.y + t.y) / 2f)
    }

    /** How thick a bone is. Anything outside that collides with the figure asks here. */
    fun colliderRadius(bone: Bone): Float = colliderRadius[bone.name] ?: defaultRadius

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

    /** Where a finger took hold: a bone, and how far along it from the joint. */
    class Grip(val bone: Bone, val offset: Float)

    /**
     * The bone whose collider is nearest to [p], and how far along it the finger landed.
     *
     * The offset is not a refinement, it is the whole point. Holding the bone's HEAD means
     * the head of the thigh IS THE HIP, so grabbing a leg anywhere along its length pinned
     * the top of the body and lifted the figure upright by the waist -- there was nothing
     * above the grip left to hang. Taking hold of the point under the finger is the
     * difference between lifting a ragdoll by the leg and picking it up by the pelvis.
     */
    fun grabAt(p: Vec2): Grip? {
        var best: Bone? = null
        var bestOffset = 0f
        var bestDist = Float.MAX_VALUE
        for (b in bones) {
            val r = colliderRadius[b.name] ?: defaultRadius
            val head = b.worldPosition
            val tip = b.tipPosition()
            val t = projection(p, head, tip)
            val near = Vec2(head.x + (tip.x - head.x) * t, head.y + (tip.y - head.y) * t)
            val d = hypot(p.x - near.x, p.y - near.y) - r
            if (d < bestDist) {
                bestDist = d
                best = b
                bestOffset = t * b.length
            }
        }
        // Generous: a finger is much fatter than a bone, and missing the grab entirely is
        // the worst possible outcome.
        val bone = best ?: return null
        return if (bestDist < spec.headHeight * 0.9f) Grip(bone, bestOffset) else null
    }

    /** Where along a segment [p] projects, clamped to the segment. */
    private fun projection(p: Vec2, a: Vec2, b: Vec2): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq < 1e-6f) return 0f
        return (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
    }

    /**
     * The point on a bone a finger is actually holding.
     *
     * The bone's own rotation carries it, so the grip stays where it was taken as the limb
     * turns under it -- which is what holding a limb means.
     */
    private fun gripPoint(bone: Bone, offset: Float): Vec2 {
        if (offset == 0f) return bone.worldPosition
        val a = bone.worldRotation
        return Vec2(
            bone.worldPosition.x + cos(a) * offset,
            bone.worldPosition.y + sin(a) * offset,
        )
    }

    /**
     * The figure's centre of gravity, in canvas coordinates.
     *
     * The one number that decides which way up a body hangs, so it is also the one worth
     * being able to see: the test bench draws it, and the pin's whole job is to end up with
     * this point directly below the finger.
     */
    fun centreOfMass(): Vec2 {
        var x = 0f
        var y = 0f
        var total = 0f
        for (b in bones) {
            val c = com(b)
            val m = mass[b.name] ?: 1f
            x += c.x * m
            y += c.y * m
            total += m
        }
        return if (total <= 0f) rootPos else Vec2(x / total, y / total)
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

    fun step(dt: Float, pins: List<Pin> = emptyList()) {
        pinned = pins.isNotEmpty()
        pinTargets = pins.map { it.target }
        applyAngles()

        // Gravity torque about each bone's head, over the weight the joint is actually
        // carrying -- which is not always its own subtree. See hangingSet.
        val gripped = if (pins.size == 1) byName[pins[0].bone] else null
        val alpha = HashMap<String, Float>(bones.size)
        for (b in bones) {
            val hx = b.worldPosition.x
            var tau = 0f
            var inertia = 0f
            for (d in hangingSet(b, gripped)) {
                val c = com(d)
                val m = mass[d.name] ?: 1f
                tau += m * gravity * (c.x - hx)
                val dx = c.x - hx
                val dy = c.y - b.worldPosition.y
                inertia += m * (dx * dx + dy * dy)
            }
            alpha[b.name] = tau / max(inertia, 1e-6f)
        }

        if (pinTargets.size == 1) {
            // A body held at ONE point is a pendulum about that point, and that is the
            // only thing left with an opinion about which way up the figure is: its feet
            // are off the ground and the pin does not care about orientation at all.
            // Without this, gravity's torque about the PELVIS is the only term there is,
            // and a figure hanging from an ankle settles with its weight below its pelvis
            // — folded up beside the hand — instead of below the hand.
            //
            // Two pins are not a pendulum, they are a hanger: the body is held at both
            // ends, there is nothing left to swing about, and the torque is not applied.
            val pin = pinTargets[0]
            var tau = 0f
            var inertia = 0f
            for (b in bones) {
                val c = com(b)
                val m = mass[b.name] ?: 1f
                tau += m * gravity * (c.x - pin.x)
                val dx = c.x - pin.x
                val dy = c.y - pin.y
                inertia += m * (dx * dx + dy * dy)
            }
            alpha[bones.first().name] = tau / max(inertia, 1e-6f)
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
        // Deliberately not while a finger is holding the figure: "a limp body that has come
        // to rest standing up should fall over" is a statement about a body standing on its
        // own. One that is being carried is not standing, and this feedback is strong
        // enough that firing it mid-hang throws the figure out of the pose entirely.
        val giving = stiffness < 0.25f && grounded && slow && !pinned &&
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
            if (giving && b.parent != null) {
                // Positive feedback on the joint's own deviation. A joint with no static
                // friction cannot hold an angle, so whatever it has already given, it
                // gives more of. Without this a limp figure that lands on its feet is a
                // rigid statue balanced on a support polygon: every joint has found its
                // zero-torque angle and nothing ever disturbs it.
                //
                // Never on the root. Its angle is not a joint's deviation, it is the
                // figure's orientation in the world, and a term proportional to THAT is a
                // torque of several rad/s^2 in whichever direction the body happens to be
                // lying — a kick, not a give.
                a += theta * give
            }
            val k = stiffness * K_MAX
            if (k > 0f) {
                val c = 2f * sqrt(k) * SPRING_ZETA
                a += -k * (theta - (target[name] ?: 0f)) - c * (omega / dt)
            }

            var next = theta + omega * (1f - ANGULAR_DAMP * dt) + a * dt * dt
            next = next.coerceIn(lowLimit(b), highLimit(b))

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
        rootVel = Vec2(rootVel.x, rootVel.y + gravity * dt)
        val damp = 1f - LINEAR_DAMP * dt
        rootVel = Vec2(rootVel.x * damp, rootVel.y * damp)
        val speed = rootVel.length()
        if (speed > MAX_SPEED) rootVel = rootVel / speed * MAX_SPEED
        rootPos = rootPos + rootVel * dt

        applyAngles()
        ground(dt)
        applyAngles()

        if (pins.isNotEmpty()) {
            holdPins(dt, pins)
            carryFloor(dt)
            applyAngles()
        }
    }

    /**
     * The floor, for a figure somebody is holding up.
     *
     * Not a wall and not a cushion: the figure may hang CARRY_SINK below the line, and past
     * that the floor is exactly as hard as it ever was. Deliberately NOT the per-bone
     * resolution a standing figure gets — that one turns the deepest bone out of the floor
     * about its own joint, and on a figure hanging head-down the deepest bone is the neck.
     * Lifting the neck is the bow. The shape of a carried body is gravity's business.
     */
    private fun carryFloor(dt: Float) {
        if (!hanging()) return
        var deepest = 0f
        for (b in bones) deepest = max(deepest, colliderLow(b) - floor)
        if (deepest > CARRY_SINK) lift(deepest - CARRY_SINK, dt)
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
        if (hanging()) {
            // Turned over and held by one finger: the finger has the weight, and the floor has
            // nothing to say about where the head is. See carryFloor, which runs after the
            // pins — the pins are what moves the figure, and a floor resolved before them is a
            // floor the hand can push straight through.
            walls()
            return
        }
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

        // And a last resort, for a figure that is not under the floor but PAST it: if every
        // bone is below the ground line, something upstream put it there, and a pet that is
        // simply not on the screen any more is the worst possible way to find out. Putting it
        // back on the floor costs one comparison per bone and turns a lost pet into a pet
        // that lands.
        var highest = Float.MAX_VALUE
        for (b in bones) highest = kotlin.math.min(highest, b.worldPosition.y)
        if (highest > floor) {
            rootPos = Vec2(rootPos.x, floor - standingSpan)
            rootVel = Vec2.ZERO
            applyAngles()
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
    private fun holdPins(dt: Float, pins: List<Pin>) {
        // Gauss-Seidel over the fingers: one finger does not care, two fingers pulling in
        // opposite directions need a couple of rounds before they agree.
        if (CARRY_GAIN > 0f) {
            // The hand has taken the weight, so the body comes with it: see CARRY_GAIN.
            var cx = 0f
            var cy = 0f
            var n = 0
            for (pin in pins) {
                val bone = byName[pin.bone] ?: continue
                val p = gripPoint(bone, pin.offset)
                cx += pin.target.x - p.x
                cy += pin.target.y - p.y
                n++
            }
            if (n > 0) {
                rootPos = Vec2(rootPos.x + cx / n * CARRY_GAIN, rootPos.y + cy / n * CARRY_GAIN)
                skeleton.update()
            }
        }

        for (round in 0 until PIN_OUTER) {
            for (pin in pins) {
                byName[pin.bone]?.let { solvePin(it, pin.target, pin.offset) }
            }
        }

        // Whatever the joints could not reach, the root carries — split between the pins,
        // so two hands pulling opposite ways do not fight over one translation.
        var dx = 0f
        var dy = 0f
        for (pin in pins) {
            val bone = byName[pin.bone] ?: continue
            val p = gripPoint(bone, pin.offset)
            dx += pin.target.x - p.x
            dy += pin.target.y - p.y
        }
        dx /= pins.size
        dy /= pins.size
        rootPos = Vec2(rootPos.x + dx, rootPos.y + dy)

        // The finger's own speed, not the leftover: the leftover is nearly zero once the
        // chain reaches, so measuring a throw by it meant a flick threw nothing.
        val target = pins[0].target
        val last = pinLastTarget
        if (last != null && dt > 1e-6f) {
            val a = 0.35f
            pinVel = Vec2(
                pinVel.x + ((target.x - last.x) / dt - pinVel.x) * a,
                pinVel.y + ((target.y - last.y) / dt - pinVel.y) * a,
            )
        }
        pinLastTarget = target
        pinLast = byName[pins[0].bone]?.let { gripPoint(it, pins[0].offset) }
    }

    /**
     * Pull one point of the figure to the finger.
     *
     * Every joint in the chain turns to line its tip up with the finger — cyclic
     * coordinate descent, which is the solve that STRAIGHTENS a chain held by its end,
     * the way a limp limb lines up with the pull when you dangle it.
     *
     * The point that has to reach the finger is the point the finger TOOK HOLD OF, not
     * the bone's joint: see [grabAt].
     *
     * The root is in the chain because two fingers pulling a pair of legs apart need it:
     * the hip's own limits will not splay a leg far enough on their own. It is held on a
     * short leash, because the root's rotation is the figure's whole orientation and
     * gravity has the stronger claim on that. Take the leash off entirely and the pin and
     * gravity undo each other every frame; a figure held by the ankle then sticks at
     * whatever angle they happen to cancel at, with its torso out sideways.
     */
    private fun solvePin(bone: Bone, target: Vec2, offset: Float) {
        val chain = chainToRoot(bone)
        for (iteration in 0 until PIN_IK_ITERATIONS) {
            val end = gripPoint(bone, offset)
            if (hypot(target.x - end.x, target.y - end.y) < 0.5f) break
            for (b in chain) {
                val e = gripPoint(bone, offset)
                val px = b.worldPosition.x
                val py = b.worldPosition.y
                if (hypot(e.x - px, e.y - py) < 1e-6f) continue
                val current = atan2(e.y - py, e.x - px)
                val wanted = atan2(target.y - py, target.x - px)
                var turn = normalizeAngle(wanted - current)
                if (b.parent == null) turn *= ROOT_PIN_GAIN
                val turned = (b.rotation + turn).coerceIn(lowLimit(b), highLimit(b))
                if (turned != b.rotation) {
                    b.rotation = turned
                    angle[b.name] = turned
                    skeleton.update()
                }
            }
        }
    }

    /**
     * What this joint is allowed to do, which is not always what the file says.
     *
     * The root bone's rotation IS the figure's global orientation. Its authored limits are
     * a stand-in for the one thing that actually has an opinion about that — the ground —
     * so they apply whenever the figure is on its own. While a finger is holding it, the
     * authored limit is what stopped a figure picked up by the ankle from hanging: the
     * solver could rotate every joint EXCEPT the one that would have turned the body over,
     * so it folded the legs instead and left the torso sticking out sideways.
     *
     * Deliberately not "while airborne": a figure in free fall keeps its authored limits
     * so that it lands the way it always did. Losing that made every landing sprawl.
     */
    /**
     * What a joint is carrying: the bones whose weight pulls on it.
     *
     * This is the subtree — the marionette model, every joint feels what dangles from it —
     * UNLESS the finger is holding something inside that subtree. Then the pull is on the far
     * side: the joint is not holding that body up, that body is holding THE JOINT up, and the
     * weight on it is everything else.
     *
     * Ignoring this is why picking the figure up by the ankle bent it like a bow instead of
     * turning it over. The hip's subtree is the legs, so the hip felt the weight of a pair of
     * legs and nothing else, and the torso — a separate branch off the same root — was never
     * felt anywhere except at its own neck. So the hip had no reason to turn the body over,
     * the torso had no reason to hang, and the figure folded at the waist, which is exactly
     * what it looked like: bowing.
     *
     * Nothing changes while no finger is holding anything: the subtree is the answer, and a
     * figure falling on its own behaves exactly as it always did.
     */
    private fun hangingSet(bone: Bone, gripped: Bone?): List<Bone> {
        val sub = subtrees[bone.name]!!
        if (gripped == null) return sub
        if (!sub.contains(gripped)) return sub
        return bones.filter { !sub.contains(it) }
    }

    /**
     * Is this figure hanging off the finger rather than resting on the ground?
     *
     * It matters because the floor resolves the two completely differently, and getting it
     * wrong is visible in both directions: a hanging figure whose head the floor insists on
     * lifting turns over into a bow, and a figure that is merely being dragged along the
     * ground — limp enough that it flops while you pull it — must still land on the floor
     * rather than sink through it.
     *
     * Orientation is the whole test, and it is the honest one. A figure turned past this far
     * over is not standing on anything: everything under the finger is below the finger.
     */
    private fun hanging(): Boolean {
        if (!pinned) return false
        return abs(normalizeAngle(angle[bones.first().name] ?: 0f)) > UPSIDE_DOWN
    }

    private fun lowLimit(bone: Bone): Float =
        if (bone.parent == null && pinned) -FULL_TURN else bone.minAngle

    private fun highLimit(bone: Bone): Float =
        if (bone.parent == null && pinned) FULL_TURN else bone.maxAngle

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

    /**
     * Something hit the figure.
     *
     * [strength] is a change in how fast the whole body ends up moving, in px/s — not a
     * force. Nobody tuning a prop can know what the bone masses work out to, and one number
     * that means "this shoves the body this fast" is the only kind anybody can turn.
     *
     * A hit that is off centre also spins the figure about the hit joint. Without that a
     * hit from the left and a hit from the right look identical, and the character reads as
     * being pushed by nothing in particular.
     */
    fun impulse(bone: Bone, dir: Vec2, strength: Float) {
        rootVel = rootVel + dir * strength
        val armX = bone.worldPosition.x - rootPos.x
        val armY = bone.worldPosition.y - rootPos.y
        val spin = (armX * dir.y - armY * dir.x) * strength * SPIN_GAIN
        angle[bone.name] = ((angle[bone.name] ?: 0f) + spin).coerceIn(bone.minAngle, bone.maxAngle)
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

    /** One finger: the bone it is holding, where on that bone, and where the finger is. */
    class Pin(val bone: String, val target: Vec2, val offset: Float = 0f)

    /** How fast the finger was moving when it let go. That is what a throw is. */
    fun releaseSpeed(): Float = pinVel.length()

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
        /**
         * How much a hit off centre spins the figure. A hit 300px from the root at 500px/s
         * is about a third of a radian, which reads as a shove rather than a spin.
         */
        const val SPIN_GAIN = 2.0e-6f

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

        /** Gauss-Seidel rounds over the fingers; two pins need a couple before they agree. */
        const val PIN_OUTER = 3

        /**
         * How much of its turn the ROOT takes, against every other joint in the chain.
         *
         * 0.15 is where both behaviours survive: below about 0.1 a pair of legs cannot be
         * splayed against the hip's own limits, above about 0.3 the pin starts winning
         * against gravity and a figure held by the ankle stops hanging. Mirrors
         * ROOT_PIN_GAIN in tools/ragdoll.py, where tools/drag_check.py pins both ends.
         */
        const val ROOT_PIN_GAIN = 0.15f

        /**
         * How much of the finger's pull the ROOT takes, before the chain is solved at all.
         *
         * This is the difference between POSING a limb and CARRYING the figure, and it is the
         * whole of "I grab its leg and lift, and the pet comes up with my hand and hangs". The
         * chain solve can always reach the finger by bending something — grab an ankle and
         * lift and it rotates the hip, which lifts the foot and leaves the body standing on
         * the other leg, leaning, exactly like a bow. The body only comes up if the ROOT takes
         * part of the pull.
         *
         * Not 1.0: pulling the root all the way is exact and leaves the chain with nothing to
         * do, so a limb can never be posed and a hanging arm keeps whatever shape it had. Not
         * 0.0: the figure never leaves the ground and cannot be picked up at all. Mirrors
         * CARRY_GAIN in tools/ragdoll.py, where tools/carry_check.py pins it down.
         */
        const val CARRY_GAIN = 0.85f

        /**
         * How far a hanging figure may dip below the floor line, in px.
         *
         * A body picked up by one ankle only needs about a figure's height of lift to turn
         * right over, and less than that leaves its head below the floor line on the way. The
         * floor is still a floor — see carryFloor — but it is not in the way of a body hanging
         * off a hand.
         */
        const val CARRY_SINK = 460f

        /**
         * How far over a figure must be turned before the finger, and not the floor, is what
         * is holding it up, in radians. Not a quarter turn: a figure laid on its side is on
         * the floor, and the floor resolves it exactly as it always did. This is the line
         * between standing on the ground and hanging off a hand.
         */
        const val UPSIDE_DOWN = 2.0f

        /** Half a turn, the widest a joint can be when the ground is not the boss of it. */
        private const val FULL_TURN = 3.1415927f

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