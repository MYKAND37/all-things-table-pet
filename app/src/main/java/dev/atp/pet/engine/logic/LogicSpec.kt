package dev.atp.pet.engine.logic

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
)

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
    val bone: String = "",
    val prop: String = "",
    /** For the three state actions: which state to turn on, off, or over. */
    val state: String = "",
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
)

/** What a rule can do, with the parameter the editor has to ask for. */
enum class ActionKind(val id: String, val label: String, val needs: String) {
    SAY("say", "说一句话", "text"),
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
    STATE_TOGGLE("stateToggle", "切换状态", "state");

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
) {

    fun rule(index: Int): RuleSpec? = rules.getOrNull(index)

    companion object {

        fun parse(text: String): LogicSpec {
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

            val ruleArr = o.optJSONArray("rules")
            val rules = (0 until (ruleArr?.length() ?: 0)).map { i ->
                val r = ruleArr!!.getJSONObject(i)
                val condArr = r.optJSONArray("if")
                val actArr = r.optJSONArray("then")
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
                        )
                    },
                    actions = (0 until (actArr?.length() ?: 0)).map { j ->
                        val a = actArr!!.getJSONObject(j)
                        ActionSpec(
                            kind = a.optString("kind", "say"),
                            text = a.optString("text", ""),
                            stat = a.optString("stat", ""),
                            value = a.optDouble("value", 0.0).toFloat(),
                            bone = a.optString("bone", ""),
                            prop = a.optString("prop", ""),
                            state = a.optString("state", ""),
                        )
                    },
                    cooldown = r.optDouble("cooldown", 0.0).toFloat(),
                    once = r.optBoolean("once", false),
                )
            }
            if (stats.isEmpty()) return parse(DEFAULT)
            return LogicSpec(stats, rules, states)
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

            val rules = JSONArray()
            for (r in spec.rules) {
                val conds = JSONArray()
                for (c in r.conditions) {
                    conds.put(
                        JSONObject()
                            .put("kind", c.kind).put("stat", c.stat)
                            .put("op", c.op).put("value", c.value.toDouble())
                            .put("state", c.state)
                    )
                }
                val acts = JSONArray()
                for (a in r.actions) {
                    acts.put(
                        JSONObject()
                            .put("kind", a.kind).put("text", a.text).put("stat", a.stat)
                            .put("value", a.value.toDouble()).put("bone", a.bone)
                            .put("prop", a.prop).put("state", a.state)
                    )
                }
                rules.put(
                    JSONObject()
                        .put("on", r.on).put("part", r.part)
                        .put("cooldown", r.cooldown.toDouble()).put("once", r.once)
                        .put("if", conds).put("then", acts)
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
        { "kind": "burst", "text": "blood", "value": 14 }
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
      "if": [ { "kind": "stat", "stat": "H", "op": ">", "value": 0 } ],
      "then": [ { "kind": "say", "text": "干嘛？" } ]
    }
  ]
}
"""
    }
}
