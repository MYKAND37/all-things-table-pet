package dev.atp.pet.engine.anim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 一个关键帧：**某一刻，某个值是这么多**。
 *
 * [t] 是动画自己的时间（秒，和 [AnimFrame.seconds] 同一把尺子），[v] 是这个通道的值：
 * **旋转和帧里的角度是同一个单位**（就是骨骼自己的 `rotation`，弧度）、位置是像素、缩放是倍率。
 * 单位这件事曾经错过一次（写成了度，大 57 倍），后果是"打一个关键帧骨头猛地一转，两个关键帧
 * 之间像没有过渡" —— 界面上给人看的度数只在**显示**那一层换（`formatValue`）。
 *
 * [ease] 是**从这一帧到下一帧**这段怎么走 —— 缓动属于"段"，不属于"点"：同一个关键帧，
 * 从它出去的那一段可以慢慢起步，而走进它的那一段是匀速的。所以它挂在段的起点上。
 */
data class AnimKey(
    val t: Float,
    val v: Float,
    val ease: Int = EASE_SMOOTH,
) {
    companion object {
        /** 匀速。Blockbench 的 linear。 */
        const val EASE_LINEAR = 0

        /**
         * 平滑（默认）：两头慢中间快，等于把这一段走成一个 smoothstep。
         *
         * 做默认值是刻意的：这个应用里的宠物是**有物理的**，一段匀速的关节运动和一段有
         * 加减速的运动放在一起，后者看起来才像"它在动"而不是"它在被拖"。想要老实的匀速，
         * 每段都能改成 [EASE_LINEAR]。
         */
        const val EASE_SMOOTH = 1

        /** 阶跃：到下一帧之前一动不动，然后**跳**过去。逐帧动画的老规矩。 */
        const val EASE_STEP = 2

        val EASES = listOf(EASE_LINEAR, EASE_SMOOTH, EASE_STEP)
    }
}

/**
 * 一根骨头的四条通道：旋转（**弧度和帧一致**）、位置（像素，画面上）、缩放（倍率）。
 *
 * 位置和缩放是**只改画面**的（1.23.0）：它们进不了求解器 —— 一个关节被"平移"到别处，
 * 物理上没有意义（那等于瞬移一节骨头，碰撞体重叠、绳子长度全乱）。所以它们画在求解器算完
 * 的姿势**之上**：宠物该掉还是掉、该被抓还是被抓，只是那一节的样子偏一点、大一点。
 * 旋转不一样，它一直就是**交给求解器的目标**（半路被打一下接得回来，正是这个应用的主语）。
 */
data class BoneTrack(
    val rot: List<AnimKey> = emptyList(),
    val x: List<AnimKey> = emptyList(),
    val y: List<AnimKey> = emptyList(),
    val scale: List<AnimKey> = emptyList(),
) {
    /** 这一条通道的那一串关键帧；[channel] 不认识时给空表。 */
    fun keys(channel: Int): List<AnimKey> = when (channel) {
        Timeline.ROTATION -> rot
        Timeline.POSITION_X -> x
        Timeline.POSITION_Y -> y
        Timeline.SCALE -> scale
        else -> emptyList()
    }

    fun withKeys(channel: Int, next: List<AnimKey>): BoneTrack = when (channel) {
        Timeline.ROTATION -> copy(rot = next)
        Timeline.POSITION_X -> copy(x = next)
        Timeline.POSITION_Y -> copy(y = next)
        Timeline.SCALE -> copy(scale = next)
        else -> this
    }

    /** 这条骨头的通道里最后的一个时间点（时长要用它兜底）。 */
    fun lastTime(): Float {
        var last = 0f
        for (list in listOf(rot, x, y, scale)) {
            for (k in list) last = max(last, k.t)
        }
        return last
    }
}

/**
 * 某一刻的一整套样子：骨架要摆的姿势、画面上额外的偏移与缩放、这一帧的图。
 *
 * [angles] 和 `Anim.sample` 给出的是同一个东西（交给求解器的目标角度）；[offsetX]/[offsetY]/
 * [scale] 只有被关键帧写过的骨头才在里面 —— **没写过 = 不动**，而不是"归零"，
 * 后者会让一条只动了手的通道把整只宠物按回原位。
 *
 * [state]/[frame]/[frames] 是照着帧那一半原样带过来的（图永远由帧决定，见 [AnimationSpec]），
 * 所以播放器只需要问一次"这一刻是什么样"。
 */
class TrackSample(
    val angles: Map<String, Float>,
    val offsetX: Map<String, Float>,
    val offsetY: Map<String, Float>,
    val scale: Map<String, Float>,
    val state: String,
    val frame: Int,
    val frames: Int,
)

/**
 * 时间轴上的动画。
 *
 * 和 [Anim] 的关系是"同一个东西的两半"，不是两套实现：
 *
 *  * **没有通道时**（`tracks` 是空的，也就是 1.22.0 及以前的所有动画），这里给的就是
 *    [Anim.sample] 的结果 —— 一帧一帧地插值。老文件因此不需要迁移就照旧播；
 *  * **有通道时**，某根骨头**只要自己有一条通道**，它的旋转就归通道管（通道赢），其余骨头
 *    照旧走帧。[Timeline.bake] 把整段帧一次性写成通道，而且**可证明无损**：帧模型在每一帧
 *    里也是每根骨头各自线性插值，所以"在每个帧起点取一次有效姿势、把这些点连起来"和原来
 *    一模一样（tools/timeline_check.py 拿随机动画逐点比过）。
 *
 * 时长仍然**只有一个来源**：帧的秒数之和（[Anim.duration]）。通道里超过末尾的关键帧会被
 * 夹住 —— 一个"时间轴比动画还长"的状态没有任何界面说得清。
 */
object Timeline {

    /** 通道：旋转、X 偏移、Y 偏移、缩放。界面一次编一条，`channel` 就是"现在在编哪条"。 */
    const val ROTATION = 0
    const val POSITION_X = 1
    const val POSITION_Y = 2
    const val SCALE = 3

    val CHANNELS = listOf(ROTATION, POSITION_X, POSITION_Y, SCALE)

    /** 通道的默认值：没写过的时候它在哪儿。 */
    fun defaultValue(channel: Int): Float = if (channel == SCALE) 1f else 0f

    /**
     * 采样：这一刻的样子。没有通道时就是 [Anim.sample] 那一套。
     *
     * 逐条通道各自插值，骨头与骨头之间互不影响 —— 这正是"给每根骨头单独加关键帧"的意思。
     */
    fun sample(spec: AnimationSpec, seconds: Float): TrackSample? {
        val base = Anim.sample(spec, seconds) ?: return null
        if (spec.tracks.isEmpty()) {
            return TrackSample(
                base.angles, emptyMap(), emptyMap(), emptyMap(),
                base.state, base.frame, base.frames,
            )
        }

        val time = localTime(spec, seconds)
        val angles = LinkedHashMap(base.angles)
        val ox = LinkedHashMap<String, Float>()
        val oy = LinkedHashMap<String, Float>()
        val sc = LinkedHashMap<String, Float>()

        for ((bone, track) in spec.tracks) {
            if (track.rot.isNotEmpty()) angles[bone] = valueAt(track.rot, time)
            if (track.x.isNotEmpty()) ox[bone] = valueAt(track.x, time)
            if (track.y.isNotEmpty()) oy[bone] = valueAt(track.y, time)
            if (track.scale.isNotEmpty()) sc[bone] = valueAt(track.scale, time)
        }
        return TrackSample(angles, ox, oy, sc, base.state, base.frame, base.frames)
    }

    /**
     * 把"墙上经过了多少秒"换成"动画自己的第几秒"：速度倍率与循环都在这里，和 [Anim.sample]
     * 同一套算法，所以通道和帧看到的是同一个时刻。
     */
    fun localTime(spec: AnimationSpec, seconds: Float): Float {
        val total = Anim.duration(spec)
        if (total <= 0f) return 0f
        val raw = max(0f, seconds) * Anim.speedOf(spec)
        return if (spec.loop) raw - total * kotlin.math.floor(raw / total) else min(raw, total)
    }

    /**
     * 一条通道在某一刻的值。
     *
     * 规矩三条，都是"没写的地方不该乱动"：
     *  * 第一个关键帧之前 = 第一个的值（不是默认值：一段从第 2 秒才开始的挥手，不该在第 0 秒
     *    把这只手先扳回 0 度）；
     *  * 最后一个之后 = 最后一个的值；
     *  * 两个关键帧之间按起点那一个的 [AnimKey.ease] 走。
     */
    fun valueAt(keys: List<AnimKey>, time: Float): Float {
        if (keys.isEmpty()) return 0f
        if (keys.size == 1 || time <= keys[0].t) return keys[0].v
        val last = keys[keys.size - 1]
        if (time >= last.t) return last.v
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            if (time < a.t || time > b.t) continue
            val span = b.t - a.t
            if (span <= 0f) return b.v
            return a.v + (b.v - a.v) * ease((time - a.t) / span, a.ease)
        }
        return last.v
    }

    /** 0..1 的进度按缓动掰一下。 */
    fun ease(f: Float, kind: Int): Float {
        val t = f.coerceIn(0f, 1f)
        return when (kind) {
            AnimKey.EASE_LINEAR -> t
            AnimKey.EASE_STEP -> if (t >= 1f) 1f else 0f
            // smoothstep：两头导数为 0，所以"起步"和"停下"是看得出来的。
            else -> t * t * (3f - 2f * t)
        }
    }

    /**
     * 在某一刻插一个关键帧（同一时刻已有一个就改它），返回新的那串 —— 按时间排好。
     *
     * 排序在这儿做、也只在这儿做：别处（界面、存档、采样）都假定"关键帧是按时间排的"，
     * 而这个假定只要有一处不成立，采样就会静默地给出错的值。
     */
    fun withKey(keys: List<AnimKey>, t: Float, v: Float, ease: Int = AnimKey.EASE_SMOOTH): List<AnimKey> {
        val out = keys.filter { abs(it.t - t) > TIME_EPSILON }.toMutableList()
        out.add(AnimKey(t, v, ease))
        out.sortBy { it.t }
        return out
    }

    /** 挪一个关键帧：时间变、值也可能变（菱形是往两个方向拖的）。 */
    fun moved(keys: List<AnimKey>, index: Int, t: Float, v: Float): List<AnimKey> {
        if (index !in keys.indices) return keys
        return withKey(keys.filterIndexed { i, _ -> i != index }, t, v, keys[index].ease)
    }

    fun removed(keys: List<AnimKey>, index: Int): List<AnimKey> =
        if (index !in keys.indices) keys else keys.filterIndexed { i, _ -> i != index }

    /**
     * 把一帧一帧的动画**烘**成通道：每根骨头、每个"提到过它的帧"一个关键帧。
     *
     * 烘完再采样和烘之前**逐点相同**（`tools/timeline_check.py` 拿 60 段随机动画 × 41 个时刻
     * 逐点比过，最大差 0.000000）。要精确，就得照着帧模型真正的规矩来 —— 而 1.25.0 之后那条
     * 规矩简单了：**每根骨头各自在"提到过它的帧"之间线性插值，两头保持**（见 [Anim.poseAt]）。
     * 所以烘培就是"在这些帧的起点各打一个关键帧，全部匀速"，循环时再在末尾补一个"回到第一帧"
     * 的关键帧把圈闭上。
     *
     * 上一版这里要用**阶跃**去凑"只写一侧时保持不动"，于是烘完的动作会在关键帧处**一下子跳过去**
     * —— 用户报的「部位的关键帧像是在瞬移」「还没到下一个关键帧动作就执行完了」就是它。帧模型
     * 自己平滑之后，阶跃这一半就没有存在的理由了。
     *
     * 已经有过通道的骨头不重烘：用户手调过的那几条通道，不该被一次"转换"覆盖掉。
     */
    fun bake(spec: AnimationSpec): Map<String, BoneTrack> {
        val frames = spec.frames
        if (frames.isEmpty()) return spec.tracks
        val out = LinkedHashMap(spec.tracks)
        val starts = ArrayList<Float>(frames.size)
        var acc = 0f
        for (f in frames) {
            starts.add(acc)
            acc += Anim.frameSeconds(f)
        }
        val total = acc

        val names = LinkedHashSet<String>()
        for (f in frames) names.addAll(f.angles.keys)

        for (name in names) {
            val existing = out[name]?.rot
            if (!existing.isNullOrEmpty()) continue
            var keys = existing ?: emptyList()
            val mentions = frames.indices.filter { frames[it].angles.containsKey(name) }
            for (m in mentions) {
                keys = withKey(keys, starts[m], frames[m].angles[name] ?: 0f, AnimKey.EASE_LINEAR)
            }
            // 循环：末尾补一个"回到第一个提到它的帧的值"，那一圈才是闭合的（和播放时一样）。
            if (spec.loop && mentions.isNotEmpty()) {
                keys = withKey(
                    keys, total, frames[mentions.first()].angles[name] ?: 0f, AnimKey.EASE_LINEAR,
                )
            }
            out[name] = (out[name] ?: BoneTrack()).copy(rot = keys)
        }
        return out
    }

    /** 这一段动画有没有被烘过（有通道的骨头数）——界面用它说"现在是谁在管"。 */
    fun trackedBones(spec: AnimationSpec): Int = spec.tracks.count { it.value.rot.isNotEmpty() }

    /**
     * 在 [from] 这一刻**插入**了一段 [delta] 秒：所有在这一刻之后的关键帧整体往后挪。
     *
     * 没有这一步的话，往中间插一帧会让后面所有的关键帧"对不上图"—— 图往后走了一格，通道
     * 却留在原地。挪完全部夹在新的末尾以内（见 [maxTime]）：一个"时间轴比动画还长"的状态
     * 没有任何界面说得清。
     */
    fun shifted(
        tracks: Map<String, BoneTrack>,
        from: Float,
        delta: Float,
        maxT: Float,
    ): Map<String, BoneTrack> {
        if (tracks.isEmpty() || delta == 0f) return tracks
        val out = LinkedHashMap<String, BoneTrack>()
        for ((bone, track) in tracks) {
            out[bone] = BoneTrack(
                rot = shiftKeys(track.rot, from, delta, maxT),
                x = shiftKeys(track.x, from, delta, maxT),
                y = shiftKeys(track.y, from, delta, maxT),
                scale = shiftKeys(track.scale, from, delta, maxT),
            )
        }
        return out
    }

    private fun shiftKeys(keys: List<AnimKey>, from: Float, delta: Float, maxT: Float): List<AnimKey> {
        if (keys.isEmpty()) return keys
        val out = ArrayList<AnimKey>(keys.size)
        for (k in keys) {
            val t = if (k.t >= from) k.t + delta else k.t
            out.add(AnimKey(t.coerceIn(0f, max(0f, maxT)), k.v, k.ease))
        }
        return out.sortedBy { it.t }
    }

    /**
     * **删掉** [from] 到 [to] 这一段：区间里的关键帧跟着没了，后面的整体往前挪。
     *
     * 区间里的关键帧是**删**而不是"挤到边界上"：挤过去会在同一个时刻堆出两个关键帧，而
     * "两个时刻相同的关键帧"这种东西采样时只有一个能被用到 —— 用户看到的是"删了一帧之后
     * 多出来一个删不掉的菱形"。
     */
    fun dropped(
        tracks: Map<String, BoneTrack>,
        from: Float,
        to: Float,
        maxT: Float,
    ): Map<String, BoneTrack> {
        if (tracks.isEmpty() || to <= from) return tracks
        val out = LinkedHashMap<String, BoneTrack>()
        for ((bone, track) in tracks) {
            out[bone] = BoneTrack(
                rot = dropKeys(track.rot, from, to, maxT),
                x = dropKeys(track.x, from, to, maxT),
                y = dropKeys(track.y, from, to, maxT),
                scale = dropKeys(track.scale, from, to, maxT),
            )
        }
        return out
    }

    private fun dropKeys(keys: List<AnimKey>, from: Float, to: Float, maxT: Float): List<AnimKey> {
        if (keys.isEmpty()) return keys
        val out = ArrayList<AnimKey>(keys.size)
        for (k in keys) {
            when {
                k.t < from -> out.add(k)
                k.t >= to -> out.add(AnimKey((k.t - (to - from)).coerceIn(0f, max(0f, maxT)), k.v, k.ease))
            }
        }
        return out.sortedBy { it.t }
    }

    /**
     * 一个关键帧最多能拖到哪儿：动画的末尾。
     *
     * 通道比动画长是没有意义的状态（采样永远走不到那儿，界面也没有一条线能画它），所以
     * 拖到头就停在头上，而不是让它拖出去然后悄悄不生效。
     */
    fun maxTime(spec: AnimationSpec): Float = max(Anim.MIN_FRAME, Anim.duration(spec))

    /**
     * 这是第几遍（0 起）。循环动画每绕一圈 +1，不循环的永远是 0。
     *
     * 姿态锚点绑的规则"每经过一次响一次"，靠的就是它：同一个 pass 里响过的锚点记在宿主那儿，
     * pass 一变就清空。用"帧号变了"来判断是不行的 —— 一帧的动画循环时帧号一直是 0，
     * 那条规则就再也响不了；而用"秒数取模"又会在浮点边界上抖。整遍数是个整数，不抖。
     */
    fun passIndex(spec: AnimationSpec, seconds: Float): Int {
        // 不循环的动画一辈子就一遍：它走完就停了，没有"第二遍"可言。
        if (!spec.loop) return 0
        val total = Anim.duration(spec)
        if (total <= 0f) return 0
        return max(0, kotlin.math.floor(max(0f, seconds) * Anim.speedOf(spec) / total).toInt())
    }

    /** 两个关键帧算"同一时刻"的容差，和界面那 0.01 秒的吸附对齐。 */
    const val TIME_EPSILON = 0.005f
}
