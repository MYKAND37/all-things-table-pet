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
    /**
     * How big one drop of this is, as a multiple of [Fluid.RADIUS].
     *
     * A droplet is a thing people have opinions about: blood beads, slime comes in lumps,
     * rain is a mist of small ones. At 1 it is exactly the drop the simulator has always
     * drawn, so every liquid written before this switch existed still looks the same.
     *
     * It is not a drawing trick. The radius is what the floor, the walls and the body hold a
     * drop off by, and the crowd sits further apart in proportion, so a liquid set to 2 is a
     * puddle of big blobs rather than the old puddle with fatter circles painted over it.
     */
    val size: Float = 1f,
    /**
     * How see-through the drops are: 1 is solid paint, 0.5 you can see the pet through.
     *
     * Multiplied into BOTH alphas the bench draws a drop with -- the soft halo and the body --
     * so it dims the whole blob instead of bleaching a bright rim onto it. Never taken all
     * the way to zero: a drop nobody can see is a drop somebody will swear is not there.
     */
    val opacity: Float = 1f,
    /**
     * 画在角色的**后面**（true，默认）还是**前面**（false）。
     *
     * 「液体显示在角色前面还是后面」：血溅在身上、水淋在毛上，应该盖住角色；而地上的水洼、
     * 雨丝、身后的黏液该在它后面。默认 true —— 这正是液体一直以来的样子（世界先画液体、
     * 再画角色），所以旧数据一个像素都不变。
     *
     * 注意它和粒子的默认值**相反**（粒子默认在前面）：各按各的老行为来，升级不会偷偷换样子。
     */
    val behind: Boolean = true,
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
    /** 画在角色后面还是前面。也是洒出来的那一刻从种类上抄下来的。 */
    val behind: Boolean = true,
    /**
     * How solid this one drop is drawn, 0..1. Copied from the kind when it is spilled.
     *
     * On the drop and not looked up from the kind at draw time for the same reason [colour]
     * is: a drop outlives the liquid it came from. Editing 血 to be see-through must not
     * retroactively make the puddle already on the bench transparent -- and it must not fail
     * to, either, which is why the editor spills nothing and the next 喷一把 is the one that
     * looks different.
     */
    val alpha: Float = 1f,
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
        size: Float = 1f,
        opacity: Float = 1f,
    ) {
        val n = count.coerceIn(0, 200)
        for (i in 0 until n) {
            val angle = random.nextFloat() * TWO_PI
            val v = speed * (0.2f + random.nextFloat() * 0.8f)
            add(
                colour, at.x + (random.nextFloat() - 0.5f) * 12f,
                at.y + (random.nextFloat() - 0.5f) * 12f,
                cos(angle) * v, sin(angle) * v - 120f,
                viscosity, liquid, collides, size, opacity,
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
        size: Float = 1f,
        opacity: Float = 1f,
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
                viscosity, liquid, collides, size, opacity,
            )
        }
        while (drops.size > MAX_DROPS) drops.removeAt(0)
    }

    /** One drop, born where and how its caller asked. Shared by [spill] and [pour]. */
    private fun add(
        colour: Int, x: Float, y: Float, vx: Float, vy: Float,
        viscosity: Float, liquid: String, collides: Boolean,
        size: Float = 1f, opacity: Float = 1f, behind: Boolean = true,
    ) {
        drops.add(
            Drop(
                x = x, y = y, vx = vx, vy = vy,
                colour = colour, radius = RADIUS * size.coerceIn(MIN_SIZE, MAX_SIZE),
                viscosity = viscosity, liquid = liquid, collides = collides,
                alpha = opacity.coerceIn(MIN_OPACITY, 1f),
                // 画在角色后面还是前面，洒出来的那一刻就从种类上抄下来（和 collides 一样）：
                // 一滴已经落在半空的液体，不该因为用户改了设置而突然换一层。
                behind = behind,
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
        val scale = crowdScale()
        for (pass in 0 until PASSES) separate(scale)

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
     * How much further apart this bench's drops want to sit, as a multiple of [SPACING].
     *
     * One number for the whole puddle, taken from the BIGGEST drop on it, and 1 whenever
     * everything on the bench is an ordinary drop -- which is what makes this invisible to
     * every liquid that does not touch the size switch. It has to be shared: the spacing is
     * what turns a heap into a puddle, and two drops that both want the same room cannot each
     * have their own answer to where the middle is.
     *
     * Both directions count. Bigger drops want more room or they draw on top of each other;
     * smaller ones want LESS, or a mist of tiny drops spreads into a film of separate beads
     * -- the spacing is what makes them merge into one body, and that is as true at half size
     * as it is at one. The floor of [MIN_SIZE] is the only limit, and [add] applies it.
     *
     * Sizing ONE liquid is exact: its drops keep their relative spacing and the puddle is the
     * same puddle, drawn and collided twice as wide. Sizing two liquids differently on one
     * bench is the approximation -- both crowds use the bigger spacing -- and the cost is paid
     * by the SMALL drops, which sit a little airier than they asked to. The alternative is a
     * grid per size class, which is a lot of machinery for a bench most people never mix.
     */
    private fun crowdScale(): Float {
        var widest = 0f
        for (drop in drops) if (drop.radius > widest) widest = drop.radius
        return if (widest <= 0f) 1f else widest / RADIUS
    }

    /**
     * One relaxation pass: every pair contributes to a total displacement, applied at the
     * end, capped in size.
     *
     * [scale] widens the spacing, the reach and the speed limit together, so a pass over big
     * drops is the same pass as over small ones, only further apart.
     */
    private fun separate(scale: Float) {
        val spacing = SPACING * scale
        grid.clear()
        for (drop in drops) {
            drop.pushX = 0f
            drop.pushY = 0f
            grid.getOrPut(cellOf(drop.x, drop.y, spacing)) { mutableListOf() }.add(drop)
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
                            if (dist < 1e-4f || dist >= spacing * COHESION_RANGE) continue
                            val ux = dx / dist
                            val uy = dy / dist
                            val reach = spacing * (1f + 0.9f * o.viscosity)
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

        val limit = spacing * MAX_CORRECTION
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

    private fun cellOf(x: Float, y: Float, spacing: Float): Long =
        cellKey((x / spacing).toInt(), (y / spacing).toInt())

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

        /**
         * How far the size switch goes: a drop a fifth of the base width, up to four times it.
         *
         * The top end is where a single drop is 60 px across -- a blob you can see the pet
         * behind, which is as big as "液滴" means anything. The bottom end is a mist fine
         * enough that a hundred drops read as one spray. Past either end the drops stop being
         * drops: below, the 12 px spawn ball is wider than the drop and a spill is one dot.
         */
        const val MIN_SIZE = 0.2f
        const val MAX_SIZE = 4f

        /**
         * The floor under the opacity switch.
         *
         * 0.05 still draws: a drop at a twentieth is a ghost, but it is a ghost somebody chose,
         * and 0 is a drop that is on the bench, that a rule can find, that the pet can be
         * standing in -- and that nobody can see. Refusing to go all the way to invisible is a
         * smaller surprise than a liquid that is there and cannot be found.
         */
        const val MIN_OPACITY = 0.05f
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
