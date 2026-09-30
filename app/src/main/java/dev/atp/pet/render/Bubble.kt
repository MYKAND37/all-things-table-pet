package dev.atp.pet.render

/**
 * 气泡的大小：一句话折成几行，或者一张图缩到多大。
 *
 * 为什么单独一个对象、而且**一行 Android 都没有**：用户报的是"说话的内容框能自动适配大小"，
 * 而"框多大"这件事以前是写死的（`x ± 200`、`y - 82 .. y - 4`）—— 一行长句子从框里溢出来，
 * 短句子留一大块空白。这种东西不该靠眼睛调，它是一道算术题：给一串字（或者一张图的尺寸），
 * 算出框该多高多宽。所以它做成一串**纯函数**：量字的宽度由调用方递进来（`measure`），
 * 于是本地那个 Python 镜像可以喂一个假尺子，把折行和封顶逐条钉住（`tools/bubble_check.py`）。
 *
 * 单位是**世界像素**（和画布同一套坐标），不是 dp：气泡长在宠物头顶上，跟着缩放一起放大。
 */
object Bubble {
    /** 文字与边框之间的留白，左右各一份。 */
    const val PAD_X = 26f
    const val PAD_Y = 20f

    /** 一句话最宽到哪儿就折行。 */
    const val MAX_TEXT_W = 520f

    /** 行高（字号的 1.25 倍左右：中文挤在一起读不出来）。 */
    const val LINE_H = 54f

    /** 最多几行 —— 再长就截断：一个气泡盖住半只宠物，比看不全那句话更糟。 */
    const val MAX_LINES = 4

    /** 一张图最长的一边缩到多大。 */
    const val MAX_IMAGE = 420f

    /** 气泡和宠物头顶之间留的空。 */
    const val GAP = 12f

    /**
     * 折行：按宽度贪心折，**只在字与字之间断**（中文没有空格，所以按"字符"断）。
     *
     * 一句话里显式的换行（`\n`）先拆开、各自折 —— 用户写多行的时候那是他的意思。
     */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                out.add("")
                continue
            }
            var line = StringBuilder()
            for (ch in paragraph) {
                val next = line.toString() + ch
                if (line.isNotEmpty() && measure(next) > maxWidth) {
                    out.add(line.toString())
                    line = StringBuilder()
                }
                line.append(ch)
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

    /**
     * 一句话的框：[宽, 高]。宽取"最长那一行"，高取行数 —— **框跟着字走**，不是字跟着框。
     */
    fun textSize(lines: List<String>, measure: (String) -> Float): Pair<Float, Float> {
        val widest = lines.maxOfOrNull { measure(it) } ?: 0f
        val rows = lines.size.coerceAtLeast(1)
        return (widest + PAD_X * 2f) to (rows * LINE_H + PAD_Y * 2f)
    }

    /**
     * 一张图的框：[宽, 高]，**按比例**缩到最长边不超过 [MAX_IMAGE]。
     *
     * 小图不放大（一张 40px 的贴纸被拉到 420 会糊成一团）：缩放的倍率封顶在 1。
     */
    fun imageSize(width: Int, height: Int): Pair<Float, Float> {
        if (width <= 0 || height <= 0) return (MAX_IMAGE / 2f) to (MAX_IMAGE / 2f)
        val longest = maxOf(width, height).toFloat()
        val k = if (longest > MAX_IMAGE) MAX_IMAGE / longest else 1f
        return (width * k + PAD_X * 2f) to (height * k + PAD_Y * 2f)
    }

    /**
     * 框的四条边：以"宠物头顶那一点"为底边中点，**向上**长。
     *
     * 返回 [left, top, right, bottom]。夹在**看得见的那一块世界**（`viewLeft..viewRight`）里：
     * 气泡探出屏幕一半，是"这句话没说全"的另一种样子。
     *
     * 夹的是"看得见的那一块"，不是"角色那张画的画布"（1.39.0，用户报的"悬浮气泡被限定在了
     * 一个区域内，没有跟着角色走"）：那只宠物的画布是 1024 宽，而它能在整块屏幕（测试场里是
     * 6144 宽的世界）上走 —— 按画布夹，宠物一走出那 1024 的带子，气泡就**钉在带子边上不动
     * 了**。区域本来就该是"你现在看得见的那一块"。
     */
    fun box(x: Float, y: Float, size: Pair<Float, Float>, viewLeft: Float, viewRight: Float): FloatArray {
        val half = size.first / 2f
        val rightMost = (viewRight - size.first).coerceAtLeast(viewLeft)
        val left = (x - half).coerceIn(viewLeft, rightMost)
        val bottom = y - GAP
        return floatArrayOf(left, bottom - size.second, left + size.first, bottom)
    }

    /** 折行 + 封顶：最多 [MAX_LINES] 行，超出的用省略号收尾（那半句是真的没地方放了）。 */
    fun fit(text: String, measure: (String) -> Float): List<String> {
        val lines = wrap(text, MAX_TEXT_W, measure)
        if (lines.size <= MAX_LINES) return lines
        val kept = lines.take(MAX_LINES).toMutableList()
        kept[MAX_LINES - 1] = kept[MAX_LINES - 1] + "…"
        return kept
    }
}
