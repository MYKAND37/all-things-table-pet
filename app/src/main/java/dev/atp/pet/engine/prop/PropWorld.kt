package dev.atp.pet.engine.prop

import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.Skeleton
import kotlin.math.abs
import kotlin.math.max

/** One prop touching one bone, this frame. */
class Hit(val prop: Prop, val bone: Bone, val value: Float)

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

    fun remove(prop: Prop) {
        live.remove(prop)
    }

    /** The prop under a finger, or null. Nearest centre wins, so small things stay pickable. */
    fun grabAt(point: Vec2): Prop? {
        var best: Prop? = null
        var bestDistance = GRAB_SLACK
        for (p in live) {
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
    ): List<Hit> {
        clock += dt
        val hits = mutableListOf<Hit>()
        val gone = mutableListOf<Prop>()

        for (p in live) {
            p.age += dt

            if (!p.held) {
                if (!p.onFloor) {
                    p.velocity = p.velocity + Vec2(0f, gravity * p.spec.gravityScale * dt)
                }
                p.position = p.position + p.velocity * dt
                p.rotation += p.spin * dt
                p.spin *= max(0f, 1f - 2f * dt)
            }

            collide(p, skeleton, radiusOf, hits, onImpulse)
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
        return hits
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
