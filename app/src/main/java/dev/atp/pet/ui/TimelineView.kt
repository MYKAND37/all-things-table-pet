package dev.atp.pet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.atp.pet.R
import dev.atp.pet.engine.anim.Anim
import dev.atp.pet.engine.anim.AnimKey
import dev.atp.pet.engine.anim.AnimationSpec
import dev.atp.pet.engine.anim.BoneTrack
import dev.atp.pet.engine.anim.Timeline
import dev.atp.pet.engine.anim.TimelineLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 时间轴（1.23.0）：上面是标尺和播放头，下面一行一个通道，菱形就是关键帧。
 *
 * 三条它**不**做的事，都是刻意的：
 *
 *  * 它不存数据。宿主拿着 [AnimationSpec]，这里只是把 `tracks` 画出来、把手指的动作翻译成
 *    "第几根骨头的第几个关键帧挪到了 (t, v)"；
 *  * 它不算几何。[TimelineLayout] 是纯数学、能被本地镜像测试，这里只负责"画"和"命中"；
 *  * 它不做时间轴以外的编辑：加帧、换图、播放都在工作台那条底栏上。这一版最容易出的错是
 *    "同一个东西有两个地方能改"，所以能不加的地方一个都不加。
 *
 * 于是这个文件只剩下三段：量多高、画一遍、把手指分派给三个目标（标尺 / 图那一行 / 菱形）。
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** 有通道的骨头（加上当前选中的那一根），按这个顺序一行一行画。 */
    var bones: List<String> = emptyList()
        private set
    private var tracks: Map<String, BoneTrack> = emptyMap()
    private var spec: AnimationSpec? = null

    /** 现在在编哪条通道（旋转 / 位置X / 位置Y / 缩放）。一次只编一条，手机屏幕放不下四条。 */
    var channel: Int = Timeline.ROTATION
        private set

    /** 播放头在哪一秒。 */
    var playhead: Float = 0f
        private set

    var selectedBone: String? = null
        private set
    var selectedFrame: Int = 0
        private set

    /** 选中的是当前骨头的第几个关键帧，-1 = 没选。 */
    var selectedKey: Int = -1
        private set

    var onScrub: ((Float) -> Unit)? = null

    /**
     * 一个菱形被拖到了 (t, v)。[done] = 手指抬起来了 —— 拖动期间界面要立刻跟着动，但**落盘
     * 只在抬手那一次**（一秒几十次写文件是拿电池换一个没人看得见的中间状态）。
     */
    var onKeyMoved: ((String, Int, Float, Float, Boolean) -> Unit)? = null
    var onKeyPicked: ((String, Int) -> Unit)? = null
    var onFramePicked: ((Int) -> Unit)? = null
    var onBonePicked: ((String) -> Unit)? = null

    // ── 输入 ───────────────────────────────────────────────────────────────

    /**
     * 一份新数据：动画本身、要显示哪几行的骨头、编哪条通道。
     *
     * [keepRows] 是"选中的那根骨头即使没有通道也给它一行"：没有这一条，用户永远打不下第一个
     * 关键帧（没有行就没有地方放菱形）。
     */
    fun setData(spec: AnimationSpec, bones: List<String>, channel: Int) {
        this.spec = spec
        this.tracks = spec.tracks
        this.bones = bones
        this.channel = channel
        requestLayout()
        invalidate()
    }

    fun setPlayhead(t: Float) {
        playhead = t
        invalidate()
    }

    fun setSelection(bone: String?, key: Int, frame: Int) {
        selectedBone = bone
        selectedKey = key
        selectedFrame = frame
        invalidate()
    }

    private fun laneOf(row: Int): TimelineLayout.Lane {
        val top = dp(RULER_DP) + dp(ART_ROW_DP) + row * dp(ROW_DP)
        val left = dp(TimelineLayout.NAME_COLUMN_DP)
        return TimelineLayout.Lane(
            left, top, max(1f, width - left - dp(RIGHT_PAD_DP)), dp(ROW_DP), duration(),
        )
    }

    private fun artLane(): TimelineLayout.Lane {
        val left = dp(TimelineLayout.NAME_COLUMN_DP)
        return TimelineLayout.Lane(
            left, dp(RULER_DP), max(1f, width - left - dp(RIGHT_PAD_DP)), dp(ART_ROW_DP), duration(),
        )
    }

    private fun duration(): Float = max(0.01f, spec?.let { Timeline.maxTime(it) } ?: 0.01f)

    private fun rowAt(y: Float): Int {
        val first = dp(RULER_DP) + dp(ART_ROW_DP)
        if (y < first) return -1
        val row = ((y - first) / dp(ROW_DP)).toInt()
        return if (row in bones.indices) row else -1
    }

    private fun keysOf(bone: String): List<AnimKey> =
        tracks[bone]?.keys(channel) ?: emptyList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // 高度只由"有几行"决定：行数是内容，不是布局参数 —— 一只手和十九根骨头的时间轴
        // 不该一样高，而外面那个 ScrollView 负责更长的情况。
        // dp() 给的是像素的浮点数，而 setMeasuredDimension 要的是整数 —— 这里向上取整，
        // 最后一行不会因为少了半个像素被切掉。
        val h = dp(RULER_DP) + dp(ART_ROW_DP) + bones.size * dp(ROW_DP) + dp(BOTTOM_PAD_DP)
        setMeasuredDimension(w, Math.ceil(h.toDouble()).toInt())
    }

    // ── 画 ─────────────────────────────────────────────────────────────────

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.density
        color = 0xCC171528.toInt()
    }
    private val tinyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * resources.displayMetrics.density
        color = 0x99171528.toInt()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = 0x22171528
    }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val rowHotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33C9B6FF }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6C4CE0.toInt() }
    private val keyHotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF171528.toInt() }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0x886C4CE0.toInt()
    }
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0xFFB4212B.toInt()
    }
    private val playHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB4212B.toInt() }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33B4212B }
    private val artHotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66B4212B }
    private val diamond = Path()
    private val scratch = RectF()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        if (width <= 0) return

        drawRuler(canvas)
        drawArtRow(canvas, s)
        for (row in bones.indices) drawBoneRow(canvas, row)
        drawPlayhead(canvas)
    }

    /** 标尺：一条横线、几根刻度、几个秒数。刻度间隔按总长挑，永远是 0.1/0.25/0.5/1/2/5。 */
    private fun drawRuler(canvas: Canvas) {
        val lane = TimelineLayout.Lane(
            dp(TimelineLayout.NAME_COLUMN_DP), 0f,
            max(1f, width - dp(TimelineLayout.NAME_COLUMN_DP) - dp(RIGHT_PAD_DP)), dp(RULER_DP),
            duration(),
        )
        canvas.drawLine(lane.left, lane.height, lane.left + lane.width, lane.height, linePaint)
        val step = rulerStep(duration())
        var t = 0f
        while (t <= duration() + 1e-4f) {
            val x = TimelineLayout.timeToX(lane, t)
            canvas.drawLine(x, lane.height - dp(4f), x, lane.height, linePaint)
            canvas.drawText(trimSeconds(t), x + dp(2f), lane.height - dp(6f), tinyPaint)
            t += step
        }
        canvas.drawText(context.getString(R.string.timeline_seconds), 2f, dp(12f), tinyPaint)
    }

    /** 图那一行：一帧一块，宽度就是这一帧走多久。点一块 = 选中那一帧。 */
    private fun drawArtRow(canvas: Canvas, s: AnimationSpec) {
        val lane = artLane()
        canvas.drawRect(lane.left, lane.top, lane.left + lane.width, lane.top + lane.height, rowPaint)
        canvas.drawText(
            context.getString(R.string.timeline_art_row),
            2f, lane.top + lane.height * 0.7f, tinyPaint,
        )
        var acc = 0f
        for ((i, f) in s.frames.withIndex()) {
            val here = i == selectedFrame
            val left = TimelineLayout.timeToX(lane, acc)
            val right = TimelineLayout.timeToX(lane, acc + Anim.frameSeconds(f))
            scratch.set(left + 1f, lane.top + 2f, max(left + 2f, right - 1f), lane.top + lane.height - 2f)
            canvas.drawRoundRect(scratch, dp(3f), dp(3f), if (here) artHotPaint else artPaint)
            val text = (i + 1).toString() + if (f.state.isEmpty()) "" else " " + f.state
            if (scratch.width() > dp(18f)) {
                canvas.drawText(text, scratch.left + dp(3f), lane.top + lane.height * 0.7f, tinyPaint)
            }
            acc += Anim.frameSeconds(f)
        }
    }

    private fun drawBoneRow(canvas: Canvas, row: Int) {
        val bone = bones[row]
        val lane = laneOf(row)
        val hot = bone == selectedBone
        canvas.drawRect(lane.left, lane.top, lane.left + lane.width, lane.top + lane.height,
            if (hot) rowHotPaint else rowPaint)
        canvas.drawText(shortName(bone), 2f, lane.top + lane.height * 0.68f, labelPaint)

        val keys = keysOf(bone)
        val range = TimelineLayout.rangeOf(keys, channel)
        // 先把相邻关键帧连起来：两点之间就是插值走的那条路，一眼看得出快慢。
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            canvas.drawLine(
                TimelineLayout.timeToX(lane, a.t), TimelineLayout.valueToY(a.v, range[0], range[1], lane),
                TimelineLayout.timeToX(lane, b.t), TimelineLayout.valueToY(b.v, range[0], range[1], lane),
                curvePaint,
            )
        }
        for ((i, k) in keys.withIndex()) {
            val x = TimelineLayout.timeToX(lane, k.t)
            val y = TimelineLayout.valueToY(k.v, range[0], range[1], lane)
            // 菱形：一个转了 45 度的方块。用 Path 而不是 canvas.rotate —— 转画布要配对 save，
            // 而这里根本没有那个必要。
            val r = dp(if (hot && i == selectedKey) 6f else 4.5f)
            diamond.reset()
            diamond.moveTo(x, y - r)
            diamond.lineTo(x + r, y)
            diamond.lineTo(x, y + r)
            diamond.lineTo(x - r, y)
            diamond.close()
            canvas.drawPath(diamond, if (hot && i == selectedKey) keyHotPaint else keyPaint)
            // 阶跃的关键帧画成方的一角：一眼看得出"这里不是斜坡，是跳"。
            if (k.ease == AnimKey.EASE_STEP) {
                canvas.drawRect(x - r, y - r, x + r, y + r, linePaint)
            }
        }
    }

    private fun drawPlayhead(canvas: Canvas) {
        val lane = TimelineLayout.Lane(
            dp(TimelineLayout.NAME_COLUMN_DP), 0f,
            max(1f, width - dp(TimelineLayout.NAME_COLUMN_DP) - dp(RIGHT_PAD_DP)),
            dp(RULER_DP) + dp(ART_ROW_DP) + bones.size * dp(ROW_DP), duration(),
        )
        val x = TimelineLayout.timeToX(lane, playhead)
        canvas.drawLine(x, 0f, x, lane.height, playPaint)
        diamond.reset()
        val r = dp(5f)
        diamond.moveTo(x, 0f)
        diamond.lineTo(x + r, r * 1.6f)
        diamond.lineTo(x - r, r * 1.6f)
        diamond.close()
        canvas.drawPath(diamond, playHeadPaint)
    }

    /** 刻度间隔：让整条轴上大约 5 个标签，而且永远是好读的数字。 */
    private fun rulerStep(total: Float): Float {
        for (candidate in RULER_STEPS) {
            if (total / candidate <= 5.5f) return candidate
        }
        return RULER_STEPS[RULER_STEPS.size - 1]
    }

    private fun trimSeconds(t: Float): String =
        if (abs(t - Math.round(t)) < 0.01f) Math.round(t).toString() else "%.1f".format(t)

    private fun shortName(bone: String): String =
        if (bone.length <= 9) bone else bone.substring(0, 8) + "…"

    // ── 手指 ───────────────────────────────────────────────────────────────

    private var dragKey = -1
    private var dragBone: String? = null
    private var scrubbing = false
    private var dragT = 0f
    private var dragV = 0f
    private var dragMoved = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = spec ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 标尺：拖播放头。
                if (event.y < dp(RULER_DP)) {
                    scrubbing = true
                    grabParent()
                    scrubTo(event.x)
                    return true
                }
                // 图那一行：点一块就是选中那一帧。
                if (event.y < dp(RULER_DP) + dp(ART_ROW_DP)) {
                    val lane = artLane()
                    val t = TimelineLayout.xToTime(lane, event.x)
                    var acc = 0f
                    for ((i, f) in s.frames.withIndex()) {
                        val d = Anim.frameSeconds(f)
                        if (t >= acc && t < acc + d) {
                            onFramePicked?.invoke(i)
                            return true
                        }
                        acc += d
                    }
                    return false
                }
                val row = rowAt(event.y)
                if (row < 0) return false
                val bone = bones[row]
                // 名字列：选中这一根（它的菱形亮起来）。
                if (event.x < dp(TimelineLayout.NAME_COLUMN_DP)) {
                    onBonePicked?.invoke(bone)
                    return true
                }
                // 轨道上：按到菱形就拖它；没按到就不接这一下（让外面的 ScrollView 滚）。
                val lane = laneOf(row)
                val keys = keysOf(bone)
                val range = TimelineLayout.rangeOf(keys, channel)
                val hit = TimelineLayout.hitKey(
                    keys, lane, range[0], range[1], event.x, event.y, dp(TimelineLayout.HIT_RADIUS_DP),
                )
                if (hit < 0) return false
                dragKey = hit
                dragBone = bone
                dragMoved = false
                grabParent()
                onKeyPicked?.invoke(bone, hit)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrubbing) {
                    scrubTo(event.x)
                    return true
                }
                val bone = dragBone ?: return false
                val row = bones.indexOf(bone)
                if (row < 0 || dragKey < 0) return false
                val lane = laneOf(row)
                val keys = keysOf(bone)
                val range = TimelineLayout.rangeOf(keys, channel)
                val t = TimelineLayout.snapTime(
                    TimelineLayout.snapToFrame(
                        TimelineLayout.xToTime(lane, event.x),
                        TimelineLayout.frameStarts(s),
                        TimelineLayout.FRAME_SNAP,
                    ),
                    duration(),
                )
                val v = TimelineLayout.yToValue(event.y, range[0], range[1], lane)
                dragT = t
                dragV = v
                dragMoved = true
                onKeyMoved?.invoke(bone, dragKey, t, v, false)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 抬手 = "就停在这儿"：这一次才写文件（见 onKeyMoved 的说明）。
                val bone = dragBone
                val index = dragKey
                if (bone != null && index >= 0 && dragMoved &&
                    event.actionMasked == MotionEvent.ACTION_UP
                ) {
                    onKeyMoved?.invoke(bone, index, dragT, dragV, true)
                }
                scrubbing = false
                dragKey = -1
                dragBone = null
                dragMoved = false
                releaseParent()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scrubTo(x: Float) {
        val lane = TimelineLayout.Lane(
            dp(TimelineLayout.NAME_COLUMN_DP), 0f,
            max(1f, width - dp(TimelineLayout.NAME_COLUMN_DP) - dp(RIGHT_PAD_DP)), dp(RULER_DP),
            duration(),
        )
        val spec0 = spec ?: return
        val t = TimelineLayout.snapTime(
            TimelineLayout.snapToFrame(
                TimelineLayout.xToTime(lane, x),
                TimelineLayout.frameStarts(spec0),
                TimelineLayout.FRAME_SNAP,
            ),
            duration(),
        )
        playhead = t
        invalidate()
        onScrub?.invoke(t)
    }

    /** 拖菱形的时候别让外面的 ScrollView 抢走这一串事件。 */
    private fun grabParent() {
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun releaseParent() {
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private companion object {
        /** 标尺那一行多高。 */
        const val RULER_DP = 20f

        /** 图那一行。 */
        const val ART_ROW_DP = 20f

        /** 一根骨头一行。 */
        const val ROW_DP = 26f

        /** 右边留一点，最后一个菱形不会被切掉。 */
        const val RIGHT_PAD_DP = 10f

        const val BOTTOM_PAD_DP = 2f

        /** 刻度候选：永远是这些数字，用户看到的标签才好读。 */
        val RULER_STEPS = floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 30f)
    }
}
