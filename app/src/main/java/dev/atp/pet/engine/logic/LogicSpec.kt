package dev.atp.pet.engine.logic

import dev.atp.pet.engine.event.GameEvent
import dev.atp.pet.engine.fluid.LiquidSpec
import dev.atp.pet.engine.fluid.Liquids
import dev.atp.pet.engine.fluid.parseColour
import dev.atp.pet.engine.particle.ParticleKinds
import dev.atp.pet.engine.particle.ParticleSpec
import dev.atp.pet.engine.state.StatSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named switch the character owns: 穿衣服, 睡着了, 拿着刀…
 *
 * Not a number — an on/off fact about the character that its artwork and its rules can
 * both read. Layers tag themselves with one (a part that only exists while the state is
 * on), rules test one, and rules set one. That is the whole mechanism; what it means is
 * whatever the person writing the rules decides it means.
 */
data class StateSpec(val id: String, val name: String, val initial: Boolean)

/**
 * One clause of a rule's IF.
 *
 * [kind] is a string rather than an enum because this is a file format: a rule the user
 * wrote has to keep loading after a new kind of condition is invented. Unknown kinds read
 * as false instead of failing the whole file.
 */
data class ConditionSpec(
    val kind: String,
    val stat: String,
    val op: String,
    val value: Float,
    /** For kind = "state": which state, and [op] is "on" or "off". */
    val state: String = "",
    /**
     * How this clause joins the one BEFORE it: [Joins.AND] or [Joins.OR].
     *
     * A clause is a module, and the connector is the thing that was missing: with only one
     * of them the IF could be a single clause and there was nothing to assemble. The first
     * clause's join is never read — there is nothing before it to join to — and is kept only
     * so the list stays a uniform shape.
     */
    val join: String = Joins.AND,
)

/**
 * Who a rule set belongs to.
 *
 * A character is not the only thing that can have logic. A prop can — a candle that burns
 * down, a ball that bounces differently once it has been dropped — and so can a liquid. The
 * rules are the same rules; what changes is what the events are ABOUT and what the actions
 * act on, which is exactly the fields already in [dev.atp.pet.engine.event.GameEvent] and
 * [ActionSpec] read from the other end.
 *
 * Subjects are strings rather than a sealed class because every one of them is written into
 * a file, and a file that names a prop that has since been deleted has to load as "nothing
 * to run" rather than as an exception.
 */
object Subjects {
    /** The character itself. Every character has one, and it is the default everywhere. */
    const val PET = "pet"

    const val PROP_PREFIX = "prop:"
    const val LIQUID_PREFIX = "liquid:"

    /**
     * A PART of the character: one bone, with rules of its own.
     *
     * This is what makes 让手流汗 a rule that lives ON the hand rather than a rule on the pet
     * that keeps asking whether the part is a hand. The bone is named exactly as the rig names
     * it, so `part:hand_L` is a subject and `hand_L` is still the bone.
     */
    const val PART_PREFIX = "part:"

    /**
     * How a PART's own state is tagged on a drawing: `hand_L:sweat`, and `!hand_L:sweat` for
     * the layer that draws while it is off. A global state is its plain name.
     *
     * This is what lets the two levels share a name. A hand that sweats and a character that
     * sweats are two different switches, and the renderer holds one map of them — so the map's
     * key has to say which one it is, and the layer's tag says it in the file as well. Written
     * by CharacterStore.addVariant, read by LayerSpec.visible (which needs no change at all:
     * the tag IS the key).
     */
    const val STATE_SEPARATOR = ":"

    fun stateTag(bone: String, state: String): String = bone + STATE_SEPARATOR + state

    /** Which bone a layer's state tag belongs to, or "" for a global state. */
    fun tagBone(tag: String): String {
        val bare = tag.removePrefix("!")
        return if (bare.contains(STATE_SEPARATOR)) bare.substringBefore(STATE_SEPARATOR) else ""
    }

    /** The state id inside a tag, whichever level it is. "!" is not part of the id. */
    fun tagState(tag: String): String {
        val bare = tag.removePrefix("!")
        return if (bare.contains(STATE_SEPARATOR)) bare.substringAfter(STATE_SEPARATOR) else bare
    }

    fun prop(id: String): String = PROP_PREFIX + id

    fun liquid(id: String): String = LIQUID_PREFIX + id

    fun part(bone: String): String = PART_PREFIX + bone

    fun isProp(subject: String): Boolean = subject.startsWith(PROP_PREFIX)

    fun isLiquid(subject: String): Boolean = subject.startsWith(LIQUID_PREFIX)

    fun isPart(subject: String): Boolean = subject.startsWith(PART_PREFIX)

    /** The prop or liquid id inside a subject, or "" for the character. */
    fun objectId(subject: String): String =
        if (isProp(subject) || isLiquid(subject)) subject.substringAfter(':') else ""

    /** Which prop this subject is about, or "" if it is not about one. */
    fun propId(subject: String): String = if (isProp(subject)) objectId(subject) else ""

    fun liquidId(subject: String): String = if (isLiquid(subject)) objectId(subject) else ""

    /** Which bone this subject is, or "" if it is not a part. */
    fun partId(subject: String): String =
        if (isPart(subject)) subject.substringAfter(':') else ""

    /**
     * Does this subject's engine hear this event?
     *
     * Three rules, and they are different on purpose:
     *
     *  * the character hears EVERYTHING — it is the whole body, and half the rules in the
     *    shipped file are about the figure as a whole;
     *  * a part hears what happened to that part, and what happened to the whole body: a hand
     *    is part of a thrown pet, so 被甩出去 has to reach it;
     *  * a prop hears what happened to IT — the event names its id — and nothing else.
     *
     * The split that makes this decidable: the events that come down the character's own
     * [path][PET] are events ABOUT THE FIGURE (it was thrown, it landed, it was clicked, a prop
     * hit its hand). An event about a prop — that it landed, that it hit something — is
     * delivered to that prop BY NAME and never goes this way, which is why a part cannot
     * mistake another thing's landing for its own.
     *
     * Mirrored in tools/logic_check.py, which also reads the three prefixes back out of this
     * file to make sure the two agree about a file format.
     */
    fun hears(subject: String, event: GameEvent): Boolean = when {
        subject == PET -> true
        isPart(subject) -> {
            val bone = partId(subject)
            // Empty means "anywhere" — the same reading a rule gives it, via GameEvent.touches.
            event.part.isEmpty() || event.touches(bone)
        }
        isProp(subject) -> event.prop.isNotEmpty() && event.prop == propId(subject)
        else -> false
    }
}

/**
 * The two ways a clause can be joined to the one before it.
 *
 * The reading order is left to right with 而且 binding tighter, which is what anybody who
 * has written "a and b or c" already expects: it is a list of AND-groups, and the rule runs
 * if ANY group holds. "如果 痛苦 > 80 而且 状态 机械臂 或者 生命 < 20" is therefore
 * (痛苦>80 而且 机械臂) 或者 (生命<20).
 */
object Joins {
    const val AND = "and"
    const val OR = "or"

    fun of(id: String): String = if (id == OR) OR else AND

    fun label(id: String): String = if (of(id) == OR) "或者" else "而且"

    /** The other one. Tapping a connector flips it, so it only ever needs the complement. */
    fun flip(id: String): String = if (of(id) == OR) AND else OR
}

/**
 * One thing a rule does.
 *
 * Everything a rule can reach is in here, unused fields included, so adding an action kind
 * never changes the file format — only what the editor offers and what the engine does.
 */
data class ActionSpec(
    val kind: String,
    val text: String = "",
    val stat: String = "",
    val value: Float = 0f,
    /**
     * The other end of a range, for the actions that have one.
     *
     * Only 数值随机 uses it so far, and it is a field rather than a second meaning for
     * [value] because a range whose ends are read out of one number and one duration is a
     * range nobody can explain in a file. The two ends are SORTED when they are used, so a
     * range typed backwards is still a range.
     */
    val value2: Float = 0f,
    val bone: String = "",
    val prop: String = "",
    /** For the three state actions: which state to turn on, off, or over. */
    val state: String = "",
    /**
     * For 跳到规则: WHICH rule, as the number the editor and the log both show — 1 is the
     * first rule in the file, and 0 means "no rule", which is what every other action has.
     *
     * A field of its own rather than [value], for the same reason [value2] is one: a rule
     * number read out of the same number as a duration is a rule number nobody can explain
     * in a file. It is also the only 1-based thing in the engine, and it is 1-based because
     * it is the only one a PERSON reads — off the screen, out of the log — rather than code.
     */
    val rule: Int = 0,
)

/**
 * WHEN [on] happens to [part], IF every [conditions] holds, THEN do [actions].
 *
 * [cooldown] is what keeps a rule that fires on a continuous event — "被打到" while a prop
 * is resting on the character — from firing sixty times a second. [once] is for the things
 * that should happen exactly once in a life, like dying.
 */
data class RuleSpec(
    val on: String,
    val part: String,
    val conditions: List<ConditionSpec>,
    val actions: List<ActionSpec>,
    val cooldown: Float,
    val once: Boolean,
    /**
     * What to do when the conditions do NOT hold.
     *
     * Empty means "do nothing", which is what every rule did before there was an else, and
     * it keeps the old behaviour exactly: a rule whose conditions fail without an else is
     * skipped entirely, cooldown included, so it can fire the instant they become true.
     * With an else the rule fires either way, and the cooldown applies to whichever branch
     * ran -- otherwise "if hurt, whimper, else stay quiet" would whimper on every tick.
     */
    val elseActions: List<ActionSpec> = emptyList(),
)

/** What a rule can do, with the parameter the editor has to ask for. */
enum class ActionKind(val id: String, val label: String, val needs: String) {
    SAY("say", "说一句话", "text"),

    /**
     * A number from a range, drawn fresh every time the rule runs.
     *
     * The generator the logic system did not have: with this and the 概率 condition, "one
     * time in five it flinches" and "the bruise is somewhere between a little and a lot" are
     * rules rather than code. The dice are seeded per engine so that a test can say what it
     * expects -- see RuleEngine.
     */
    RANDOM("random", "数值随机", "statRange"),
    ADD("add", "数值增加", "stat"),
    SET("set", "数值设为", "statValue"),
    POSE("pose", "摆动作", "pose"),
    CLEAR_POSE("clearPose", "松开动作", ""),
    SPAWN("spawn", "生成道具", "prop"),
    BURST("burst", "喷粒子", "burst"),
    IMPULSE("impulse", "推一下", "boneValue"),
    BREAK("break", "打坏部位", "bone"),
    WAIT("wait", "等一会儿", "seconds"),
    STATE_ON("stateOn", "打开状态", "state"),
    STATE_OFF("stateOff", "关闭状态", "state"),
    STATE_TOGGLE("stateToggle", "切换状态", "state"),
    SPILL("spill", "喷液体", "liquid"),

    /**
     * Raise a signal for the character's own rules to hear.
     *
     * The module that makes 就 a logic module: what comes after 就 can be another rule's
     * 当, so a long chain of behaviour is written as several small rules that name their
     * signals rather than as one action list that has to be read in order to be understood.
     * Not visible by itself — that is the point.
     */
    EMIT("emit", "发一个信号", "text"),

    /**
     * Shove a prop, by name.
     *
     * The first action that names something other than the character as the thing it acts
     * ON. That is the other half of "these things can be objects": a prop having its own
     * logic is one thing, and the character's logic being able to reach out and move a prop
     * is what makes the two halves into one system.
     */
    PUSH_PROP("pushProp", "推一下道具", "propValue"),

    /** Take a prop or a liquid off the bench. What a candle does when it burns out. */
    CLEAR("clear", "清掉道具", "clearWhat"),

    /**
     * Hand control to another rule.
     *
     * The first action that is not about the world at all, but about the LIST. A rule has
     * always been a straight line — 当 → 如果 → 就, and then the next rule gets its turn —
     * and this is the one way to say "and now that one". It is deliberately the last thing a
     * rule can do: see RuleEngine.fire for what "control" is handed over and what is not.
     */
    GOTO("goto", "跳到规则", "rule");

    companion object {
        fun of(id: String): ActionKind = values().firstOrNull { it.id == id } ?: SAY
    }
}

/** The comparisons a condition can make. */
enum class CompareOp(val id: String, val label: String) {
    GT(">", "大于"),
    GE(">=", "不小于"),
    LT("<", "小于"),
    LE("<=", "不大于"),
    EQ("=", "等于");

    companion object {
        fun of(id: String): CompareOp = values().firstOrNull { it.id == id } ?: GE
    }
}

/**
 * The whole of a character's logic: its numbers, and the rules that move them.
 *
 * Stored beside the package in logic.json and owned by the user, exactly like poses.json.
 * A rule the user wrote is not a package asset, so nothing here is ever refreshed from the
 * bundled defaults once the file exists.
 */
class LogicSpec(
    val stats: List<StatSpec>,
    val rules: List<RuleSpec>,
    val states: List<StateSpec> = emptyList(),
    /** The liquids a rule can spill. Editable, because "blood" is a decision. */
    val liquids: List<LiquidSpec> = Liquids.DEFAULTS,
    /**
     * The particles a rule can spray. Editable for the same reason, and because two of the
     * six switches -- does it fall, does it leave a mark -- are the whole personality of a
     * particle: 汗 and 火花 are the same code with different answers.
     */
    val particles: List<ParticleSpec> = ParticleKinds.DEFAULTS,
) {

    fun rule(index: Int): RuleSpec? = rules.getOrNull(index)

    companion object {

        fun parse(text: String): LogicSpec {
            val spec = read(text)
            return if (spec.stats.isEmpty()) read(DEFAULT) else spec
        }

        /**
         * A spec for a prop or a liquid.
         *
         * Deliberately does NOT fall back to the character's defaults: a candle with no
         * rules is a candle with no rules, and handing it a life bar and a pain stat because
         * its file was empty would be the app inventing a character.
         */
        fun parseObject(text: String): LogicSpec = read(text)

        private fun read(text: String): LogicSpec {
            val o = JSONObject(text)
            val statArr = o.optJSONArray("stats")
            val stats = (0 until (statArr?.length() ?: 0)).map { i ->
                val s = statArr!!.getJSONObject(i)
                val id = s.optString("id", "S" + i)
                StatSpec(
                    id = id,
                    name = s.optString("name", id),
                    initial = s.optDouble("value", 0.0).toFloat(),
                    min = s.optDouble("min", 0.0).toFloat(),
                    max = s.optDouble("max", 100.0).toFloat(),
                )
            }

            val stateArr = o.optJSONArray("states")
            val states = (0 until (stateArr?.length() ?: 0)).map { i ->
                val s = stateArr!!.getJSONObject(i)
                val id = s.optString("id", "state" + i)
                StateSpec(id, s.optString("name", id), s.optBoolean("on", false))
            }

            val liquidArr = o.optJSONArray("liquids")
            val liquids = if (liquidArr == null || liquidArr.length() == 0) {
                Liquids.DEFAULTS
            } else {
                (0 until liquidArr.length()).map { i ->
                    val l = liquidArr.getJSONObject(i)
                    val id = l.optString("id", "liquid" + i)
                    LiquidSpec(
                        id = id,
                        name = l.optString("name", id),
                        colour = parseColour(l.optString("colour", ""), 0xFFB4212B.toInt()),
                        viscosity = l.optDouble("viscosity", 0.0).toFloat(),
                    )
                }
            }

            // The same rule as liquids: a file that says nothing gets the shipped kinds, and
            // a file that says something -- even an empty list -- is taken at its word.
            val particleArr = o.optJSONArray("particles")
            val particles = if (particleArr == null || particleArr.length() == 0) {
                ParticleKinds.DEFAULTS
            } else {
                (0 until particleArr.length()).map { i ->
                    val p = particleArr.getJSONObject(i)
                    val id = p.optString("id", "particle" + i)
                    ParticleSpec(
                        id = id,
                        name = p.optString("name", id),
                        colour = parseColour(p.optString("colour", ""), 0xFF9A8FA6.toInt()),
                        size = p.optDouble("size", 1.0).toFloat().coerceIn(0.2f, 4f),
                        gravity = p.optBoolean("gravity", true),
                        stains = p.optBoolean("stains", false),
                    )
                }
            }

            val ruleArr = o.optJSONArray("rules")
            val rules = (0 until (ruleArr?.length() ?: 0)).map { i ->
                val r = ruleArr!!.getJSONObject(i)
                val condArr = r.optJSONArray("if")
                val actArr = r.optJSONArray("then")
                val elseArr = r.optJSONArray("else")
                RuleSpec(
                    on = r.optString("on", "tick"),
                    part = r.optString("part", ""),
                    conditions = (0 until (condArr?.length() ?: 0)).map { j ->
                        val c = condArr!!.getJSONObject(j)
                        ConditionSpec(
                            kind = c.optString("kind", "stat"),
                            stat = c.optString("stat", ""),
                            op = c.optString("op", ">="),
                            value = c.optDouble("value", 0.0).toFloat(),
                            state = c.optString("state", ""),
                            join = c.optString("join", Joins.AND),
                        )
                    },
                    actions = (0 until (actArr?.length() ?: 0)).map { j ->
                        val a = actArr!!.getJSONObject(j)
                        ActionSpec(
                            kind = a.optString("kind", "say"),
                            text = a.optString("text", ""),
                            stat = a.optString("stat", ""),
                            value = a.optDouble("value", 0.0).toFloat(),
                            value2 = a.optDouble("value2", 0.0).toFloat(),
                            bone = a.optString("bone", ""),
                            prop = a.optString("prop", ""),
                            state = a.optString("state", ""),
                            rule = a.optInt("rule", 0),
                        )
                    },
                    cooldown = r.optDouble("cooldown", 0.0).toFloat(),
                    once = r.optBoolean("once", false),
                    elseActions = (0 until (elseArr?.length() ?: 0)).map { j ->
                        val a = elseArr!!.getJSONObject(j)
                        ActionSpec(
                            kind = a.optString("kind", "say"),
                            text = a.optString("text", ""),
                            stat = a.optString("stat", ""),
                            value = a.optDouble("value", 0.0).toFloat(),
                            value2 = a.optDouble("value2", 0.0).toFloat(),
                            bone = a.optString("bone", ""),
                            prop = a.optString("prop", ""),
                            state = a.optString("state", ""),
                            rule = a.optInt("rule", 0),
                        )
                    },
                )
            }
            return LogicSpec(stats, rules, states, liquids, particles)
        }

        fun toJson(spec: LogicSpec): String {
            val root = JSONObject().put("version", VERSION)
            val stats = JSONArray()
            for (s in spec.stats) {
                stats.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put("value", s.initial.toDouble())
                        .put("min", s.min.toDouble())
                        .put("max", s.max.toDouble())
                )
            }
            root.put("stats", stats)

            val states = JSONArray()
            for (s in spec.states) {
                states.put(JSONObject().put("id", s.id).put("name", s.name).put("on", s.initial))
            }
            root.put("states", states)

            val liquids = JSONArray()
            for (l in spec.liquids) {
                liquids.put(
                    JSONObject()
                        .put("id", l.id).put("name", l.name)
                        .put("colour", String.format("#%06X", l.colour and 0xFFFFFF))
                        .put("viscosity", l.viscosity.toDouble())
                )
            }
            root.put("liquids", liquids)

            val particles = JSONArray()
            for (p in spec.particles) {
                particles.put(
                    JSONObject()
                        .put("id", p.id).put("name", p.name)
                        .put("colour", String.format("#%06X", p.colour and 0xFFFFFF))
                        .put("size", p.size.toDouble())
                        .put("gravity", p.gravity)
                        .put("stains", p.stains)
                )
            }
            root.put("particles", particles)

            val rules = JSONArray()
            for (r in spec.rules) {
                val conds = JSONArray()
                for (c in r.conditions) {
                    conds.put(
                        JSONObject()
                            .put("kind", c.kind).put("stat", c.stat)
                            .put("op", c.op).put("value", c.value.toDouble())
                            .put("state", c.state).put("join", c.join)
                    )
                }
                val acts = JSONArray()
                for (a in r.actions) {
                    acts.put(
                        JSONObject()
                            .put("kind", a.kind).put("text", a.text).put("stat", a.stat)
                            .put("value", a.value.toDouble()).put("value2", a.value2.toDouble())
                            .put("bone", a.bone)
                            .put("prop", a.prop).put("state", a.state)
                            .put("rule", a.rule)
                    )
                }
                val elses = JSONArray()
                for (a in r.elseActions) {
                    elses.put(
                        JSONObject()
                            .put("kind", a.kind).put("text", a.text).put("stat", a.stat)
                            .put("value", a.value.toDouble()).put("value2", a.value2.toDouble())
                            .put("bone", a.bone)
                            .put("prop", a.prop).put("state", a.state)
                            .put("rule", a.rule)
                    )
                }
                rules.put(
                    JSONObject()
                        .put("on", r.on).put("part", r.part)
                        .put("cooldown", r.cooldown.toDouble()).put("once", r.once)
                        .put("if", conds).put("then", acts).put("else", elses)
                )
            }
            root.put("rules", rules)
            return root.toString(2)
        }

        const val VERSION = 1

        /**
         * What a character starts with: two numbers and five rules that do something
         * visible, so the logic panel is never an empty page somebody has to guess at.
         */
        /**
     * What a prop or a liquid starts with when it is first given logic of its own.
     *
     * One number and no rules, because the first rule is the thing the user is about to
     * write: a candle's clock is almost always 耐久 and almost never 生命.
     */
    val OBJECT_DEFAULT = """
{
  "version": 1,
  "stats": [ { "id": "L", "name": "耐久", "value": 100, "min": 0, "max": 100 } ],
  "states": [],
  "liquids": [],
  "particles": [],
  "rules": []
}
"""

    val DEFAULT = """
{
  "version": 1,
  "stats": [
    { "id": "H", "name": "生命", "value": 100, "min": 0, "max": 100 },
    { "id": "P", "name": "痛苦", "value": 0, "min": 0, "max": 100 }
  ],
  "states": [
    { "id": "dressed", "name": "穿着", "on": false },
    { "id": "hurt", "name": "受伤", "on": false }
  ],
  "liquids": [
    { "id": "blood", "name": "血", "colour": "#B4212B", "viscosity": 0.35 },
    { "id": "water", "name": "水", "colour": "#3D8FD1", "viscosity": 0.0 },
    { "id": "slime", "name": "史莱姆", "colour": "#5FA83C", "viscosity": 0.8 },
    { "id": "ink", "name": "墨", "colour": "#23202E", "viscosity": 0.15 }
  ],
  "particles": [
    { "id": "blood", "name": "血", "colour": "#C92A2A", "size": 1.0, "gravity": true, "stains": true },
    { "id": "sweat", "name": "汗", "colour": "#4C8DE0", "size": 1.0, "gravity": true, "stains": true },
    { "id": "spark", "name": "火花", "colour": "#F2A93B", "size": 1.0, "gravity": true, "stains": false },
    { "id": "dust", "name": "灰尘", "colour": "#9A8FA6", "size": 1.0, "gravity": true, "stains": false },
    { "id": "star", "name": "星星", "colour": "#E45CA8", "size": 1.0, "gravity": false, "stains": false },
    { "id": "heart", "name": "爱心", "colour": "#E2557B", "size": 1.0, "gravity": false, "stains": false }
  ],
  "rules": [
    {
      "on": "thrown", "part": "", "cooldown": 1.0, "once": false,
      "if": [ { "kind": "stat", "stat": "H", "op": ">", "value": 0 } ],
      "then": [
        { "kind": "say", "text": "哇啊——！" },
        { "kind": "add", "stat": "P", "value": 20 }
      ]
    },
    {
      "on": "landed", "part": "", "cooldown": 0.4, "once": false,
      "if": [ { "kind": "stat", "stat": "H", "op": ">", "value": 0 } ],
      "then": [
        { "kind": "add", "stat": "P", "value": 12 },
        { "kind": "burst", "text": "dust", "value": 8 }
      ]
    },
    {
      "on": "propHit", "part": "", "cooldown": 0.25, "once": false,
      "if": [],
      "then": [
        { "kind": "add", "stat": "P", "value": 10 },
        { "kind": "burst", "text": "spark", "value": 10 },
        { "kind": "say", "text": "呜！" }
      ]
    },
    {
      "on": "tick", "part": "", "cooldown": 6.0, "once": false,
      "if": [ { "kind": "chance", "value": 25 } ],
      "then": [
        { "kind": "say", "text": "唔……" },
        { "kind": "random", "stat": "P", "value": 5, "value2": 25 }
      ]
    },
    {
      "on": "tick", "part": "", "cooldown": 1.0, "once": false,
      "if": [ { "kind": "stat", "stat": "P", "op": ">=", "value": 80 } ],
      "then": [ { "kind": "say", "text": "……好疼" }, { "kind": "burst", "text": "sweat", "value": 4 } ]
    },
    {
      "on": "tick", "part": "", "cooldown": 2.0, "once": true,
      "if": [ { "kind": "stat", "stat": "H", "op": "<=", "value": 0 } ],
      "then": [
        { "kind": "say", "text": "……" },
        { "kind": "clearPose", "text": "" },
        { "kind": "burst", "text": "blood", "value": 14 },
        { "kind": "spill", "text": "blood", "value": 40 }
      ]
    },
    {
      "on": "click", "part": "", "cooldown": 0.6, "once": false,
      "if": [ { "kind": "stat", "stat": "H", "op": ">", "value": 0 } ],
      "then": [ { "kind": "stateToggle", "state": "dressed" } ]
    },
    {
      "on": "thrown", "part": "", "cooldown": 5.0, "once": false,
      "if": [ { "kind": "state", "stat": "", "state": "dressed", "op": "on" } ],
      "then": [ { "kind": "say", "text": "别弄脏衣服！" } ]
    },
    {
      "on": "click", "part": "", "cooldown": 0.6, "once": false,
      "if": [ { "kind": "state", "stat": "", "state": "dressed", "op": "on" } ],
      "then": [ { "kind": "say", "text": "好看吗？" } ],
      "else": [ { "kind": "say", "text": "干嘛？" } ]
    },
    {
      "on": "tick", "part": "", "cooldown": 3.0, "once": false,
      "if": [
        { "kind": "stat", "stat": "P", "op": ">=", "value": 60 },
        { "kind": "state", "stat": "", "state": "dressed", "op": "on", "join": "and" },
        { "kind": "stat", "stat": "H", "op": "<=", "value": 20, "join": "or" }
      ],
      "then": [ { "kind": "say", "text": "……站不住了" } ]
    },
    {
      "on": "thrown", "part": "", "cooldown": 1.0, "once": false,
      "if": [],
      "then": [ { "kind": "emit", "text": "被扔过" } ]
    },
    {
      "on": "emit", "part": "被扔过", "cooldown": 3.0, "once": false,
      "if": [ { "kind": "stat", "stat": "P", "op": ">=", "value": 40 } ],
      "then": [ { "kind": "say", "text": "别又扔我……" }, { "kind": "burst", "text": "sweat", "value": 3 } ]
    }
  ]
}
"""
    }
}
