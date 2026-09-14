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
import dev.atp.pet.engine.fluid.Fluid
import dev.atp.pet.engine.fluid.LiquidSpec
import dev.atp.pet.engine.fluid.Liquids
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
import dev.atp.pet.engine.skeleton.LayerSpec
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

    /** The layers as loaded, so the 状态 panel can count what hangs off each state. */
    private var layersNow: List<LayerSpec> = emptyList()

    /** Liquid, if any has been spilled. Created with the world: it needs the floor. */
    private var fluid: Fluid? = null
    private var liquids: List<LiquidSpec> = Liquids.DEFAULTS

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

    /**
     * One finger per pointer.
     *
     * Pulling two limbs at once is the whole point of a map here: a real hand has five
     * fingers and a ragdoll has two legs, and "hold both thighs and pull" is a thing
     * anybody tries within a minute of picking the pet up.
     */
    private val heldBones = HashMap<Int, String>()

    /** How far along that bone the finger landed. Zero would mean "by the joint", which
     *  for a leg is the hip, and a figure lifted by the hip never turns over. */
    private val heldOffsets = HashMap<Int, Float>()
    private val heldTargets = HashMap<Int, Vec2>()
    /**
     * Ropes. A rope is not a physics object: it is a pin whose target only exists while it
     * is taut, worked out fresh every frame from how far the body has got. Everything the
     * pin already does right -- joint limits, the root slide, gravity -- the rope inherits.
     */
    private class Rope(val anchor: Prop, val bone: String, val length: Float)

    private val ropes = mutableListOf<Rope>()

    private var heldProp: Prop? = null
    /** Which finger is carrying the prop, so it follows that one and not the first. */
    private var propPointer = -1

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
    private val ropePath = android.graphics.Path()
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
            parsed.drawOrder(),
            parsed.swaps,
        )

        ragdoll = Ragdoll(built, parsed, stiffness)
        layersNow = parsed.drawOrder()
        liquids = logic.liquids
        this.propSpecs = propSpecs
        propArt?.release()
        propArt = propsDir?.let { PartLibrary.loadFree(it) }
        world = PropWorld(parsed.floorY, parsed.worldWidth)
        fluid = Fluid(parsed.floorY, parsed.worldWidth)
        broken.clear()
        renderer?.hidden = broken
        particles.clear()
        bubble = null

        engine = RuleEngine(logic)
        clock = 0f
        prevRootY = built.root.worldPosition.y
        wasGrounded = true

        heldBones.clear()
        heldTargets.clear()
        heldOffsets.clear()
        heldProp = null
        framed = false
        lastFrameNs = System.nanoTime()
        report()
        fire(GameEvent(EventType.SPAWN))
        invalidate()
    }

    /**
     * The switches the character owns, as they are right now.
     *
     * A state is not only something a rule reads — it is a thing somebody can flip to see
     * what the other drawing looks like, and there is no way to write a rule about an arm
     * you have never seen fitted.
     */
    fun stateOn(id: String): Boolean = engine?.stateOn(id) == true

    fun toggleState(id: String) {
        val e = engine ?: return
        e.states[id] = !(e.states[id] ?: false)
        invalidate()
    }

    /**
     * Pour some of a liquid out, from a button rather than from a rule.
     *
     * Liquid was the one system with no way in except writing a rule and waiting for it to
     * fire, which is a strange thing to ask of somebody who just wants to know what their
     * slime looks like. The rules still spill; so does the button.
     */
    fun spill(id: String, count: Int = 40) {
        val liquid = Liquids.of(id, liquids)
        fluid?.spill(liquid.colour, Vec2(homeX(), homeY() - 400f), count, liquid.viscosity)
        invalidate()
    }

    /** How many drops are on the bench, so the 液体 bar can say whether anything is there. */
    fun dropCount(): Int = fluid?.drops?.size ?: 0

    /** How many drawings hang off a state, so the 状态 panel can say whether it is used. */
    fun stateLayerCount(id: String): Int =
        layersNow.count { it.state.removePrefix("!") == id }

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
        heldBones.clear()
        heldTargets.clear()
        heldOffsets.clear()
        heldProp = null
        world?.clear()
        ropes.clear()
        particles.clear()
        fluid?.clear()
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
                "spill" -> {
                    val liquid = Liquids.of(a.text, liquids)
                    fluid?.spill(
                        liquid.colour, pointOf(event),
                        a.value.toInt(), liquid.viscosity,
                    )
                }
                "impulse" -> {
                    val rag = ragdoll ?: continue
                    val sk = skeleton ?: continue
                    val bone = sk.find(a.bone.ifEmpty { event?.part ?: "" }) ?: sk.root
                    // "Push it away from whatever hit it" is the only one of these that
                    // needs the event, and it is the one that reads as physics rather than
                    // as animation.
                    val away = pointOf(event) - sk.root.worldPosition
                    val dir = when (a.text) {
                        "down" -> Vec2(0f, 1f)
                        "left" -> Vec2(-1f, 0f)
                        "right" -> Vec2(1f, 0f)
                        "away" -> if (away.length() < 1f) Vec2(0f, -1f) else away.normalized()
                        else -> Vec2(0f, -1f)
                    }
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
        // Handed over every frame rather than once: the renderer is rebuilt whenever the
        // rig changes, and a stale state map would show clothes that are not being worn.
        engine?.let { renderer?.states = it.states }

        val pins = heldBones.entries.mapNotNull { entry ->
            heldTargets[entry.key]?.let {
                Ragdoll.Pin(entry.value, it, heldOffsets[entry.key] ?: 0f)
            }
        }.toMutableList()
        pins.addAll(ropePins(sk))
        rag.step(dt, pins)

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
        // Liquid runs around the body: the same capsules the props collide with.
        fluid?.step(dt, s.gravity, sk) { rag.colliderRadius(it) }
        attachRopes()
    }

    /**
     * The ropes that are taut, as pins.
     *
     * A slack rope is not a pin at all -- it is a piece of string lying on the floor, and
     * the body should not feel it. Only once the distance passes the length does the pin
     * appear, with its target on the circle the rope allows, which is exactly "you may go
     * anywhere inside this circle and no further".
     */
    private fun ropePins(sk: Skeleton): List<Ragdoll.Pin> {
        if (ropes.isEmpty()) return emptyList()
        val out = mutableListOf<Ragdoll.Pin>()
        for (rope in ropes) {
            val bone = sk.find(rope.bone) ?: continue
            val p = bone.worldPosition
            val dx = p.x - rope.anchor.position.x
            val dy = p.y - rope.anchor.position.y
            val d = hypot(dx, dy)
            if (d <= rope.length) continue
            val t = rope.length / d
            out.add(
                Ragdoll.Pin(
                    rope.bone,
                    Vec2(rope.anchor.position.x + dx * t, rope.anchor.position.y + dy * t),
                )
            )
        }
        return out
    }

    /**
     * Tie a limb to a stake when the stake is against it.
     *
     * Contact is the gesture: there is no rope tool to find, and "the pet walked onto the
     * thing and got caught" is what a stake in the ground is FOR.
     */
    private fun attachRopes() {
        val w = world ?: return
        val sk = skeleton ?: return
        val rag = ragdoll ?: return
        for (prop in w.live) {
            if (prop.spec.kindOf() != PropKind.ANCHOR) continue
            if (ropes.any { it.anchor === prop }) continue
            var best: String? = null
            var bestD = Float.MAX_VALUE
            for (bone in sk.bones) {
                val gap = prop.spec.radius + rag.colliderRadius(bone)
                val head = bone.worldPosition
                val tip = bone.tipPosition()
                // Nearest point on the bone, the same capsule the liquid and the props use.
                val abx = tip.x - head.x
                val aby = tip.y - head.y
                val lenSq = abx * abx + aby * aby
                val t = if (lenSq < 1e-6f) 0f else
                    (((prop.position.x - head.x) * abx + (prop.position.y - head.y) * aby) / lenSq)
                        .coerceIn(0f, 1f)
                val d = hypot(prop.position.x - (head.x + abx * t), prop.position.y - (head.y + aby * t)) - gap
                if (d < bestD) {
                    bestD = d
                    best = bone.name
                }
            }
            if (best != null && bestD < 0f) {
                ropes.add(Rope(prop, best, prop.spec.ropeLength))
                onInfo?.invoke("拴住了 " + best + " · 绳子 " + prop.spec.ropeLength.toInt() + "px")
            }
        }
        // A rope whose stake has been taken away is not a rope any more.
        ropes.removeAll { w.live.none { live -> live === it.anchor } }
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

        drawFluid(canvas)
        drawCharacter(canvas, sk)
        drawProps(canvas)
        drawRopes(canvas, sk)
        drawBalance(canvas, sk)
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

    /**
     * The ropes, sagging when slack and straight when taut.
     *
     * The sag is the whole information: a rope drawn as a straight line tells you nothing
     * about whether the pet is at the end of it, and "am I about to be yanked back" is the
     * only question a leash ever asks.
     */
    private fun drawRopes(canvas: Canvas, sk: Skeleton) {
        if (ropes.isEmpty()) return
        worldPaint.style = Paint.Style.STROKE
        worldPaint.strokeCap = Paint.Cap.ROUND
        worldPaint.strokeWidth = 7f
        for (rope in ropes) {
            val bone = sk.find(rope.bone) ?: continue
            val a = rope.anchor.position
            val b = bone.worldPosition
            val d = hypot(b.x - a.x, b.y - a.y)
            val slack = 1f - (d / rope.length).coerceIn(0f, 1f)
            worldPaint.color = if (slack < 0.02f) 0xEE8A5A2B.toInt() else 0xBB6B5B45.toInt()
            ropePath.reset()
            ropePath.moveTo(a.x, a.y)
            ropePath.quadTo((a.x + b.x) / 2f, (a.y + b.y) / 2f + slack * 140f, b.x, b.y)
            canvas.drawPath(ropePath, worldPaint)
        }
        worldPaint.style = Paint.Style.FILL
        worldPaint.strokeWidth = 0f
    }

    /**
     * The liquid, as overlapping discs.
     *
     * Each drop is drawn twice: a wide faint one and a solid core. Overlapping faint discs
     * merge into one body of liquid where the drops are packed and fade out at the edges,
     * which is the whole of the surface-tension look and costs one more draw call.
     */
    private fun drawFluid(canvas: Canvas) {
        val f = fluid ?: return
        if (f.drops.isEmpty()) return
        worldPaint.style = Paint.Style.FILL
        for (d in f.drops) {
            worldPaint.color = d.colour
            worldPaint.alpha = 120
            canvas.drawCircle(d.x, d.y, d.radius * 1.9f, worldPaint)
            worldPaint.alpha = 215
            canvas.drawCircle(d.x, d.y, d.radius, worldPaint)
        }
        worldPaint.alpha = 255
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

    /**
     * Where the weight is, and where the body is being held.
     *
     * A body hangs when its centre of gravity ends up below the finger, and there is no way
     * to see that without drawing it — so "why is it not hanging" becomes a question the
     * screen answers instead of a paragraph. The COM is always drawn; the line to the grip
     * only exists while something is holding on.
     */
    private fun drawBalance(canvas: Canvas, sk: Skeleton) {
        val rag = ragdoll ?: return
        val com = rag.centreOfMass()

        worldPaint.style = Paint.Style.STROKE
        worldPaint.strokeWidth = 3f
        worldPaint.color = 0x88000000.toInt()
        for (held in heldBones) {
            val bone = sk.find(held.value) ?: continue
            val target = heldTargets[held.key] ?: continue
            worldPaint.color = 0x88D64545.toInt()
            canvas.drawCircle(target.x, target.y, 14f, worldPaint)
            worldPaint.color = 0x44D64545.toInt()
            canvas.drawLine(target.x, target.y, com.x, com.y, worldPaint)
        }

        val arm = com.y < (heldTargets.values.firstOrNull()?.y ?: com.y)
        worldPaint.color = if (arm) 0xCC2E9E6B.toInt() else 0xCCE08A2E.toInt()
        canvas.drawCircle(com.x, com.y, 9f, worldPaint)
        canvas.drawLine(com.x - 16f, com.y, com.x + 16f, com.y, worldPaint)
        canvas.drawLine(com.x, com.y - 16f, com.x, com.y + 16f, worldPaint)
        worldPaint.style = Paint.Style.FILL
        worldPaint.strokeWidth = 0f
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
            heldBones.isNotEmpty() -> "抓住 " + heldBones.size + " 处"
            rag.grounded -> "着地"
            else -> "空中"
        }
        val props = world?.live?.size ?: 0
        canvas.drawText(
            "刚度 " + mode + " · " + state + " · 道具 " + props +
                " · 缩放 " + (viewScale / defaultScale * 100).toInt() + "%",
            10f * density, 16f * density, textPaint
        )
        // Green when the weight is below the hand (it is hanging), amber when it is not.
        // That single dot is the whole of "is it hanging" as far as the physics is concerned.
        val com = rag.centreOfMass()
        val grips = heldTargets.values
        if (grips.isNotEmpty()) {
            val hanging = com.y > grips.first().y + 20f
            canvas.drawText(
                if (hanging) "重心在手的下面 · 吊着" else "重心还在上面 · 没吊起来",
                10f * density, 30f * density,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 10f * density
                    color = if (hanging) 0xCC2E9E6B.toInt() else 0xCCE08A2E.toInt()
                },
            )
        }

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

            // Which switches are on. They change what is drawn, so a part that has gone
            // missing is a rule question, and the answer belongs on the bench.
            val on = e.spec.states.filter { e.stateOn(it.id) }
            if (on.isNotEmpty()) {
                canvas.drawText(
                    "状态 " + on.joinToString(" ") { it.name },
                    10f * density, y + 9f * density, smallPaint,
                )
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
                if (!beginGrab(rag, event.getPointerId(0), event.x, event.y)) {
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
                // A finger that lands ON the pet pulls it; one that lands on nothing is the
                // start of a pinch. That is how "pull both thighs apart" and "zoom out" can
                // both be two-fingered gestures without either getting in the other's way.
                val index = event.actionIndex
                beginGrab(rag, event.getPointerId(index), event.getX(index), event.getY(index))
                panning = false
                pinchSpan = if (heldBones.isEmpty() && heldProp == null) span(event) else 0f
                lastFrameNs = System.nanoTime()
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                var grabbing = false
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (heldBones.containsKey(id)) {
                        heldTargets[id] = toWorld(event.getX(i), event.getY(i))
                        grabbing = true
                        if (tapBone != null &&
                            hypot(event.getX(i) - tapX, event.getY(i) - tapY) > 12f * density
                        ) {
                            tapBone = null
                        }
                    }
                }
                val prop = heldProp
                if (prop != null) {
                    val index = pointerIndex(event, propPointer)
                    if (index >= 0) {
                        prop.dragTo(toWorld(event.getX(index), event.getY(index)), 1f / 60f)
                        grabbing = true
                    }
                }
                if (grabbing) {
                    postInvalidateOnAnimation()
                    return true
                }

                // Nothing is being held, so two fingers mean zoom.
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

                if (panning) {
                    panX -= (event.x - lastPanX) / viewScale
                    panY -= (event.y - lastPanY) / viewScale
                    lastPanX = event.x
                    lastPanY = event.y
                    clampPan()
                }
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                endGrab(rag, event.getPointerId(event.actionIndex))
                pinchSpan = 0f
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                heldProp?.let { releaseProp(it) }
                heldProp = null
                propPointer = -1

                val tapped = tapBone
                val wasHolding = heldBones.isNotEmpty()
                // Read the speed BEFORE letting go: release() hands it to the body and then
                // forgets it.
                val speed = if (wasHolding) rag.releaseSpeed() else 0f
                for (id in heldBones.keys.toList()) endGrab(rag, id)
                if (wasHolding) {
                    if (speed > THROW_SPEED) {
                        fire(GameEvent(EventType.THROWN, part = tapped ?: "", value = speed))
                    } else {
                        fire(GameEvent(EventType.RELEASE, part = tapped ?: ""))
                    }
                }

                // A press that never became a drag is a click: that is how "被点一下" is
                // told apart from "被抓起", which are very different things to react to.
                if (tapped != null && System.currentTimeMillis() - tapAt < 300 &&
                    hypot(event.x - tapX, event.y - tapY) < 12f * density
                ) {
                    fire(GameEvent(EventType.CLICK, part = tapped))
                }
                tapBone = null
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
     * Take hold of whatever is under this finger. False when there is nothing there.
     *
     * A prop wins over a bone: it is drawn on top of the character, so it has to be on top
     * of it here too, or the hammer you just threw becomes impossible to pick back up.
     */
    private fun beginGrab(rag: Ragdoll, id: Int, x: Float, y: Float): Boolean {
        val p = toWorld(x, y)
        val prop = world?.grabAt(p)
        if (prop != null) {
            // Pulling the stake out of the ground is how a rope comes off. Anything else
            // needs a tool, a menu or a gesture nobody would guess.
            if (prop.spec.kindOf() == PropKind.ANCHOR) {
                ropes.removeAll { it.anchor === prop }
                onInfo?.invoke("绳子松开了")
            }
            heldProp = prop
            propPointer = id
            prop.beginDrag()
            return true
        }
        val grip = rag.grabAt(p) ?: return false
        val bone = grip.bone
        heldBones[id] = bone.name
        heldOffsets[id] = grip.offset
        heldTargets[id] = p
        tapBone = bone.name
        tapX = x
        tapY = y
        tapAt = System.currentTimeMillis()
        fire(GameEvent(EventType.GRAB, part = bone.name))
        return true
    }

    /** Let go with one finger. The body is only released once the last one is gone. */
    private fun endGrab(rag: Ragdoll, id: Int) {
        if (heldBones.remove(id) == null) return
        heldTargets.remove(id)
        heldOffsets.remove(id)
        if (heldBones.isEmpty()) rag.release()
    }

    private fun pointerIndex(e: MotionEvent, id: Int): Int {
        for (i in 0 until e.pointerCount) if (e.getPointerId(i) == id) return i
        return -1
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
        val kind = prop.spec.kindOf()
        if (kind == PropKind.DEVICE || kind == PropKind.ANCHOR) {
            // A device and a stake are placed, not thrown: whatever the finger was doing,
            // they drop where they are.
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
