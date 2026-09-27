package dev.atp.pet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A rule, drawn as the graph it is.
 *
 *   ┌──────────────┐      ┌──────────────┐      ┌──────────────┐
 *   │ 当：被甩出去  │─────▶│ 如果：生命>0  │─────▶│ 就：说「哇」  │
 *   └──────────────┘      └──────────────┘      └──────────────┘
 *
 * The list this replaced said the same thing in the same order, and that was the problem:
 * a column of cards reads as a list of things, and a rule is not a list — it is a chain
 * with a direction, and the direction is the part that is hard to see.
 *
 * The view is deliberately dumb. It is handed boxes with text already in them and reports
 * which one was touched; what a rule MEANS lives in the engine, and what a box SAYS is
 * decided by whoever built it. That keeps the whole of the drawing testable by looking at
 * it, and the whole of the meaning out of a Canvas subclass.
 */
class LogicGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /**
     * One box. [role] is 0 for 当, 1 for 如果, 2 for 就, 3 for 否则 — which is also its colour.
     *
     * Three more roles, and they are what makes the graph an assembly rather than a readout:
     *
     *   * [CONNECTOR] is the small pill between two 如果 boxes that says 而且 or 或者. Its
     *     [index] is the clause it sits in front of, because that is the clause whose join
     *     it shows, and tapping it flips that join.
     *   * [ADD] is a module that is not there yet: "＋ 加一个如果", "＋ 加一个动作". Its
     *     [index] says what to add — [ADD_CONDITION], [ADD_ACTION] or [ADD_ELSE] — because
     *     an action belongs to one of two branches and a box in a row cannot say which.
     *   * [TIMER] is the box BETWEEN two executors, "＋ 计时器": a rule is a sequence, and
     *     「先做 A，等两秒，再做 B」 is a sentence the end-of-row ADD box could not write (it
     *     appends, so the timer landed after the last action, where it delays nothing). Its
     *     [index] is where the timer goes, not which action it is: the box belongs to the gap.
     *     A gap in the 否则 list is [TIMER_ELSE], because a box cannot say which of a rule's two
     *     action lists it is in — the same reason an 否则 action box is role ELSE and not THEN.
     *
     * [row] is which row of this rule the box sits on: 0 is the rule's own row, 1.. are the rows
     * that hang below it (「否则如果」一级一行，「或者」一支一行). The rows hold the same boxes --
     * a 当, an 如果, a 就 -- and they look identical on purpose, so "which row" is the one thing
     * a box cannot say about itself and has to be told.
     *
     * The view still does not know what any of it means; it reports the box and lets the
     * caller decide, which is the whole reason the drawing is testable by looking at it.
     */
    class Node(
        val role: Int,
        val lines: List<String>,
        val index: Int,
        val row: Int = 0,
    ) {
        companion object {
            const val WHEN = 0
            const val IF = 1
            const val THEN = 2
            const val ELSE = 3
            const val CONNECTOR = 4
            const val ADD = 5
            const val TIMER = 6
            const val TIMER_ELSE = 7

            /** 「或者」：挂在「就」下面的一支备选（1.32.0）。和「否则」一样是向下的一行。 */
            const val OR = 8

            const val ADD_CONDITION = 1
            const val ADD_ACTION = 2
            const val ADD_ELSE = 3

            /**
             * 「否则如果」：往「否则」那一行里再加一个「如果」（1.32.0）。
             *
             * 和 [ADD_CONDITION] 分开，是因为它们加的东西不一样：后者给**这一行**加一个如果，
             * 前者是**再挂一行**（否则如果）= 一条新的判断级。一个「＋」说不清这两种。
             */
            const val ADD_ELSE_IF = 4

            /** 「或者」：给这一行的「就」再加一支备选（1.32.0）。 */
            const val ADD_ALT = 5
        }
    }

    /**
     * 一行**从哪里吊下来**（1.32.0）：第 [parent] 行的第 [box] 个方块底下。
     *
     * 原来这里只记"这一行上面有几行"（并行分支全是挂在同一个侦测器下面的，所以那么记够了）。
     * 现在向下的行有两种挂法 —— 「否则」挂在**如果**下面、「或者」挂在**就**下面 —— 于是
     * "挂在哪一行的第几个方块上"成了必须说出来的东西。
     */
    class Drop(val parent: Int, val box: Int) {
        companion object { val NONE = Drop(-1, -1) }
    }

    private class Placed(val node: Node, val rule: Int, val rect: RectF)

    private var rules: List<List<Node>> = emptyList()

    /** Laid out boxes, grouped by rule and in order, so onDraw allocates nothing. */
    private val rows = mutableListOf<MutableList<Placed>>()
    private val placed = mutableListOf<Placed>()

    /** Called with the rule index and the node that was tapped. */
    var onTap: ((Int, Node) -> Unit)? = null

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var fitted = false
    private var panning = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var pinchSpan = 0f
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val density = resources.displayMetrics.density

    /**
     * How many 并行分支 rows follow each rule's row.
     *
     * A count and not a name any more: a 并行组 is a rule with more executors, and what the
     * graph has to draw is a FORK -- one detector, arrows going down and splitting to each
     * executor. See RuleSpec.branches.
     */
    private var drops: List<Drop> = emptyList()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** The fork that says one detector has several executors. */
    private val fork = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        color = 0x886C4CE0.toInt()
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wire = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * density }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = 0x88000000.toInt()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x0F000000
    }

    private val wirePath = Path()

    /** Replace the model. Layout is recomputed; the pan and zoom are kept. */
    /**
     * The rows, plus where each row hangs from ([Drop], aligned with [next]).
     *
     * A row that hangs below another is one level of the same rule: 「否则如果」问下一级，
     * 「或者」给这一行的「就」多一支备选。The rows stay in the order the file has them, which is
     * the one thing about rule order anybody can rely on.
     */
    fun setRules(next: List<List<Node>>, nextDrops: List<Drop> = emptyList()) {
        rules = next
        drops = nextDrops
        layout()
        if (!fitted) fit()
        invalidate()
    }

    fun resetView() {
        fitted = false
        fit()
        invalidate()
    }

    private fun colourOf(role: Int): Int = when (role) {
        Node.WHEN -> 0xFF6C4CE0.toInt()
        Node.IF -> 0xFFE08A2E.toInt()
        Node.THEN -> 0xFF2E9E6B.toInt()
        Node.ELSE -> 0xFF7A7A88.toInt()
        // 「或者」和「否则」是同一类东西（都是向下的一行），颜色也挨着 —— 但不一样：
        // 一个是"条件不成立"，一个是"这一支按权重挑"，混起来就该出事了。
        Node.OR -> 0xFF8A6BA8.toInt()
        Node.CONNECTOR -> 0xFFB0752A.toInt()
        // 计时器在"就"那一段里，所以它借"就"的颜色：它是一次执行的一部分，只是先等一下。
        Node.TIMER, Node.TIMER_ELSE -> 0x662E9E6B.toInt()
        else -> 0xFF9AA0AE.toInt()
    }

    private fun labelOf(role: Int): String = when (role) {
        Node.WHEN -> "当"
        Node.IF -> "如果"
        Node.THEN -> "就"
        Node.ELSE -> "否则"
        Node.OR -> "或者"
        Node.CONNECTOR -> ""
        // 计时器盒子是"这里还空着"的盒子（和 ADD 一样是 ＋），里面的字说明插什么。
        else -> "＋"
    }

    /** A connector is a pill, not a box: no header, one line, and much smaller. */
    private fun isPill(role: Int): Boolean = role == Node.CONNECTOR

    // -- layout -------------------------------------------------------------

    private fun nodeWidth(node: Node): Float {
        if (isPill(node.role)) {
            var w = 0f
            for (line in node.lines) w = max(w, bodyPaint.measureText(line))
            return w + 26f * density
        }
        var w = bodyPaint.measureText(labelOf(node.role)) + 24f * density
        for (line in node.lines) w = max(w, bodyPaint.measureText(line) + 28f * density)
        return min(w, MAX_W * density)
    }

    private fun nodeHeight(node: Node): Float = if (isPill(node.role)) {
        PILL_H * density
    } else {
        (TITLE_H + node.lines.size * LINE_H) * density
    }

    private fun layout() {
        placed.clear()
        rows.clear()
        var y = 0f
        for ((ruleIndex, rule) in rules.withIndex()) {
            var x = 0f
            var tallest = 0f
            val row = mutableListOf<Placed>()
            for (node in rule) {
                val w = nodeWidth(node)
                val h = nodeHeight(node)
                val box = Placed(node, ruleIndex, RectF(x, y, x + w, y + h))
                row.add(box)
                placed.add(box)
                x += w + GAP * density
                tallest = max(tallest, h)
            }
            rows.add(row)
            y += tallest + ROW_GAP * density
        }
    }

    private fun contentBounds(): RectF {
        val box = RectF(0f, 0f, 1f, 1f)
        for (p in placed) box.union(p.rect)
        return box
    }

    /** Fit the graph into the view, but never magnify past 1:1 — big text is not a goal. */
    private fun fit() {
        if (placed.isEmpty() || width == 0 || height == 0) return
        val box = contentBounds()
        val pad = 16f * density
        val s = min(
            (width - pad * 2) / max(box.width(), 1f),
            (height - pad * 2) / max(box.height(), 1f),
        )
        scale = min(s, 1f).coerceAtLeast(0.15f)
        offsetX = pad - box.left * scale
        offsetY = pad - box.top * scale
        fitted = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!fitted) fit()
    }

    // -- drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (rules.isEmpty()) {
            canvas.drawText("还没有规则。点下面的「＋规则」。", 16f * density, 40f * density, hintPaint)
            return
        }
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        // A grid, faint: it is what makes a panned canvas read as a surface rather than as
        // a picture that jumped.
        val step = 40f
        val left = (0f - offsetX) / scale
        val top = (0f - offsetY) / scale
        val right = (width - offsetX) / scale
        val bottom = (height - offsetY) / scale
        var gx = kotlin.math.floor(left / step) * step
        while (gx < right) {
            canvas.drawLine(gx, top, gx, bottom, gridPaint)
            gx += step
        }
        var gy = kotlin.math.floor(top / step) * step
        while (gy < bottom) {
            canvas.drawLine(left, gy, right, gy, gridPaint)
            gy += step
        }

        for (row in rows) {
            for (i in 0 until row.size - 1) {
                val a = row[i].rect
                val b = row[i + 1].rect
                wire.color = 0x556C4CE0
                wirePath.reset()
                val x1 = a.right
                val y1 = a.centerY()
                val x2 = b.left
                val y2 = b.centerY()
                val bend = max(18f * density, (x2 - x1) * 0.5f)
                wirePath.moveTo(x1, y1)
                wirePath.cubicTo(x1 + bend, y1, x2 - bend, y2, x2, y2)
                canvas.drawPath(wirePath, wire)
                // An arrowhead, because a wire between two boxes is otherwise a line.
                canvas.drawLine(x2, y2, x2 - 9f * density, y2 - 5f * density, wire)
                canvas.drawLine(x2, y2, x2 - 9f * density, y2 + 5f * density, wire)
            }
        }

        for (p in placed) {
            drawNode(canvas, p)
        }
        // The forks last, on top: a line the boxes hide under is a line nobody sees.
        drawDrops(canvas)
        canvas.restore()
    }

    /**
     * 向下挂的行：从上面那个方块底下吊一根线，拐进这一行的第一个方块（箭头指过去）。
     *
     * 「否则」吊在**如果**底下（"这些如果都不成立时"），「或者」吊在**就**底下（"这一支的
     * 备选"）—— 挂在哪一个方块下面 therefore 是有意思的，不是排版细节：用户看图的时候，
     * 线的起点就是他问的问题。同一个方块下面挂好几行时，线会**并成一条主干再分叉**：
     * 各画各的会得到一捆看不出关系的斜线。
     */
    private fun drawDrops(canvas: Canvas) {
        // 先按"从哪个方块下来"归堆，再一堆画一根主干。
        val groups = LinkedHashMap<Pair<Int, Int>, MutableList<Int>>()
        for ((i, d) in drops.withIndex()) {
            if (d.parent < 0 || d.parent >= rows.size) continue
            if (i >= rows.size || i == d.parent) continue
            groups.getOrPut(d.parent to d.box) { mutableListOf() }.add(i)
        }
        for ((from, below) in groups) {
            val parentRow = rows.getOrNull(from.first) ?: continue
            val parent = parentRow.getOrNull(from.second) ?: parentRow.firstOrNull() ?: continue
            val x = (parent.rect.left + parent.rect.right) / 2f
            val top = parent.rect.bottom
            var bottom = top
            for (b in below) {
                val head = rows.getOrNull(b)?.firstOrNull() ?: continue
                bottom = max(bottom, (head.rect.top + head.rect.bottom) / 2f)
            }
            canvas.drawLine(x, top, x, bottom, fork)
            for (b in below) {
                val head = rows.getOrNull(b)?.firstOrNull() ?: continue
                val ty = (head.rect.top + head.rect.bottom) / 2f
                val tx = head.rect.left
                canvas.drawLine(x, ty, tx, ty, fork)
                canvas.drawLine(tx, ty, tx - 9f * density, ty - 5f * density, fork)
                canvas.drawLine(tx, ty, tx - 9f * density, ty + 5f * density, fork)
            }
        }
    }

    private fun drawNode(canvas: Canvas, p: Placed) {
        val r = p.rect
        val accent = colourOf(p.node.role)
        val radius = 10f * density

        fill.color = 0xF7FFFFFF.toInt()
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = accent
        stroke.strokeWidth = 1.5f * density
        canvas.drawRoundRect(r, radius, radius, stroke)

        // A colour bar down the left edge rather than a filled header: the box stays light
        // enough to read twelve lines deep, and the role is still visible at a glance.
        canvas.save()
        canvas.clipRect(r.left, r.top, r.left + 5f * density, r.bottom)
        fill.color = accent
        canvas.drawRoundRect(r, radius, radius, fill)
        canvas.restore()

        if (isPill(p.node.role)) {
            // The text sits in the middle of the card, on the wire, where the join belongs:
            // the connector is read as part of the sentence, not as another box.
            bodyPaint.color = accent
            val y = r.centerY() + bodyPaint.textSize * 0.36f
            for (line in p.node.lines) {
                canvas.drawText(line, r.left + 13f * density, y, bodyPaint)
            }
            return
        }

        titlePaint.color = accent
        canvas.drawText(
            labelOf(p.node.role),
            r.left + 14f * density,
            r.top + 17f * density,
            titlePaint,
        )
        bodyPaint.color = 0xFF171528.toInt()
        var y = r.top + (TITLE_H + 12f) * density
        for (line in p.node.lines) {
            canvas.drawText(line, r.left + 14f * density, y, bodyPaint)
            y += LINE_H * density
        }
    }

    // -- input --------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 图在一层会滚动的页面里，而"点一个方块"和"滚这一页"在 DOWN 那一刻长得一样。
                // 不拦住父容器的话，手指稍微一动，父容器就把这一串触摸抢走，方块于是**点不动**
                // —— 用户报的"方块不能点"最可能就是这一条（图自己会平移缩放，所以父容器在这块
                // 地上本来也不该抢）。UP/CANCEL 时放回去。
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTapAt = System.currentTimeMillis()
                lastTapX = event.x
                lastTapY = event.y
                panning = true
                lastPanX = event.x
                lastPanY = event.y
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                panning = false
                pinchSpan = span(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val s = span(event)
                    if (pinchSpan > 1f && s > 1f) {
                        val fx = midX(event)
                        val fy = midY(event)
                        val beforeX = (fx - offsetX) / scale
                        val beforeY = (fy - offsetY) / scale
                        scale = (scale * (s / pinchSpan)).coerceIn(0.15f, 3f)
                        offsetX = fx - beforeX * scale
                        offsetY = fy - beforeY * scale
                        fitted = true
                    }
                    pinchSpan = s
                    invalidate()
                    return true
                }
                if (panning) {
                    offsetX += event.x - lastPanX
                    offsetY += event.y - lastPanY
                    lastPanX = event.x
                    lastPanY = event.y
                    fitted = true
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                pinchSpan = 0f
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                panning = false
                pinchSpan = 0f
                // A tap is a press that did not travel. Anything else was a pan, and a pan
                // that ends on a box must not open an editor.
                val moved = hypot(event.x - lastTapX, event.y - lastTapY)
                if (moved < 12f * density && System.currentTimeMillis() - lastTapAt < 400) {
                    val hit = hitTest(event.x, event.y)
                    if (hit != null) onTap?.invoke(hit.rule, hit.node)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Placed? {
        val wx = (x - offsetX) / scale
        val wy = (y - offsetY) / scale
        for (p in placed) {
            if (wx >= p.rect.left && wx <= p.rect.right && wy >= p.rect.top && wy <= p.rect.bottom) {
                return p
            }
        }
        return null
    }

    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    private fun midX(e: MotionEvent) = if (e.pointerCount >= 2) (e.getX(0) + e.getX(1)) / 2f else e.x
    private fun midY(e: MotionEvent) = if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2f else e.y

    companion object {
        private const val TITLE_H = 22f
        private const val PILL_H = 30f
        private const val LINE_H = 26f
        private const val GAP = 30f
        private const val ROW_GAP = 26f
        private const val MAX_W = 300f

    }
}
