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
) {
    var prevX = x
    var prevY = y
    var pushX = 0f
    var pushY = 0f
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
    ) {
        val n = count.coerceIn(0, 200)
        for (i in 0 until n) {
            val angle = random.nextFloat() * TWO_PI
            val v = speed * (0.2f + random.nextFloat() * 0.8f)
            drops.add(
                Drop(
                    x = at.x + (random.nextFloat() - 0.5f) * 12f,
                    y = at.y + (random.nextFloat() - 0.5f) * 12f,
                    vx = cos(angle) * v,
                    vy = sin(angle) * v - 120f,
                    colour = colour,
                    radius = RADIUS,
                    viscosity = viscosity,
                    liquid = liquid,
                )
            )
        }
        while (drops.size > MAX_DROPS) drops.removeAt(0)
    }

    fun step(
        dt: Float,
        gravity: Float,
        skeleton: Skeleton?,
        radiusOf: (Bone) -> Float,
    ) {
        if (drops.isEmpty()) return
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
            borders(drop, d)
            if (skeleton != null) {
                for (bone in skeleton.bones) {
                    pushOut(drop, bone.worldPosition, bone.tipPosition(), radiusOf(bone))
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

    private fun borders(drop: Drop, dt: Float) {
        if (drop.y + drop.radius > floorY) {
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
