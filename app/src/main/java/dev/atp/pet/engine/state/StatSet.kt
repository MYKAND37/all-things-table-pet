package dev.atp.pet.engine.state

/**
 * A number the character carries: H = health, P = pain, or anything else the user invents.
 *
 * The engine deliberately knows nothing about what zero health means. A character dies
 * when its own rule says so, which is what "这两个逻辑在逻辑系统里可以由角色自定义" asks
 * for: the same number can mean death for one character and a second wind for another.
 */
data class StatSpec(
    val id: String,
    val name: String,
    val initial: Float,
    val min: Float,
    val max: Float,
)

class StatSet(val specs: List<StatSpec>) {

    private val value = HashMap<String, Float>()

    init {
        reset()
    }

    fun spec(id: String): StatSpec? = specs.firstOrNull { it.id == id }

    fun get(id: String): Float = value[id] ?: 0f

    /**
     * Change a stat and report what actually happened.
     *
     * Clamped, so the returned delta is not the delta that was asked for: a rule that
     * heals 50 when the character is at 90 heals 10, and a rule that reacts to "healed"
     * has to see 10 or it will fire forever.
     */
    fun add(id: String, delta: Float): Float {
        val s = spec(id) ?: return 0f
        val before = get(id)
        val after = (before + delta).coerceIn(s.min, s.max)
        value[id] = after
        return after - before
    }

    fun set(id: String, v: Float): Float = add(id, v - get(id))

    fun reset() {
        for (s in specs) value[s.id] = s.initial.coerceIn(s.min, s.max)
    }

    fun snapshot(): Map<String, Float> = LinkedHashMap(value)
}
