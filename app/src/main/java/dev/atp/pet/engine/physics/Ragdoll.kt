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

    /**
     * The app's gravity, as a multiple of the character's own.
     *
     * A character's gravity is authored per package — a heavy pet falls like a heavy pet —
     * and this is the one dial over the top of it, which is what "全局设置" is for. It is
     * live, so the slider does not have to be a rebuild.
     */
    var gravityScale: Float = 1f

    private val g: Float get() = gravity * gravityScale
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

    /** The two per-part switches, by bone. See BoneSpec.collides and .grabbable. */
    private val solid = HashMap<String, Boolean>()
    private val grabbable = HashMap<String, Boolean>()
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

    /**
     * How much of MAX_IK_RATE each joint has already spent THIS frame. See solvePin.
     *
     * The budget is per frame, not per iteration and not per round. It lives here rather than
     * inside solvePin because the reset has to happen once per frame, outside the PIN_OUTER
     * loop, and an iteration cannot know whether it is the first one.
     */
    private val ikSpent = HashMap<String, Float>()

    /**
     * Whether the figure is being treated as hanging right now.
     *
     * Stateful on purpose: it is the hysteresis in [hanging], and a plain threshold there is
     * a vibration rather than a nicety. See the note on that function.
     */
    private var hangingNow = false

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
            // The two per-part switches. A bone that does not collide keeps its geometry and
            // its mass -- it is still part of the figure and still swings -- and only the
            // WORLD stops being able to feel it: the floor ignores it, and the sandbox hands
            // the props and the liquid a radius of zero for it. A bone a finger cannot take
            // hold of is the other switch: grabAt still finds it, and the sandbox asks.
            solid[s.name] = s.collides
            grabbable[s.name] = s.grabbable
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

    /** Does the world feel this part? See BoneSpec.collides. */
    fun isSolid(name: String): Boolean = solid[name] != false

    /** May a finger take hold of this part? See BoneSpec.grabbable. */
    fun canGrab(name: String): Boolean = grabbable[name] != false

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
     *
     * Not private, and not reimplemented by the bench that also wants it: the diagnostic
     * recorder writes this point into the CSV beside the finger that is pulling it, and
     * those two are only comparable if this is the SAME point the pin solver aims at. A
     * second copy of the two lines below in the view would be a second opinion about the
     * exact thing being diagnosed. Reads only -- see PhysicsSandboxView.recordFrame.
     */
    fun gripPoint(bone: Bone, offset: Float): Vec2 {
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

    // -- the phase probe, for the bench's tuning panel -----------------------

    /**
     * Which bone a caller wants [step] split by phase, or null for nobody.
     *
     * The bench's tuning panel reads the held joint once a frame, but the four things that
     * move it in between are not reachable from out there: the integrator is inline in
     * [step], and ground, holdPins and carryFloor are private. So the panel names the bone
     * here, [step] cuts its own frame at the four phase boundaries around that bone, and
     * [probeTurn] carries the signed pieces back out. Naming the bone is what keeps this
     * honest -- the split is of the SAME joint the panel's own whole-frame number is about,
     * so the four pieces can be checked against it instead of being taken on trust.
     *
     * Nothing here feeds back into the simulation: reads and subtractions only. The rest of
     * the app leaves it null, which costs one map lookup per step.
     */
    var probeBone: String? = null

    /**
     * Signed radians each phase of the last [step] gave [probeBone], indexed by PROBE_*.
     *
     * Signed, not absolute, because the question this answers is which phase is going back
     * and forth: a joint that takes +5 degrees and then -5 has moved ten and gone nowhere,
     * and only the sign says so. The four add up to that bone's whole turn of the frame by
     * construction -- they ARE the frame, cut where the phases run -- which is what lets
     * the panel print them under its whole-frame readout and have the two agree.
     *
     * The measure is the bone's own local rotation, which is the angle the integrator, the
     * pin solver and turnOut all write. A phase that moves only the ROOT (the carry floor's
     * lift, the pin solver's leftover translation) therefore reads zero here: it moved the
     * body, not this joint's angle. That is a real answer and not a gap -- it is what says
     * the floor and the carry are not what a dragged bone is shaking from.
     */
    val probeTurn = FloatArray(PROBE_PHASES)

    /**
     * Cut the probe's frame here: what [probe] turned since the previous cut is what the
     * phase that just ran did to it, and the rotation returned is where the next cut
     * measures from.
     *
     * Only [step] calls this, and only immediately after each phase has run -- the cut is
     * the boundary. A null probe writes zero rather than leaving the last frame's numbers
     * behind, so [probeTurn] is always one frame's split and never a mixture of two.
     */
    private fun probeCut(probe: Bone?, phase: Int, from: Float): Float {
        if (probe == null) {
            probeTurn[phase] = 0f
            return from
        }
        val at = probe.rotation
        probeTurn[phase] = at - from
        return at
    }

    // -- the step -----------------------------------------------------------

    fun step(dt: Float, pins: List<Pin> = emptyList()) {
        // Sampled before anything moves, and before the first applyAngles: the four cuts
        // below then telescope to exactly the turn this frame leaves the bone with, which is
        // the number the panel's whole-frame readout takes as its own. See probeCut.
        val probe = probeBone?.let { byName[it] }
        var probeAt = probe?.rotation ?: 0f

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
                tau += m * g * (c.x - hx)
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
                tau += m * g * (c.x - pin.x)
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
        rootVel = Vec2(rootVel.x, rootVel.y + g * dt)
        val damp = 1f - LINEAR_DAMP * dt
        rootVel = Vec2(rootVel.x * damp, rootVel.y * damp)
        val speed = rootVel.length()
        if (speed > MAX_SPEED) rootVel = rootVel / speed * MAX_SPEED
        rootPos = rootPos + rootVel * dt

        applyAngles()
        probeAt = probeCut(probe, PROBE_INTEGRATOR, probeAt)
        ground(dt)
        applyAngles()
        probeAt = probeCut(probe, PROBE_GROUND, probeAt)

        // The last two phases only run when something is being held. Zeroed first, then
        // overwritten by the cuts below if they do run: a stale number from the last frame
        // that had pins is a contribution this frame never made.
        probeTurn[PROBE_PINS] = 0f
        probeTurn[PROBE_CARRY] = 0f
        if (pins.isNotEmpty()) {
            holdPins(dt, pins)
            probeAt = probeCut(probe, PROBE_PINS, probeAt)
            carryFloor(dt)
            probeAt = probeCut(probe, PROBE_CARRY, probeAt)
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
        for (b in bones) {
            // A part that does not collide hangs straight through the line, and the floor
            // must not lift the whole figure because of a ribbon somebody switched off.
            if (!isSolid(b.name)) continue
            deepest = max(deepest, colliderLow(b) - floor)
        }
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
        // One chance per bone per frame. Turning the SAME bone four times is what made the
        // feet buzz: with the contact only ~68 px from the joint, the step that would clear
        // the penetration is larger than MAX_TURN_STEP, so the pass rotates its full 20 deg
        // and carries the contact past the joint -- dx changes sign, the next pass computes
        // the same 20 deg the other way, and the two undo each other four times inside one
        // frame. The net motion is nothing; the flicker is at four times the frame rate, which
        // is the 振幅不大、频率大 the user reports, and it lands on whichever part is touching
        // the floor -- the feet.
        val turned = HashSet<String>()
        for (pass in 0 until GROUND_PASSES) {
            var deepest = 0f
            var target: Bone? = null
            for (b in bones) {
                if (!isSolid(b.name)) continue
                if (turned.contains(b.name)) continue
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
            if (turnOut(target, deepest)) turned.add(target.name) else lift(deepest, dt)
        }

        if (!settled) {
            // Ran out of passes with something still under the floor: the root carries it.
            var worst = 0f
            for (b in bones) {
                if (!isSolid(b.name)) continue
                worst = max(worst, colliderLow(b) - floor)
            }
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

    /**
     * Rotate a bone about its own joint until the part of it in the floor comes out.
     *
     * Returns false when turning cannot help, and the root has to carry the figure instead.
     * That is now also the answer for a rotation that makes things WORSE: the linear estimate
     * pen/dx stops meaning anything once the step is big enough to swing the contact past the
     * joint, and applying it anyway is how the pass ended up undoing itself every frame.
     */
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
        skeleton.update()
        // Did that actually lift the part out, or did it swing the contact over the top and
        // push it deeper? A correction that does not help is a correction that will be undone
        // by the next pass, so it is not applied at all.
        if (colliderLow(bone) - floor > pen - 0.05f) {
            bone.rotation = before
            angle[bone.name] = before
            skeleton.update()
            return false
        }
        // Absorb it into the integrator history: a contact that is already resting must
        // not feed the correction back in as velocity and bounce.
        anglePrev[bone.name] = (anglePrev[bone.name] ?: 0f) + (after - before)
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

        // One rate budget per joint per FRAME, shared by every iteration and every outer round.
        //
        // This reset is the whole point. The cap inside solvePin used to be applied to each
        // iteration on its own, which quietly multiplied it by
        // PIN_OUTER * PIN_IK_ITERATIONS = 18: a joint could turn 18x the intended rate in a
        // single frame -- measured 46-77 deg/frame against a nominal 8.6. The reason it showed
        // on the hands and feet and nowhere else is solvePin's early exit: a proximal joint
        // reaches the finger on an early pass and breaks out, so it never spent the allowance
        // it had, while the extremities are exactly the ones that keep iterating.
        ikSpent.clear()
        for (round in 0 until PIN_OUTER) {
            for (pin in pins) {
                byName[pin.bone]?.let { solvePin(it, pin.target, pin.offset, dt) }
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
    private fun solvePin(bone: Bone, target: Vec2, offset: Float, dt: Float) {
        val chain = chainToRoot(bone)
        for (iteration in 0 until PIN_IK_ITERATIONS) {
            val end = gripPoint(bone, offset)
            if (hypot(target.x - end.x, target.y - end.y) < 0.5f) break
            for (b in chain) {
                val e = gripPoint(bone, offset)
                val px = b.worldPosition.x
                val py = b.worldPosition.y
                if (hypot(e.x - px, e.y - py) < LEVER_MIN) continue
                val current = atan2(e.y - py, e.x - px)
                val wanted = atan2(target.y - py, target.x - px)
                // Only a fraction of the way, if the gain says so: an aim that is taken in
                // full every frame is the relay described on PIN_JOINT_GAIN, and the rate
                // limit below then has to stop it every frame, which is the flip itself.
                var turn = normalizeAngle(wanted - current) * PIN_JOINT_GAIN
                if (b.parent == null) turn *= ROOT_PIN_GAIN
                // A limit on how far one FRAME may swing a joint toward the finger. See
                // MAX_IK_RATE: an exact aim applied every frame to a body that is also being
                // integrated is a fight, and the fight is the buzz.
                //
                // The budget is whatever is LEFT of this frame's allowance, so the iterations
                // and the outer rounds share one cap instead of each getting their own. What is
                // spent is the rotation actually APPLIED, not the one asked for: a joint pinned
                // against its limit has not moved and must not be charged for it.
                val cap = MAX_IK_RATE * dt - (ikSpent[b.name] ?: 0f)
                if (cap <= 0f) continue
                turn = turn.coerceIn(-cap, cap)
                val turned = (b.rotation + turn).coerceIn(lowLimit(b), highLimit(b))
                if (turned != b.rotation) {
                    // The turn just made IS this joint's velocity for the next frame.
                    //
                    // Verlet reads angle - anglePrev as the joint's velocity, so what these two
                    // writes leave behind is what the integrator does next. Writing angle and
                    // leaving anglePrev alone hands the correction over as EXTRA velocity, on
                    // top of whatever the joint already had: it overshoots the finger, the pin
                    // turns it back, and the two alternate for ever. That is the buzz, and it is
                    // why the panel shows the integrator and the IK as the only two hot phases --
                    // each one is the other's input.
                    //
                    // Absorbing the turn instead (anglePrev += got, which is what turnOut does
                    // for the floor) also fixes the integrator but stops the body coming with the
                    // hand, and the floor-hold and two-finger cases go red. This is the velocity
                    // pass of PBD, and it is the one the suites stay green with. Mirrored in
                    // tools/ragdoll.py; see tools/mirror_check.py.
                    val got = turned - b.rotation
                    ikSpent[b.name] = (ikSpent[b.name] ?: 0f) + abs(got)
                    b.rotation = turned
                    angle[b.name] = turned
                    anglePrev[b.name] = turned - got
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
        if (!pinned) {
            hangingNow = false
            return false
        }

        // With hysteresis, and it is worth 70x rather than being a nicety.
        //
        // The floor treats a carried figure in one of two ways. Hanging, the figure may sink
        // CARRY_SINK below the line and the per-bone resolution stands down. Not hanging,
        // every part of it is turned out of the ground and the whole figure is lifted. Those
        // two answers differ by HUNDREDS of pixels — and both of them MOVE the figure, and
        // moving the figure is what changes the root angle. So a figure that sits on the
        // threshold flips between the two models every single frame, and every flip throws
        // it: measured in tools/ragdoll.py, with a finger holding the hip just under the
        // floor line the root alternated 311 px per frame, and 252 px holding it on the line.
        // With this, 4.5 px and 5.4 px.
        //
        // So it takes more to START hanging than to STOP: past UPSIDE_DOWN going over, and
        // back under HANGING_OFF coming back. In between it keeps whichever it was — which is
        // also the honest reading, because "is this figure on the ground or off it" does not
        // change every sixteen milliseconds.
        //
        // Mirrored in tools/ragdoll.py, and pinned down by the floor-hold cases at the bottom
        // of tools/drag_check.py.
        val turned = abs(normalizeAngle(angle[bones.first().name] ?: 0f))
        if (hangingNow) {
            if (turned < HANGING_OFF) hangingNow = false
        } else if (turned > UPSIDE_DOWN) {
            hangingNow = true
        }
        return hangingNow
    }

    /**
     * Whether the last step decided the figure is hanging off the finger.
     *
     * Read-only, and read from outside by exactly one thing: the bench's diagnostic recorder,
     * which prints it per frame. It is exposed rather than re-derived because the state lives
     * in the hysteresis above -- a view that recomputed "is it hanging" from the root angle
     * would be a second threshold, and the whole point of [hanging] is that there is one.
     */
    val isHanging: Boolean get() = hangingNow

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
         * How much of the aim at the finger one joint actually takes, per solve.
         *
         * 1.0 is "point this joint straight at the target" and it is where this has always
         * been. It is also the top of the relay a drag turns into: an exact aim that
         * MAX_IK_RATE cuts off every frame arrives short, next frame aims again and goes
         * past, and the signed turn alternates at the cap itself -- measured on the held
         * bone as +-8.5944 degrees, which is exactly MAX_IK_RATE at 60 Hz, changing sign on
         * 63 of 179 frames. World angles add down a chain, so every joint in it is flipping
         * and the far end accumulates all of them, which is why the buzz is worst at the
         * fingertips. Turning this down to 0.1 took the same measurement to a 9.5% sign
         * change rate and the mean step from 9.20 to 4.33 degrees, and the price is the
         * limb lagging behind the finger -- a trade between a number that can be measured
         * and a feel only the owner can judge.
         *
         * A `var`, unlike everything else here, so the bench's tuning panel can move it
         * while a finger is on the pet: a value that needs a rebuild per try is a value
         * nobody finds. Mirrors PIN_JOINT_GAIN in tools/ragdoll.py -- see
         * tools/mirror_check.py, which reads this declaration as text and goes quiet about
         * both files if it stops looking like a constant.
         */
        var PIN_JOINT_GAIN = 1.0f

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
         * How fast the pin's IK may turn a joint, in radians per second.
         *
         * The aim is exact -- the joint is pointed straight at the target -- and an exact aim
         * applied every frame to a body that is also being integrated is a FIGHT. Measured:
         * the held hand jumped 55 degrees in one frame, then 39 the next, and then rode its
         * joint limits back and forth, which is the buzz reported as 振幅不大、频率大 with
         * v1.0.0. A limb pulls toward what it is holding at a finite rate; this is that rate.
         *
         * It is a RATE rather than a per-frame step so that a 120 Hz phone behaves like a
         * 60 Hz one. Measured against no limit at all: the bone being held went from a tremor
         * ratio of 1.32 and an amplitude of 13.2 degrees to 0.99 and 4.6, and the foot that
         * used to swing 60 degrees while a lying pet was dragged went to 11.6. Slower than
         * this and the floor starts winning arguments it should lose (the floor-hold cases in
         * tools/drag_check.py go red at 6).
         *
         * A `var` like PIN_JOINT_GAIN, and for the same reason: it is the other half of the
         * same rate limit, and which of the two is the wrong number is not knowable without
         * trying both on a real phone. Nothing in the app writes this one today -- the
         * panel's slider is the gain.
         */
        var MAX_IK_RATE = 9.0f

        /**
         * How short a lever arm stops being a lever, in px. See solvePin.
         *
         * Turning a joint moves the point it is holding by |r| px per radian, where r runs
         * from the joint to the point the finger took hold of. A finger that took hold 0.5 px
         * from the joint — and the offset is a distance along the bone, so a grab AT a joint
         * is exactly this — cannot be moved by turning that joint at all. The aim does not
         * care, because the aim is an ANGLE: |e| / |r| radians, so a 1.4 px error asks for 70
         * degrees, and its sign flips the moment that sub-pixel error changes side. The joint
         * then spends its whole per-frame allowance every frame, one way and back.
         *
         * Measured on the hip dragged along the floor with the pin 0.5 px along the root bone:
         * the root turned +8.13, -8.13, +8.13 degrees per frame — the full MAX_IK_RATE * dt —
         * changing sign on 54 of 179 frames, and the worst bone in the body alternated by
         * 15.5 px per frame. 6 px, because a joint spending its entire allowance moves the
         * grabbed point by |r| * MAX_IK_RATE * dt, which is under a pixel at 60 Hz — twice the
         * 0.5 px at which solvePin calls the pin satisfied. A length, not a rate, so it does
         * not move with the frame rate, and short enough that it can only ever switch off the
         * GRABBED bone: every ancestor's lever is the chain out to the grab. Mirrors LEVER_MIN
         * in tools/ragdoll.py, where the measurements and tools/drag_check.py's cases are.
         */
        const val LEVER_MIN = 6.0f

        /**
         * How much of the way to the finger a target moves in one frame: a first-order low
         * pass on the thing the finger is asking for. 1.0 is "use the finger as it is".
         *
         * A real finger is not a straight line. Measured on a mid-bone grab (30 px along the
         * hand) dragged at 260 px/s, with the finger jumping +-8 px per frame the way a
         * thumb on glass does, the held joint's own turn alternates 38% of frames at 1.06
         * degrees and the whole body oscillates 6.29 px per frame. Nothing upstream of the
         * finger causes that and nothing downstream can undo it: the solver is being asked
         * for a real position, and the position is noisy. At 0.35 the same drag reads 15% at
         * 0.265 degrees and 2.19 px -- a quarter of the movement -- and the price is that the
         * pet trails the finger by v * (1 - a) / a / 60, which is 8.8 px at 260 px/s and
         * nothing at all once the finger stops.
         *
         * The cap (MAX_IK_RATE) does NOT touch this: measured across 9, 20, 30, 60, 120 and
         * off, the same drag reads the same to three decimals. This is the input, that is the
         * solver.
         *
         * 0.35 rather than the smallest number that helps, because the lag is what the user
         * feels and it grows as 1/a: 0.15 is 25 px of trail, which is a rubber band. Mirrors
         * PIN_TARGET_ALPHA in tools/ragdoll.py; the numbers above are drag_check.py's.
         */
        var PIN_TARGET_ALPHA = 0.35f

        /**
         * One step of that low pass, as a function rather than a line in the bench.
         *
         * It is here so that the two implementations can be checked against each other: the
         * filter is a MECHANISM, not a rendering detail, and mirror_check reads this
         * expression in both files. The caller owns `previous` -- the bench keeps one per
         * finger, seeded with the raw target on the frame the finger lands so that taking
         * hold does not itself move the pet.
         */
        fun smoothTarget(previous: Vec2, target: Vec2, alpha: Float = PIN_TARGET_ALPHA): Vec2 =
            Vec2(
                previous.x + (target.x - previous.x) * alpha,
                previous.y + (target.y - previous.y) * alpha,
            )

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

        /**
         * How far back from "turned over" a figure has to come before it stops hanging.
         *
         * The gap between this and UPSIDE_DOWN is the hysteresis, and it is doing real work:
         * the two floor models a carried figure can get differ by hundreds of pixels, so a
         * figure that sits on a hard threshold is thrown by it every frame. See [hanging].
         */
        const val HANGING_OFF = 1.65f

        /** Half a turn, the widest a joint can be when the ground is not the boss of it. */
        private const val FULL_TURN = 3.1415927f

        /** Floor resolution passes per step, and the largest turn one pass may apply. */
        const val GROUND_PASSES = 4
        const val MAX_TURN_STEP = 0.35f

        /** A whisper of noise, so an exactly symmetrical pose can break on its own. */
        const val ANGULAR_NOISE = 2.0e-5f
        const val LINEAR_NOISE = 0.6f

        /** How strongly a resting limp joint amplifies its own deviation, in 1/s^2. */
        const val COLLAPSE_GAIN = 10f

        /**
         * The four phases of one step, in the order [step] runs them, for [probeTurn].
         *
         * INDICES, not physics -- the only numbers here that are. They are app-side because
         * the reference has no panel to hand a split to: tools/ragdoll.py was cut the same
         * way when the buzz was diagnosed, but in a scratch script, so mirror_check will
         * list these five under "only in Ragdoll.kt" and that is the whole of the reason.
         *
         * The split is only as good as this order matching the calls in [step]; the panel
         * prints the four under names that say which is which, and the four add up to the
         * frame's whole turn only while the cuts sit in the right places.
         */
        const val PROBE_INTEGRATOR = 0
        const val PROBE_GROUND = 1
        const val PROBE_PINS = 2
        const val PROBE_CARRY = 3

        /** How many phases there are. See [probeTurn]. */
        private const val PROBE_PHASES = 4
    }
}