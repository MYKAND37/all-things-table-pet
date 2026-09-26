package dev.atp.pet.engine.anim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 时间轴那一行的几何：**时间和像素怎么换、值和高度怎么换、手指碰到了哪个关键帧**。
 *
 * 单独放在引擎里（而不是画在那个 View 里）有两个理由，而且都不是洁癖：
 *
 *  * 它是**纯数学**，所以能在本地被镜像和测试（`tools/timeline_check.py`）。这一版最容易
 *    悄悄错的地方正是这里 —— "菱形画在哪儿"和"拖出来的值是几"必须严格互逆，错了看起来
 *    只是"手感怪"，不会报任何错；
 *  * View 那边只剩"画"和"把事件转成这个对象",于是那段代码短到能一眼看完。
 *
 * 单位一律是**像素**（调用方量好了再传进来），所以这里不需要 density，也就不需要 Android。
 */
object TimelineLayout {

    /** 一行：名字列的右边就是轨道；轨道横向铺满 [duration] 秒。 */
    class Lane(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val duration: Float,
    )

    fun timeToX(lane: Lane, t: Float): Float {
        if (lane.duration <= 0f || lane.width <= 0f) return lane.left
        return lane.left + (t / lane.duration).coerceIn(0f, 1f) * lane.width
    }

    fun xToTime(lane: Lane, x: Float): Float {
        if (lane.width <= 0f) return 0f
        return ((x - lane.left) / lane.width).coerceIn(0f, 1f) * lane.duration
    }

    /**
     * 这一行的取值范围（下界, 上界），给"值画在高度上的哪个位置"用。
     *
     * 三条规矩：
     *  * 默认值永远在范围里（一条只有 0 度的通道，菱形应该画在中间，不是贴在边上）；
     *  * 每类通道有一个**最小跨度**（旋转 60°、位置 80px、缩放 0.6）：一条常量通道如果按
     *    "数据范围"来画，跨度为 0，除零就来了；
     *  * 上下各留 15% 边距，菱形不会贴在行的边线上。
     */
    fun rangeOf(keys: List<AnimKey>, channel: Int): FloatArray {
        val def = Timeline.defaultValue(channel)
        var lo = def
        var hi = def
        for (k in keys) {
            lo = min(lo, k.v)
            hi = max(hi, k.v)
        }
        val minSpan = when (channel) {
            Timeline.ROTATION -> MIN_ROT_SPAN
            Timeline.SCALE -> MIN_SCALE_SPAN
            else -> MIN_OFFSET_SPAN
        }
        if (hi - lo < minSpan) {
            val mid = (lo + hi) / 2f
            lo = mid - minSpan / 2f
            hi = mid + minSpan / 2f
        }
        val pad = (hi - lo) * PAD_RATIO
        return floatArrayOf(lo - pad, hi + pad)
    }

    /** 值 → 行内的高度（**上大下小**：值越大越靠上，这是所有曲线编辑器的方向）。 */
    fun valueToY(v: Float, lo: Float, hi: Float, lane: Lane): Float {
        if (hi - lo <= 0f || lane.height <= 0f) return lane.top + lane.height / 2f
        val f = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        return lane.top + lane.height - f * lane.height
    }

    fun yToValue(y: Float, lo: Float, hi: Float, lane: Lane): Float {
        if (lane.height <= 0f) return lo
        val f = (1f - (y - lane.top) / lane.height).coerceIn(0f, 1f)
        return lo + f * (hi - lo)
    }

    /**
     * 手指下面有没有关键帧：有就给它的下标，没有给 -1。
     *
     * 命中半径按像素给（菱形本身只有几个像素，手指有十几毫米宽），比的是**最近的**那一个 ——
     * 两个关键帧挨在一起时，选近的那个才是用户想要的。
     */
    fun hitKey(
        keys: List<AnimKey>,
        lane: Lane,
        lo: Float,
        hi: Float,
        px: Float,
        py: Float,
        radiusPx: Float,
    ): Int {
        var best = -1
        var bestD = radiusPx
        for ((i, k) in keys.withIndex()) {
            val dx = timeToX(lane, k.t) - px
            val dy = valueToY(k.v, lo, hi, lane) - py
            val d = kotlin.math.hypot(dx, dy)
            if (d <= bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /**
     * 手指在这一行上碰到了哪个关键帧：**只看横向**。
     *
     * 关键帧在时间轴上是一条带子上的点，值不是在这里调的（旋转的值来自"你把这一节摆成什么样"），
     * 所以命中只需要问"离哪个最近"，而且整行的高度都算数 —— 用户不用正好按在那个小菱形上。
     */
    fun hitKeyInRow(keys: List<AnimKey>, lane: Lane, px: Float, radiusPx: Float): Int {
        var best = -1
        var bestD = radiusPx
        for ((i, k) in keys.withIndex()) {
            val d = abs(timeToX(lane, k.t) - px)
            if (d <= bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /**
     * 把一行里的一个横向位移换算成**秒**（1.28.0）。
     *
     * 「左右拖动轴上的帧方块，控制这一帧到下一帧的时间」：整条轨道铺满 [duration] 秒，
     * 所以拖过轨道的几分之几，就是改了几分之几的时长。夹在上下限里由调用方做（那是界面的
     * 尺子，不是几何）。
     */
    fun secondsDelta(lane: Lane, dx: Float): Float {
        if (lane.width <= 0f) return 0f
        return dx / lane.width * lane.duration
    }

    /** 拖出来的时间吸附到 0.01 秒的格子上，并夹在 [0, duration] 里。 */
    fun snapTime(t: Float, duration: Float): Float {
        val clamped = t.coerceIn(0f, max(0f, duration))
        return Math.round(clamped / TIME_GRID) * TIME_GRID
    }

    /**
     * 离 [t] 最近的**帧边界**，比 [tolerance] 秒近就吸过去 —— 否则原样返回。
     *
     * 帧边界是这张图上唯一"有意义"的线（图就是在那儿换的），所以关键帧和播放头都应该容易
     * 落在它上面；但**只是容易，不是强制**：这一版的全部意义就是让每根骨头有自己的时间点。
     */
    fun snapToFrame(t: Float, frameStarts: List<Float>, tolerance: Float): Float {
        var best = t
        var bestD = tolerance
        for (s in frameStarts) {
            val d = abs(s - t)
            if (d < bestD) {
                bestD = d
                best = s
            }
        }
        return best
    }

    /** 每一帧起点的时间（时长只有这一个来源：帧的秒数之和）。 */
    fun frameStarts(spec: AnimationSpec): List<Float> {
        val out = ArrayList<Float>(spec.frames.size + 1)
        var acc = 0f
        for (f in spec.frames) {
            out.add(acc)
            acc += Anim.frameSeconds(f)
        }
        return out
    }

    /** 播放头离哪一帧最近（0 起）——"现在演到第几帧"用帧序号说，比秒数好懂。 */
    fun frameAt(spec: AnimationSpec, t: Float): Int {
        val starts = frameStarts(spec)
        var best = 0
        for (i in starts.indices) {
            if (t + TIME_GRID >= starts[i]) best = i
        }
        return best
    }

    /** 时间轴上的格子：0.01 秒。拖动吸附用它，也定义了两个关键帧算不算"同一时刻"。 */
    const val TIME_GRID = 0.01f

    /**
     * 旋转通道最少画 1 弧度的跨度（≈57°），位置最少 80 像素，缩放最少 0.6 倍。
     *
     * 旋转的单位是**弧度**（和帧里的角度、求解器的目标同一个单位，见 MainActivity.studioKeyValue）
     * —— 老代码这里写 60，是把它当度数了，于是一条常量通道会在行里被压成一条线。
     */
    const val MIN_ROT_SPAN = 1f
    const val MIN_OFFSET_SPAN = 80f
    const val MIN_SCALE_SPAN = 0.6f

    /** 取值范围上下各留这么多。 */
    const val PAD_RATIO = 0.15f

    /** 名字列：左边写骨头名的那一栏有多宽（像素，宿主按 density 算好传进来）。 */
    const val NAME_COLUMN_DP = 76f

    /** 手指命中菱形的半径（dp）。菱形本身只有 5dp 宽，手指有十几毫米。 */
    const val HIT_RADIUS_DP = 16f

    /** 播放头吸附到帧边界的容差（秒）。 */
    const val FRAME_SNAP = 0.06f
}
