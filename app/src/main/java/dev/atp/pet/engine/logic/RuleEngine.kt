package dev.atp.pet.engine.logic

import dev.atp.pet.engine.event.EventType
import dev.atp.pet.engine.event.GameEvent
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.state.StatSet
import kotlin.math.abs
import kotlin.random.Random

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
class RuleEngine(val spec: LogicSpec, seed: Long = 20260915L) {
    /**
     * 只有世界能回答的问题。
     *
     * The second kind of detector the owner asked for: 「手是否比肩膀高」「有没有被绳子连着」.
     * Neither is a number the character carries and neither is a state somebody declared -- they
     * are facts about where the parts ARE and what they are tied to, and the rule engine has no
     * business knowing how a skeleton is stored. So the world hands in an object that answers
     * exactly these questions, and the ENGINE keeps the comparison (which way round, how many
     * pixels of margin): that way "手比肩膀高 30px" is a rule, and a phone with no world attached
     * -- the mirror tests, the editor -- can still be asked what it would say.
     */
    interface Facts {
        /** Where a part is, in world pixels, or null when there is no such part right now. */
        fun at(bone: String): Vec2?

        /** Is anything tied to this part by a rope? An empty [bone] asks about the whole body. */
        fun tied(bone: String): Boolean

        /**
         * Is a switch this engine does NOT own on? Null when the world does not know it either.
         *
         * The switches of a character are one namespace with addresses: a global state is its
         * own name, a part's own state is `bone:state` (see Subjects.stateTag). Each engine
         * keeps the ones it DECLARES and asks the world about every other name -- which is what
         * lets a rule on the hand test the character's 穿着, and a rule on the character test
         * 手在出汗. Nothing here knows what a bone is; the world does the routing.
         *
         * The default is null (nobody knows) rather than false, so the world -- the only side
         * that can tell -- can say "no such switch" instead of "off". A CONDITION reads both as
         * false, for the same reason an unknown condition kind does: a file from a newer version
         * must not stop the rest of it. A write says so out loud; see [setSwitch].
         */
        fun switchOn(tag: String): Boolean? = null

        /**
         * Flip a switch this engine does not own. True when the world took it.
         *
         * The write half of [switchOn], and deliberately not "create it if it is missing": a
         * switch nobody declared is one no artwork and no other rule can see, so a rule that
         * sets one is asking for something that does not exist, and the answer is false.
         */
        fun setSwitch(tag: String, on: Boolean): Boolean = false
    }


    val stats = StatSet(spec.stats)

    /**
     * The dice.
     *
     * Seeded, and given by the caller, for two reasons: a test can then say what it expects
     * (see tools/logic_check.py, which mirrors this and rolls four hundred times), and the
     * bench can hand a fresh seed per load so that a character does not do the same thing
     * every single time it is summoned. One generator per engine, so a prop's rules and the
     * character's rules do not take each other's rolls.
     */
    private val random = Random(seed)

    /**
     * Every switch the character owns, by id. Read by the artwork (a part that only exists
     * while its state is on) and by the rules; written only by the rules.
     */
    val states = LinkedHashMap<String, Boolean>()

    init {
        for (s in spec.states) states[s.id] = s.initial
    }

    /**
     * Who answers the questions this engine cannot: see [Facts]. Null means "nobody has
     * attached a world", and every fact condition then reads as false -- the same answer an
     * unknown condition kind gets, and for the same reason: a rule from a newer version of the
     * app must not stop the rest of the file.
     */
    var facts: Facts? = null

    fun stateOn(id: String): Boolean = states[id] == true

    /**
     * Is this switch on? Mine, or somebody else's through the world.
     *
     * "Mine" is the states this file DECLARES -- the map is only ever seeded from [spec] and a
     * foreign name is never written into it -- so a part's rule that names 穿着 gets the
     * character's answer rather than a copy of it that could drift.
     */
    private fun switchOf(name: String): Boolean {
        if (name.isEmpty()) return false
        states[name]?.let { return it }
        return facts?.switchOn(name) ?: false
    }

    /**
     * Flip one switch: mine directly, somebody else's through the world.
     *
     * A name the world does not recognise writes NOTHING and says so in the log. Before this,
     * `states[name] = on` created the key whether or not anybody had declared it, so a part's
     * rule that set 穿着 turned on a switch that existed only inside that part's own map, that
     * no layer and no other rule could see -- a silent no-op with a success-looking rule.
     */
    private fun setSwitch(name: String, on: Boolean) {
        if (name.isEmpty()) return
        if (states.containsKey(name)) {
            states[name] = on
            return
        }
        if (facts?.setSwitch(name, on) != true) log("    没有「" + name + "」这个状态，没有改")
    }

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

    /**
     * A line from the WORLD about an action it was handed.
     *
     * The engine logs what it decided; only the bench knows whether the prop existed, whether
     * the pose name meant anything, whether that body could be swapped in. Those failures used
     * to be silent -- the rule fired, the action was handed over, and nothing happened -- and
     * the log is exactly where somebody looks when a rule "does not work".
     */
    fun note(text: String) {
        log("    " + text)
    }

    fun reset() {
        stats.reset()
        for (s in spec.states) states[s.id] = s.initial
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
            // Each due continuation is a resolution of its own, so it gets its own `ran`: the
            // at-most-once guard is per event, not per lifetime, and 一次 is what lifetime means.
            for (p in due) out.addAll(follow(p.actions, mutableSetOf()))
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
        // The rules that have FIRED during this one event. See fire() for why it is filled in
        // there and not when a rule is considered.
        val ran = mutableSetOf<Int>()
        // 分支 with a detector of their own, once each per event: they are not rules, so `ran`
        // does not cover them, and two listeners for one event would otherwise fire twice.
        val branchRan = mutableSetOf<Pair<Int, Int>>()
        for ((index, rule) in spec.rules.withIndex()) {
            // The rule's own 当, and the branches that hang off it, are fired inside fire().
            //
            // The branches that carry a detector of their own are checked in the SAME pass but
            // OUTSIDE those filters, and that is the whole point of them: they are waiting for
            // their own event, so a rule whose 当 is something else entirely must not be what
            // decides whether they are looked at.
            val heard = rule.on == event.type.id &&
                event.touches(rule.part) &&
                // WHICH prop or particle. Two rules can otherwise be identical on the screen
                // and behave differently for reasons nobody can see. See RuleSpec.about.
                event.aboutIs(rule.about)
            if (heard) out.addAll(fire(index, rule, ran))
            for ((bi, branch) in rule.branches.withIndex()) {
                if (!branch.ownDetector) continue
                if (branch.on != event.type.id) continue
                if (!event.touches(branch.part)) continue
                if (!event.aboutIs(branch.about)) continue
                if (!branchRan.add(index to bi)) continue
                out.addAll(branchActions(index, rule, bi))
            }
        }
        return out
    }

    /**
     * Fire one rule and follow wherever its actions point.
     *
     * A rule that arrives here from a 跳到规则 gets the same treatment as one the event matched,
     * with one exception: **当 and 部位 are not checked**. That is the whole point of a jump —
     * if the target still had to be triggered by the same event, jumping to it would be a way of
     * writing the rule twice rather than a way of reaching it.
     *
     * Everything else still applies, because the target really is firing: its own 如果 decides
     * between 就 and 否则, and its own 冷却 and 一次 are consumed. A rule that has already had its
     * 一次 is not brought back to life by being jumped to.
     */
    private fun fire(index: Int, rule: RuleSpec, ran: MutableSet<Int>): List<ActionSpec> {
        // At most once per event, per rule. This is what makes a loop impossible rather than
        // merely unlikely: A → B → A stops at the second A, and any chain is bounded by the
        // number of rules in the file, so there is no hop counter to tune and none to forget.
        //
        // Marked when the rule FIRES, not when it is considered. A rule that was looked at and
        // skipped — conditions false, cooldown still running — has not had its turn, and it may
        // legitimately hold a moment later in the same event, because the rule that skipped it
        // can have moved a stat.
        if (index in ran) return emptyList()
        if (index in firedOnce) return emptyList()

        val holds = holds(rule)
        // A rule whose conditions fail and which has no else is skipped WITHOUT consuming its
        // cooldown, so it can fire the instant they become true. That is what every rule did
        // before there was an else, and it has to keep doing it.
        if (!holds && rule.elseActions.isEmpty()) return emptyList()

        val last = lastFired[index]
        if (rule.cooldown > 0f && last != null && clock - last < rule.cooldown) return emptyList()

        ran.add(index)
        lastFired[index] = clock
        if (rule.once) firedOnce.add(index)
        // 否则 is the path where the conditions did NOT hold, so there is nothing to fork:
        // a detector that did not fire cannot have branches hanging off it.
        if (!holds) {
            log("  规则 " + (index + 1) + " → 否则")
            return follow(rule.elseActions, ran)
        }
        if (rule.branches.isEmpty()) {
            log("  规则 " + (index + 1) + " →")
            return follow(rule.actions, ran)
        }
        // 并行: every executor forked off this one detector, in the order they were written.
        // The rule's own 就 is the first of them -- a group is a rule that grew more executors,
        // not a second kind of thing.
        log("  规则 " + (index + 1) + " → 并行 " + (rule.branches.size + 1) + " 支")
        val out = follow(rule.actions, ran).toMutableList()
        for ((bi, branch) in rule.branches.withIndex()) {
            // A branch with its own 当 is not driven by this one; it is waiting for its own
            // event and resolve() will hand it over when that arrives.
            if (branch.ownDetector) continue
            // 分支自己的如果: the group has already said yes -- its detector fired and its own
            // 如果 held -- so this is the question this executor asks about itself, and it can
            // only ever narrow what this one branch does. The other branches are unaffected:
            // 全部响 is about the fork, not about every executor doing the same thing.
            if (!holdsConditions(branch.conditions)) {
                log("  规则 " + (index + 1) + " 的分支 " + (bi + 2) + " → 如果不对，跳过")
                continue
            }
            out.addAll(follow(branch.actions, ran))
        }
        return out
    }

    /**
     * One 并行分支's executor, for a branch that carries its own detector.
     *
     * It is the rule's 冷却 and 一次 that apply, not the branch's own: a group is one thing that
     * happens, and two executors of it getting out of step with each other is not a feature
     * anybody asked for. The branch's own 如果 IS checked here -- that one is the branch's, so
     * it is asked before the group's cooldown is spent. 当 and 部位 are not: the branch said
     * WHEN, and the event has already matched it.
     */
    private fun branchActions(index: Int, rule: RuleSpec, branch: Int): List<ActionSpec> {
        // 分支自己的如果 comes first, and BEFORE the group's cooldown is spent: a branch that
        // was looked at and said no has not had its turn, exactly like a rule whose conditions
        // fail and which has no else. It can therefore fire the instant they become true.
        if (!holdsConditions(rule.branches[branch].conditions)) {
            log("  规则 " + (index + 1) + " 的分支 " + (branch + 2) + " → 如果不对，跳过")
            return emptyList()
        }
        val last = lastFired[index]
        if (rule.cooldown > 0f && last != null && clock - last < rule.cooldown) return emptyList()
        if (rule.once && index in firedOnce) return emptyList()
        lastFired[index] = clock
        if (rule.once) firedOnce.add(index)
        log("  规则 " + (index + 1) + " 的分支 " + (branch + 2) + " →")
        return follow(rule.branches[branch].actions, mutableSetOf(index))
    }

    /**
     * Run one action list and chase the jump at the end of it, if there is one.
     *
     * Recursion rather than a loop with a cursor, because what a jump does is exactly "fire that
     * rule now", and fire() is the only thing that knows how. The depth is the length of the
     * chain, which `ran` bounds by the number of rules.
     */
    private fun follow(actions: List<ActionSpec>, ran: MutableSet<Int>): List<ActionSpec> {
        val r = run(actions)
        val out = r.actions.toMutableList()
        if (r.jump <= 0) return out
        val target = r.jump - 1
        if (target !in spec.rules.indices) {
            // The editor cannot write this, but a hand-edited file can, and a numbered jump is
            // the one action whose mistake is silent: nothing happens and nothing says why.
            log("    没有规则 " + r.jump + "，这次跳转没有去处")
            return out
        }
        out.addAll(fire(target, spec.rules[target], ran))
        return out
    }

    /** Does this rule's own 如果 hold? The rule's clauses, and nothing else. */
    private fun holds(rule: RuleSpec): Boolean = holdsConditions(rule.conditions)

    /**
     * One 如果, judged.
     *
     * A rule's own conditions and a branch's conditions are the same thing asked in two
     * places, so there is one implementation of "does this list hold" and not two that can
     * drift apart. No clause at all is "always", not "never": that is what makes 当……就 a
     * rule somebody can write, and it is what a branch with no 如果 does too.
     */
    private fun holdsConditions(conditions: List<ConditionSpec>): Boolean {
        if (conditions.isEmpty()) return true

        // Left to right, 而且 binding tighter than 或者: a list of AND-groups, and the rule
        // runs if ANY of them holds. See Joins.
        var group = true
        var anyGroup = false
        for ((i, c) in conditions.withIndex()) {
            if (i > 0 && Joins.of(c.join) == Joins.OR) {
                if (group) anyGroup = true
                group = true
            }
            group = group && holdsOne(c)
        }
        return anyGroup || group
    }

    /**
     * One clause on its own.
     *
     * Two kinds, and they are the two things a character can be asked about: a number it
     * carries ("is pain over 80") and a fact about it ("is it wearing clothes"). An unknown
     * kind is false rather than fatal, so a rule from a newer version of the app cannot stop
     * the rest of the file from running.
     */
    private fun holdsOne(c: ConditionSpec): Boolean {
        if (c.kind == "chance") {
            // A percentage, rolled afresh every time the rule is considered: 0 is never, 100
            // is always, and the roll is per evaluation rather than per rule, so a chance
            // clause on 每隔一会儿 is one roll per interval and one on 被打到 is one per hit.
            val roll = random.nextFloat() * 100f
            val hit = roll < c.value
            if (hit) log("    🎲 " + trim(c.value) + "% 中了")
            return hit
        }
        if (c.kind == "state") {
            // 全局的还是这一节自己的，由"这个名字我宣没声明过"决定；不认识的问世界。
            val on = switchOf(c.state)
            val want = c.op != "off"
            return on == want
        }
        if (c.kind == "pose") {
            // 「手是否比肩膀高」: two parts, one axis, a margin. Screen coordinates: y grows
            // downwards, so "higher up" is a SMALLER y -- which is exactly the kind of sign
            // that is invisible in a still pose and backwards in every other one.
            val f = facts ?: return false
            val a = f.at(c.bone) ?: return false
            val b = f.at(c.other) ?: return false
            return when (c.axis) {
                "left" -> b.x - a.x >= c.value
                "right" -> a.x - b.x >= c.value
                else -> b.y - a.y >= c.value
            }
        }
        if (c.kind == "tied") {
            // 「有没有被绳子连着」. [op] is the state convention: "on" (the default) asks
            // whether it IS tied, "off" asks whether it is free.
            val f = facts ?: return false
            val on = f.tied(c.bone)
            return on == (c.op != "off")
        }
        if (c.kind != "stat") return false
        val v = stats.get(c.stat)
        return when (c.op) {
            ">" -> v > c.value
            ">=" -> v >= c.value
            "<" -> v < c.value
            "<=" -> v <= c.value
            "=" -> abs(v - c.value) < 0.001f
            else -> false
        }
    }

    /**
     * Carry out one rule's actions.
     *
     * Numbers are applied here and everything visible is returned. "wait" schedules the
     * rest of the list and ends the rule, which is what makes "hit it, then it falls over
     * a moment later" a thing somebody can write without a scripting language.
     */
    private fun run(actions: List<ActionSpec>): Ran {
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
                "random" -> {
                    // The two ends are sorted rather than trusted: a range typed backwards is
                    // still a range, and a range whose ends are equal is a fixed value. The
                    // number is drawn afresh every time the action runs, and the stat's own
                    // limits still apply -- StatSet is the one that clamps.
                    val lo = minOf(a.value, a.value2)
                    val hi = maxOf(a.value, a.value2)
                    stats.set(a.stat, lo + random.nextFloat() * (hi - lo))
                    log("    " + a.stat + " 随机 " + trim(lo) + ".." + trim(hi) +
                        " → " + trim(stats.get(a.stat)))
                }
                "stateOn", "stateOff", "stateToggle" -> {
                    if (a.state.isNotEmpty()) {
                        val before = switchOf(a.state)
                        val after = when (a.kind) {
                            "stateOn" -> true
                            "stateOff" -> false
                            else -> !before
                        }
                        setSwitch(a.state, after)
                        if (after != before) {
                            log("    状态 " + a.state + (if (after) " 打开" else " 关闭"))
                        }
                    }
                }
                "wait" -> {
                    val rest = actions.drop(i + 1)
                    if (rest.isNotEmpty()) pending.add(Pending(clock + a.value, rest))
                    return Ran(out, 0)
                }
                "goto" -> {
                    // Hand control over, and STOP here: the actions written after a jump do not
                    // run. 等一会儿 is the same early return, with the opposite intent -- it
                    // queues the rest for later, a jump throws the rest away. Worth a log line,
                    // because a list that quietly stops halfway is invisible until somebody
                    // counts, and the number of actions that did not run is the whole surprise.
                    val left = actions.size - i - 1
                    log(
                        "    跳到规则 " + a.rule +
                            (if (left > 0) "，后面 " + left + " 个动作不跑" else "")
                    )
                    return Ran(out, a.rule)
                }
                else -> out.add(a)
            }
        }
        return Ran(out, 0)
    }

    /** A number the way the log wants to read it: no trailing ".0" on whole numbers. */
    private fun trim(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)

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

/**
 * What running one action list produced.
 *
 * [actions] are the ones the world still has to perform. [jump] is the rule a 跳到规则 handed
 * control to, or 0 when control stays where it is.
 *
 * Two values rather than a List with a marker in it, because a jump is not an action the caller
 * can perform — it is a decision the engine has already made, and by the time the caller sees
 * this list it has already been carried out.
 */
private class Ran(val actions: List<ActionSpec>, val jump: Int)
