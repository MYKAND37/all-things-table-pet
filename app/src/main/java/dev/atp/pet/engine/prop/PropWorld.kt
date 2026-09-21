package dev.atp.pet.engine.prop

import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.Skeleton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** One prop touching one part of the body, this frame.
 *
 * [part] is the name the RULES get: a bone's name for a bone, a node's name for a node. A node
 * is a place on a bone, and the place is what a rule is usually about -- "被碰到 · 指尖" is a
 * different instruction from "被碰到 · 整只手". [bone] is what the shove goes to either way.
 */
class Hit(val prop: Prop, val bone: Bone, val value: Float, val part: String = bone.name)

/**
 * A named point on the body, as the world sees it: what a NodeSpec is once the rig is built.
 *
 * It is felt as a circle and it is not a shape the solver knows about -- a node has no length
 * and no joint, so there is nothing about it to solve. What it does have is a place and a size,
 * which is exactly what a contact needs.
 */
class NodePoint(val name: String, val at: Vec2, val radius: Float, val bone: Bone)

/** A prop in the arena, at one moment. */
class Prop(
    val serial: Int,
    val spec: PropSpec,
    var position: Vec2,
    var velocity: Vec2 = Vec2.ZERO,
    var rotation: Float = 0f,
) {
    var held = false
    var spin = 0f

    /**
     * Nailed down: the world does not move it, not even by gravity.
     *
     * Set on the props that are placed by pointing — see [PropKind.isPointed] — where the
     * prop is a MARKER for something the bench puts in the world (a peg, a rope) rather
     * than a thing with a position of its own. The bench draws and enforces those; here it
     * only has to be true that nothing else will.
     */
    var planted = false

    /** How long it has existed, and how long it has been nearly still. */
    var age = 0f
    var still = 0f
    var onFloor = false

    private var lastDrag: Vec2? = null

    fun beginDrag() {
        lastDrag = position
        held = true
    }

    /**
     * Follow the finger, and remember the speed of doing so.
     *
     * That remembered speed is the whole of throwing: letting go keeps whatever velocity
     * the drag had, so a flick throws and a slow placement does not.
     */
    fun dragTo(point: Vec2, dt: Float) {
        val prev = lastDrag
        if (prev != null && dt > 1e-4f) {
            val v = (point - prev) / dt
            velocity = if (v.length() > MAX_DRAG_SPEED) v.normalized() * MAX_DRAG_SPEED else v
        }
        position = point
        lastDrag = point
    }

    fun endDrag() {
        lastDrag = null
        held = false
    }

    companion object {
        const val MAX_DRAG_SPEED = 4000f
    }
}

/**
 * Every prop in the arena, and how they meet the character.
 *
 * Props do not push the character through the rule engine: the shove is physics and
 * happens whether or not anybody wrote a rule. What the rules get is an event — "被道具碰到,
 * 力度 640, 打在 hand_L 上" — and the character's own logic decides what that costs.
 *
 * There is no dependency on the ragdoll here on purpose. The caller passes in how thick a
 * bone is and what to do when something hits one, so this file can be reasoned about (and
 * tested) without a body attached.
 */
class PropWorld(private val floorY: Float, private val worldWidth: Float) {

    val live = mutableListOf<Prop>()

    private var nextSerial = 1
    private val lastHit = HashMap<String, Float>()
    private var clock = 0f

    fun clear() {
        live.clear()
        lastHit.clear()
    }

    fun spawn(
        spec: PropSpec,
        at: Vec2,
        velocity: Vec2 = Vec2.ZERO,
        rotation: Float = 0f,
    ): Prop {
        // Make room by discarding transient things first: a wall of bullets must never
        // push a device the user placed out of the world.
        while (live.size >= MAX_PROPS) {
            val oldest = live.indexOfFirst { it.spec.transient }
            if (oldest < 0) break
            live.removeAt(oldest)
        }
        val prop = Prop(nextSerial++, spec, at, velocity, rotation)
        live.add(prop)
        return prop
    }

    /** Take every prop of one kind off the bench — what a candle does when it burns out. */
    fun removeOf(id: String): Int {
        val gone = live.filter { it.spec.id == id }
        live.removeAll(gone)
        return gone.size
    }

    fun remove(prop: Prop) {
        live.remove(prop)
    }

    /** The prop under a finger, or null. Nearest centre wins, so small things stay pickable. */
    fun grabAt(point: Vec2): Prop? {
        var best: Prop? = null
        var bestDistance = GRAB_SLACK
        for (p in live) {
            // A nail is taken out by tapping it, not by dragging it: the drag of a planted
            // prop would have to mean "move the thing it is planted in", and for a rope that
            // is not even a thing that exists. See the bench's tap handling.
            if (p.planted) continue
            val d = (p.position - point).length() - p.spec.radius
            if (d < bestDistance) {
                bestDistance = d
                best = p
            }
        }
        return best
    }

    fun step(
        dt: Float,
        gravity: Float,
        skeleton: Skeleton,
        radiusOf: (Bone) -> Float,
        onImpulse: (Bone, Vec2, Float) -> Unit,
        nodes: List<NodePoint> = emptyList(),
    ): List<Hit> {
        clock += dt
        val hits = mutableListOf<Hit>()
        val gone = mutableListOf<Prop>()

        for (p in live) {
            p.age += dt
            // Planted props are not stepped at all -- no gravity, no contact, no borders.
            // Aging them is still worth doing: TRANSIENT_LIFE is measured in age for the
            // bullets, and a nail has no business being the one prop that never gets older.
            if (p.planted) continue

            if (!p.held) {
                if (!p.onFloor) {
                    p.velocity = p.velocity + Vec2(0f, gravity * p.spec.gravityScale * dt)
                }
                p.position = p.position + p.velocity * dt
                p.rotation += p.spin * dt
                p.spin *= max(0f, 1f - 2f * dt)
            }

            collide(p, skeleton, radiusOf, hits, onImpulse)
            // Nodes after the bones, so a prop that is inside both is pushed out of the limb
            // first and only then out of the point that names a spot on it.
            for (n in nodes) nodeCollide(p, n, hits, onImpulse)
            borders(p)

            if (p.velocity.length() < 40f) p.still += dt else p.still = 0f
            val spent = p.spec.transient && (p.age > TRANSIENT_LIFE || p.still > 1.5f)
            if (spent) {
                gone.add(p)
                for (key in lastHit.keys.toList()) {
                    if (key.startsWith(p.serial.toString() + "|")) lastHit.remove(key)
                }
            }
        }
        live.removeAll(gone)
        // Props against each other LAST, so that what a prop was pushed into by its neighbour
        // is not something the bones and the floor have already had their say about.
        separate()
        return hits
    }

    /**
     * Prop against prop. A prop is the circle its drawing is, so this is circle against circle.
     *
     * The overlap is split EVENLY, and that is a decision rather than a default. A prop's weight
     * is deliberately not in PropSpec — "how heavy it is ... is the rule set's business" — so an
     * even split is the only one the spec can justify. Reading a mass out of `force` would be
     * inventing a number the editor never wrote and no rule can reach; a hammer and a cushion
     * are the same prop with different rules, and that has to stay true here too.
     *
     * It is also what Fluid already does when two drops overlap (`(reach - dist) * 0.5f`), and
     * for the same reason: two things of the same kind have no way to tell each other apart.
     *
     * A held prop does not give. The finger owns its position, for the same reason a held prop
     * is allowed to be pressed INTO the character instead of being shoved off it: a hand is not
     * something the world pushes back. The other prop therefore takes the whole separation,
     * which is what lets a held hammer knock a thrown one out of the air.
     */
    private fun separate() {
        // A set, not a list: one prop can be in three collisions in the same frame, and the
        // borders must still run for it ONCE. Twice would be twice the resting friction.
        val moved = mutableSetOf<Prop>()
        for (i in live.indices) {
            val a = live[i]
            for (j in i + 1 until live.size) {
                val b = live[j]
                val gap = a.spec.radius + b.spec.radius
                val offset = b.position - a.position
                val distance = offset.length()
                if (distance >= gap) continue
                val normal = if (distance < 1e-3f) Vec2(0f, -1f) else offset / distance
                val overlap = gap - distance

                // Two fingers pressing two props together: neither one is the one that gives.
                if (a.held && b.held) continue
                // A planted prop does not give either, and for the same reason a held one
                // does not: something else is holding it in place. A nail driven through
                // the table is exactly as immovable as a thumb pressing it there.
                if (a.planted && b.planted) continue
                val shareA = if (a.held || a.planted) 0f else if (b.held || b.planted) 1f else 0.5f
                if (shareA > 0f) {
                    a.position = a.position - normal * (overlap * shareA)
                    moved.add(a)
                }
                if (shareA < 1f) {
                    b.position = b.position + normal * (overlap * (1f - shareA))
                    moved.add(b)
                }

                // Only a CLOSING pair bounces. Two props resting against each other are not
                // closing, and if they were kicked apart every frame they would buzz -- the
                // same mistake the floor pass makes when it turns a resting bone twice.
                val closing = (b.velocity.x - a.velocity.x) * normal.x +
                    (b.velocity.y - a.velocity.y) * normal.y
                if (closing >= 0f) continue
                val kick = (1f + RESTITUTION) * closing
                if (shareA > 0f) {
                    a.velocity = a.velocity + normal * (kick * shareA)
                }
                if (shareA < 1f) {
                    b.velocity = b.velocity - normal * (kick * (1f - shareA))
                }
            }
        }

        // A prop pushed aside can be pushed into the floor or a wall, so the borders get the
        // last word — but ONLY for the ones that actually moved. borders() also applies the
        // resting friction, so running it a second time for a prop that never moved would
        // charge that friction twice in one frame.
        for (p in moved) borders(p)
    }

    /** The floor and the arena walls. A prop that has come to rest reports it. */
    private fun borders(p: Prop) {
        val r = p.spec.radius
        if (p.position.y + r >= floorY) {
            p.position = Vec2(p.position.x, floorY - r)
            if (p.velocity.y > 0f) p.velocity = Vec2(p.velocity.x, -p.velocity.y * RESTITUTION)
            p.velocity = Vec2(p.velocity.x * (1f - 4f * 0.016f), p.velocity.y)
            if (abs(p.velocity.y) < 30f) p.velocity = Vec2(p.velocity.x, 0f)
            p.spin *= 0.7f
            p.onFloor = true
        } else {
            p.onFloor = false
        }
        if (p.position.x - r < 0f) {
            p.position = Vec2(r, p.position.y)
            p.velocity = Vec2(abs(p.velocity.x) * RESTITUTION, p.velocity.y)
        } else if (p.position.x + r > worldWidth) {
            p.position = Vec2(worldWidth - r, p.position.y)
            p.velocity = Vec2(-abs(p.velocity.x) * RESTITUTION, p.velocity.y)
        }
    }

    /**
     * Prop against every bone. A bone is treated as a capsule of its own thickness, which
     * is the same shape the floor contact already uses.
     */
    private fun collide(
        p: Prop,
        skeleton: Skeleton,
        radiusOf: (Bone) -> Float,
        hits: MutableList<Hit>,
        onImpulse: (Bone, Vec2, Float) -> Unit,
    ) {
        var speed = p.velocity.length()
        for (bone in skeleton.bones) {
            // A radius of zero means "this part is not there as far as the world is concerned",
            // which is what the rig's 碰撞 switch is for. It has to be a SKIP and not a zero
            // radius: a zero-radius bone is an infinitely thin line, and a prop is still shoved
            // off a line -- so "碰撞 off" used to leave the part bumping props with an invisible
            // wire. The rig always derives a real radius (a file saying 0 means "work one out"),
            // so nothing else can arrive here as zero.
            if (radiusOf(bone) <= 0f) continue
            val head = bone.worldPosition
            val tip = bone.tipPosition()
            val closest = closestOnSegment(p.position, head, tip)
            val gap = p.spec.radius + radiusOf(bone)
            val offset = p.position - closest
            val distance = offset.length()
            if (distance >= gap) continue

            val normal = if (distance < 1e-3f) Vec2(0f, -1f) else offset / distance
            // Push out and bounce, but never while a finger is holding it: a held prop has
            // to be able to be pressed against the character, not shoved off it.
            if (!p.held) {
                p.position = closest + normal * gap
                val into = p.velocity.x * normal.x + p.velocity.y * normal.y
                if (into < 0f) {
                    p.velocity = p.velocity - normal * (into * (1f + RESTITUTION))
                }
                if (speed > IMPULSE_FLOOR) {
                    onImpulse(bone, normal * -1f, speed * p.spec.force * IMPULSE_GAIN)
                }
            }

            // One contact event per bone per quarter second. Contact is continuous — a prop
            // resting on the character touches it every frame — and the rules would
            // otherwise be handed the same event sixty times a second.
            val key = p.serial.toString() + "|" + bone.name
            val last = lastHit[key]
            if (last == null || clock - last >= CONTACT_PERIOD) {
                lastHit[key] = clock
                hits.add(Hit(p, bone, max(speed, MIN_HIT) * p.spec.force))
            }
            speed = 0f
        }
    }

    /**
     * A prop against one named point.
     *
     * The same overlap, the same restitution, the same contact throttle and the same impulse
     * as a bone -- deliberately, and it is not a copy: it is the same code with a circle whose
     * centre happens to come from the rig instead of from a capsule. A node that felt different
     * from the limb it sits on would be a second kind of contact to explain.
     */
    private fun nodeCollide(
        p: Prop,
        node: NodePoint,
        hits: MutableList<Hit>,
        onImpulse: (Bone, Vec2, Float) -> Unit,
    ) {
        if (node.radius <= 0f) return
        val speed = p.velocity.length()
        val dx = p.position.x - node.at.x
        val dy = p.position.y - node.at.y
        val distance = hypot(dx, dy)
        val gap = p.spec.radius + node.radius
        if (distance >= gap) return
        val normal = if (distance < 1e-3f) Vec2(0f, -1f) else Vec2(dx / distance, dy / distance)
        if (!p.held) {
            p.position = Vec2(node.at.x + normal.x * gap, node.at.y + normal.y * gap)
            val into = p.velocity.x * normal.x + p.velocity.y * normal.y
            if (into < 0f) {
                p.velocity = p.velocity - normal * (into * (1f + RESTITUTION))
            }
            if (speed > IMPULSE_FLOOR) {
                onImpulse(node.bone, normal * -1f, speed * p.spec.force * IMPULSE_GAIN)
            }
        }
        val key = p.serial.toString() + "|" + node.name
        val last = lastHit[key]
        if (last == null || clock - last >= CONTACT_PERIOD) {
            lastHit[key] = clock
            hits.add(Hit(p, node.bone, max(speed, MIN_HIT) * p.spec.force, node.name))
        }
    }

    private fun closestOnSegment(p: Vec2, a: Vec2, b: Vec2): Vec2 {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val len2 = abx * abx + aby * aby
        if (len2 < 1e-6f) return a
        val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / len2).coerceIn(0f, 1f)
        return Vec2(a.x + abx * t, a.y + aby * t)
    }

    companion object {
        const val MAX_PROPS = 40
        private const val RESTITUTION = 0.28f
        private const val GRAB_SLACK = 40f
        /** Below this closing speed a touch is a nudge, not a hit. */
        private const val IMPULSE_FLOOR = 200f
        /** px/s of shove per unit of "how hard", before the prop's own force. */
        private const val IMPULSE_GAIN = 0.22f
        private const val MIN_HIT = 220f
        private const val CONTACT_PERIOD = 0.25f
        private const val TRANSIENT_LIFE = 8f
    }
}
