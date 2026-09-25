package dev.atp.pet.engine.anim

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 一帧。
 *
 * 一个动画的每一帧说两件事，而且**两件都是可选的** —— 这正是"演算"和"绘制"能在同一个模型里
 * 共存的原因：
 *
 *  * [angles]：这一帧的**姿势**（骨骼名 → 角度）。两帧之间的角度是**插值**过去的，所以
 *    「把手从 A 摆到 B，中间自己走」不需要画任何图 —— 用户只给两个端点；
 *  * [state]：这一帧**画哪一套图**。它不是"一张图"，而是一个**开关的名字**：给某根骨头加过
 *    「帧2」这套图（部位 → ＋加一张状态「帧2」的图），这一帧把它打开，那根骨头就换成那张画。
 *    于是"逐帧绘制"用的是现成的那条路，而"半演算"是免费的：图换了，骨骼照样按 [angles] 动。
 *
 *    **可以同时开好几个**（1.22.0）：几个开关用 `+` 连起来，`"帧2+出汗"` 就是"这一帧既画
 *    帧2 那套、也画手出汗那张"。一帧里要同时换两个部位的图，一个名字装不下。
 *
 * [seconds] 是这一帧**走多久**（不是"停多久"）：0.4 表示用 0.4 秒从这一帧过渡到下一帧。
 * 一帧动画（只有一帧）就是"保持这个样子"。
 */
data class AnimFrame(
    val angles: Map<String, Float> = emptyMap(),
    val state: String = "",
    val seconds: Float = Anim.DEFAULT_FRAME_SECONDS,
)

/**
 * 一段动画：一串帧、一个速度、要不要循环。
 *
 * [speed] 是**倍率**：2 是两倍快，0.5 是慢放。它是这里的字段而不是"每一帧的秒数乘一下"，
 * 因为用户调的是"这段动作太快了"，不是"第 3 帧应该 0.23 秒" —— 调速度不该改坏已经摆好的帧。
 *
 * 数据是**按骨骼套**存的（`animations.json` 和 `poses.json` 一个地方），因为帧里的角度是
 * 骨头名字，换一套身体那些名字就不存在了。
 */
data class AnimationSpec(
    val id: String,
    val name: String,
    val frames: List<AnimFrame> = emptyList(),
    val speed: Float = 1f,
    val loop: Boolean = true,
    /**
     * 每根骨头自己的关键帧通道（1.23.0）：旋转、X/Y 偏移、缩放。
     *
     * **空的时候一切都和 1.22.0 一样**（帧一帧一帧地插值），所以老文件不用迁移；一旦某根
     * 骨头有了通道，它的旋转就归通道管（[Timeline.sample]）。[Timeline.bake] 把整段帧写成
     * 通道，而且逐点无损。
     */
    val tracks: Map<String, BoneTrack> = emptyMap(),
)

/**
 * 某一时刻这一套动画的样子。
 *
 * [frame] 是**第几帧**（从 0 数），给界面显示「第 3/8 帧」用；[angles] 已经插值过；
 * [state] 是当前这一帧的开关。
 */
class AnimSample(
    val angles: Map<String, Float>,
    val state: String,
    val frame: Int,
    val frames: Int,
)

/**
 * 动画的播放位置：给定**真实经过的秒数**，算出这一刻该摆什么姿势、画哪一套图。
 *
 * 纯函数，没有时钟、没有 Android —— 所以它能在本地被镜像和测试（tools/anim_check.py），
 * 而播放器那边只剩"把 dt 加起来"这一件事（那是没法测的那一半，所以它越薄越好）。
 */
object Anim {
    /**
     * 一帧最短这么长。
     *
     * 0 秒的帧在数学上是"直接跳过去"，在插值里却是"除以零" —— 而且从界面上看，0 秒的帧
     * 和删掉它没有区别，所以这里夹住而不是崩掉或者跳过。界面的最小档是 0.1 秒。
     */
    const val MIN_FRAME = 0.05f

    /**
     * 新抓的一帧默认走多久。
     *
     * 是这里的一个常数而不是各写各的 `0.4f`：抓帧的地方（工作台、测试场那张动作表）都要一个
     * 起点，而两个地方各自写一个数字，就会出现"同一件事在两个界面里默认不一样"。
     */
    const val DEFAULT_FRAME_SECONDS = 0.4f

    /** 速度的上下限。0 会让时间停住（等于卡在第一帧），负数是倒放 —— 都不是这一版要的。 */
    const val MIN_SPEED = 0.1f
    const val MAX_SPEED = 4f

    fun frameSeconds(frame: AnimFrame): Float = max(MIN_FRAME, frame.seconds)

    /**
     * 一帧的 state 里写着的那几个开关，拆成一个个名字。
     *
     * 分隔符是 `+`，而这个符号**进不了状态 id**：状态 id 走 `RigEdit.sanitise`，那里把 `+`
     * 换成 `_`（和空格、`/` 一样的待遇）。所以 `"a+b"` 永远是"两个开关 a 和 b"，不会是
     * "一个叫 a+b 的开关" —— 拆分的歧义在源头就被掐掉了。
     *
     * 空字符串（这一帧不换图）拆出来是空表，不是"一个叫空字符串的开关"。
     */
    fun statesOf(state: String): List<String> =
        state.split('+').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 反过来：把几个开关拼成帧里存的那一个字符串。
     *
     * 去重但**保序**：先点「帧2」再点「出汗」和反过来是同一件事，而名字在原处比字母序好读。
     * 空表拼回空字符串，所以"什么都不开"和"没写过"是同一个值 —— 帧的 state 只有一个空串。
     */
    fun joinStates(states: Collection<String>): String =
        states.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("+")

    /**
     * 第 [index] 帧**开始那一刻**的姿势，或者空表（没有这一帧）。
     *
     * 这就是编辑器里"选中第 3 帧"该显示的样子，而它不等于"第 3 帧里写的角度"：[blend] 那条
     * 规矩（没写到的一侧保持另一侧的值）意味着**下一帧新引入的那根骨头，在这一帧里已经是
     * 下一帧的值了**。编辑器必须照播放时的规矩显示，否则用户看到的是一个引擎演不出来的姿势
     * —— 摆好了、存下去、一播却不一样，是最难查的那种错。
     *
     * 所以这里不是"另算一套"，就是把 `sample` 在帧起点那一瞬的结果取出来：`blend(这一帧,
     * 下一帧, 0)`。循环时下一帧是第一帧，和播放时一模一样。
     */
    fun framePose(a: AnimationSpec, index: Int): Map<String, Float> {
        val here = a.frames.getOrNull(index) ?: return emptyMap()
        val next = a.frames.getOrNull(index + 1)
            ?: if (a.loop) a.frames.firstOrNull() else null
        return if (next == null || next === here) here.angles else blend(here, next, 0f)
    }

    /** 第 [index] 帧在动画自己的时间里从第几秒开始（给"跳到这一帧"用）。 */
    fun startSeconds(a: AnimationSpec, index: Int): Float {
        var sum = 0f
        for (i in 0 until index.coerceIn(0, a.frames.size)) sum += frameSeconds(a.frames[i])
        return sum
    }

    /** 整套动画走完一遍要多久，**单位是动画自己的时间**（不含速度倍率）。 */
    fun duration(a: AnimationSpec): Float {
        var sum = 0f
        for (f in a.frames) sum += frameSeconds(f)
        return sum
    }

    /** 同样的东西，换成"挂在墙上"的秒数 —— 速度 2 就是一半的时间。界面显示这个。 */
    fun realDuration(a: AnimationSpec): Float = duration(a) / speedOf(a)

    fun speedOf(a: AnimationSpec): Float = a.speed.coerceIn(MIN_SPEED, MAX_SPEED)

    /**
     * 这一刻的样子，或者 null（这套动画一帧都没有）。
     *
     * [seconds] 是**真实经过的秒数**：速度倍率在这里面算，所以"2 倍速"这件事有一个能被测试
     * 的地方，而不是散在播放器里的一句乘法。
     *
     * 循环时，最后一帧会**插值回第一帧** —— 走路的循环因此是闭合的（最后一帧到第一帧之间
     * 不是一下跳过去，而是接着走完那一段）。不循环时时间在末尾夹住：停在最后一帧上。
     */
    fun sample(a: AnimationSpec, seconds: Float): AnimSample? {
        val frames = a.frames
        if (frames.isEmpty()) return null
        val total = duration(a)
        val raw = max(0f, seconds) * speedOf(a)
        val t = if (a.loop) raw - total * floor(raw / total) else min(raw, total)

        var acc = 0f
        for (i in frames.indices) {
            val d = frameSeconds(frames[i])
            val last = i == frames.lastIndex
            // `t < acc + d` 落在帧内；最后一帧兜住 total（浮点加起来可能差一点点）。
            if (t < acc + d || last) {
                val f = if (d <= 0f) 0f else ((t - acc) / d).coerceIn(0f, 1f)
                val here = frames[i]
                val next = frames.getOrNull(i + 1) ?: if (a.loop) frames[0] else here
                return AnimSample(blend(here, next, f), here.state, i, frames.size)
            }
            acc += d
        }
        // 走不到这里：最后一帧一定兜住。写出来是为了让编译器也看得见。
        val only = frames.last()
        return AnimSample(only.angles, only.state, frames.lastIndex, frames.size)
    }

    /**
     * 两帧之间插值：每一根骨头各自算，而且**没有写到的那一侧保持另一侧的值**。
     *
     * 那不是顺手，是刻意的：一个只动手臂的两帧动画，如果"帧里没提腿"就当成腿是 0 度，
     * 那第二帧一到，整只宠物的腿会瞬间弹回站姿 —— 用户看到的是"动画把姿势弄坏了"。
     * 只写要动的那几节，是写动画最自然的方式。
     */
    private fun blend(a: AnimFrame, b: AnimFrame, f: Float): Map<String, Float> {
        if (a.angles.isEmpty() && b.angles.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Float>()
        for (name in a.angles.keys + b.angles.keys) {
            val from = a.angles[name] ?: b.angles[name] ?: 0f
            val to = b.angles[name] ?: a.angles[name] ?: 0f
            out[name] = from + (to - from) * f
        }
        return out
    }
}
