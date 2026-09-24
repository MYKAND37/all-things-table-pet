package dev.atp.pet.ui

import android.content.Context
import dev.atp.pet.R
import dev.atp.pet.engine.event.EventType
import dev.atp.pet.engine.logic.ActionKind
import dev.atp.pet.engine.logic.Joins
import dev.atp.pet.engine.prop.PropKind

/**
 * 应用**自己的功能词**，按 id 查资源。
 *
 * 为什么要有这一层：这些字（当 / 如果 / 就、14 种事件、28 种动作、道具类型、19 根骨头的中文名、
 * 四档刚度）原来写在**引擎的枚举**里 —— 而引擎是故意不依赖 Android 的（那是它能在本地被完整
 * 镜像测试的原因），所以它里面的字**永远不可能有第二种语言**。
 *
 * 于是分成两半：引擎只留 id（谁读它都不出错），给人看的字住在资源里，两边靠 id 对上。
 * 引擎那份中文 label 留着当兜底 —— 引擎自己的日志和 `GameEvent.describe` 还在用它，
 * 而那是调试读数，不是界面。
 *
 * 表是显式的（不用反射查资源名）：`tools/kotlin_check.py` 会核对每一个 `R.string.*` 真的
 * 存在，所以一个拼错的键在那一步就红了，而不是等到手机上显示成空白。
 */
object Labels {

    private val EVENTS = mapOf(
        "spawn" to R.string.vocab_event_spawn,
        "rigSwap" to R.string.vocab_event_rigSwap,
        "tick" to R.string.vocab_event_tick,
        "grab" to R.string.vocab_event_grab,
        "release" to R.string.vocab_event_release,
        "thrown" to R.string.vocab_event_thrown,
        "landed" to R.string.vocab_event_landed,
        "impact" to R.string.vocab_event_impact,
        "click" to R.string.vocab_event_click,
        "longPress" to R.string.vocab_event_longPress,
        "propHit" to R.string.vocab_event_propHit,
        "propNear" to R.string.vocab_event_propNear,
        "propAway" to R.string.vocab_event_propAway,
        "emit" to R.string.vocab_event_emit,
    )

    private val ACTIONS = mapOf(
        "say" to R.string.vocab_action_say,
        "random" to R.string.vocab_action_random,
        "add" to R.string.vocab_action_add,
        "set" to R.string.vocab_action_set,
        "pose" to R.string.vocab_action_pose,
        "clearPose" to R.string.vocab_action_clearPose,
        "spawn" to R.string.vocab_action_spawn,
        "burst" to R.string.vocab_action_burst,
        "pour" to R.string.vocab_action_pour,
        "stream" to R.string.vocab_action_stream,
        "impulse" to R.string.vocab_action_impulse,
        "depth" to R.string.vocab_action_depth,
        "break" to R.string.vocab_action_break,
        "show" to R.string.vocab_action_show,
        "detach" to R.string.vocab_action_detach,
        "rejoin" to R.string.vocab_action_rejoin,
        "morph" to R.string.vocab_action_morph,
        "setRig" to R.string.vocab_action_setRig,
        "playAnim" to R.string.vocab_action_playAnim,
        "wait" to R.string.vocab_action_wait,
        "stateOn" to R.string.vocab_action_stateOn,
        "stateOff" to R.string.vocab_action_stateOff,
        "stateToggle" to R.string.vocab_action_stateToggle,
        "spill" to R.string.vocab_action_spill,
        "emit" to R.string.vocab_action_emit,
        "pushProp" to R.string.vocab_action_pushProp,
        "clear" to R.string.vocab_action_clear,
        "goto" to R.string.vocab_action_goto,
    )

    private val PROP_KINDS = mapOf(
        "hold" to R.string.vocab_propkind_hold,
        "device" to R.string.vocab_propkind_device,
        "throw" to R.string.vocab_propkind_throw,
        "shot" to R.string.vocab_propkind_shot,
        "pin" to R.string.vocab_propkind_pin,
        "rope" to R.string.vocab_propkind_rope,
    )

    private val BONES = mapOf(
        "hip" to R.string.bone_hip,
        "spine" to R.string.bone_spine,
        "chest" to R.string.bone_chest,
        "neck" to R.string.bone_neck,
        "head" to R.string.bone_head,
        "shoulder_L" to R.string.bone_shoulder_L,
        "upperarm_L" to R.string.bone_upperarm_L,
        "forearm_L" to R.string.bone_forearm_L,
        "hand_L" to R.string.bone_hand_L,
        "shoulder_R" to R.string.bone_shoulder_R,
        "upperarm_R" to R.string.bone_upperarm_R,
        "forearm_R" to R.string.bone_forearm_R,
        "hand_R" to R.string.bone_hand_R,
        "thigh_L" to R.string.bone_thigh_L,
        "shin_L" to R.string.bone_shin_L,
        "foot_L" to R.string.bone_foot_L,
        "thigh_R" to R.string.bone_thigh_R,
        "shin_R" to R.string.bone_shin_R,
        "foot_R" to R.string.bone_foot_R,
    )

    /** 14 种事件。 */
    fun event(context: Context, type: EventType): String =
        pick(context, EVENTS[type.id], type.label)

    /** 28 种动作。 */
    fun action(context: Context, kind: ActionKind): String =
        pick(context, ACTIONS[kind.id], kind.label)

    /** 6 种道具类型。 */
    fun propKind(context: Context, kind: PropKind): String =
        pick(context, PROP_KINDS[kind.id], kind.label)

    /** 而且 / 或者。 */
    fun join(context: Context, id: String): String =
        if (Joins.of(id) == Joins.OR) context.getString(R.string.vocab_join_or)
        else context.getString(R.string.vocab_join_and)

    /** 四档刚度（界面那一排 chip）。 */
    fun stiffness(context: Context, step: Int): String = context.getString(
        when (step.coerceIn(0, 3)) {
            0 -> R.string.stiffness_0
            1 -> R.string.stiffness_1
            2 -> R.string.stiffness_2
            else -> R.string.stiffness_3
        }
    )

    /**
     * 一根骨头的中文名，或者 ""（没有中文名就用它自己的名字，见 boneLabel）。
     *
     * 返回空串而不是原样返回 bone：调用方要能分清"有名字"和"没有名字" —— 一个节点的名字就
     * 是它自己，而一根骨头没有中文名时界面得显示它的英文 id。
     */
    fun bone(context: Context, bone: String): String =
        BONES[bone]?.let { context.getString(it) } ?: ""

    /** 一个键查不到就退回引擎那份（宁可显示中文，也不要显示空白）。 */
    private fun pick(context: Context, id: Int?, fallback: String): String =
        if (id == null) fallback else context.getString(id)
}
