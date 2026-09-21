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

    /**
     * The body just changed to another rig of the same pet. Nothing else did.
     *
     * A new event rather than a second meaning for 出现时: 出现时 is "this pet is here", and a
     * rig that a rule can listen for is how "变成机械形态 → 说一句话" is written as two rules
     * instead of one action list. The numbers, the states, the rules, the particles and the
     * liquids all carry straight through it -- see ActionKind.SET_RIG.
     */
    RIG_SWAP("rigSwap", "换了骨骼套", ""),
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
    PROP_HIT("propHit", "被道具碰到", "力度"),

    /**
     * A prop came CLOSE, without touching. `value` = the gap in px between the two surfaces.
     *
     * 「当某个物品靠近时」 was the missing detector: 被道具碰到 only exists once the prop has
     * already arrived, so "back away from the hammer" could not be written at all — by the time
     * the rule ran, the hammer was on it. [GameEvent.part] is the bone the prop came closest to,
     * so naming a part (or a prefix like `hand`) means "this part is the one it is near".
     *
     * It fires on the way IN only, once per approach, and [PROP_AWAY] is when that approach
     * ends: "near" as a state would be a rule every frame, and the way back out is the other
     * half of the same measurement rather than a second feature.
     */
    PROP_NEAR("propNear", "有东西靠近", "距离"),

    /**
     * The prop that had come close is gone again. `value` = how far away it was when it turned
     * around, which is not the same number as the one it arrived at: the gap has to open up
     * noticeably before this fires, or a prop resting at the edge would flap in and out.
     *
     * See [PROP_NEAR].
     */
    PROP_AWAY("propAway", "有东西走开", "距离"),

    /**
     * A signal a rule raised.
     *
     * The one event with no physics behind it: something else in the character's own logic
     * asked for it. It is what makes a rule set a program rather than a list — "hit, then a
     * moment later say something, then turn a state on" is three rules that talk to each
     * other instead of one action list, and the same signal can be listened for by more than
     * one rule.
     *
     * Which signal it is lives in [GameEvent.part], because a signal is matched exactly the
     * way a part is: a rule that names nothing hears every signal, and one that names a
     * prefix hears the family.
     */
    EMIT("emit", "收到信号", "");

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
 * somebody writing a rule by hand almost always means. For [EventType.EMIT] it is the name
 * of the signal instead, and it is matched by the same rule.
 */
data class GameEvent(
    val type: EventType,
    val part: String = "",
    val value: Float = 0f,
    /** Which prop was involved, if any. Empty otherwise. */
    val prop: String = "",
    /**
     * Which kind of particle was involved, if any. Empty otherwise.
     *
     * A field of its own rather than a second meaning for [prop], for the same reason
     * ActionSpec.rule is one: "spark" and "hammer" come out of two different registries, and
     * an event that names one of them in a field called `prop` is an event nobody can read.
     * The two are never both set — a particle is not a prop and does not hit one.
     */
    val particle: String = "",
    /** Seconds since the character appeared. The log is readable in order. */
    val at: Float = 0f,
) {
    /** Does this event belong to the part a rule asked about? Empty means anywhere. */
    fun touches(rulePart: String): Boolean =
        rulePart.isEmpty() || part == rulePart || part.startsWith(rulePart)

    fun describe(): String {
        val where = if (part.isEmpty()) "" else " · " + part
        // WHICH prop, said out loud. "被道具碰到" on its own is the one line in the log that
        // cannot answer the question the rule was written to ask -- a rule about the hammer
        // and a rule about the ball read identically without this.
        val who = when {
            prop.isNotEmpty() -> " · " + prop
            particle.isNotEmpty() -> " · " + particle
            else -> ""
        }
        val how = if (value <= 0f) "" else " " + value.toInt()
        return type.label + where + who + how
    }

    /**
     * Does this event come from the thing a rule asked about?
     *
     * A prop event names its prop and a particle event names its kind; a rule may ask for
     * either by name, and an empty answer means "from anything". See RuleSpec.about.
     */
    fun aboutIs(who: String): Boolean = who.isEmpty() || who == prop || who == particle
}
