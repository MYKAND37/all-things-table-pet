package dev.atp.pet.engine.event

/**
 * Something that happened to the character.
 *
 * The logic layer is written against what HAPPENED, never against the physics. Nothing in
 * a rule knows how the ragdoll works, or that a bone has an angle at all — a rule says
 * "when it lands hard" and the engine is the only thing that knows what "hard" measures.
 * That is what lets the same rule set survive a rewrite of the solver underneath it.
 */
enum class EventType(val id: String, val label: String, val unit: String) {
    SPAWN("spawn", "出现时", ""),
    TICK("tick", "每隔一会儿", ""),
    GRAB("grab", "被抓起", ""),
    RELEASE("release", "被放下", ""),
    /** value = how fast, in px/s. */
    THROWN("thrown", "被甩出去", "速度"),
    /** value = vertical speed at the moment of contact. */
    LANDED("landed", "落地", "冲击"),
    /** value = impact speed times the mass of whatever hit it. */
    IMPACT("impact", "被打到", "力度"),
    CLICK("click", "被点一下", ""),
    PROP_HIT("propHit", "被道具碰到", "力度");

    // There is deliberately no "a stat changed" event. Every reaction to a number can be
    // written as TICK plus a condition, which fires half a second later and cannot feed
    // itself — and a rule that changes the number it watches is exactly the shape of bug
    // that is impossible to explain to the person who wrote it.

    companion object {
        fun of(id: String): EventType = values().firstOrNull { it.id == id } ?: TICK
    }
}

/**
 * One thing that happened, with everything a rule might ask about it.
 *
 * [part] is a bone name, empty when the event belongs to the figure as a whole. A rule may
 * name a bone exactly, or name a prefix — "hand" catches hand_L and hand_R, which is what
 * somebody writing a rule by hand almost always means.
 */
data class GameEvent(
    val type: EventType,
    val part: String = "",
    val value: Float = 0f,
    /** Which prop was involved, if any. Empty otherwise. */
    val prop: String = "",
    /** Seconds since the character appeared. The log is readable in order. */
    val at: Float = 0f,
) {
    /** Does this event belong to the part a rule asked about? Empty means anywhere. */
    fun touches(rulePart: String): Boolean =
        rulePart.isEmpty() || part == rulePart || part.startsWith(rulePart)

    fun describe(): String {
        val where = if (part.isEmpty()) "" else " · " + part
        val how = if (value <= 0f) "" else " " + value.toInt()
        return type.label + where + how
    }
}
