package dev.atp.pet.engine.logic

import dev.atp.pet.engine.event.EventType
import dev.atp.pet.engine.event.GameEvent
import dev.atp.pet.engine.state.StatSet
import kotlin.math.abs

/**
 * Runs a character's rules.
 *
 * The split that makes this testable: the engine owns the numbers, and hands back only the
 * actions the world has to carry out. Nothing here knows what a speech bubble is, or a
 * bone, or a particle — so the whole of the logic layer runs in a test with no Android at
 * all, and the sandbox is the only thing that has to know how to draw any of it.
 *
 * See tools/logic_check.py, which mirrors this file and carries the cases that matter:
 * cooldowns, once-only rules, part matching, delayed actions, and the order rules run in.
 */
class RuleEngine(val spec: LogicSpec) {

    val stats = StatSet(spec.stats)

    /** Seconds since the character appeared. Rules and the log both read it. */
    var clock = 0f
        private set

    private val lines = ArrayDeque<String>()
    private val lastFired = HashMap<Int, Float>()
    private val firedOnce = HashSet<Int>()
    private val pending = mutableListOf<Pending>()
    private var tickAccum = 0f

    /** A rule that said "wait": the rest of its actions, and when they are due. */
    private class Pending(val due: Float, val actions: List<ActionSpec>)

    /** Newest last, capped: this is a readout, not a database. */
    fun log(): List<String> = lines.toList()

    fun reset() {
        stats.reset()
        clock = 0f
        tickAccum = 0f
        lastFired.clear()
        firedOnce.clear()
        pending.clear()
        lines.clear()
    }

    /**
     * One event, and everything it sets off.
     *
     * Returns the actions the world has to perform. Stat changes are already applied: a
     * rule that reads a number sees the value the previous rule left, because the rules in
     * a file run in the order they are written.
     */
    fun handle(event: GameEvent): List<ActionSpec> = resolve(event.copy(at = clock))

    /** Time passing: delayed actions come due, and TICK is raised on its own schedule. */
    fun step(dt: Float): List<ActionSpec> {
        clock += dt
        val out = mutableListOf<ActionSpec>()

        val due = pending.filter { it.due <= clock }
        if (due.isNotEmpty()) {
            pending.removeAll(due)
            for (p in due) out.addAll(run(p.actions))
        }

        tickAccum += dt
        if (tickAccum >= TICK_SECONDS) {
            // Reset rather than subtract: a frame that took two seconds should raise one
            // tick, not four. Ticks are a heartbeat, not an accounting of elapsed time.
            tickAccum = 0f
            out.addAll(resolve(GameEvent(EventType.TICK, at = clock)))
        }
        return out
    }

    private fun resolve(event: GameEvent): List<ActionSpec> {
        // TICK is not worth a line: it fires twice a second and would bury everything else.
        if (event.type != EventType.TICK) log("· " + event.describe())
        val out = mutableListOf<ActionSpec>()
        for ((index, rule) in spec.rules.withIndex()) {
            if (rule.on != event.type.id) continue
            if (!event.touches(rule.part)) continue
            if (index in firedOnce) continue
            val last = lastFired[index]
            if (rule.cooldown > 0f && last != null && clock - last < rule.cooldown) continue
            if (!holds(rule)) continue

            lastFired[index] = clock
            if (rule.once) firedOnce.add(index)
            log("  规则 " + (index + 1) + " →")
            out.addAll(run(rule.actions))
        }
        return out
    }

    /** Every condition has to hold. An unknown kind is false, not fatal. */
    private fun holds(rule: RuleSpec): Boolean {
        for (c in rule.conditions) {
            if (c.kind != "stat") return false
            val v = stats.get(c.stat)
            val ok = when (c.op) {
                ">" -> v > c.value
                ">=" -> v >= c.value
                "<" -> v < c.value
                "<=" -> v <= c.value
                "=" -> abs(v - c.value) < 0.001f
                else -> false
            }
            if (!ok) return false
        }
        return true
    }

    /**
     * Carry out one rule's actions.
     *
     * Numbers are applied here and everything visible is returned. "wait" schedules the
     * rest of the list and ends the rule, which is what makes "hit it, then it falls over
     * a moment later" a thing somebody can write without a scripting language.
     */
    private fun run(actions: List<ActionSpec>): List<ActionSpec> {
        val out = mutableListOf<ActionSpec>()
        for ((i, a) in actions.withIndex()) {
            when (a.kind) {
                "add" -> {
                    val delta = stats.add(a.stat, a.value)
                    if (delta != 0f) log("    " + a.stat + " " + (if (delta > 0f) "+" else "") + delta.toInt())
                }
                "set" -> {
                    val delta = stats.set(a.stat, a.value)
                    if (delta != 0f) log("    " + a.stat + " = " + stats.get(a.stat).toInt())
                }
                "wait" -> {
                    val rest = actions.drop(i + 1)
                    if (rest.isNotEmpty()) pending.add(Pending(clock + a.value, rest))
                    return out
                }
                else -> out.add(a)
            }
        }
        return out
    }

    private fun log(text: String) {
        lines.addLast(text + "        " + "%.1f".format(clock) + "s")
        while (lines.size > MAX_LOG) lines.removeFirst()
    }

    companion object {
        /** How often the "每隔一会儿" event is raised. */
        const val TICK_SECONDS = 0.5f
        private const val MAX_LOG = 60
    }
}
