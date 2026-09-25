package dev.atp.pet.engine.particle

/**
 * A kind of particle: what it looks like, and what it does.
 *
 * Deliberately shaped like [dev.atp.pet.engine.fluid.LiquidSpec]: a liquid is a colour and a
 * viscosity, a particle is a colour and three switches. Both are declared by the character,
 * edited in their own screen, and named by a rule (喷液体 血 / 喷粒子 火花).
 *
 * [gravity] is the one the user asked for by name -- 粒子可以有用户决定是否受重力影响 -- and it
 * is a switch rather than a number because "does it fall" is the question; how fast a star
 * drifts downward is not something anybody has ever wanted to type. [stains] is the other
 * half of what a particle IS in this app: it lands, and the ones that should leave a mark
 * leave one.
 */
data class ParticleSpec(
    val id: String,
    val name: String,
    val colour: Int,
    /** A multiplier on the drawn size and on the mark it leaves. 1.0 is the app's own. */
    val size: Float = 1f,
    val gravity: Boolean = true,
    val stains: Boolean = false,
    /**
     * 画在角色的**后面**（true）还是**前面**（false，默认）。
     *
     * 「是显示在角色面前还是后面」：火花、血溅在前面才对，而灰尘、烟、影子一样的东西应该在
     * 角色后面 —— 那是"这一层空气在它后面"，不是"它身上粘了灰"。默认 false = 和以前一样
     * （粒子一直画在最上层），所以旧数据一个像素都不变。
     */
    val behind: Boolean = false,
)

/** The kinds a character can spray, and the six it starts with. */
object ParticleKinds {

    /**
     * The particles that shipped before there was a screen for them.
     *
     * The colours are the ones the sandbox has always used, and two of them float: stars and
     * hearts were drawn as a special case in the old code, which is exactly the special case
     * that becomes a switch once somebody can edit it.
     */
    val DEFAULTS = listOf(
        ParticleSpec("blood", "血", 0xFFC92A2A.toInt(), 1f, gravity = true, stains = true),
        ParticleSpec("sweat", "汗", 0xFF4C8DE0.toInt(), 1f, gravity = true, stains = true),
        ParticleSpec("spark", "火花", 0xFFF2A93B.toInt(), 1f, gravity = true, stains = false),
        ParticleSpec("dust", "灰尘", 0xFF9A8FA6.toInt(), 1f, gravity = true, stains = false),
        ParticleSpec("star", "星星", 0xFFE45CA8.toInt(), 1f, gravity = false, stains = false),
        ParticleSpec("heart", "爱心", 0xFFE2557B.toInt(), 1f, gravity = false, stains = false),
    )

    /**
     * The named kind, or the first one.
     *
     * An id nobody declares is not an error: a rule that says 喷粒子 血 still has to do
     * something after the character's blood has been renamed or deleted, and a puff of the
     * default particle is a better answer than nothing at all. Mirrors Liquids.of, on purpose.
     */
    fun of(id: String, all: List<ParticleSpec>): ParticleSpec =
        all.firstOrNull { it.id == id } ?: all.firstOrNull() ?: DEFAULTS.first()
}
