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

    /** One box. [role] is 0 for 当, 1 for 如果, 2 for 就 — which is also its colour. */
    class Node(val role: Int, val lines: List<String>, val index: Int)

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

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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
    fun setRules(next: List<List<Node>>) {
        rules = next
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
        0 -> 0xFF6C4CE0.toInt()
        1 -> 0xFFE08A2E.toInt()
        else -> 0xFF2E9E6B.toInt()
    }

    private fun labelOf(role: Int): String = when (role) {
        0 -> "当"
        1 -> "如果"
        else -> "就"
    }

    // -- layout -------------------------------------------------------------

    private fun nodeWidth(node: Node): Float {
        var w = bodyPaint.measureText(labelOf(node.role)) + 24f * density
        for (line in node.lines) w = max(w, bodyPaint.measureText(line) + 28f * density)
        return min(w, MAX_W * density)
    }

    private fun nodeHeight(node: Node): Float =
        (TITLE_H + node.lines.size * LINE_H) * density

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
        canvas.restore()
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
        private const val LINE_H = 26f
        private const val GAP = 44f
        private const val ROW_GAP = 26f
        private const val MAX_W = 300f
    }
}
