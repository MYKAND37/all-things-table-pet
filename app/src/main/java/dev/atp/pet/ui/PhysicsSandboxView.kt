package dev.atp.pet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.engine.event.EventType
import dev.atp.pet.engine.event.GameEvent
import dev.atp.pet.engine.logic.ActionSpec
import dev.atp.pet.engine.logic.LogicSpec
import dev.atp.pet.engine.logic.RuleEngine
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.physics.Ragdoll
import dev.atp.pet.engine.prop.Prop
import dev.atp.pet.engine.prop.PropKind
import dev.atp.pet.engine.prop.PropSpec
import dev.atp.pet.engine.prop.PropWorld
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.Skeleton
import dev.atp.pet.render.PartLibrary
import dev.atp.pet.render.PartRenderer
import dev.atp.pet.render.Particles
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import java.io.File

/**
 * The test bench: where a character actually runs.
 *
 * The play area is wider than the artwork: a pet confined to the width of its own drawing
 * hits a wall the moment it is thrown anywhere. The view is a window onto that area —
 * pinch to zoom, drag the background to pan, and it slides by itself to keep the pet in
 * sight.
 *
 * Everything the character DOES lives here, and the split is deliberate. The physics
 * decides what happened; the rule engine decides what it means; this view is the only
 * thing that knows how to make any of it visible. A rule says "say 好疼" and has no idea
 * what a speech bubble is.
 */
class PhysicsSandboxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var spec: CharacterSpec? = null
    private var skeleton: Skeleton? = null
    private var renderer: PartRenderer? = null
    private var library: PartLibrary? = null
    private var ragdoll: Ragdoll? = null

    // ── the world ───────────────────────────────────────────────────────────
    private var engine: RuleEngine? = null
    private var propSpecs: List<PropSpec> = emptyList()
    private var propArt: PartLibrary? = null
    private var world: PropWorld? = null
    private val particles = Particles()

    /** Parts whose artwork is gone. The bones are still there; only the drawing is not. */
    private val broken = HashSet<String>()

    /** Named actions, so a rule can say "摆动作 挥手". */
    private var poseByName: Map<String, Map<String, Float>> = emptyMap()

    private var clock = 0f
    private var bubble: String? = null
    private var bubbleLeft = 0f

    /** Last time the figure was on the ground, and how fast it was falling before that. */
    private var wasGrounded = true
    private var prevRootY = 0f
    private var fallSpeed = 0f

    /** world -> view, and the world coordinate sitting at the viewport's top-left. */
    private var viewScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var defaultScale = 1f
    private var framed = false

    private var heldBone: String? = null
    private var heldProp: Prop? = null
    private var pinTarget = Vec2.ZERO

    /** A press on a bone that has not turned into a drag yet: a tap is a click. */
    private var tapBone: String? = null
    private var tapX = 0f
    private var tapY = 0f
    private var tapAt = 0L

    private var panning = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var lastTapAt = 0L
    private var lastFrameNs = 0L
    private var pinchSpan = 0f

    private val density = resources.displayMetrics.density

    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x55000000
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x12000000
    }
    private val canvasPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33C04000
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * density, 8f * density), 0f)
    }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val worldPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = 0xCC222233.toInt()
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * density
        color = 0xAA222233.toInt()
    }

    var onInfo: ((String) -> Unit)? = null

    /** 0 = limp ragdoll, 1 = holds a pose. Changing it takes effect immediately. */
    var stiffness: Float = 0f
        set(value) {
            field = value
            ragdoll?.stiffness = value
            invalidate()
        }

    // ── loading ─────────────────────────────────────────────────────────────

    fun load(
        folder: CharacterFolder,
        logic: LogicSpec,
        propSpecs: List<PropSpec>,
        propsDir: File?,
    ) {
        library?.release()
        val parsed = CharacterSpec.parse(folder.specText())
        val built = parsed.buildSkeleton()
        built.update()
        spec = parsed
        skeleton = built

        val loaded = PartLibrary.load(folder.partsDir, parsed.bones.map { it.name })
        library = loaded
        renderer = if (loaded.isEmpty) null else PartRenderer(
            built,
            loaded,
            parsed.layers.sortedBy { it.z }.map { it.bone },
            parsed.swaps,
        )

        ragdoll = Ragdoll(built, parsed, stiffness)
        this.propSpecs = propSpecs
        propArt?.release()
        propArt = propsDir?.let { PartLibrary.loadFree(it) }
        world = PropWorld(parsed.floorY, parsed.worldWidth)
        broken.clear()
        renderer?.hidden = broken
        particles.clear()
        bubble = null

        engine = RuleEngine(logic)
        clock = 0f
        prevRootY = built.root.worldPosition.y
        wasGrounded = true

        heldBone = null
        heldProp = null
        framed = false
        lastFrameNs = System.nanoTime()
        report()
        fire(GameEvent(EventType.SPAWN))
        invalidate()
    }

    /** Where the character stands, in canvas coordinates: spawns and bursts land here. */
    private fun homeX(): Float = ragdoll?.rootPos?.x ?: (spec?.centreX ?: 0f)
    private fun homeY(): Float = ragdoll?.rootPos?.y ?: (spec?.canvasHeight ?: 0f)

    private fun report() {
        val parts = library?.size ?: 0
        val props = propSpecs.size
        onInfo?.invoke(
            (spec?.id ?: "") + " · " + parts + " 部位 · " + props + " 道具 · " +
                "拖角色，拖背景平移"
        )
    }

    fun setPoseNames(poses: Map<String, Map<String, Float>>) {
        poseByName = poses
    }

    /** null clears back to limp. */
    fun applyPose(angles: Map<String, Float>?, home: Boolean = true) {
        val rag = ragdoll ?: return
        if (angles == null) {
            rag.clearPose()
        } else {
            // Choosing an action is a request to watch it. A pet that has been thrown into
            // a corner would otherwise strike the pose lying on its side, off screen.
            if (home) {
                rag.home()
                framePet()
            }
            rag.applyPose(angles)
        }
        stiffness = rag.stiffness
        invalidate()
    }

    /** Ask the character something on the host's behalf: a button, a restart, a debug poke. */
    fun raise(type: EventType, part: String = "", value: Float = 0f) {
        fire(GameEvent(type, part = part, value = value))
        invalidate()
    }

    fun spawnProp(id: String) {
        val s = propSpecs.firstOrNull { it.id == id } ?: return
        spawn(s, Vec2(homeX() + 260f, homeY() - 700f), Vec2(0f, 200f))
        invalidate()
    }

    fun resetWorld() {
        val rag = ragdoll ?: return
        rag.reset()
        heldBone = null
        heldProp = null
        world?.clear()
        particles.clear()
        broken.clear()
        renderer?.hidden = broken
        engine?.reset()
        clock = 0f
        wasGrounded = true
        prevRootY = rag.rootPos.y
        framePet()
        fire(GameEvent(EventType.SPAWN))
        postInvalidateOnAnimation()
    }

    // ── events ──────────────────────────────────────────────────────────────

    private fun fire(event: GameEvent) {
        val e = engine ?: return
        val actions = e.handle(event.copy(at = clock))
        perform(actions, event)
    }

    /**
     * Carry out what the rules asked for.
     *
     * The event that caused them comes along because "喷粒子" and "推一下" need to happen
     * AT the part that was hit, and the rule has no idea where that is.
     */
    private fun perform(actions: List<ActionSpec>, event: GameEvent?) {
        for (a in actions) {
            when (a.kind) {
                "say" -> {
                    bubble = a.text
                    bubbleLeft = BUBBLE_SECONDS
                }
                "pose" -> applyPose(poseByName[a.text], home = false)
                "clearPose" -> applyPose(null)
                "spawn" -> propSpecs.firstOrNull { it.id == a.prop }?.let {
                    spawn(it, Vec2(homeX() - 200f, homeY() - 900f), Vec2(120f, 60f))
                }
                "burst" -> particles.burst(a.text, pointOf(event), a.value.toInt())
                "impulse" -> {
                    val rag = ragdoll ?: continue
                    val sk = skeleton ?: continue
                    val bone = sk.find(a.bone.ifEmpty { event?.part ?: "" })
                        ?: sk.root
                    val dir = Vec2(0f, -1f)
                    rag.impulse(bone, dir, a.value)
                }
                "break" -> {
                    val name = a.bone.ifEmpty { event?.part ?: "" }
                    if (name.isNotEmpty()) {
                        broken.add(name)
                        renderer?.hidden = broken
                        particles.burst("spark", pointOf(event), 12)
                    }
                }
            }
        }
    }

    private fun pointOf(event: GameEvent?): Vec2 {
        val sk = skeleton ?: return Vec2.ZERO
        val name = event?.part ?: ""
        if (name.isNotEmpty()) {
            val bone = sk.find(name)
            if (bone != null) return bone.worldPosition
        }
        return Vec2(homeX(), homeY() - 400f)
    }

    private fun spawn(s: PropSpec, at: Vec2, velocity: Vec2) {
        world?.spawn(s, at, velocity)
    }

    // -- viewport -----------------------------------------------------------

    private fun viewWidth(): Float = if (viewScale <= 0f) 0f else width / viewScale
    private fun viewHeight(): Float = if (viewScale <= 0f) 0f else height / viewScale

    private fun clampPan() {
        val s = spec ?: return
        val vw = viewWidth()
        val vh = viewHeight()
        val maxX = max(0f, s.worldWidth - vw)
        val maxY = max(0f, s.floorY - vh)
        panX = panX.coerceIn(0f, maxX)
        panY = panY.coerceIn(0f, maxY)
    }

    /** Centre the window on the pet, at the given zoom. */
    private fun framePet() {
        val s = spec ?: return
        val rag = ragdoll ?: return
        val vw = viewWidth()
        val vh = viewHeight()
        panX = rag.rootPos.x - vw / 2f
        panY = if (vh >= s.floorY) 0f else s.floorY - vh
        clampPan()
        framed = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = spec ?: return
        val pad = 4f * density
        // Fit the height: the pet is tall, and cropping it vertically to fill a phone
        // screen sideways would be a strange default. Horizontal room is the point, and
        // that is what the panning is for.
        defaultScale = (h - pad * 2) / s.floorY
        if (!framed) {
            viewScale = defaultScale
            framePet()
        } else {
            viewScale = viewScale.coerceIn(defaultScale * 0.25f, defaultScale * 6f)
            clampPan()
        }
    }

    private fun vx(worldX: Float) = (worldX - panX) * viewScale
    private fun vy(worldY: Float) = (worldY - panY) * viewScale
    private fun toWorld(x: Float, y: Float) = Vec2(panX + x / viewScale, panY + y / viewScale)

    /** Slide the window only far enough to keep the pet on screen; never fight a pan. */
    private fun follow() {
        val rag = ragdoll ?: return
        val vw = viewWidth()
        if (vw <= 0f) return
        val petX = rag.rootPos.x
        val left = panX + vw * 0.12f
        val right = panX + vw * 0.88f
        when {
            petX < left -> panX -= (left - petX) * 0.12f
            petX > right -> panX += (petX - right) * 0.12f
            else -> return
        }
        clampPan()
    }

    // -- simulation ---------------------------------------------------------

    private fun simulate(dt: Float) {
        val s = spec ?: return
        val sk = skeleton ?: return
        val rag = ragdoll ?: return

        clock += dt
        if (bubbleLeft > 0f) bubbleLeft -= dt

        val actions = engine?.step(dt) ?: emptyList()
        if (actions.isNotEmpty()) perform(actions, null)

        rag.step(dt, heldBone, pinTarget)

        world?.let { w ->
            val hits = w.step(
                dt,
                s.gravity,
                sk,
                { bone -> rag.colliderRadius(bone) },
                { bone, dir, strength -> rag.impulse(bone, dir, strength) },
            )
            for (h in hits) fire(
                GameEvent(EventType.PROP_HIT, part = h.bone.name, value = h.value, prop = h.prop.spec.id)
            )
        }

        // Landing is a transition, not a state: it is the moment the figure stops falling,
        // and the speed it had is what the rules get to see.
        val y = rag.rootPos.y
        if (dt > 1e-4f) fallSpeed = (y - prevRootY) / dt
        prevRootY = y
        val grounded = rag.grounded
        if (grounded && !wasGrounded && abs(fallSpeed) > 120f) {
            val impact = abs(fallSpeed)
            fire(GameEvent(EventType.LANDED, value = impact))
            lowestBone()?.let { fire(GameEvent(EventType.IMPACT, part = it.name, value = impact)) }
        }
        wasGrounded = grounded

        particles.step(dt, s.floorY)
    }

    private fun lowestBone(): Bone? {
        val sk = skeleton ?: return null
        val rag = ragdoll ?: return null
        var best: Bone? = null
        var low = -Float.MAX_VALUE
        for (b in sk.bones) {
            val v = rag.colliderLow(b)
            if (v > low) {
                low = v
                best = b
            }
        }
        return best
    }

    // -- drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val sk = skeleton ?: return
        val rag = ragdoll ?: return
        if (viewScale <= 0f) return

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else (now - lastFrameNs) / 1_000_000_000f
        lastFrameNs = now
        simulate(if (dt > 0.05f) 0.05f else dt)
        if (!panning && heldProp == null) follow()

        val step = 256f
        var gx = 0f
        while (gx <= s.worldWidth) {
            if (vx(gx) >= -2f && vx(gx) <= width + 2f) {
                canvas.drawLine(vx(gx), 0f, vx(gx), vy(s.floorY), gridPaint)
            }
            gx += step
        }
        var gy = 0f
        while (gy <= s.floorY) {
            if (vy(gy) >= -2f && vy(gy) <= height + 2f) {
                canvas.drawLine(0f, vy(gy), width.toFloat(), vy(gy), gridPaint)
            }
            gy += step
        }
        canvas.drawLine(0f, vy(s.floorY), width.toFloat(), vy(s.floorY), groundPaint)

        // Everything below shares the world transform, so it pans and zooms together.
        canvas.save()
        canvas.translate(-panX * viewScale, -panY * viewScale)
        canvas.scale(viewScale, viewScale)

        particles.draw(canvas, worldPaint)

        drawCharacter(canvas, sk)
        drawProps(canvas)
        drawBubble(canvas, sk)

        canvas.restore()

        drawHud(canvas, rag)
        postInvalidateOnAnimation()
    }

    private fun drawCharacter(canvas: Canvas, sk: Skeleton) {
        // Where the artwork's own canvas sits, so the pet's home patch is findable.
        val s = spec
        if (s != null) {
            canvas.drawRect(0f, 0f, s.canvasWidth, s.floorY, canvasPaint)
        }
        val art = renderer
        if (art != null) {
            art.draw(canvas)
            return
        }
        for (bone in sk.bones) {
            val h = bone.worldPosition
            val t = bone.tipPosition()
            bonePaint.color = colourFor(bone.name)
            canvas.drawLine(h.x, h.y, t.x, t.y, bonePaint)
            jointPaint.color = bonePaint.color
            canvas.drawCircle(h.x, h.y, 4f, jointPaint)
        }
    }

    private fun drawProps(canvas: Canvas) {
        val w = world ?: return
        val art = propArt
        for (prop in w.live) {
            val part = art?.parts?.get(prop.spec.id)
            if (part == null) {
                // No drawing yet: an outline of exactly the circle it collides with, which
                // is the number that actually matters.
                worldPaint.style = Paint.Style.STROKE
                worldPaint.strokeWidth = 3f
                worldPaint.color = 0x9946AA6E.toInt()
                canvas.drawCircle(prop.position.x, prop.position.y, prop.spec.radius, worldPaint)
                worldPaint.style = Paint.Style.FILL
                continue
            }
            val box = prop.spec.radius * 2f
            val scale = box / max(part.bitmap.width, part.bitmap.height).toFloat()
            canvas.save()
            canvas.translate(prop.position.x, prop.position.y)
            canvas.rotate(Math.toDegrees(prop.rotation.toDouble()).toFloat())
            canvas.scale(scale, scale)
            canvas.drawBitmap(
                part.bitmap,
                -part.bitmap.width / 2f - part.offsetX,
                -part.bitmap.height / 2f - part.offsetY,
                worldPaint,
            )
            canvas.restore()
        }
    }

    private fun drawBubble(canvas: Canvas, sk: Skeleton) {
        val text = bubble
        val s = spec ?: return
        if (text == null || bubbleLeft <= 0f) return
        val head = sk.find("head") ?: sk.root
        val x = head.worldPosition.x
        val y = head.worldPosition.y - s.headHeight * 0.45f
        val box = RectF(x - 200f, y - 82f, x + 200f, y - 4f)
        worldPaint.style = Paint.Style.FILL
        worldPaint.color = 0xF2FFFFFF.toInt()
        canvas.drawRoundRect(box, 26f, 26f, worldPaint)
        worldPaint.style = Paint.Style.STROKE
        worldPaint.strokeWidth = 4f
        worldPaint.color = 0x44222233
        canvas.drawRoundRect(box, 26f, 26f, worldPaint)
        worldPaint.style = Paint.Style.FILL
        worldPaint.strokeWidth = 0f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 44f
        textPaint.color = 0xFF222233.toInt()
        canvas.drawText(text, x, y - 22f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 11f * density
    }

    private fun drawHud(canvas: Canvas, rag: Ragdoll) {
        val mode = when {
            stiffness <= 0.01f -> "松垮"
            stiffness >= 0.99f -> "硬挺"
            else -> "半软"
        }
        val carrying = heldProp
        val state = when {
            carrying != null -> "拿着" + carrying.spec.name
            heldBone != null -> "抓住"
            rag.grounded -> "着地"
            else -> "空中"
        }
        val props = world?.live?.size ?: 0
        canvas.drawText(
            "刚度 " + mode + " · " + state + " · 道具 " + props +
                " · 缩放 " + (viewScale / defaultScale * 100).toInt() + "%",
            10f * density, 16f * density, textPaint
        )

        // Stat bars. These are the numbers the rules move, so they belong on the bench
        // rather than behind a menu: half of writing a rule is watching the number.
        val e = engine
        if (e != null) {
            var y = 30f * density
            for (stat in e.spec.stats) {
                val value = e.stats.get(stat.id)
                val span = (stat.max - stat.min).coerceAtLeast(0.0001f)
                val frac = ((value - stat.min) / span).coerceIn(0f, 1f)
                canvas.drawText(
                    stat.name + " " + value.toInt(),
                    10f * density, y + 9f * density, smallPaint
                )
                val left = 78f * density
                val right = left + 120f * density
                panelPaint.color = 0x33000000
                canvas.drawRoundRect(RectF(left, y, right, y + 8f * density), 4f * density, 4f * density, panelPaint)
                barPaint.color = statColour(stat.id)
                canvas.drawRoundRect(
                    RectF(left, y, left + (right - left) * frac, y + 8f * density),
                    4f * density, 4f * density, barPaint,
                )
                y += 14f * density
            }
        }

        // The event log: what happened, in order. Rules are written by watching this.
        val lines = (engine?.log() ?: emptyList()).takeLast(6)
        if (lines.isNotEmpty()) {
            val lineHeight = 12f * density
            val top = height - 10f * density - lineHeight * lines.size
            panelPaint.color = 0x1A000000
            canvas.drawRoundRect(
                RectF(6f * density, top - 6f * density, width - 6f * density, height - 4f * density),
                8f * density, 8f * density, panelPaint,
            )
            var y = top + 9f * density
            for (line in lines) {
                canvas.drawText(line, 12f * density, y, smallPaint)
                y += lineHeight
            }
        }
    }

    private fun statColour(id: String): Int = when (id) {
        "H" -> 0xFFD64545.toInt()
        "P" -> 0xFFE08A2E.toInt()
        else -> 0xFF6C4CE0.toInt()
    }

    private fun colourFor(name: String): Int = when {
        name.startsWith("shoulder") || name.startsWith("upperarm") ||
            name.startsWith("forearm") || name.startsWith("hand") ->
            if (name.endsWith("_L")) 0xFFE25A8C.toInt() else 0xFFE88C3C.toInt()
        name.startsWith("thigh") || name.startsWith("shin") || name.startsWith("foot") ->
            if (name.endsWith("_L")) 0xFF46AA6E.toInt() else 0xFF3C96B4.toInt()
        else -> 0xFF7C5CE0.toInt()
    }

    // -- input --------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rag = ragdoll ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastTapAt < 300) {
                    resetWorld()
                    lastTapAt = 0L
                    return true
                }
                lastTapAt = now
                val p = toWorld(event.x, event.y)

                // A prop under the finger wins: it is on top of the character in the
                // drawing, so it has to be on top of it here too.
                val prop = world?.grabAt(p)
                if (prop != null) {
                    heldProp = prop
                    prop.beginDrag()
                    lastFrameNs = System.nanoTime()
                    postInvalidateOnAnimation()
                    return true
                }

                val bone = rag.grabAt(p)
                if (bone != null) {
                    heldBone = bone.name
                    pinTarget = p
                    tapBone = bone.name
                    tapX = event.x
                    tapY = event.y
                    tapAt = now
                    fire(GameEvent(EventType.GRAB, part = bone.name))
                } else {
                    // Nothing under the finger: the finger is moving the window.
                    panning = true
                    lastPanX = event.x
                    lastPanY = event.y
                }
                lastFrameNs = System.nanoTime()
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger always means zoom, whatever the first one was doing.
                if (heldBone != null) rag.release()
                heldBone = null
                heldProp?.endDrag()
                heldProp = null
                panning = false
                tapBone = null
                pinchSpan = span(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val s = span(event)
                    if (pinchSpan > 1f && s > 1f) {
                        val before = toWorld(midX(event), midY(event))
                        viewScale = (viewScale * (s / pinchSpan))
                            .coerceIn(defaultScale * 0.25f, defaultScale * 6f)
                        val after = toWorld(midX(event), midY(event))
                        // Keep the point between the fingers pinned while zooming.
                        panX += before.x - after.x
                        panY += before.y - after.y
                        clampPan()
                    }
                    pinchSpan = s
                    postInvalidateOnAnimation()
                    return true
                }

                if (tapBone != null && hypot(event.x - tapX, event.y - tapY) > 12f * density) {
                    tapBone = null
                }

                val prop = heldProp
                if (prop != null) {
                    prop.dragTo(toWorld(event.x, event.y), 1f / 60f)
                } else if (heldBone != null) {
                    pinTarget = toWorld(event.x, event.y)
                } else if (panning) {
                    panX -= (event.x - lastPanX) / viewScale
                    panY -= (event.y - lastPanY) / viewScale
                    lastPanX = event.x
                    lastPanY = event.y
                    clampPan()
                }
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val prop = heldProp
                if (prop != null) {
                    releaseProp(prop)
                } else if (heldBone != null) {
                    val speed = rag.releaseSpeed()
                    heldBone = null
                    rag.release()
                    if (speed > THROW_SPEED) {
                        fire(GameEvent(EventType.THROWN, part = tapBone ?: "", value = speed))
                    } else {
                        fire(GameEvent(EventType.RELEASE, part = tapBone ?: ""))
                    }
                }
                // A press that never became a drag is a click: that is how "被点一下" is
                // told apart from "被抓起", which are very different things to react to.
                val tapped = tapBone
                if (tapped != null && System.currentTimeMillis() - tapAt < 300) {
                    fire(GameEvent(EventType.CLICK, part = tapped))
                }
                tapBone = null
                heldProp = null
                panning = false
                pinchSpan = 0f
                lastFrameNs = System.nanoTime()
                postInvalidateOnAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Let a prop go.
     *
     * A 射击 prop does not fly: it fires. The drag was the aim, the prop stays where it was
     * put, and a bullet leaves along the direction the finger went — which is why the two
     * kinds are worth having even though a bullet is just a small fast throw.
     */
    private fun releaseProp(prop: Prop) {
        val w = world ?: return
        val travelled = prop.velocity.length()
        val dir = if (travelled < 1f) Vec2.ZERO else prop.velocity.normalized()
        prop.endDrag()

        if (prop.spec.kindOf() == PropKind.SHOT && travelled > 200f) {
            w.spawn(
                prop.spec.bullet(),
                prop.position - dir * (prop.spec.radius + 10f),
                dir * SHOT_SPEED,
            )
            fire(GameEvent(EventType.RELEASE, part = "", value = 0f))
            return
        }
        if (prop.spec.kindOf() == PropKind.DEVICE) {
            // A device is placed, not thrown: whatever the finger was doing, it drops.
            prop.velocity = Vec2.ZERO
        }
        if (travelled > THROW_SPEED) {
            fire(GameEvent(EventType.THROWN, part = "", value = travelled))
        } else {
            fire(GameEvent(EventType.RELEASE))
        }
    }

    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    private fun midX(e: MotionEvent) = if (e.pointerCount >= 2) (e.getX(0) + e.getX(1)) / 2f else e.x
    private fun midY(e: MotionEvent) = if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2f else e.y

    companion object {
        private const val BUBBLE_SECONDS = 2.6f
        /** Above this the release is a throw, below it the pet was simply put down. */
        private const val THROW_SPEED = 700f
        private const val SHOT_SPEED = 2600f
    }
}
