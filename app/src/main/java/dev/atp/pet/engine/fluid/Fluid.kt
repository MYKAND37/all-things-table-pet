package dev.atp.pet.engine.fluid

import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.Skeleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * A kind of liquid: what it looks like, and how much it holds together.
 *
 * [viscosity] is not simulated as a fluid property -- it is how strongly the drops pull
 * back together, which is the one number that separates water (runs, spreads thin) from
 * slime (beads up, moves as a lump).
 */
data class LiquidSpec(
    val id: String,
    val name: String,
    val colour: Int,
    val viscosity: Float,
    /**
     * Whether the character's body is solid to this liquid.
     *
     * On is what a liquid has always done: the drops run around the limbs and pile up on the
     * feet rather than through them. Off is for the other half of "what is this stuff" — acid
     * that eats through the pet, rain, a puddle that is only a drawing. The floor and the
     * walls still hold it either way; it is the BODY this switches off, because that is the
     * only thing the drops were ever pushed out of.
     */
    val collides: Boolean = true,
)

/** One blob of liquid. Drawn as a circle; only the crowding between them makes it fluid. */
class Drop(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val colour: Int,
    val radius: Float,
    val viscosity: Float,
    /**
     * Which liquid this is, by name.
     *
     * The colour is not enough. Two liquids can be the same colour, and a liquid with its
     * own rules — a puddle that spreads, an acid that eats — has to be findable in a crowd
     * of drops that all look alike.
     */
    val liquid: String = "",
    /** Whether the body pushes this drop around. Copied from the kind when it is spilled. */
    val collides: Boolean = true,
) {
    var prevX = x
    var prevY = y
    var pushX = 0f
    var pushY = 0f

    /**
     * Whether the floor has ever stopped it.
     *
     * This is what makes 落地 a thing that happens ONCE to a drop rather than a state it is
     * in. Every drop in a settled puddle is on the floor every single frame, so "it is on the
     * floor" would fire sixty times a second over a puddle that is not moving — and the two
     * cleverer tests both failed on measurement. A speed test: a drop on the second layer of
     * a puddle is squeezed down onto the floor at 300px/s and is not landing (a settled
     * 120-drop puddle reported eight of those in ten seconds). A height test: a drop sliding
     * off the top of the pile really has fallen, and it is still not new liquid arriving.
     *
     * "The first time the floor stops it" is the one that matches what a rule means by 落地 —
     * 这一滴东西到地上了 — and it cannot be noisy, because it cannot happen twice.
     */
    var landed = false
}

/**
 * Liquid as a crowd of drops that push each other apart.
 *
 * There is no grid of cells and no pressure solve. What makes it read as liquid is one
 * thing: gravity piles the drops up and the crowding flattens the pile into a puddle.
 * Everything else here exists to keep that from going wrong --
 *
 *   * the correction is accumulated and applied once per pass, so the result does not
 *     depend on which neighbour the grid happens to return first;
 *   * it is capped, because a spill drops a hundred drops within a few pixels of each
 *     other and the sum of a hundred corrections is a grenade;
 *   * and the velocity of a drop is read back off where it ENDED UP. Gravity adds 40px/s
 *     to a drop sitting on the floor every single step; the floor puts the position back
 *     and nothing puts the velocity back, so without this every drop in a settled puddle
 *     reports itself as moving at 300px/s while going nowhere.
 *
 * Mirrored in tools/fluid_check.py, which carries the numbers and the tests.
 */
class Fluid(private val floorY: Float, private val worldWidth: Float) {

    val drops = ArrayList<Drop>()

    private val random = Random(20260914)
    private val grid = HashMap<Long, MutableList<Drop>>()

    fun clear() {
        drops.clear()
    }

    /** Take one liquid off the bench, leaving every other one alone. */
    fun clearOf(liquid: String) {
        if (liquid.isEmpty()) {
            clear()
            return
        }
        drops.removeAll { it.liquid == liquid }
    }

    /**
     * Pour liquid out at a point.
     *
     * All of it goes in and the OLDEST drops are the ones that go. A wound that keeps
     * bleeding has to keep bleeding, and a spill that silently does nothing because the
     * pool is full is worse than one that pushes the old liquid out.
     */
    fun spill(
        colour: Int,
        at: Vec2,
        count: Int,
        viscosity: Float,
        speed: Float = 260f,
        liquid: String = "",
        collides: Boolean = true,
    ) {
        val n = count.coerceIn(0, 200)
        for (i in 0 until n) {
            val angle = random.nextFloat() * TWO_PI
            val v = speed * (0.2f + random.nextFloat() * 0.8f)
            add(
                colour, at.x + (random.nextFloat() - 0.5f) * 12f,
                at.y + (random.nextFloat() - 0.5f) * 12f,
                cos(angle) * v, sin(angle) * v - 120f,
                viscosity, liquid, collides,
            )
        }
        while (drops.size > MAX_DROPS) drops.removeAt(0)
    }

    /**
     * Pour: the same drops, in a COLUMN instead of a splash.
     *
     * A spill throws in every direction from one point, which is what a splash is. A stream
     * that does that is not a stream — it is a splash that takes longer, and at twenty drops
     * a second it reads as a puff hanging in the air. A column gives every drop the SAME
     * direction (down, by default) with only a few degrees of jitter, and spawns them along
     * the emitter rather than in a 12 px ball, so they travel together and land as one line.
     * Gravity then bends the line into the arc a poured liquid actually makes.
     *
     * [spread] is the half-angle of the jitter, in radians: 0.05 is about 3 degrees.
     */
    fun pour(
        colour: Int,
        at: Vec2,
        count: Int,
        viscosity: Float,
        speed: Float = 320f,
        liquid: String = "",
        collides: Boolean = true,
        spread: Float = COLUMN_SPREAD,
    ) {
        val n = count.coerceIn(0, 200)
        for (i in 0 until n) {
            val angle = HALF_PI + (random.nextFloat() - 0.5f) * 2f * spread
            val v = speed * (0.9f + random.nextFloat() * 0.2f)
            add(
                colour, at.x + (random.nextFloat() - 0.5f) * 2f,
                at.y + (random.nextFloat() - 0.5f) * 2f,
                cos(angle) * v, sin(angle) * v,
                viscosity, liquid, collides,
            )
        }
        while (drops.size > MAX_DROPS) drops.removeAt(0)
    }

    /** One drop, born where and how its caller asked. Shared by [spill] and [pour]. */
    private fun add(
        colour: Int, x: Float, y: Float, vx: Float, vy: Float,
        viscosity: Float, liquid: String, collides: Boolean,
    ) {
        drops.add(
            Drop(
                x = x, y = y, vx = vx, vy = vy,
                colour = colour, radius = RADIUS, viscosity = viscosity,
                liquid = liquid, collides = collides,
            )
        )
    }

    /**
     * Move every drop, and report which LIQUIDS had a drop touch down this frame.
     *
     * The return value is what makes a liquid a subject that can be written about: the bench
     * hands each id to that liquid's own engine as a 落地. A list rather than a callback for
     * the same reason Particles.step returns one — the caller has the clock, the event and
     * the engine, and this class has none of them — and a list rather than a set because how
     * many drops landed is the number a rule about "a splash" wants next.
     *
     * Empty ids are skipped: a drop spilled without a name (the tools do this) belongs to no
     * subject, and `liquid:` is not a subject anybody can write a rule on.
     */
    fun step(
        dt: Float,
        gravity: Float,
        skeleton: Skeleton?,
        radiusOf: (Bone) -> Float,
    ): List<String> {
        if (drops.isEmpty()) return emptyList()
        val landed = ArrayList<String>()
        val d = dt.coerceIn(0f, 0.05f)

        for (drop in drops) {
            drop.prevX = drop.x
            drop.prevY = drop.y
            drop.vy += gravity * d
            val drag = max(0f, 1f - AIR_DRAG * d)
            drop.vx *= drag
            drop.vy *= drag
            drop.x += drop.vx * d
            drop.y += drop.vy * d
        }

        // Crowding first: it is what turns a heap into a puddle.
        for (pass in 0 until PASSES) separate()

        for (drop in drops) {
            if (borders(drop, d) && drop.liquid.isNotEmpty()) landed.add(drop.liquid)
            // The body, unless this liquid was told to ignore it. The floor and the walls are
            // in borders() above and are not optional: "does not collide with the character"
            // is a statement about the pet, not about the world.
            if (skeleton != null && drop.collides) {
                for (bone in skeleton.bones) {
                    // Zero means "not there" -- see PropWorld.collide. The liquid has to skip it
                    // for the same reason a prop does: a zero-radius bone is a line, and a drop
                    // is still held off a line.
                    val r = radiusOf(bone)
                    if (r <= 0f) continue
                    pushOut(drop, bone.worldPosition, bone.tipPosition(), r)
                }
            }
        }

        // Velocity is where the drop ended up, not what it was pushed with.
        if (d > 1e-6f) {
            for (drop in drops) {
                drop.vx = (drop.x - drop.prevX) / d
                drop.vy = (drop.y - drop.prevY) / d
            }
        }
        return landed
    }

    /**
     * One relaxation pass: every pair contributes to a total displacement, applied at the
     * end, capped in size.
     */
    private fun separate() {
        grid.clear()
        for (drop in drops) {
            drop.pushX = 0f
            drop.pushY = 0f
            grid.getOrPut(cellOf(drop.x, drop.y)) { mutableListOf() }.add(drop)
        }

        for ((key, bucket) in grid) {
            val cx = (key shr 32).toInt()
            val cy = key.toInt()
            for (ox in -1..1) {
                for (oy in -1..1) {
                    val other = grid[cellKey(cx + ox, cy + oy)] ?: continue
                    for (drop in bucket) {
                        for (o in other) {
                            if (o === drop) continue
                            val dx = o.x - drop.x
                            val dy = o.y - drop.y
                            val dist = hypot(dx, dy)
                            if (dist < 1e-4f || dist >= SPACING * COHESION_RANGE) continue
                            val ux = dx / dist
                            val uy = dy / dist
                            val reach = SPACING * (1f + 0.9f * o.viscosity)
                            if (dist < reach) {
                                val move = (reach - dist) * 0.5f * STIFFNESS
                                drop.pushX -= ux * move
                                drop.pushY -= uy * move
                                o.pushX += ux * move
                                o.pushY += uy * move
                            } else {
                                // Apart, so pull them together -- with a force that goes to
                                // zero exactly at the resting distance, so a puddle settles
                                // at a spacing instead of collapsing to a point.
                                val move = (dist - reach) * 0.5f * cohesionOf(drop, o)
                                drop.pushX += ux * move
                                drop.pushY += uy * move
                                o.pushX -= ux * move
                                o.pushY -= uy * move
                            }
                        }
                    }
                }
            }
        }

        val limit = SPACING * MAX_CORRECTION
        for (drop in drops) {
            val m = hypot(drop.pushX, drop.pushY)
            if (m > limit) {
                drop.pushX = drop.pushX / m * limit
                drop.pushY = drop.pushY / m * limit
            }
            drop.x += drop.pushX
            drop.y += drop.pushY
        }
    }

    /** Sticky liquids hold together harder; the pair takes the weaker of the two. */
    private fun cohesionOf(a: Drop, b: Drop): Float =
        COHESION + COHESION * minOf(a.viscosity, b.viscosity)

    private fun cellOf(x: Float, y: Float): Long =
        cellKey((x / SPACING).toInt(), (y / SPACING).toInt())

    private fun cellKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    /**
     * Walls and floor. Returns true on the frame the floor FIRST stops this drop: its 落地.
     *
     * The floor stopping it means the clamp below, which is the one moment the liquid is
     * actually being held up — and [Drop.landed] makes it a latch, so a drop reports once in
     * its life no matter how many times the crowding pushes it back down afterwards. That
     * latch is the whole mechanism, and it replaced two that looked more physical and
     * measured worse; the numbers are on [Drop.landed].
     */
    private fun borders(drop: Drop, dt: Float): Boolean {
        var stopped = false
        if (drop.y + drop.radius > floorY) {
            stopped = !drop.landed
            drop.landed = true
            drop.y = floorY - drop.radius
            if (drop.vy > 0f) drop.vy = 0f
            drop.vx *= max(0f, 1f - FLOOR_FRICTION * dt)
        }
        if (drop.x - drop.radius < 0f) {
            drop.x = drop.radius
            drop.vx = abs(drop.vx) * 0.2f
        } else if (drop.x + drop.radius > worldWidth) {
            drop.x = worldWidth - drop.radius
            drop.vx = -abs(drop.vx) * 0.2f
        }
        return stopped
    }

    /** Liquid runs around a limb rather than through it. */
    private fun pushOut(drop: Drop, a: Vec2, b: Vec2, radius: Float) {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        val t = if (lenSq < 1e-6f) 0f else
            (((drop.x - a.x) * abx + (drop.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
        val cx = a.x + abx * t
        val cy = a.y + aby * t
        val dx = drop.x - cx
        val dy = drop.y - cy
        val dist = hypot(dx, dy)
        val gap = drop.radius + radius
        if (dist >= gap || dist < 1e-5f) return
        val ux = dx / dist
        val uy = dy / dist
        drop.x = cx + ux * gap
        drop.y = cy + uy * gap
        val into = drop.vx * ux + drop.vy * uy
        if (into < 0f) {
            drop.vx -= ux * into
            drop.vy -= uy * into
        }
    }

    companion object {
        /** Radius of one drop, and the distance drops want to keep. They overlap: that is
         *  what makes a puddle look like one body of liquid instead of a heap of balls. */
        const val RADIUS = 15f
        const val SPACING = 15f

        /** How hard they push back, and how hard they pull together once apart. */
        const val STIFFNESS = 0.45f
        const val COHESION = 0.10f
        const val COHESION_RANGE = 1.9f

        /** One pass may not move a drop further than this, in spacings. The safety catch. */
        const val MAX_CORRECTION = 0.5f

        const val PASSES = 2
        const val MAX_DROPS = 600
        const val FLOOR_FRICTION = 6f
        const val AIR_DRAG = 0.4f

        /** Half a turn: down the screen, which is where a poured liquid goes. */
        private const val HALF_PI = 1.5707964f

        /**
         * How wide a poured column is allowed to fan, as a half-angle in radians.
         *
         * 0.05 rad is about 3 degrees: wide enough that the jet is not a single file of drops
         * drawn on top of each other, narrow enough that it does not read as a spray. Mirrors
         * COLUMN_SPREAD in tools/fluid_check.py, where the landing spread is measured.
         */
        const val COLUMN_SPREAD = 0.05f
        private const val TWO_PI = 6.2831855f
    }
}

/** Parse "#RRGGBB" without dragging Android's Color into the engine. */
fun parseColour(text: String, fallback: Int): Int {
    val t = text.trim().removePrefix("#")
    if (t.length != 6 && t.length != 8) return fallback
    return try {
        val v = t.toLong(16)
        if (t.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    } catch (e: NumberFormatException) {
        fallback
    }
}

/** The liquids a character starts with, and the shape of the list the user can edit. */
object Liquids {
    val DEFAULTS = listOf(
        LiquidSpec("blood", "血", 0xFFB4212B.toInt(), 0.35f),
        LiquidSpec("water", "水", 0xFF3D8FD1.toInt(), 0.0f),
        LiquidSpec("slime", "史莱姆", 0xFF5FA83C.toInt(), 0.8f),
        LiquidSpec("ink", "墨", 0xFF23202E.toInt(), 0.15f),
    )

    fun of(id: String, all: List<LiquidSpec>): LiquidSpec =
        all.firstOrNull { it.id == id } ?: DEFAULTS.first()

    /** Every liquid with drops on the bench right now, in the order they first appeared. */
    fun present(drops: List<Drop>): List<String> {
        val out = LinkedHashSet<String>()
        for (d in drops) if (d.liquid.isNotEmpty()) out.add(d.liquid)
        return out.toList()
    }
}
