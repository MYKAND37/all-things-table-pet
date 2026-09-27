package dev.atp.pet.engine.logic

import dev.atp.pet.engine.event.GameEvent
import dev.atp.pet.engine.fluid.Fluid
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
     * For kind = "pose" and kind = "tied": WHICH PART.
     *
     * A field of its own rather than a second meaning for [stat], for the reason this file
     * keeps repeating: `"stat": "hand_L"` is a file where a number and a bone are the same
     * word, and the person reading it has to know which line of code decided that.
     */
    val bone: String = "",
    /**
     * For kind = "pose": the part it is compared AGAINST — 「手 比 肩膀 高」 names two bones.
     */
    val other: String = "",
    /**
     * For kind = "pose": which way the comparison goes — "up" or "left".
     *
     * Two answers rather than four: 「A 在 B 上面」 and 「B 在 A 上面」 are the same question
     * asked twice, and a menu with 上下左右 in it invites writing the same rule two ways.
     * [value] is the margin in pixels, so 「高过 30 像素」 is a real rule and 「差不多高」 is
     * not something you have to hand-tune.
     */
    val axis: String = "",
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
object Shapes {
    /** 柱状：滴子都朝一个方向出来，落下去是一道。 */
    const val COLUMN = "column"

    /** 乱撒：和 喷液体 一样，全圆随机方向。 */
    const val SCATTER = "scatter"

    const val DEFAULT = COLUMN

    fun of(id: String): String = if (id == SCATTER) SCATTER else COLUMN
}

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
     * A KIND of particle: 血, 汗, 火花, 灰尘 — one subject per kind, not per drop.
     *
     * The last of the four things that can hold logic, and the same shape as the other three:
     * the character DECLARES the particles (name, colour, gravity, stains), and each kind can
     * carry rules of its own in `characters/<角色>/particles/<粒子>/logic.json`.
     *
     * Per kind rather than per drop because a drop lives for about a second: a rule that had
     * to be written on one spark would have nothing left to run against by the time anybody
     * read the log. "火花 落地 → 点燃" is a rule about sparks.
     */
    const val PARTICLE_PREFIX = "particle:"

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

    fun particle(id: String): String = PARTICLE_PREFIX + id

    fun isProp(subject: String): Boolean = subject.startsWith(PROP_PREFIX)

    fun isLiquid(subject: String): Boolean = subject.startsWith(LIQUID_PREFIX)

    fun isPart(subject: String): Boolean = subject.startsWith(PART_PREFIX)

    fun isParticle(subject: String): Boolean = subject.startsWith(PARTICLE_PREFIX)

    /** The prop or liquid id inside a subject, or "" for the character. */
    fun objectId(subject: String): String =
        if (isProp(subject) || isLiquid(subject)) subject.substringAfter(':') else ""

    /** Which prop this subject is about, or "" if it is not about one. */
    fun propId(subject: String): String = if (isProp(subject)) objectId(subject) else ""

    fun liquidId(subject: String): String = if (isLiquid(subject)) objectId(subject) else ""

    /** Which bone this subject is, or "" if it is not a part. */
    fun partId(subject: String): String =
        if (isPart(subject)) subject.substringAfter(':') else ""

    /** Which kind of particle this subject is, or "" if it is not one. */
    fun particleId(subject: String): String =
        if (isParticle(subject)) subject.substringAfter(':') else ""

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
     *
     * WHO ACTUALLY CALLS THIS, because the `when` below reads as if it decided for everything:
     * the bench calls it in exactly one loop, and that loop has already skipped every subject
     * that is not a part. Props, liquids and particles are delivered TO BY NAME at the place
     * that knows what happened (`fireTo(Subjects.prop(id), …)` and friends), which is more
     * precise than a predicate over a shared event could be. So the isProp branch here is
     * reachable only if that filter ever widens, and a particle branch would be dead code
     * today -- which is why there is not one.
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
    /**
     * 第二节骨头：**改变部位深度**排到谁的前面/后面时用得着（1.27.0）。
     *
     * 一个字段而不是把两节骨头挤进 [text]：这一版刚因为"两个东西共用一个字段"出过事，
     * 而规则文件是要能被人读的 —— `"bone": "hand_L", "bone2": "chest"` 一眼就懂。
     */
    val bone2: String = "",
    val prop: String = "",
    /**
     * The shape of a stream: [Shapes.COLUMN] (a jet that lands as one line) or
     * [Shapes.SCATTER] (a splash spread over seconds). Empty means column.
     *
     * A field of its own rather than a second meaning for `text` or `value2`, for the reason
     * this file keeps repeating: a file has to be readable by a person, and "the liquid id is
     * in text except when it is a shape" is not readable. Only 流液体 uses it so far.
     */
    val shape: String = "",
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
/**
 * 一支「就」的备选：`就 A 或者 B 或者 C`（1.32.0）。
 *
 * 多支之间是**按相对权重挑一支做**，不是都做 —— 「哪个触发频率高」正是权重在说的那件事。
 * 权重是**相对**的（1 / 2 / 3 就是 1:2:3 的概率），不用凑总数：凑总数的话，改一支就得改
 * 其他所有支，而用户想说的只是"这一支多一点"。[Weights.pick] 是挑选那一步，纯函数。
 *
 * 主「就」自己也是一支（它的权重写在 [RuleSpec.thenWeight] 上）：没有「或者」时那一份权重
 * 没有意义，行为就是老行为（全部做）。
 */
data class ActionAlt(
    val weight: Int = 1,
    val actions: List<ActionSpec> = emptyList(),
)

/**
 * 「否则如果」：上面那些都不成立时，才轮到问它（1.32.0）。
 *
 * 一条规则因此是一串 if / else if / else：主「如果」→ 一级级往下的「否则如果」→ 最后的
 * 「否则」（没有条件，兜底）。每一级有它**自己**的「如果」、自己的「就」、自己的「或者」，
 * 所以"一级里再分几种情况"不需要再嵌一层结构。
 */
data class ElseIfSpec(
    val conditions: List<ConditionSpec> = emptyList(),
    /** 这一级的「就」的权重（只在它有「或者」时起作用）。 */
    val weight: Int = 1,
    val actions: List<ActionSpec> = emptyList(),
    val alts: List<ActionAlt> = emptyList(),
)

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
    /**
     * 主「就」这一支的权重，只在它有「或者」时起作用（1.32.0）。
     *
     * 没有「或者」时它是 1，而且**不起作用**：一支的时候没有"挑"这回事，行为和老文件完全
     * 一样（照旧做全部动作）。
     */
    val thenWeight: Int = 1,
    /** 「就」后面的「或者」：多支备选，按相对权重挑一支（1.32.0）。 */
    val alts: List<ActionAlt> = emptyList(),
    /**
     * 「否则如果」链（1.32.0）：主「如果」不成立时，**从头往下问**，第一个成立的做它那一支；
     * 一个都不成立才轮到 [elseActions]。
     *
     * 一条规则的读法因此是一句话：「如果 A 就 X，否则如果 B 就 Y，否则就 Z」。级数没有上限，
     * 顺序就是文件里的顺序（和规则本身一样，顺序是唯一能靠得住的东西）。
     */
    val elseIfs: List<ElseIfSpec> = emptyList(),
    /** 最后那个「否则」的权重，同上（只在它有「或者」时起作用）。 */
    val elseWeight: Int = 1,
    /** 最后那个「否则」的「或者」分支。 */
    val elseAlts: List<ActionAlt> = emptyList(),
    /**
     * WHICH prop or particle this rule is about, or empty for "any of them".
     *
     * The event already carried it ([GameEvent.prop] and [GameEvent.particle]) and the log
     * says it out loud as of 1.10.2; what was missing was a rule being able to SAY it, so
     * "被道具碰到 → 说 哎哟" could not be told apart from "被锤子碰到 → 说 哎哟". A field of
     * its own rather than a second meaning for [part], because `part` is a bone and this is a
     * thing.
     */
    val about: String = "",
)

/**
 * 「或者」的挑选：按**相对权重**在几支里挑一支（1.32.0）。
 *
 * 单独一个 object 而不是写在引擎里，是为了让"挑选"这一步是**纯函数**：掷骰子那一步
 * （`Random.nextInt(total)`）留在引擎里，[pick] 只吃一个 [0, total) 的整数 —— 于是它可以
 * 在本地被逐点镜像和测试（tools/logic_check.py），而"哪一支被挑中"这件事不靠跑 App 才知道。
 */
object Weights {
    /** 权重是让人填的小整数：0 = 永不（除非全都 0），上限只是防手滑打出一串 9。 */
    const val MIN = 0
    const val MAX = 999

    fun clamp(w: Int): Int = w.coerceIn(MIN, MAX)

    /** 总权重。全 0 时按"总是第一支"处理（见 [pick]）。 */
    fun total(weights: List<Int>): Int = weights.sumOf { clamp(it) }

    /**
     * 挑第几支。[roll] 是 [0, total) 里的一个整数。
     *
     * 全填 0 时挑**第一支**，而不是"什么都不做"：一条写着"就 A 或者 B"的规则什么都不做，
     * 是没人能解释的行为；而"我把权重都填成 0"最接近的意思就是"还是走第一支"。
     */
    fun pick(weights: List<Int>, roll: Int): Int {
        val sum = total(weights)
        if (sum <= 0) return 0
        val at = ((roll % sum) + sum) % sum
        var acc = 0
        for ((i, w) in weights.withIndex()) {
            acc += clamp(w)
            if (at < acc) return i
        }
        return weights.lastIndex.coerceAtLeast(0)
    }
}

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

    /**
     * 流液体：和 喷液体 是同一种东西，只是摊在时间上。
     *
     * 喷液体 是一次四十滴，落地就见分晓；流 是每秒多少滴、流多少秒，位置每一帧重新取自
     * 主体自己身上——所以一条长在手上的"流液体 血"，手走到哪儿血就跟到哪儿，像一道伤口
     * 而不是一次喷溅。数量写在 value（每秒），时长写在 value2（秒）。
     */
    POUR("pour", "流液体", "liquidStream"),

    /** 持续喷粒子：同上，喷的是粒子。 */
    STREAM("stream", "持续喷粒子", "burstStream"),
    IMPULSE("impulse", "推一下", "boneValue"),

    /**
     * 改变部位深度：把这一节拉到最前面（或者压到最后面）。
     *
     * 图层深度本来是文件里的一行（`layers` 的 z），规则改不了它 —— 而"这一截现在在前面还是
     * 在后面"恰恰是很多动作要的效果：抬手时袖子该在胸前面，手放下时该在后面。所以这是一个
     * **运行时的**顺序：动的是这一次会话里怎么画，不动文件，收回/换骨骼套就回到原样。
     *
     * 参数：哪根骨头（[ActionSpec.bone]），怎么排（[ActionSpec.text]）：`"front"`/`"back"` 是
     * 最前面/最后面，`"before"`/`"after"` 是**排到另一节骨头的前面/后面**
     * （[ActionSpec.bone2]，1.27.0）。
     */
    DEPTH("depth", "改变部位深度", "boneFront"),
    /**
     * 隐藏部位：把一根骨头的图藏起来。
     *
     * 骨架和物理一点都不动——那根骨头还在、还有重量、还会被拖——藏掉的只是"画它"这件事。
     * 所以要"恢复了"就得有 [SHOW]：一个只能藏不能显的动作和"打坏"没有区别。
     *
     * 名字原本叫「打坏部位」，但它从来没打断过任何东西：它一直实现的就是隐藏。
     */
    BREAK("break", "隐藏部位", "bone"),

    /** 显示部位：把 [BREAK] 藏起来的图放回去。 */
    SHOW("show", "显示部位", "bone"),

    /**
     * 断开部位：这一节从身上掉下去，落到地面。
     *
     * 掉下去的是一份**它的图**：真正的骨头仍然在骨架里（隐藏着），所以宠物还是平衡的、
     * 还是会甩那条看不见的腿——这是明写的取巧，另一半（把骨架真的拆成两个刚体）要重写
     * 整个求解器，那套东西从头到尾只有一个根。见 PhysicsSandboxView.Debris。
     */
    DETACH("detach", "断开部位", "bone"),

    /** 接回部位：把 [DETACH] 掉下去的那一节接回来（图重新画上，地上的那块收走）。 */
    REJOIN("rejoin", "接回部位", "bone"),

    /**
     * 变身：换成另一个角色。
     *
     * 换的是**一整套**：骨架、图画、道具、以及那个角色自己的逻辑。这是「增删骨骼」的另一半
     * ——一节一节地加骨头只能得到同一只宠物，换一套骨骼才叫变身，而换骨骼必然要连图画一起
     * 换（没有图画的骨头是看不见的）。所以这个动作的目标写在 [ActionSpec.text] 里，是一个
     * 角色的 id。
     *
     * 它由宿主动手而不是测试场：角色是文件夹、图画和几个文件，只有 Activity 知道它们在
     * 哪儿。测试场只说"变成这个"，然后被整个重新装载一次。
     */
    MORPH("morph", "变身", "character"),

    /**
     * 换骨骼套：同一只桌宠的另一套骨架和另一套图。
     *
     * 和 [MORPH] 是两件不同的事，所以是两个动作。变身换的是**谁**——另一只桌宠、它自己的
     * 规则和数值，世界整个重新装载一次；换骨骼套换的是**长什么样**——同一只桌宠、同一套
     * 规则和数值、同一批粒子和液体，只有身体换掉。前者是"变成别的角色"，后者是"变成另一
     * 个形态"，而"肚子痛到一半变成机械形态还是痛"正是这一版要的东西。
     *
     * 目标写在 [ActionSpec.text] 里，是那一套的名字（空 = 默认那套）。和变身一样由宿主动手：
     * 骨骼套是文件夹，只有 Activity 知道它们在哪儿。
     */
    SET_RIG("setRig", "换骨骼套", "rig"),

    /**
     * 播放一段动画（1.17.0）。
     *
     * 和 [POSE] 是两层：一个动作是**一个样子**，一段动画是**一串会动的时间轴**（关键帧之间
     * 由求解器插值走完，见 engine/anim）。动画按名字找，和动作一样由宿主动手 —— 帧属于
     * 哪一套骨骼套只有宿主知道。
     */
    PLAY_ANIM("playAnim", "播放动画", "anim"),

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
                        // A file written before this switch existed gets true, which is what
                        // every liquid did then.
                        collides = l.optBoolean("collides", true),
                        // The same rule for the two looks: absent means the drop this app has
                        // always drawn -- one radius, solid paint -- so an old file is not
                        // silently restyled by an upgrade.
                        size = l.optDouble("size", 1.0).toFloat()
                            .coerceIn(Fluid.MIN_SIZE, Fluid.MAX_SIZE),
                        opacity = l.optDouble("opacity", 1.0).toFloat()
                            .coerceIn(Fluid.MIN_OPACITY, 1f),
                        // 缺键 = 后面 = 老行为（那时液体就是先于角色画的）。
                        behind = l.optBoolean("behind", true),
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
                        // 画在角色后面还是前面（1.26.0）。缺键 = 前面 = 老行为。
                        behind = p.optBoolean("behind", false),
                    )
                }
            }

            // One place that knows how an action is written down. It was inline three times
            // (then, else, and the branches), which is three places for a new action field to be
            // forgotten in -- and a forgotten field is an action that silently loses a setting
            // every time the file is saved. 1.32.0 added two more lists to that set (每一级
            // 「否则如果」的 then、每一支「或者」的 then), which is exactly why it stays one place.
            fun actionsOf(arr: JSONArray?): List<ActionSpec> =
                (0 until (arr?.length() ?: 0)).map { j ->
                    val a = arr!!.getJSONObject(j)
                    ActionSpec(
                        kind = a.optString("kind", "say"),
                        text = a.optString("text", ""),
                        stat = a.optString("stat", ""),
                        value = a.optDouble("value", 0.0).toFloat(),
                        value2 = a.optDouble("value2", 0.0).toFloat(),
                        bone = a.optString("bone", ""),
                        bone2 = a.optString("bone2", ""),
                        prop = a.optString("prop", ""),
                        state = a.optString("state", ""),
                        shape = a.optString("shape", ""),
                        rule = a.optInt("rule", 0),
                    )
                }

            // Same argument as actionsOf, one box up the chain: the 否则如果 steps each carry an
            // 如果, which would have been the second place that knows how a condition is written
            // down.
            fun condsOf(arr: JSONArray?): List<ConditionSpec> =
                (0 until (arr?.length() ?: 0)).map { j ->
                    val c = arr!!.getJSONObject(j)
                    ConditionSpec(
                        kind = c.optString("kind", "stat"),
                        stat = c.optString("stat", ""),
                        op = c.optString("op", ">="),
                        value = c.optDouble("value", 0.0).toFloat(),
                        state = c.optString("state", ""),
                        bone = c.optString("bone", ""),
                        other = c.optString("other", ""),
                        axis = c.optString("axis", "up"),
                        join = c.optString("join", Joins.AND),
                    )
                }

            val ruleArr = o.optJSONArray("rules")
            val rules = (0 until (ruleArr?.length() ?: 0)).map { i ->
                val r = ruleArr!!.getJSONObject(i)
                val condArr = r.optJSONArray("if")
                val actArr = r.optJSONArray("then")
                val elseArr = r.optJSONArray("else")
                // 否则如果链（1.32.0）。老的 `branches` 键**不再读**：并行分支这一版删掉了
                // （用户的原话是"跟新建一条规则没有区别"），老文件里还留着的那一段会被忽略，
                // 下一次存盘就没了 —— 这是有意选的"直接删"，不是漏读。
                val elseIfArr = r.optJSONArray("elseIfs")
                fun altsOf(arr: JSONArray?): List<ActionAlt> =
                    (0 until (arr?.length() ?: 0)).map { k ->
                        val raw = arr!!.getJSONObject(k)
                        ActionAlt(
                            weight = raw.optInt("weight", 1),
                            actions = actionsOf(raw.optJSONArray("then")),
                        )
                    }
                RuleSpec(
                    on = r.optString("on", "tick"),
                    part = r.optString("part", ""),
                    // A file that says nothing gets "any of them", which is what every rule
                    // meant before this existed.
                    about = r.optString("about", ""),
                    conditions = condsOf(condArr),
                    actions = actionsOf(actArr),
                    cooldown = r.optDouble("cooldown", 0.0).toFloat(),
                    once = r.optBoolean("once", false),
                    elseActions = actionsOf(elseArr),
                    thenWeight = r.optInt("thenWeight", 1),
                    alts = altsOf(r.optJSONArray("alts")),
                    elseIfs = (0 until (elseIfArr?.length() ?: 0)).map { k ->
                        val e = elseIfArr!!.getJSONObject(k)
                        ElseIfSpec(
                            conditions = condsOf(e.optJSONArray("if")),
                            weight = e.optInt("weight", 1),
                            actions = actionsOf(e.optJSONArray("then")),
                            alts = altsOf(e.optJSONArray("alts")),
                        )
                    },
                    elseWeight = r.optInt("elseWeight", 1),
                    elseAlts = altsOf(r.optJSONArray("elseAlts")),
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
                        .put("collides", l.collides)
                        // Rounded to three decimals through a Double, not written as the raw
                        // Float: a Float 0.7 serialises as 0.699999988079071, and this file is
                        // meant to be read by a person. Not "%.3f" either -- that formats with
                        // the phone's locale, and on a phone that writes 0,700 the read-back
                        // throws.
                        .put("size", round3(l.size))
                        .put("opacity", round3(l.opacity))
                        .put("behind", l.behind)
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
                        .put("behind", p.behind)
                )
            }
            root.put("particles", particles)

            val rules = JSONArray()
            for (r in spec.rules) {
                fun condJson(list: List<ConditionSpec>): JSONArray {
                    val arr = JSONArray()
                    for (c in list) {
                        arr.put(
                            JSONObject()
                                .put("kind", c.kind).put("stat", c.stat)
                                .put("op", c.op).put("value", c.value.toDouble())
                                .put("state", c.state).put("join", c.join)
                                .apply { if (c.bone.isNotEmpty()) put("bone", c.bone) }
                                .apply { if (c.other.isNotEmpty()) put("other", c.other) }
                                .apply { if (c.axis.isNotEmpty()) put("axis", c.axis) }
                        )
                    }
                    return arr
                }
                fun actionJson(list: List<ActionSpec>): JSONArray {
                    val arr = JSONArray()
                    for (a in list) {
                        arr.put(
                            JSONObject()
                                .put("kind", a.kind).put("text", a.text).put("stat", a.stat)
                                .put("value", a.value.toDouble())
                                .put("value2", a.value2.toDouble())
                                .put("bone", a.bone).put("bone2", a.bone2)
                                .put("prop", a.prop).put("state", a.state)
                                // Written only when it was chosen: a file that never asked for
                                // a shape does not grow a key that means "the default".
                                .apply { if (a.shape.isNotEmpty()) put("shape", a.shape) }
                                .put("rule", a.rule)
                        )
                    }
                    return arr
                }
                val acts = actionJson(r.actions)
                // 「或者」的写法：一支一个对象，权重只在不是 1 的时候写出去（和别的默认值一样，
                // "没选过的东西不长出一个键"）。
                fun altsJson(alts: List<ActionAlt>): JSONArray {
                    val arr = JSONArray()
                    for (a in alts) {
                        arr.put(
                            JSONObject()
                                .apply { if (a.weight != 1) put("weight", a.weight) }
                                .put("then", actionJson(a.actions))
                        )
                    }
                    return arr
                }
                val elseIfs = JSONArray()
                for (e in r.elseIfs) {
                    elseIfs.put(
                        JSONObject()
                            .apply { if (e.conditions.isNotEmpty()) put("if", condJson(e.conditions)) }
                            .apply { if (e.weight != 1) put("weight", e.weight) }
                            .put("then", actionJson(e.actions))
                            .apply { if (e.alts.isNotEmpty()) put("alts", altsJson(e.alts)) }
                    )
                }
                val elses = actionJson(r.elseActions)
                rules.put(
                    JSONObject()
                        .put("on", r.on).put("part", r.part)
                        .put("cooldown", r.cooldown.toDouble()).put("once", r.once)
                        .put("if", condJson(r.conditions)).put("then", acts).put("else", elses)
                        // Only when it was chosen: a rule that is about anything does not grow a
                        // key that says "anything", and a rule with no 或者 does not grow an
                        // empty alternatives list.
                        .apply { if (r.about.isNotEmpty()) put("about", r.about) }
                        .apply { if (r.thenWeight != 1) put("thenWeight", r.thenWeight) }
                        .apply { if (r.alts.isNotEmpty()) put("alts", altsJson(r.alts)) }
                        .apply { if (r.elseIfs.isNotEmpty()) put("elseIfs", elseIfs) }
                        .apply { if (r.elseWeight != 1) put("elseWeight", r.elseWeight) }
                        .apply { if (r.elseAlts.isNotEmpty()) put("elseAlts", altsJson(r.elseAlts)) }
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
    { "id": "blood", "name": "血", "colour": "#B4212B", "viscosity": 0.35, "collides": true },
    { "id": "water", "name": "水", "colour": "#3D8FD1", "viscosity": 0.0, "collides": true },
    { "id": "slime", "name": "史莱姆", "colour": "#5FA83C", "viscosity": 0.8, "collides": true },
    { "id": "ink", "name": "墨", "colour": "#23202E", "viscosity": 0.15, "collides": true }
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

/**
 * A Float as a Double rounded to three decimals, for the file.
 *
 * `0.7f.toDouble()` is 0.699999988079071 and a file full of those is a file nobody reads.
 * Through the rounded Double it comes out 0.7 exactly, and the round trip is lossless for
 * every value the editors can produce -- they all step in quarters and tenths.
 */
private fun round3(v: Float): Double = Math.round(v * 1000f).toDouble() / 1000.0
