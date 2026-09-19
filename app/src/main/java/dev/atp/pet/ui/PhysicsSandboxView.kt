package dev.atp.pet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.atp.pet.R
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.engine.event.EventType
import dev.atp.pet.engine.event.GameEvent
import dev.atp.pet.engine.fluid.Fluid
import dev.atp.pet.engine.particle.ParticleSpec
import dev.atp.pet.engine.fluid.LiquidSpec
import dev.atp.pet.engine.fluid.Liquids
import dev.atp.pet.engine.logic.ActionSpec
import dev.atp.pet.engine.logic.LogicSpec
import dev.atp.pet.engine.logic.RuleEngine
import dev.atp.pet.engine.logic.Shapes
import dev.atp.pet.engine.logic.Subjects
import dev.atp.pet.data.Settings
import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.math.normalizeAngle
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
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** Signals raised by rules and not yet handed back to the engine. See drainSignals. */
    private val signals = mutableListOf<String>()

    /**
     * The logic that belongs to props and liquids, and the engines running it.
     *
     * One engine per SUBJECT rather than one per object: two candles burn down together,
     * because they are the same candle. That is also the honest reading of "a prop uses that
     * logic set" — the logic is about the KIND of thing, and there is one bench.
     */
    private var objectLogic: Map<String, LogicSpec> = emptyMap()
    private val objectEngines = HashMap<String, RuleEngine>()

    /** Props whose landing has already been reported, so it is a transition and not a state. */
    private val propLanded = HashSet<Int>()

    /**
     * The app's own switches: gravity, what is drawn, and whether the effects run.
     *
     * Held here rather than read from disk every frame, and handed over whole by
     * [applySettings], because every one of them is something the bench reads while it is
     * already running, and none of them is about the character.
     */
    private var settings: Settings = Settings.DEFAULT

    fun applySettings(next: Settings) {
        settings = next
        ragdoll?.gravityScale = next.gravityScale
        invalidate()
    }

    /** Which subject every action currently being performed belongs to. See perform. */
    private var acting: String = Subjects.PET

    /** Where a bubble should be drawn, when the thing speaking is not the character. */
    private var bubbleAt: Vec2? = null

    /** The layers as loaded, so the 状态 panel can count what hangs off each state. */
    private var layersNow: List<LayerSpec> = emptyList()

    /** Liquid, if any has been spilled. Created with the world: it needs the floor. */
    private var fluid: Fluid? = null
    private var liquids: List<LiquidSpec> = Liquids.DEFAULTS

    /** Parts whose artwork is gone. The bones are still there; only the drawing is not. */
    private val broken = HashSet<String>()

    /**
     * A piece that has come off the figure: 断开部位.
     *
     * What it is: a copy of the part's artwork, starting where the bone was, keeping whatever
     * velocity the body had at that point, and falling to the floor.
     *
     * What it is NOT: a second body. The figure's own skeleton keeps the bone -- hidden, but
     * still there, still carrying weight, still swinging. So a pet whose hand has come off
     * still balances as if it had one. That is a deliberate cheat and it is written here
     * rather than left to be discovered, because the honest version means splitting the
     * skeleton into two ragdolls, and the whole solver is built around exactly one root.
     *
     * [position] is where the bone's REST position has been carried to: the artwork is drawn
     * relative to that, so a piece appears exactly where its bone was when it left.
     */
    /**
     * One bone of a piece, as it was when the piece left: head, tip and collider radius,
     * all relative to the piece's own origin.
     *
     * The piece carries its shape around with it, so the floor, the body and the finger all
     * see the limb rather than a circle drawn around it. One bounding circle was what this
     * started as, and it was wrong in three visible ways at once: an arm rested a whole arm's
     * length above the floor, the body shoved it away from a limb's distance off, and it
     * could be picked up from mid-air.
     */
    private class Piece(val head: Vec2, val tail: Vec2, val r: Float)

    private class Debris(
        /** The bone it was torn off at, and every bone under it: the piece is the whole limb. */
        val bones: List<String>,
        /** The bones' capsules, relative to [position] and turned by [angle]. */
        val shape: List<Piece>,
        var position: Vec2,
        var velocity: Vec2,
        var angle: Float,
        var spin: Float,
        /** The furthest any of the shape reaches from the origin: a cheap "not near it". */
        val bound: Float,
        var settled: Boolean = false,
    )

    /** A capsule moving with a piece: where its ends are right now, in canvas coordinates. */
    private fun headAt(d: Debris, p: Piece): Vec2 = d.position + p.head.rotated(d.angle)
    private fun tailAt(d: Debris, p: Piece): Vec2 = d.position + p.tail.rotated(d.angle)

    /** How far below [Debris.position] the piece's lowest point hangs. */
    private fun lowestDrop(d: Debris): Float =
        d.shape.maxOf { max(headAt(d, it).y, tailAt(d, it).y) + it.r } - d.position.y

    /**
     * How far the piece reaches left and right of its origin, at the angle it is lying at.
     *
     * The walls ask this: [Debris.bound] would also answer it, but a bound is the radius of a
     * circle around the whole limb, and a piece that stopped a whole arm's length short of the
     * wall would be the same bug as one that rested an arm's length above the floor.
     */
    private fun reachX(d: Debris): Float {
        var r = 0f
        for (pc in d.shape) {
            r = max(r, max(abs(headAt(d, pc).x - d.position.x),
                abs(tailAt(d, pc).x - d.position.x)) + pc.r)
        }
        return r
    }

    /** Distance from [p] to the piece's surface, negative inside it. */
    private fun distanceTo(d: Debris, p: Vec2): Float {
        if (hypot(p.x - d.position.x, p.y - d.position.y) > d.bound + 32f) return Float.MAX_VALUE
        var best = Float.MAX_VALUE
        for (pc in d.shape) {
            val a = headAt(d, pc)
            val b = tailAt(d, pc)
            val abx = b.x - a.x
            val aby = b.y - a.y
            val lenSq = abx * abx + aby * aby
            val k = if (lenSq < 1e-6f) 0f else
                (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
            val dist = hypot(p.x - (a.x + abx * k), p.y - (a.y + aby * k)) - pc.r
            if (dist < best) best = dist
        }
        return best
    }

    private val debris = mutableListOf<Debris>()

    /** The piece a finger is carrying right now, and where that finger is. */
    private var heldDebris: Debris? = null
    private var debrisPointer = -1
    private var fingerOnDebris: Vec2? = null

    /** Where the piece was touched, relative to its origin: what it hangs from while carried. */
    private var holdOffset = Vec2.ZERO

    /**
     * The finger that is TURNING the carried piece, and where it was on the last frame.
     *
     * One finger carries a piece and two fingers turn it, the way a photograph behaves in a
     * gallery: the second finger's angle around the piece is the angle it has been turned by.
     * A piece could otherwise only ever be moved -- it could not be picked up and put back
     * down the other way up, and once it had settled on the floor its angle was frozen for
     * good.
     */
    private var twistPointer = -1
    private var twistAt = 0f

    /** Where each bone's joint was last frame, so a piece torn off can keep its momentum. */
    private val lastBoneAt = HashMap<String, Vec2>()

    /**
     * Bones that have been 断开'd. Kept here as well as in the ragdoll ([Ragdoll.setGone])
     * because a reset has to be able to put them back: the ragdoll does not know which of
     * the hidden bones were hidden and which were removed.
     */
    private val detached = HashSet<String>()

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

    /**
     * A rule that asked for a STREAM instead of a splash: 流液体 / 持续喷粒子.
     *
     * The engine hands over an action once and forgets it, so anything that lasts longer than
     * a frame has to live somewhere the frame loop can find it. This is that: what to pour,
     * whose place to pour it from (asked again every frame, so a hand that moves takes the
     * stream with it), how fast, and how much longer.
     *
     * [carry] is the fraction of a drop that has been earned but not yet spilled. A stream of
     * 3 drops per second at 60 Hz earns 0.05 of a drop a frame, and dropping the remainder
     * every frame would make it a rate of 60 instead.
     */
    private class Emitter(
        val kind: String,
        val id: String,
        val subject: String,
        val rate: Float,
        var left: Float,
        /** 柱状 or 乱撒, for a liquid stream. See Shapes. */
        val shape: String = Shapes.COLUMN,
        var carry: Float = 0f,
    )

    private val emitters = mutableListOf<Emitter>()

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

    /**
     * The tuning panel's own paints. It sits over the artwork rather than over the HUD's pale
     * corner, so it is dark with light writing, and the numbers are monospaced: a readout
     * that is being read out loud must not shuffle sideways when a digit changes.
     */
    private val tunePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xD9171528.toInt()
    }
    private val tuneText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        color = 0x99FFFFFF.toInt()
    }
    private val tuneValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f * density
        color = 0xFFF4F2FB.toInt()
        typeface = Typeface.MONOSPACE
    }

    /** The phase rows' numbers: smaller than the headline pair, and still monospaced. */
    private val tunePhase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * density
        color = 0xFFF4F2FB.toInt()
        typeface = Typeface.MONOSPACE
    }
    /**
     * The path line under the two buttons: smaller than the readouts and monospaced.
     *
     * Monospaced because it is broken at slashes to fit, and a proportional font would make
     * the break points move with the digits. Small because getExternalFilesDir is long.
     */
    private val tunePath = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8f * density
        color = 0x88FFFFFF.toInt()
        typeface = Typeface.MONOSPACE
    }
    private val tunePad = 12f * density

    /**
     * The strip at the end of a phase row that the ← 凶手 marker is drawn in.
     *
     * Kept clear on every row, marked or not, so the rate column can be right-aligned in
     * one place: a column that jumps sideways when the culprit moves is a column nobody can
     * compare down.
     */
    private val culpritStrip = 42f * density

    var onInfo: ((String) -> Unit)? = null

    /**
     * Hand a finished CSV to the activity, which owns the share sheet. See MainActivity.
     *
     * A callback rather than an Intent built here because this view has no Activity of its
     * own to start one from, and because the file is the only thing the bench knows about:
     * whether it goes to a mail app, a chat or a cable is not a question about physics.
     */
    var onShareFile: ((File) -> Unit)? = null

    /** The 导出诊断 recorder: one row per frame, and the file the share button sends. */
    private val diag = DragDiagRecorder()

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
        objectLogic: Map<String, LogicSpec> = emptyMap(),
    ): Boolean {
        // A character whose file cannot be read must not take the app down with it. This is
        // on the way IN -- the bench loads on launch and again after every edit -- so a
        // truncated character.json used to mean an app that crashed on startup, every
        // startup, with nothing to be done about it from inside the app. The bench goes
        // empty and the caller says why.
        val parsed = CharacterSpec.parseOrNull(folder.specText()) ?: run {
            unload()
            return false
        }
        library?.release()
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
        particles.setKinds(logic.particles)
        this.propSpecs = propSpecs
        propArt?.release()
        propArt = propsDir?.let { PartLibrary.loadFree(it) }
        world = PropWorld(parsed.floorY, parsed.worldWidth)
        fluid = Fluid(parsed.floorY, parsed.worldWidth)
        broken.clear()
        renderer?.hidden = broken
        particles.clear()
        bubble = null
        bubbleAt = null

        // Seeded from the clock, so that two summons of the same character do not roll the
        // same dice in the same order. Only the rules that use 概率 and 数值随机 can tell;
        // everything else is exactly as reproducible as it was.
        engine = RuleEngine(logic, seed = System.nanoTime())
        this.objectLogic = objectLogic
        objectEngines.clear()
        propLanded.clear()
        clock = 0f
        prevRootY = built.root.worldPosition.y
        wasGrounded = true

        heldBones.clear()
        heldTargets.clear()
        emitters.clear()
        debris.clear()
        heldDebris = null
        debrisPointer = -1
        fingerOnDebris = null
        holdOffset = Vec2.ZERO
        twistPointer = -1
        lastBoneAt.clear()
        for (name in detached) ragdoll?.setGone(name, false)
        detached.clear()
        heldOffsets.clear()
        heldProp = null
        framed = false
        lastFrameNs = System.nanoTime()
        report()
        fire(GameEvent(EventType.SPAWN))
        invalidate()
        return true
    }

    /**
     * Empty the bench, and mean it.
     *
     * Everything the world owns goes, the bitmaps included: a library that has been released
     * but is still referenced is a renderer handing recycled pixels to a canvas, which is a
     * crash a long way from the thing that caused it. The next successful load builds all of
     * it again.
     */
    private fun unload() {
        library?.release()
        library = null
        renderer = null
        spec = null
        skeleton = null
        ragdoll = null
        world = null
        fluid = null
        engine = null
        propArt?.release()
        propArt = null
        propSpecs = emptyList()
        objectEngines.clear()
        propLanded.clear()
        heldBones.clear()
        heldTargets.clear()
        emitters.clear()
        debris.clear()
        heldDebris = null
        debrisPointer = -1
        fingerOnDebris = null
        holdOffset = Vec2.ZERO
        twistPointer = -1
        lastBoneAt.clear()
        for (name in detached) ragdoll?.setGone(name, false)
        detached.clear()
        heldOffsets.clear()
        heldProp = null
        propPointer = -1
        ropes.clear()
        broken.clear()
        signals.clear()
        particles.clear()
        bubble = null
        bubbleAt = null
        framed = false
        invalidate()
    }

    /**
     * The switches the character owns, as they are right now.
     *
     * A state is not only something a rule reads — it is a thing somebody can flip to see
     * what the other drawing looks like, and there is no way to write a rule about an arm
     * you have never seen fitted.
     */
    fun stateOn(tag: String): Boolean = engineFor(tag)?.stateOn(Subjects.tagState(tag)) == true

    fun toggleState(tag: String) {
        val e = engineFor(tag) ?: return
        val id = Subjects.tagState(tag)
        e.states[id] = !(e.states[id] ?: false)
        invalidate()
    }

    /**
     * Which engine owns a switch, from the tag the screen and the layers use.
     *
     * A tag is a plain state id for the character's own states, or "hand_L:sweat" for a part's
     * -- the same tag a layer carries, which is why the two levels can share a name without
     * anybody having to remember which one they are looking at. See Subjects.stateTag.
     */
    /** Every switch there is, keyed the way the layers name them. See engineFor. */
    private fun mergedStates(): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        engine?.states?.let { out.putAll(it) }
        for ((subject, e) in objectEngines) {
            if (!Subjects.isPart(subject)) continue
            val bone = Subjects.partId(subject)
            for ((id, on) in e.states) out[Subjects.stateTag(bone, id)] = on
        }
        return out
    }

    private fun engineFor(tag: String): RuleEngine? {
        val bone = Subjects.tagBone(tag)
        return if (bone.isEmpty()) engine else objectEngines[Subjects.part(bone)]
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
        fluid?.spill(
            liquid.colour, Vec2(homeX(), homeY() - 400f), count, liquid.viscosity,
            liquid = id, collides = liquid.collides,
        )
        invalidate()
    }

    /** How many drops are on the bench, so the 液体 bar can say whether anything is there. */
    fun dropCount(): Int = fluid?.drops?.size ?: 0

    /**
     * Hand over the liquid definitions the rules and the buttons refer to.
     *
     * The bench keeps its own copy -- it needs one to look up a colour by name -- and editing
     * 液体管理 deliberately does NOT reload the bench, because that would throw away the pet
     * that is mid-fall on it. Without this, a liquid added in 液体管理 pours as whatever the
     * default liquid happens to be, which is a colour that is not the one on the chip.
     */
    fun setLiquids(list: List<LiquidSpec>) {
        liquids = list
        invalidate()
    }

    /**
     * The particles this character declares, handed over the same way the liquids are.
     *
     * Same reason, too: 粒子管理 edits the list while the pet is standing on the bench, and
     * reloading the bench to pick up a colour would throw away whatever is mid-fall on it.
     */
    fun setParticles(list: List<ParticleSpec>) {
        particles.setKinds(list)
        invalidate()
    }

    /** Spray some of a particle out, from a button rather than from a rule. */
    fun spray(id: String, count: Int = 14) {
        particles.burst(id, Vec2(homeX(), homeY() - 300f), count)
        invalidate()
    }

    /** How many particles are alive, so 粒子管理 can say whether anything is there. */
    fun particleCount(): Int = particles.size

    /** How many marks they have left on the floor. */
    fun stainCount(): Int = particles.stainCount

    /** Sweep the bench. The pet, the props and the liquid are not touched. */
    fun clearParticles() {
        particles.clear()
        invalidate()
    }

    /** Mop the bench. The props and the pet are not touched. */
    fun clearLiquid() {
        fluid?.clear()
        invalidate()
    }

    /** How many drawings hang off a state, so the 状态 panel can say whether it is used. */
    fun stateLayerCount(tag: String): Int =
        layersNow.count { it.state.removePrefix("!") == tag }

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
        emitters.clear()
        debris.clear()
        heldDebris = null
        debrisPointer = -1
        fingerOnDebris = null
        holdOffset = Vec2.ZERO
        twistPointer = -1
        lastBoneAt.clear()
        for (name in detached) ragdoll?.setGone(name, false)
        detached.clear()
        heldOffsets.clear()
        heldProp = null
        signals.clear()
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
        drainSignals()
        // ...and every PART that should hear it.
        //
        // A part's rules are about that part -- 让手流汗 lives on the hand -- and about the
        // whole figure, which the hand is part of: 被甩出去 has to reach it too. See
        // Subjects.hears for the rule, and note that props and liquids keep the explicit
        // delivery they always had: this loop is only about parts, so nothing that was
        // already listening hears anything twice.
        for (subject in objectEngines.keys.toList()) {
            if (!Subjects.isPart(subject)) continue
            if (!Subjects.hears(subject, event)) continue
            fireTo(subject, event)
        }
    }

    /**
     * Hand the queued signals to the engine, a bounded number of times.
     *
     * The bound is the whole point. Signals are how one rule's 就 becomes another rule's 当,
     * which is a loop the moment somebody writes "when X, raise X" — and they will, because
     * it is one line and it looks harmless. Thirty-two rounds is far more than any chain
     * anybody means, and the ones past it are dropped rather than recalled next frame, so a
     * loop costs a frame of work each time instead of the whole app.
     */
    private fun drainSignals() {
        var guard = 0
        while (signals.isNotEmpty() && guard++ < MAX_SIGNALS_PER_FRAME) {
            val name = signals.removeAt(0)
            val e = engine ?: break
            perform(e.handle(GameEvent(EventType.EMIT, part = name, at = clock)), null)
        }
        signals.clear()
    }

    /**
     * Carry out what the rules asked for.
     *
     * The event that caused them comes along because "喷粒子" and "推一下" need to happen
     * AT the part that was hit, and the rule has no idea where that is.
     *
     * [subject] is whose rules these were. Almost every action already means "at the thing
     * this is about", and for a prop or a liquid that thing is not the character — so the
     * same action list, run from a candle's rules, burns the candle rather than the pet.
     */
    private fun perform(
        actions: List<ActionSpec>,
        event: GameEvent?,
        subject: String = Subjects.PET,
    ) {
        val outer = acting
        acting = subject
        try {
            runActions(actions, event)
        } finally {
            acting = outer
        }
    }

    private fun runActions(actions: List<ActionSpec>, event: GameEvent?) {
        for (a in actions) {
            when (a.kind) {
                "say" -> {
                    bubble = a.text
                    bubbleLeft = BUBBLE_SECONDS
                    bubbleAt = subjectPoint(acting)
                }
                "pose" -> applyPose(poseByName[a.text], home = false)
                "clearPose" -> applyPose(null)
                "spawn" -> propSpecs.firstOrNull { it.id == a.prop }?.let {
                    // From an object's own rules the new prop appears where that object is:
                    // a candle that spawns smoke should do it at the candle.
                    val at = subjectPoint(acting) ?: Vec2(homeX() - 200f, homeY() - 900f)
                    spawn(it, at, Vec2(120f, 60f))
                }
                "pushProp" -> {
                    val id = a.prop.ifEmpty { Subjects.propId(acting) }
                    val dir = directionOf(a.text, event)
                    if (id.isNotEmpty()) {
                        for (prop in world?.live.orEmpty()) {
                            if (prop.spec.id == id) prop.velocity = prop.velocity + dir * a.value
                        }
                    }
                }
                "clear" -> {
                    val what = a.prop.ifEmpty { Subjects.objectId(acting) }
                    if (Subjects.isLiquid(acting) || a.text == "liquid") {
                        fluid?.clearOf(what)
                    } else if (what.isNotEmpty()) {
                        world?.removeOf(what)
                    } else {
                        fluid?.clear()
                    }
                    particles.burst("dust", pointOf(event), 8)
                }
                "burst" -> particles.burst(a.text, pointOf(event), a.value.toInt())
                "spill" -> {
                    val liquid = Liquids.of(a.text, liquids)
                    fluid?.spill(
                        liquid.colour, pointOf(event),
                        a.value.toInt(), liquid.viscosity, liquid = liquid.id,
                        collides = liquid.collides,
                    )
                }
                // 流液体 / 持续喷粒子: registered rather than done. A second stream of the same
                // thing on the same subject replaces the first instead of running beside it --
                // two rules both saying "pour blood" is one wound, and two emitters would drain
                // the pool twice as fast for no reason anybody could see.
                "pour", "stream" -> {
                    if (a.text.isNotEmpty()) {
                        val id = a.text
                        val rate = a.value.coerceIn(1f, 200f)
                        val seconds = if (a.value2 > 0f) a.value2.coerceIn(0.1f, 60f) else 1f
                        emitters.removeAll { it.kind == a.kind && it.id == id && it.subject == acting }
                        emitters.add(
                            Emitter(a.kind, id, acting, rate, seconds, Shapes.of(a.shape))
                        )
                    }
                }
                "emit" -> {
                    // A signal, raised by one rule and heard by another. Queued rather than
                    // fired here and now: "when signal X, raise signal X" is one line to
                    // write and an infinite recursion to run, and a queue with a cap turns
                    // it into a signal that simply stops being heard.
                    if (a.text.isNotEmpty()) signals.add(a.text)
                }
                "impulse" -> {
                    val dir = directionOf(a.text, event)
                    // A prop's own rules shove the prop. Same action, same list, and the
                    // only thing that changed is what "this" means.
                    val propId = Subjects.propId(acting)
                    if (propId.isNotEmpty()) {
                        for (prop in world?.live.orEmpty()) {
                            if (prop.spec.id == propId) prop.velocity = prop.velocity + dir * a.value
                        }
                        continue
                    }
                    val rag = ragdoll ?: continue
                    val sk = skeleton ?: continue
                    val bone = sk.find(a.bone.ifEmpty { event?.part ?: "" }) ?: sk.root
                    rag.impulse(bone, dir, a.value)
                }
                // 隐藏 / 显示：骨架和物理一点都不动，动的只是"画不画它"。
                "break" -> {
                    val name = a.bone.ifEmpty { event?.part ?: "" }
                    if (name.isNotEmpty()) {
                        broken.add(name)
                        renderer?.hidden = broken
                        particles.burst("spark", pointOf(event), 12)
                    }
                }
                "show" -> {
                    val name = a.bone.ifEmpty { event?.part ?: "" }
                    if (name.isNotEmpty()) {
                        broken.remove(name)
                        renderer?.hidden = broken
                    }
                }
                // 断开 / 接回：断开是"藏起来 + 掉一块在地上"，接回是两样都收回去。
                "detach" -> {
                    val name = a.bone.ifEmpty { event?.part ?: "" }
                    if (name.isNotEmpty()) {
                        detachBone(name)
                        particles.burst("blood", pointOf(event), 10)
                    }
                }
                "rejoin" -> {
                    val name = a.bone.ifEmpty { event?.part ?: "" }
                    if (name.isNotEmpty()) rejoinBone(name)
                }
            }
        }
    }

    /**
     * The four directions a shove can go, as unit vectors.
     *
     * "away" is the only one that needs the event: it means away from whatever hit it, which
     * is the one that reads as physics rather than as animation.
     */
    private fun directionOf(name: String, event: GameEvent?): Vec2 {
        val sk = skeleton
        val away = if (sk == null) Vec2.ZERO else pointOf(event) - sk.root.worldPosition
        return when (name) {
            "down" -> Vec2(0f, 1f)
            "left" -> Vec2(-1f, 0f)
            "right" -> Vec2(1f, 0f)
            "away" -> if (away.length() < 1f) Vec2(0f, -1f) else away.normalized()
            else -> Vec2(0f, -1f)
        }
    }

    /**
     * Where the thing the rules are about is, in canvas coordinates.
     *
     * A prop is where it is; a liquid is the middle of its own drops, which is the puddle.
     * Null for the character and for an object that has gone, and every caller then falls
     * back to where the pet is — because "the thing this is about is not there" should show
     * up in the middle of the bench rather than at the origin.
     */
    private fun subjectPoint(subject: String): Vec2? {
        if (Subjects.isPart(subject)) {
            // The part's own place on the figure. This is what makes "让手流汗" work as one
            // rule: the spray lands on the hand whether the rule fired on a tick, on being
            // hit, or on anything else that did not name a bone.
            return skeleton?.find(Subjects.partId(subject))?.worldPosition
        }
        if (Subjects.isProp(subject)) {
            val id = Subjects.objectId(subject)
            return world?.live?.firstOrNull { it.spec.id == id }?.position
        }
        if (Subjects.isLiquid(subject)) {
            val id = Subjects.objectId(subject)
            var x = 0f
            var y = 0f
            var n = 0
            for (d in fluid?.drops.orEmpty()) {
                if (d.liquid != id) continue
                x += d.x
                y += d.y
                n++
            }
            return if (n == 0) null else Vec2(x / n, y / n)
        }
        return null
    }

    private fun pointOf(event: GameEvent?): Vec2 {
        subjectPoint(acting)?.let { return it }
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

    /**
     * Bring the finger back into view, if a zoomed-in window has lost it.
     *
     * Only when it is completely off the screen, and only just inside when it is: this is a
     * rescue, not a camera. Following a drag continuously is the obvious thing to write and it
     * fights the finger -- the window moves, the world under the finger moves with it, and the
     * pet is dragged by the camera as much as by the hand. Off-screen is a state the user can
     * see and did not ask for; a few pixels inside the edge is a state they can act on.
     */
    private fun rescueGrip() {
        val grip = heldTargets.values.firstOrNull() ?: return
        val vh = viewHeight()
        if (vh <= 0f) return
        val margin = vh * 0.12f
        val moved = when {
            grip.y < panY + margin -> {
                panY = grip.y - margin
                true
            }
            grip.y > panY + vh - margin -> {
                panY = grip.y - vh + margin
                true
            }
            else -> false
        }
        if (moved) clampPan()
    }

    /**
     * Keep the pet itself in the window, the way follow() keeps it there sideways.
     *
     * follow() has only ever moved horizontally, and the room got deeper: the pet now stands
     * near the BOTTOM of a room that is taller than a phone screen, so pinching to zoom --
     * which the status line invites you to do -- slides the pet out of the window below, and
     * nothing brought it back. An empty bench is the worst possible way to find out that the
     * camera is looking at the wrong part of the room, and this is the half that was missing.
     *
     * The middle of the figure is what follows, not one bone: a pet picked up by an ankle is
     * centred on the middle of what is hanging, which is where the eye is anyway.
     */
    private fun followVertical() {
        val sk = skeleton ?: return
        val vh = viewHeight()
        if (vh <= 0f || sk.bones.isEmpty()) return
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (b in sk.bones) {
            val y = b.worldPosition.y
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        if (top > bottom) return
        val centre = (top + bottom) / 2f
        // Entirely out of the window: go there now rather than easing towards it. Easing is
        // the right answer for a nudge and the wrong one for a pet nobody can see.
        if (bottom < panY || top > panY + vh) {
            panY = centre - vh / 2f
            clampPan()
            return
        }
        val topEdge = panY + vh * FOLLOW_MARGIN
        val bottomEdge = panY + vh * (1f - FOLLOW_MARGIN)
        when {
            centre < topEdge -> panY -= (topEdge - centre) * FOLLOW_EASE
            centre > bottomEdge -> panY += (centre - bottomEdge) * FOLLOW_EASE
            else -> return
        }
        clampPan()
    }

    /**
     * A pet that is nowhere on the screen comes straight back.
     *
     * followVertical() eases, which is right for a nudge and wrong for a pet nobody can see:
     * this is the same "window contains none of it" answer with no easing at all. It only
     * fires when every bone is outside, so inside the dead zone it does nothing whatsoever.
     *
     * It runs under the same guard as the two above, which is deliberate. 镜头跟着 off means
     * "do not move my camera" -- and a pet somewhere the user panned to is a pet they chose
     * to look away from. The way back is the one that is already written on the screen:
     * 双击复位, which reframes on the pet and works whatever the switches say.
     */
    private fun rescuePet() {
        val sk = skeleton ?: return
        if (sk.bones.isEmpty()) return
        val vw = viewWidth()
        val vh = viewHeight()
        if (vw <= 0f || vh <= 0f) return
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (b in sk.bones) {
            val p = b.worldPosition
            if (p.x < left) left = p.x
            if (p.x > right) right = p.x
            if (p.y < top) top = p.y
            if (p.y > bottom) bottom = p.y
        }
        if (left > right || top > bottom) return
        var moved = false
        if (right < panX || left > panX + vw) {
            panX = (left + right) / 2f - vw / 2f
            moved = true
        }
        if (bottom < panY || top > panY + vh) {
            panY = (top + bottom) / 2f - vh / 2f
            moved = true
        }
        if (moved) clampPan()
    }

    // -- simulation ---------------------------------------------------------

    private fun simulate(dt: Float) {
        val s = spec ?: return
        val sk = skeleton ?: return
        val rag = ragdoll ?: return

        clock += dt
        if (bubbleLeft > 0f) bubbleLeft -= dt
        // Counted from zero every frame rather than accumulated for ever: what the panel
        // prints is how many steps THIS frame's delta was spread over. See stepsPerFrame.
        stepsPerFrame = 0

        val actions = engine?.step(dt) ?: emptyList()
        if (actions.isNotEmpty()) {
            perform(actions, null)
            // Every path that performs actions has to drain what they raised, and this one is
            // not fire(): a signal raised by a 每隔一会儿 rule would otherwise sit in the queue
            // until the next touch, which is a rule that works when you poke the pet and does
            // nothing when you leave it alone.
            drainSignals()
        }
        // Handed over every frame rather than once: the renderer is rebuilt whenever the
        // rig changes, and a stale state map would show clothes that are not being worn.
        //
        // The renderer holds ONE map of switches, so a part's own states go in under the tag
        // the layers use -- "hand_L:sweat" -- and the character's own go in under their plain
        // names. That is the whole of the two levels as far as drawing is concerned:
        // LayerSpec.visible needs no change, because a tag is a key either way.
        renderer?.states = mergedStates()

        val pins = heldBones.entries.mapNotNull { entry ->
            // Handed over as it is. The low pass that used to be here went with the solver
            // that needed it: 0.18.0 takes the finger as it finds it.
            heldTargets[entry.key]?.let {
                Ragdoll.Pin(entry.value, it, heldOffsets[entry.key] ?: 0f)
            }
        }.toMutableList()
        pins.addAll(ropePins(sk))
        // The bone the tuning panel is about, named BEFORE the step rather than after it:
        // the four phase boundaries only exist inside the step, so the bone has to be handed
        // in first and the pieces read back out once it has run. The same name goes to
        // measureJitter below, which is what lets the split and the whole-frame turn be two
        // readings of one joint instead of two readings of two. See Ragdoll.probeBone.
        val watched = heldBones.values.firstOrNull()
        rag.probeBone = watched
        rag.step(dt, pins)
        // What that step was handed, and that there was one of it. Taken here rather than in
        // onDraw because this is the call the number is about: the panel prints the delta the
        // physics actually got, not the one the frame was measured at. See stepMs.
        stepMs = dt * 1000f
        stepMsRing[stepMsHead] = stepMs
        stepMsHead = (stepMsHead + 1) % STEP_SAMPLES
        stepsPerFrame++
        // Straight after the step, so that what the tuning panel shows is where the held
        // joint ENDED UP this frame. See measureJitter. It hands back that same signed turn,
        // which is the one number on the row below that cannot be read off the ragdoll.
        val turn = measureJitter(rag, watched)
        // And the frame into the file, when somebody asked for the file. Read-only in both
        // directions: every value it writes was already computed for the panel, and the
        // panel's numbers are the same numbers whether or not anything is recording.
        recordFrame(dt, s, rag, sk, turn)

        world?.let { w ->
            val hits = w.step(
                dt,
                s.gravity * settings.gravityScale,
                sk,
                // A part with collides = false is invisible to the props: same bone, same
                // mass, same swing, radius zero. See BoneSpec.collides.
                { bone -> if (rag.isSolid(bone.name)) rag.colliderRadius(bone) else 0f },
                { bone, dir, strength -> rag.impulse(bone, dir, strength) },
            )
            for (h in hits) {
                fire(GameEvent(EventType.PROP_HIT, part = h.bone.name, value = h.value, prop = h.prop.spec.id))
                // The prop hears about it too: it hit something, and what it hit is the part.
                fireTo(
                    Subjects.prop(h.prop.spec.id),
                    GameEvent(EventType.IMPACT, part = h.bone.name, value = h.value, prop = h.prop.spec.id),
                )
            }
            // A prop that has just touched down. A transition, not a state: "on the floor" as
            // a state is a TICK rule with a condition, which is where that belongs.
            for (prop in w.live) {
                if (prop.onFloor && propLanded.add(prop.serial)) {
                    fireTo(
                        Subjects.prop(prop.spec.id),
                        GameEvent(EventType.LANDED, value = abs(prop.velocity.y), prop = prop.spec.id),
                    )
                } else if (!prop.onFloor) {
                    propLanded.remove(prop.serial)
                }
            }
        }
        stepObjects(dt)

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

        if (settings.particles) {
            val landed = particles.step(dt, s.floorY)
            // One event per KIND per frame, carrying how many drops it was. Forty sparks
            // landing together are one splash; forty events would be forty runs of the same
            // rules inside a single frame, and a rule with no cooldown would do its thing
            // forty times for one visible thing happening.
            for ((id, n) in landed.groupingBy { it }.eachCount()) {
                fireTo(
                    Subjects.particle(id),
                    GameEvent(EventType.LANDED, value = n.toFloat(), particle = id),
                )
            }
        }
        // Liquid runs around the body: the same capsules the props collide with. The switch
        // is a performance one first and an aesthetic one second, and liquid is the expensive
        // half of the two.
        if (settings.liquid) {
            val touchedDown = fluid?.step(dt, s.gravity * settings.gravityScale, sk) {
                if (rag.isSolid(it.name)) rag.colliderRadius(it) else 0f
            }.orEmpty()
            // One 落地 per liquid per frame, carrying how many drops it was -- the same shape
            // the particle kinds get below, and for the same reason: a wound that sprays
            // forty drops is one splash, not forty runs of the same rule inside one frame.
            //
            // This is the line the liquids never had. The README's own event table has
            // promised 落地 to props AND liquids since the two were written, and nothing on
            // the bench ever handed a liquid an event: a liquid's engine only ever saw 出现时
            // and 每隔一会儿, so every rule written on one with 当=落地 was dead on arrival.
            for ((id, n) in touchedDown.groupingBy { it }.eachCount()) {
                fireTo(Subjects.liquid(id), GameEvent(EventType.LANDED, value = n.toFloat()))
            }
        }
        stepEmitters(dt)
        rememberBones(sk)
        stepDebris(dt, s)
        attachRopes()
    }

    /**
     * Every piece that has come off, drawn where it fell. See [Debris].
     *
     * One motion for the whole piece: it turns about the joint it was torn off at (that
     * joint's rest position), and every bone in it is drawn under that same motion, so an arm
     * keeps its elbow and its hand.
     */
    private fun drawDebris(canvas: Canvas) {
        val art = renderer ?: return
        for (d in debris) {
            val origin = art.restPosition(d.bones.first()) ?: continue
            val move = Transform(d.position + (Vec2.ZERO - origin).rotated(d.angle), d.angle)
            for (b in d.bones) art.drawMoved(canvas, b, move)
        }
    }

    /** One frame of "where was every joint", for the pieces that are about to leave. */
    private fun rememberBones(sk: Skeleton) {
        for (b in sk.bones) lastBoneAt[b.name] = b.worldPosition
    }

    /**
     * Gravity, the floor and the walls, and nothing else.
     *
     * A piece that has come off does not collide with the figure it came off: it is a copy of
     * a drawing, and shoving the body around with it would be the bench pretending the copy
     * is real -- the one thing this cheat must not do. The floor is real, because a piece
     * that fell through the bench would be worse than no feature at all.
     */
    private fun stepDebris(dt: Float, spec: CharacterSpec) {
        if (debris.isEmpty()) return
        val floorY = spec.floorY
        for (d in debris) {
            if (d.settled) continue
            if (d === heldDebris) {
                // Carried: it goes where the finger goes (and no further down than the
                // floor), and it keeps no velocity of its own -- letting go hands it the
                // finger's, which is what makes throwing a piece feel like throwing.
                //
                // The grab offset is kept, so the piece hangs from the point that was
                // touched instead of snapping its shoulder under the finger.
                val at = fingerOnDebris ?: d.position
                val was = d.position
                d.position = Vec2(at.x + holdOffset.x, min(at.y + holdOffset.y, floorY - lowestDrop(d)))
                d.velocity = (d.position - was) / max(dt, 1e-3f)
                d.spin *= 0.85f
                d.angle += d.spin * dt
                bodyPushOut(d, spec, floorY)
                continue
            }
            d.velocity = Vec2(
                d.velocity.x * (1f - DEBRIS_DRAG * dt),
                d.velocity.y + spec.gravity * settings.gravityScale * dt,
            )
            d.position = d.position + d.velocity * dt
            d.angle += d.spin * dt
            val drop = lowestDrop(d)
            if (d.position.y + drop >= floorY) {
                d.position = Vec2(d.position.x, floorY - drop)
                // Landing is not a bounce: a hand that hits the table stops. What is left is
                // the slide, and the spin dies with it.
                d.velocity = Vec2(d.velocity.x * 0.5f, 0f)
                d.spin *= 0.4f
                if (d !== heldDebris && abs(d.velocity.x) < DEBRIS_REST && abs(d.spin) < 0.2f) {
                    d.velocity = Vec2.ZERO
                    d.spin = 0f
                    d.settled = true
                }
            }
            bodyPushOut(d, spec, floorY)
            val half = reachX(d) + 4f
            if (d.position.x - half < 0f) {
                d.position = Vec2(half, d.position.y)
                d.velocity = Vec2(abs(d.velocity.x) * 0.3f, d.velocity.y)
            } else if (d.position.x + half > spec.worldWidth) {
                d.position = Vec2(spec.worldWidth - half, d.position.y)
                d.velocity = Vec2(-abs(d.velocity.x) * 0.3f, d.velocity.y)
            }
        }
    }

    /**
     * Contact between a piece and the body, in BOTH directions.
     *
     * The piece is moved out of the body, and the body is pushed away from the piece by the
     * same overlap -- a severed arm that lands across the pet's back now shoves it, which is
     * what "the piece can move the bones it came off" means. It used to be one-way, on the
     * theory that a hand should not wag the dog; the owner asked for the other behaviour and
     * the argument for one-way was never a measurement, just tidiness.
     *
     * The push on the body is an impulse, because that is the only door the solver leaves
     * open for something outside it: [impulse] hands the figure a velocity and a spin about
     * the bone that was touched. It is scaled by how deep the contact is and capped, so a
     * piece resting on the pet keeps nudging it while a piece dropped on it from a height
     * shoves it once, hard.
     */
    private fun bodyPushOut(d: Debris, spec: CharacterSpec, floorY: Float) {
        val sk = skeleton ?: return
        val rag = ragdoll ?: return
        // The piece's own bones are tested against the body's, end by end: a limb is a handful
        // of capsules, and the ends are what sticks out of it. Every overlap found is applied
        // as a move of the whole piece -- it is rigid, so pushing its origin moves all of it.
        for (pc in d.shape) {
            for (end in listOf(headAt(d, pc), tailAt(d, pc))) {
                for (b in sk.bones) {
                    if (!rag.isSolid(b.name)) continue
                    val a = b.worldPosition
                    val t = b.tipPosition()
                    val abx = t.x - a.x
                    val aby = t.y - a.y
                    val lenSq = abx * abx + aby * aby
                    val k = if (lenSq < 1e-6f) 0f else
                        (((end.x - a.x) * abx + (end.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
                    val dx = end.x - (a.x + abx * k)
                    val dy = end.y - (a.y + aby * k)
                    val dist = hypot(dx, dy)
                    val gap = pc.r + rag.colliderRadius(b)
                    if (dist >= gap || dist < 1e-4f) continue
                    val ux = dx / dist
                    val uy = dy / dist
                    val slide = gap - dist
                    val next = Vec2(d.position.x + ux * slide, d.position.y + uy * slide)
                    d.position = Vec2(next.x, min(next.y, floorY - lowestDrop(d)))
                    val into = d.velocity.x * ux + d.velocity.y * uy
                    if (into < 0f) {
                        d.velocity = Vec2(d.velocity.x - ux * into, d.velocity.y - uy * into)
                    }
                    // And the other way. The same overlap, in the opposite direction, as a
                    // per-frame impulse on the bone that was touched -- see the note above.
                    val push = min(slide * PIECE_PUSH, PIECE_PUSH_MAX)
                    rag.impulse(b, Vec2(-ux, -uy), push)
                }
            }
        }
    }

    /** What is under this finger, if it is a piece that came off. Nearest one wins. */
    private fun debrisAt(x: Float, y: Float): Debris? {
        val p = toWorld(x, y)
        var best: Debris? = null
        var bestDist = Float.MAX_VALUE
        for (d in debris) {
            val dist = distanceTo(d, p)
            if (dist < bestDist) {
                bestDist = dist
                best = d
            }
        }
        return if (best != null && bestDist < 24f) best else null
    }

    /**
     * Take one bone's drawing off the figure and drop it.
     *
     * The velocity is the one the joint had a frame ago, so a hand torn off a pet that was
     * being thrown flies the way it was already going -- which is the whole read of the
     * effect. A piece that only ever fell straight down looks switched off, not torn.
     */
    private fun detachBone(name: String) {
        val sk = skeleton ?: return
        val bone = sk.find(name) ?: return
        val rag = ragdoll ?: return

        // The whole limb, not the joint that was named: a shoulder that comes off takes the
        // arm with it. Anything else leaves a forearm floating where the upper arm was, which
        // is not a thing that happens to a body.
        val piece = subtreeOf(bone)
        for (b in piece) {
            broken.add(b.name)
            detached.add(b.name)
            rag.setGone(b.name, true)
            for (id in heldBones.filterValues { it == b.name }.keys.toList()) endGrab(rag, id)
        }
        renderer?.hidden = broken

        // The shape it carries: every bone's capsule, measured relative to the joint it was
        // torn off at, so the floor, the body and the finger all see the limb itself rather
        // than a circle around it. Measured now, from the pose it left in.
        val shape = piece.map { b ->
            Piece(
                head = b.worldPosition - bone.worldPosition,
                tail = b.tipPosition() - bone.worldPosition,
                r = rag.colliderRadius(b),
            )
        }
        var bound = 0f
        for (pc in shape) {
            bound = max(bound, max(pc.head.length(), pc.tail.length()) + pc.r)
        }

        val was = lastBoneAt[name] ?: bone.worldPosition
        val vel = (bone.worldPosition - was) * 60f
        val kick = if (bone.worldPosition.x >= sk.root.worldPosition.x) 40f else -40f
        // One piece: a second call for the same limb replaces the first rather than dropping
        // two copies of the same arm.
        val names = piece.map { it.name }.toSet()
        debris.removeAll { d -> d.bones.any { it in names } }
        debris.add(
            Debris(
                bones = piece.map { it.name },
                shape = shape,
                position = bone.worldPosition,
                velocity = Vec2(vel.x + kick, min(vel.y, 0f) - 40f),
                angle = bone.worldRotation - bone.restRotation,
                spin = kick / 220f,
                bound = bound,
            )
        )
    }

    /** This bone and everything hanging off it, parents first -- the order artwork is drawn in. */
    private fun subtreeOf(root: Bone): List<Bone> {
        val out = mutableListOf<Bone>()
        fun walk(b: Bone) {
            out.add(b)
            for (c in b.children) walk(c)
        }
        walk(root)
        return out
    }

    /** Put it back: the drawing returns and the piece on the floor is taken away. */
    private fun rejoinBone(name: String) {
        // Whichever piece this bone is part of -- it may be the shoulder that was named or
        // the hand that came off with it -- the whole piece goes back.
        val piece = debris.firstOrNull { name in it.bones }?.bones ?: listOf(name)
        for (b in piece) {
            broken.remove(b)
            detached.remove(b)
            ragdoll?.setGone(b, false)
        }
        renderer?.hidden = broken
        debris.removeAll { d -> d.bones.any { it in piece } }
    }

    /**
     * Spill whatever the rules are still streaming, at the rate they asked for.
     *
     * The position is looked up every frame rather than remembered: "让手流血" is a stream that
     * comes out of the hand, so if the hand moves the blood follows. A stream whose subject has
     * gone falls back to the bench's own spot, which is the same answer `pointOf` gives a rule.
     */
    private fun stepEmitters(dt: Float) {
        if (emitters.isEmpty()) return
        val finished = mutableListOf<Emitter>()
        for (e in emitters) {
            e.left -= dt
            e.carry += e.rate * dt
            val n = e.carry.toInt()
            e.carry -= n
            if (n > 0) {
                val at = subjectPoint(e.subject) ?: Vec2(homeX(), homeY() - 400f)
                if (e.kind == "pour") {
                    val liquid = Liquids.of(e.id, liquids)
                    if (Shapes.of(e.shape) == Shapes.COLUMN) {
                        fluid?.pour(
                            liquid.colour, at, n, liquid.viscosity,
                            liquid = liquid.id, collides = liquid.collides,
                        )
                    } else {
                        fluid?.spill(
                            liquid.colour, at, n, liquid.viscosity,
                            liquid = liquid.id, collides = liquid.collides,
                        )
                    }
                } else {
                    particles.burst(e.id, at, n)
                }
            }
            if (e.left <= 0f) finished.add(e)
        }
        emitters.removeAll(finished)
    }

    /**
     * Run the props' and liquids' own logic, for the ones that are on the bench.
     *
     * An engine only runs while its subject is actually present. A candle's rules ticking
     * away in an empty room would burn it down before anybody lit it — and "is it there" is
     * the one condition every rule of an object's would otherwise have to write for itself.
     */
    private fun stepObjects(dt: Float) {
        val w = world
        val f = fluid

        val present = LinkedHashSet<String>()
        if (w != null) for (prop in w.live) present.add(Subjects.prop(prop.spec.id))
        if (f != null) for (id in Liquids.present(f.drops)) present.add(Subjects.liquid(id))
        // A particle kind is present while it has drops in the air. Per KIND, not per drop: a
        // drop lives about a second, so a rule written on one spark would have nothing left to
        // run against by the time anybody read the log.
        if (settings.particles) for (id in particles.liveKinds()) present.add(Subjects.particle(id))
        // Every part of the character is a subject, and it exists exactly as long as the
        // character does -- so a part is present whenever the bench is. The lookup below
        // filters this down to the parts somebody has actually given rules to.
        skeleton?.let { sk -> for (bone in sk.bones) present.add(Subjects.part(bone.name)) }

        // A subject that has just appeared gets its SPAWN, which is what a rule about "when
        // the candle appears" has been waiting for.
        for (subject in present) {
            if (objectEngines.containsKey(subject)) continue
            val spec = objectLogic[subject] ?: continue
            // Rules OR states: a part whose only logic is a switch of its own still needs an
            // engine, because that engine is what owns the switch -- the bench's chip flips it
            // and the drawing waits on it, and neither has anything to do with a rule.
            if (spec.rules.isEmpty() && spec.states.isEmpty()) continue
            // One generator per subject, and a different one each time the subject appears:
            // see the note on the character's own engine.
            val engine = RuleEngine(spec, seed = System.nanoTime())
            objectEngines[subject] = engine
            fireTo(subject, GameEvent(EventType.SPAWN))
        }

        for ((subject, engine) in objectEngines) {
            if (subject !in present) continue
            val actions = engine.step(dt)
            if (actions.isNotEmpty()) {
                perform(actions, null, subject)
                drainSignals()
            }
        }
    }

    /** Something happened to a prop or a liquid. Silently ignored if it has no logic. */
    private fun fireTo(subject: String, event: GameEvent) {
        val engine = objectEngines[subject] ?: return
        val actions = engine.handle(event.copy(at = clock))
        if (actions.isEmpty()) return
        perform(actions, event, subject)
        drainSignals()
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
        if (!panning && heldProp == null) {
            if (heldTargets.isEmpty()) {
                if (settings.followPet) {
                    follow()
                    followVertical()
                    // Those two ease towards where the pet should be; this one is for when
                    // it is nowhere at all. See rescuePet.
                    rescuePet()
                }
            } else {
                // A finger is holding the pet, and while it is, the camera does not move.
                //
                // A finger is a SCREEN position and the world point under it is
                // pan + screen/zoom, so a camera that slides is a finger that slides. With
                // 镜头跟着 on, following a held pet moves the world under the hand, the hand
                // then pulls the pet a little further, the camera follows that, and the pet
                // is shoved for ever by a hand that is not moving. Held against the floor,
                // that shove became a 311 px vibration at half the frame rate — the worst
                // bug this bench has had. See the note in Ragdoll.hanging for the half of it
                // that was the solver, and the floor-hold cases in tools/drag_check.py,
                // which drag the finger exactly the way the camera used to.
                //
                // Nothing is lost by it: the finger is already on the screen, and the only
                // other thing that could need keeping in view is the body hanging off it,
                // which is what rescueGrip is for.
                rescueGrip()
            }
        }

        if (settings.showGrid) {
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
        }
        if (settings.showGround) {
            canvas.drawLine(0f, vy(s.floorY), width.toFloat(), vy(s.floorY), groundPaint)
        }

        // Everything below shares the world transform, so it pans and zooms together.
        canvas.save()
        canvas.translate(-panX * viewScale, -panY * viewScale)
        canvas.scale(viewScale, viewScale)

        if (settings.particles) particles.draw(canvas, worldPaint)

        if (settings.liquid) drawFluid(canvas)
        drawCharacter(canvas, sk)
        drawDebris(canvas)
        drawProps(canvas)
        drawRopes(canvas, sk)
        if (settings.showBalance) drawBalance(canvas, sk)
        drawBubble(canvas, sk)

        canvas.restore()

        drawHud(canvas, rag)
        // Over the top of everything else, including the event log: while the panel is open
        // it is the thing being read, and it is drawn on top of the pet for the same reason
        // it takes the finger first. See tuneDown.
        drawTune(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawCharacter(canvas: Canvas, sk: Skeleton) {
        // Where the artwork's own canvas sits, so the pet's home patch is findable.
        val s = spec
        if (s != null) {
            canvas.drawRect(0f, 0f, s.canvasWidth, s.floorY, canvasPaint)
        }
        val art = renderer
        if (art != null) art.draw(canvas)
        // The rig over the artwork is a switch, and it is the only way to see whether a
        // drawing actually sits on the bone it is attached to.
        if (art != null && !settings.showBones) return
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
        // A prop or a liquid can say something too, and then it says it about itself: a
        // candle that announces it is burning down has to do it over the candle.
        val at = bubbleAt
        val head = sk.find("head") ?: sk.root
        val x = at?.x ?: head.worldPosition.x
        val y = (at?.y ?: head.worldPosition.y) - s.headHeight * 0.45f
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
        // Which switches are on, on the HUD as well as on the chip row: a state that changes
        // what is drawn is invisible until you know it is on, and "the pet looks wrong" is
        // usually "a state I forgot about".
        val on = engine?.states?.filterValues { it }?.keys?.toList().orEmpty()
        val switches = if (on.isEmpty()) "" else " · 开：" + on.joinToString(" ")
        canvas.drawText(
            "刚度 " + mode + " · " + state + " · 道具 " + props + switches +
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

        // Why the bench is empty, when the bench is empty.
        //
        // Every one of these has actually happened here: a parts folder with nothing in it, a
        // layer list that no longer matches the files, every drawing hidden by a state, and --
        // twice -- a pet drawn somewhere that is not the screen. From the outside they look
        // identical, an empty room, which is the worst possible way to be told about any of
        // them. So the bench works out which one it is and says it in the middle of the room.
        val blank = blankReason()
        if (blank.isNotEmpty()) {
            canvas.drawText(
                blank, width / 2f, height / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 13f * density
                    color = 0xCCB03030.toInt()
                    textAlign = Paint.Align.CENTER
                },
            )
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

    /**
     * The one line the bench shows when it drew no part at all, or "" when it drew something.
     *
     * Two numbers do all the work: how many layers COULD draw (their artwork is on disk) and
     * how many did. Nothing here is a guess about the physics -- it is the renderer reporting
     * on itself, which is the only part of the app that knows.
     */
    private fun blankReason(): String {
        val art = renderer ?: return "这个角色没有部位图：parts/ 是空的，所以什么都画不出来"
        val parts = library?.size ?: 0
        return when {
            art.drawable == 0 ->
                "有 " + parts + " 张部位图，但 character.json 里没有和它们对得上的图层"
            art.drawnLastFrame == 0 ->
                "这个状态下所有图都被藏起来了：看看状态开关，和图层的状态标记"
            else -> ""
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

    // ── the IK tuning panel ─────────────────────────────────────────────────

    /**
     * A bench for the one number that can be read off it: how much the drag is reversing.
     *
     * What is on it, and what is not: the two numbers, the four rows that say which phase of
     * the step is doing the reversing, the timing row, and the raw record. The two sliders
     * that used to be here set knobs the 0.18.0 solver does not have -- a slider that moves a
     * number nothing reads is worse than no slider, so they went with the knobs.
     *
     * The buzz a drag turns into is a relay between the exact aim and the per-frame rate
     * limit, and the gain is what decides how hard the solver pushes into that limit.
     * Measured in tools/ragdoll.py on the held bone: a sign change on 63 of 179 frames, every
     * step pinned at the 8.5944 degrees the rate allows. Turning the gain down fixes those
     * numbers and changes the feel -- the limb stops shaking and starts lagging the finger --
     * and only a hand on a real phone can price that trade. So the number has to move while
     * the pet is being dragged, and this panel prints the two things an eye cannot judge
     * while dragging: how often the bone reverses, and how far it goes each frame.
     *
     * Drawn on the canvas rather than built from widgets, because this view has no children
     * and because it is the only place that knows which bone the finger is holding.
     */
    private var tuning = false

    /** The finger the panel owns, or -1. It is not a bone, a prop, a pan or a pinch. */
    private var tunePointer = -1

    /**
     * Whether that finger is on the panel at all.
     *
     * It used to be a mode -- which of the two sliders it had taken hold of -- and both of
     * those sliders are gone with the two knobs the 0.18.0 solver does not have. What is left
     * is still worth keeping: a finger that lands on the panel must not also drag the pet.
     */
    private var tuneDrag = TUNE_DRAG_NONE

    /** Which bone the readout is about, and the rotation the frame before left it at. */
    private var jitterBone: String? = null
    private var jitterPrev = 0f
    private var jitterHasPrev = false

    /** The window: one signed per-frame turn per frame, with the time it was taken. */
    private val jitterTurn = FloatArray(JITTER_SAMPLES)
    private val jitterAt = FloatArray(JITTER_SAMPLES)
    private var jitterHead = 0
    private var jitterCount = 0

    /** What the panel prints: sign changes over comparable pairs, and the mean step in rad. */
    private var jitterRate = 0f
    private var jitterAmp = 0f
    private var jitterPairs = 0

    /**
     * The same window cut by phase: one ring per phase, the piece this frame's step handed
     * back, and the rate over the window.
     *
     * Everything here is in PHASE_* order, which is the order Ragdoll.PROBE_* names, which
     * is the order the rows are drawn in and the order phaseLabel names them. Three lists
     * that have to agree, which is why the mapping from Ragdoll's four numbers into these
     * four slots is written out one line per phase rather than looped over: a loop cannot
     * be read to see whether it is right.
     */
    private val phaseTurn = Array(PHASE_COUNT) { FloatArray(JITTER_SAMPLES) }
    private val phaseNow = FloatArray(PHASE_COUNT)
    private val phaseRate = FloatArray(PHASE_COUNT)
    private val phaseLabel = intArrayOf(
        R.string.sandbox_tune_phase_integrator,
        R.string.sandbox_tune_phase_ground,
        R.string.sandbox_tune_phase_pins,
        R.string.sandbox_tune_phase_carry,
    )

    /** Which rows are relaying, i.e. at or above PHASE_CULPRIT. See summariseJitter. */
    private val phaseHot = BooleanArray(PHASE_COUNT)

    /**
     * What the physics was actually handed, and how many times it was stepped for one frame.
     *
     * The ragdoll cannot know either: it is given whatever this view's frame took. That is
     * the point of printing them -- tools/ragdoll.py is stepped at a fixed 1/60 and this is
     * stepped at the frame's own delta, and a reading taken on the phone only compares with
     * a reading taken in Python if the difference is on the screen. Everything the solver
     * does per frame scales with this number -- a 120 Hz phone steps half as far as a 60 Hz
     * one, and a hitched frame steps three times as far -- which is why the peak is printed
     * beside it rather than the current value alone.
     *
     * The count is one today and is printed rather than assumed: this view has no fixed-step
     * accumulator, [Ragdoll.step] is called once with the frame's own delta, and a substep
     * loop added later would change what every number on this panel means.
     */
    private var stepMs = 0f
    private val stepMsRing = FloatArray(STEP_SAMPLES)
    private var stepMsHead = 0
    private var stepsPerFrame = 0

    /**
     * Read the held bone's rotation once a frame, so the panel can say how much it shakes.
     *
     * What is compared is `bone.rotation` at the end of one frame against the end of the
     * last: the joint's own LOCAL angle, after the solver, the integrator and the ground have
     * all had their say. The difference is therefore how far that joint actually turned, and
     * not how far the IK asked it to turn -- the asking is what the solver's aim decides, and
     * the gap between asking and turning is the bug this panel exists to tune out. Note that
     * it is a local angle: world angles are what the eye sees, and they are this plus every
     * joint above it, which is why a chain of small flips reads as one big shake at the end.
     *
     * Which bone: the one under the finger. With two fingers on two limbs the reading is
     * about one of them, because an average of two joints is a number about neither. The
     * name is handed in rather than looked up here, because it is also the name the probe
     * was armed with: the split and the whole turn below are two readings of one joint, and
     * the four phases adding up to the turn is the only thing that makes them comparable.
     *
     * It RETURNS that turn, or NaN when there is no turn to report -- no bone in the hand, or
     * no frame before this one to difference against -- and the reason is the recorder: the
     * CSV has to carry the same signed turn the panel's headline number is taken from. A
     * second copy of this subtraction down there would be a second reading of the joint, and
     * the one thing this panel has ever proved is that two readings of a shaking thing are
     * two different numbers.
     */
    private fun measureJitter(rag: Ragdoll, name: String?): Float {
        val bone = name?.let { skeleton?.find(it) }
        if (bone == null) {
            // Nothing in the hand. The window is dropped rather than left standing: a stale
            // two seconds would sit there looking like a reading of the next drag.
            forgetJitter()
            return Float.NaN
        }
        if (jitterBone != bone.name) {
            forgetJitter()
            jitterBone = bone.name
        }
        if (!jitterHasPrev) {
            // The first frame of a grab has no frame before it, and counting the jump from
            // wherever the joint sat when the finger took hold would put that jump in the
            // window as a step the size of the grab itself.
            jitterHasPrev = true
            jitterPrev = bone.rotation
            return Float.NaN
        }
        val turn = bone.rotation - jitterPrev
        jitterPrev = bone.rotation
        jitterTurn[jitterHead] = turn
        // The same frame, cut up. One signed piece per phase, taken from the step that just
        // ran, and written at the same ring index as the whole turn so that one timestamp
        // and one window rule serve all five numbers.
        phaseNow[PHASE_INTEGRATOR] = rag.probeTurn[Ragdoll.PROBE_INTEGRATOR]
        phaseNow[PHASE_GROUND] = rag.probeTurn[Ragdoll.PROBE_GROUND]
        phaseNow[PHASE_PINS] = rag.probeTurn[Ragdoll.PROBE_PINS]
        phaseNow[PHASE_CARRY] = rag.probeTurn[Ragdoll.PROBE_CARRY]
        for (p in 0 until PHASE_COUNT) phaseTurn[p][jitterHead] = phaseNow[p]
        jitterAt[jitterHead] = clock
        jitterHead = (jitterHead + 1) % JITTER_SAMPLES
        if (jitterCount < JITTER_SAMPLES) jitterCount++
        summariseJitter()
        return turn
    }

    // -- the raw record -----------------------------------------------------

    /**
     * One CSV row for the frame that just ran, when somebody has the recorder open.
     *
     * This is the whole of the feature and it is deliberately dumb: every value it writes was
     * already computed this frame for the panel above, and it is written in the units the
     * argument is had in -- degrees for angles, ms for time, px for positions -- because the
     * file is going to be read by a person before it is read by a script.
     *
     * The COLUMNS, in the order DIAG_HEADER names them and this function appends them:
     *
     *   frame               rows written since the recording started, plus one
     *   t                   seconds of physics inside this recording, at the frame's start
     *   dt_ms               the delta the step was actually given, ms (see stepMs)
     *   steps_this_frame    how many times it was stepped for this frame (see stepsPerFrame)
     *   target_x/y          where the finger is, world px
     *   grip_x/y            where the point the finger took hold of ended up, world px
     *   pin_bone            which bone that is, or empty when nothing is held
     *   held_offset         how far along the bone the finger landed, px
     *   turn_total_deg      the held joint's net turn this frame, signed (measureJitter)
     *   integ/ground/pins/carry_deg
     *                       the four phase contributions to that same turn, signed
     *   (the two knob columns went with the knobs -- this solver has neither)
     *                       the two constants the solver ran with, both live-adjustable
     *   stiffness, gravity_scale
     *                       what the integrator ran with
     *   bones_touching_floor
     *                       how many solid bones' colliders are under the floor line
     *   hanging             whether the step treated the figure as hanging off the finger
     *
     * SIGNED is the point of the four phase columns and of turn_total_deg. A joint that takes
     * +5 degrees and then -5 has travelled ten and gone nowhere, and the whole reason this
     * file exists is to find out which of the four is doing the reversing. An absolute value
     * here would make the culprit look exactly like a smooth drag of the same size.
     *
     * Empty cells mean "there was no such thing this frame": nothing was held, or the frame
     * was the first of a grab and had nothing before it to turn away from. Zero would be a
     * claim, and a false one.
     */
    private fun recordFrame(dt: Float, s: CharacterSpec, rag: Ragdoll, sk: Skeleton, turn: Float) {
        if (!diag.active) return
        val held = heldBones.entries.firstOrNull()
        val bone = held?.let { sk.find(it.value) }
        val offset = held?.let { heldOffsets[it.key] } ?: 0f
        val target = held?.let { heldTargets[it.key] }
        val grip = if (bone == null) null else rag.gripPoint(bone, offset)
        var low = 0
        for (b in sk.bones) {
            if (rag.isSolid(b.name) && rag.colliderLow(b) > s.floorY) low++
        }
        val row = StringBuilder(192)
        row.append(diag.rows + 1).append(',')
        row.append(plain(diag.seconds)).append(',')
        row.append(plain(dt * 1000f)).append(',')
        row.append(stepsPerFrame).append(',')
        row.append(target?.let { fixed(it.x, 3) }.orEmpty()).append(',')
        row.append(target?.let { fixed(it.y, 3) }.orEmpty()).append(',')
        row.append(grip?.let { fixed(it.x, 3) }.orEmpty()).append(',')
        row.append(grip?.let { fixed(it.y, 3) }.orEmpty()).append(',')
        row.append(bone?.name.orEmpty()).append(',')
        row.append(if (bone == null) "" else fixed(offset, 3)).append(',')
        row.append(degrees(turn)).append(',')
        for (p in 0 until PHASE_COUNT) row.append(degrees(phaseNow[p])).append(',')
        row.append(fixed(rag.stiffness, 4)).append(',')
        row.append(fixed(rag.gravityScale, 3)).append(',')
        row.append(low).append(',')
        row.append(if (rag.isHanging) 1 else 0)
        diag.row(row.toString(), dt)
        // One message, on the frame it happens, and never again: the recording is closed by
        // then, so the next frame returns at the top of this function.
        if (!diag.active) {
            onInfo?.invoke(
                context.getString(
                    if (diag.full) R.string.sandbox_tune_diag_full
                    else R.string.sandbox_tune_diag_write_failed,
                    diag.rows,
                )
            )
        }
    }

    /**
     * One CSV number, in Locale.US and never the phone's own locale.
     *
     * A German or French phone writes "8,5944", and a comma inside a field is the one thing a
     * comma-separated file cannot survive: the file would open with twice as many columns as
     * its own header. The panel is allowed to print a decimal comma, and does; this is not
     * the panel.
     */
    private fun fixed(v: Float, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", v)

    /** The one value that is not a measurement: a time, and three digits is well past enough. */
    private fun plain(v: Float): String = String.format(Locale.US, "%.3f", v)

    /** Radians in, degrees out, signed, and empty when there was no reading. See recordFrame. */
    private fun degrees(rad: Float): String =
        if (rad.isNaN()) "" else String.format(Locale.US, "%.4f", Math.toDegrees(rad.toDouble()))

    private fun forgetJitter() {
        jitterBone = null
        jitterHasPrev = false
        jitterHead = 0
        jitterCount = 0
        jitterRate = 0f
        jitterAmp = 0f
        jitterPairs = 0
        for (p in 0 until PHASE_COUNT) {
            phaseNow[p] = 0f
            phaseRate[p] = 0f
            phaseHot[p] = false
        }
    }

    /**
     * The numbers, over the samples still inside the window.
     *
     * The mean includes every frame, still ones and all, because that is what "how far it
     * moves per frame" has to mean to be comparable with the Python. The five sign change
     * rates -- the whole frame's and one per phase -- are one rule written once; see
     * signChanges. The phase rates are the new question: which of the four things a step
     * does to a joint is the one reversing.
     */
    private fun summariseJitter() {
        val first = (jitterHead - jitterCount + JITTER_SAMPLES) % JITTER_SAMPLES
        var sum = 0f
        var used = 0
        for (i in 0 until jitterCount) {
            val k = (first + i) % JITTER_SAMPLES
            // The clock is reset by a restart of the bench, and a sample from before that is
            // older than it looks. Dropping it here as well means the window never has to
            // trust that the only thing which clears a grab is the only thing that zeroes it.
            val age = clock - jitterAt[k]
            if (age < 0f || age > JITTER_WINDOW) continue
            used++
            sum += abs(jitterTurn[k])
        }
        jitterAmp = if (used == 0) 0f else sum / used
        val total = signChanges(jitterTurn, first)
        jitterRate = total.rate
        jitterPairs = total.pairs
        for (p in 0 until PHASE_COUNT) {
            phaseRate[p] = signChanges(phaseTurn[p], first).rate
            // Every row that is reversing on more of the window's moving frames than it is
            // not, rather than only the worst of them: measured on the reference, a drag puts
            // TWO rows there at once -- the pin and the integrator, which carries the pin's
            // own step on as velocity the frame after (solvePin writes the angle and Verlet
            // reads the difference as speed) -- and marking one of the two would say the
            // other is fine. What the marker means is exactly what its threshold says.
            phaseHot[p] = phaseRate[p] >= PHASE_CULPRIT
        }
    }

    /** A sign change rate, and the number of frame pairs it was taken over. */
    private class Flips(val rate: Float, val pairs: Int)

    /**
     * The panel's one piece of arithmetic: how many of the window's frame pairs reversed,
     * out of the pairs there were to reverse.
     *
     * Only pairs where BOTH frames moved count. A joint that is exactly still has no
     * direction to reverse, and letting those frames vote would report a slow smooth drag as
     * a shaking one. The share rather than the count, because it has to mean the same thing
     * in a window that has just filled as in one that has been running for a minute.
     *
     * This is the whole of the rate, for the whole frame and for every phase: five numbers
     * taken five times over the same window, and a second copy of the rule would be a second
     * chance for the panel to contradict itself -- a phase reading 100% while the total
     * reads 0.
     */
    private fun signChanges(ring: FloatArray, first: Int): Flips {
        var pairs = 0
        var flips = 0
        var last = 0
        for (i in 0 until jitterCount) {
            val k = (first + i) % JITTER_SAMPLES
            val age = clock - jitterAt[k]
            if (age < 0f || age > JITTER_WINDOW) continue
            val turn = ring[k]
            val sign = if (turn > 0f) 1 else if (turn < 0f) -1 else 0
            if (sign == 0) continue
            if (last != 0) {
                pairs++
                if (sign != last) flips++
            }
            last = sign
        }
        return Flips(if (pairs == 0) 0f else 100f * flips / pairs, pairs)
    }

    /**
     * Where the tab and the panel sit together: under the pet chooser, which is the one strip
     * of this view that something else is always drawn over, and above the pet, which stands
     * on the floor at the bottom. What is behind it is sky, and it is drawn last of
     * everything.
     *
     * Slid up when the panel is taller than the room below that strip, which a phone on its
     * side is: the panel has grown a phase table, a timing row and a second slider, and the
     * 6dp of sky left over is not worth hanging the bottom controls off the screen for. Tab
     * and box move together -- [tunePanel] hangs off [tuneTab] -- so what is read and what is
     * touched stay on the screen as one thing, and a phone held upright is untouched: the
     * panel fits and the tab stays exactly where it has always been.
     */
    private fun tuneTop(): Float =
        min(46f * density, max(6f * density, height - (TUNE_PANEL_H + 36f) * density))

    private fun tuneTab(): RectF {
        val right = width - 6f * density
        val top = tuneTop()
        return RectF(right - 74f * density, top, right, top + 24f * density)
    }

    /**
     * The panel's box, and with it where everything in it sits, in dp below its top: the
     * title at 20, the two headline numbers at 48 and 76, the phase table's header at 92
     * with a row every 20 after it, the timing row at 188, the gain on the same 28 rhythm
     * at 212 (slider centre 18 below it, ends named 22 below that), 回 1.0 from 30 below the
     * slider to 12 above the box's bottom edge, then the rate limit: its row at 316 with the
     * switch that turns it off, its slider at 342, and its ends named at 364.
     *
     * [TUNE_PANEL_H] then grew a footer for the raw record, and it is a footer rather than
     * another tab because it is used WITH the numbers above it: 开始记录 / 分享 sit on a pill
     * row at 375, and the file's path starts at 417 on 12dp lines, at most [DIAG_PATH_LINES]
     * of them, with the row count on the line after the last one. That last line is why the
     * box is [TUNE_PANEL_H] tall and not 12dp less.
     *
     * Laid out by hand because it is drawn by hand, and one place to read the rhythm from is
     * the next best thing to not having the numbers at all: every offset below is this list,
     * and a block that grows moves the ones under it with it.
     */
    private fun tunePanel(): RectF {
        val right = width - 6f * density
        val top = tuneTab().bottom + 6f * density
        // Narrower than its 244dp when it has to be: the rail on the left can be expanded and
        // this view is what is left of the window, which on a small phone is not much. The
        // rows and the slider are laid out from the box, so they follow it in.
        val left = max(6f * density, right - 244f * density)
        return RectF(left, top, right, top + TUNE_PANEL_H * density)
    }

    /**
     * 开始记录 / 停止并导出, on its own row under the rate slider.
     *
     * The same 26dp pill as 关掉限速 and in the same corner, because it is the same kind of
     * control: one press, one thing, and the label says which thing it will be. It is not a
     * real Switch widget -- this panel has no widgets at all -- so the wording carries the
     * whole state.
     */
    private fun tuneDiagSwitch(): RectF {
        val box = tunePanel()
        val w = diagButtonW()
        return RectF(
            box.right - tunePad - w, box.top + 228f * density,
            box.right - tunePad, box.top + 254f * density,
        )
    }

    /**
     * 分享, beside it: the second half of the feature, and the one that actually gets the
     * file off the phone. See MainActivity.shareFile.
     */
    private fun tuneDiagShare(): RectF {
        val s = tuneDiagSwitch()
        return RectF(s.left - 8f * density - s.width(), s.top, s.left - 8f * density, s.bottom)
    }

    /**
     * How wide each of the two buttons is: 76dp, or half the panel when the rail has eaten
     * into it. They are measured from the box rather than fixed, so that two buttons on one
     * row cannot overlap each other on the narrowest bench this panel can end up on.
     */
    private fun diagButtonW(): Float =
        min(76f * density, (tunePanel().width() - tunePad * 2f - 8f * density) / 2f)

    /**
     * The file's path, broken into lines that fit the panel width.
     *
     * In full and not shortened to a file name, because it is also what the user copies down
     * when the share sheet has nothing to share with -- and broken at SLASHES, because a path
     * cut mid-name reads as a different path. The text shrinks if it has to: the block has
     * room for DIAG_PATH_LINES lines, and a device with a longer storage path than the one
     * this was written on must not be the device that cannot see its own file.
     */
    private fun diagPathLines(path: String, width: Float): List<String> {
        val base = tunePath.textSize
        var lines = wrapPath(path, width)
        while (lines.size > DIAG_PATH_LINES && tunePath.textSize > 5f * density) {
            tunePath.textSize *= 0.85f
            lines = wrapPath(path, width)
        }
        tunePath.textSize = base
        return lines
    }

    /** One greedy pass of [diagPathLines], at whatever size the path paint is right now. */
    private fun wrapPath(path: String, width: Float): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < path.length) {
            var end = i
            var slash = -1
            while (end < path.length && tunePath.measureText(path, i, end + 1) <= width) {
                if (path[end] == '/') slash = end + 1
                end++
            }
            if (end >= path.length) {
                out.add(path.substring(i))
                break
            }
            val cut = if (slash > i) slash else end
            out.add(path.substring(i, cut))
            // A single character wider than the whole panel: nothing left to do but stop
            // rather than spin. The rest of the path goes on this line and overflows.
            if (cut <= i) {
                out.add(path.substring(i))
                break
            }
            i = cut
        }
        return out
    }

    /**
     * Start the record, or stop it and hand the file straight to the share sheet.
     *
     * The switch's own label is the promise it keeps -- 停止并导出 does both -- and 分享 stays
     * on the panel for afterwards: a share sheet that was dismissed, an app that was not
     * installed yet, and a file that is still there all want the second press.
     */
    private fun toggleDiagnostics() {
        if (diag.active) {
            stopDiagnostics(share = true)
            return
        }
        val base = context.getExternalFilesDir(null)
        if (base == null) {
            onInfo?.invoke(context.getString(R.string.sandbox_tune_diag_failed))
            return
        }
        val name = DIAG_PREFIX + SimpleDateFormat(DIAG_STAMP, Locale.US).format(Date()) + ".csv"
        if (!diag.start(File(base, DIAG_DIR), name, DIAG_HEADER)) {
            onInfo?.invoke(context.getString(R.string.sandbox_tune_diag_failed))
            return
        }
        onInfo?.invoke(context.getString(R.string.sandbox_tune_diag_started, name))
        invalidate()
    }

    /** Stop writing. The file stays where it is whether or not anyone shares it. */
    private fun stopDiagnostics(share: Boolean) {
        if (!diag.active) return
        diag.stop()
        onInfo?.invoke(context.getString(R.string.sandbox_tune_diag_saved, diag.rows))
        val f = diag.file
        if (share && f != null && diag.rows > 0) onShareFile?.invoke(f)
        invalidate()
    }

    /**
     * Send the last recording somewhere the user can reach it.
     *
     * The panel prints the path for the same reason this can fail: the file is in this app's
     * private directory on the shared card, which is not a place a file manager will show
     * them, so "it did not work" has to leave them with something to write down.
     */
    private fun shareDiagnostics() {
        val f = diag.file
        if (f == null || diag.rows == 0) {
            onInfo?.invoke(context.getString(R.string.sandbox_tune_diag_none))
            return
        }
        onShareFile?.invoke(f)
    }

    /**
     * Value to track position and back, logarithmically.
     *
     * The useful range of these numbers is not spread evenly over a linear track. For the
     * gain: 1.0 is where it has always been and 0.1 is where the buzz measurably goes away,
     * and a LINEAR track from 0.02 to 1.0 puts 0.1 in its first 8%, which is not somewhere a
     * finger can be put twice, while log puts it at 41% and still ends exactly on 1.0. For
     * the rate: 9.0 is the default and anything worth trying is a multiple of it, so the
     * same argument holds twice as hard -- 9.0 to 1000 linearly would put everything above
     * 100 in the last 9% of the track.
     */
    private fun tuneRow(
        canvas: Canvas, box: RectF, y: Float, label: String, value: String,
        right: Float = box.right - tunePad,
    ) {
        tuneText.color = 0x99FFFFFF.toInt()
        canvas.drawText(label, box.left + tunePad, y, tuneText)
        tuneValue.textAlign = Paint.Align.RIGHT
        canvas.drawText(value, right, y, tuneValue)
        tuneValue.textAlign = Paint.Align.LEFT
    }

    /**
     * One phase's line: what it did to the held joint this frame, signed, and the share of
     * the window's comparable pairs it reversed.
     *
     * The sign is the whole point of the table. A phase reading +5.53 on one frame and
     * -5.53 on the next is the one relaying; an absolute value here would make the culprit
     * look exactly like a smooth drag of the same size, which is the confusion this panel
     * exists to end.
     */
    private fun tunePhaseRow(
        canvas: Canvas,
        box: RectF,
        y: Float,
        label: String,
        now: Float,
        rate: Float,
        guilty: Boolean,
    ) {
        val color = if (guilty) 0xFFFF8A8A.toInt() else 0x99FFFFFF.toInt()
        tuneText.color = color
        canvas.drawText(label, box.left + tunePad, y, tuneText)
        tunePhase.textAlign = Paint.Align.RIGHT
        tunePhase.color = 0xFFF4F2FB.toInt()
        canvas.drawText(phaseText(now), box.left + box.width() * PHASE_VALUE_COLUMN, y, tunePhase)
        tunePhase.color = color
        canvas.drawText(phaseRateText(rate), box.right - tunePad - culpritStrip, y, tunePhase)
        tunePhase.textAlign = Paint.Align.LEFT
        if (!guilty) return
        // The marker gets its own strip at the end of the row rather than riding in the rate
        // column, so that no number moves sideways on the frame the culprit changes.
        tuneText.color = color
        tuneText.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            context.getString(R.string.sandbox_tune_culprit),
            box.right - tunePad, y, tuneText,
        )
        tuneText.textAlign = Paint.Align.LEFT
    }

    private fun drawTune(canvas: Canvas) {
        if (width <= 0) return
        val tab = tuneTab()
        tunePaint.color = if (tuning) 0xF2171528.toInt() else 0xD9171528.toInt()
        canvas.drawRoundRect(tab, tab.height() / 2f, tab.height() / 2f, tunePaint)
        tuneText.color = 0xFFF4F2FB.toInt()
        tuneText.textAlign = Paint.Align.CENTER
        canvas.drawText(
            context.getString(
                if (tuning) R.string.sandbox_tune_close else R.string.sandbox_tune_tab
            ),
            tab.centerX(), tab.centerY() + 4f * density, tuneText,
        )
        tuneText.textAlign = Paint.Align.LEFT
        if (!tuning) return

        val box = tunePanel()
        tunePaint.color = 0xE6171528.toInt()
        canvas.drawRoundRect(box, 12f * density, 12f * density, tunePaint)
        tunePaint.style = Paint.Style.STROKE
        tunePaint.strokeWidth = 1f * density
        tunePaint.color = 0x33FFFFFF
        canvas.drawRoundRect(box, 12f * density, 12f * density, tunePaint)
        tunePaint.style = Paint.Style.FILL

        tuneText.color = 0x99FFFFFF.toInt()
        canvas.drawText(
            context.getString(R.string.sandbox_tune_title),
            box.left + tunePad, box.top + 20f * density, tuneText,
        )
        tuneRow(
            canvas, box, box.top + 48f * density,
            context.getString(R.string.sandbox_tune_rate), rateText(),
        )
        tuneRow(
            canvas, box, box.top + 76f * density,
            context.getString(R.string.sandbox_tune_amp), ampText(),
        )

        // The table under the two numbers it explains: which of the four things a step does
        // to the held joint is the one going back and forth. The headline rate says how bad
        // it is and cannot say who, which is the question a gain is tuned against -- and who
        // is not guessable from out here, because three of the four phases are private
        // methods of the ragdoll and the fourth is inline in its step.
        val colValue = box.left + box.width() * PHASE_VALUE_COLUMN
        val colRate = box.right - tunePad - culpritStrip
        tuneText.color = 0x66FFFFFF
        canvas.drawText(
            context.getString(R.string.sandbox_tune_phases),
            box.left + tunePad, box.top + 92f * density, tuneText,
        )
        tuneText.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            context.getString(R.string.sandbox_tune_phase_now),
            colValue, box.top + 92f * density, tuneText,
        )
        canvas.drawText(
            context.getString(R.string.sandbox_tune_rate),
            colRate, box.top + 92f * density, tuneText,
        )
        tuneText.textAlign = Paint.Align.LEFT
        for (p in 0 until PHASE_COUNT) {
            tunePhaseRow(
                canvas, box, box.top + (112f + 20f * p) * density,
                context.getString(phaseLabel[p]), phaseNow[p], phaseRate[p],
                phaseHot[p],
            )
        }

        // What the physics is being fed, which is the first difference between this panel and
        // the reference the same numbers were first read in: tools/ragdoll.py is stepped at
        // exactly 1/60, this is stepped at whatever the frame took. The peak is printed beside
        // the current value because one hitched frame is where a figure gets its biggest kick.
        var stepPeak = 0f
        for (v in stepMsRing) stepPeak = max(stepPeak, v)
        tuneText.color = 0x66FFFFFF
        canvas.drawText(
            context.getString(R.string.sandbox_tune_timing, stepMs, stepPeak, stepsPerFrame),
            box.left + tunePad, box.top + 188f * density, tuneText,
        )

        // Which bone the two numbers are about, and how many frame pairs are behind them --
        // how you know the window has filled. It shares the timing row now: the rows under it
        // were the two knobs this solver does not have (see the class comment on the panel).
        tuneText.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            jitterBone?.let { context.getString(R.string.sandbox_tune_bone, it, jitterPairs) }
                ?: context.getString(R.string.sandbox_tune_idle),
            box.right - tunePad, box.top + 188f * density, tuneText,
        )
        tuneText.textAlign = Paint.Align.LEFT

        // ── 导出诊断: the raw record, and the two things that get it off the phone ──
        //
        // Everything above this line is a summary, and summaries are what has already failed
        // to find this bug twice: 17-39% in the Python reference, 100% in a real hand, and no
        // percentage can say what the difference between the two is. The frames themselves
        // can, so this block writes them to a file and then gets the file out of the app's
        // private directory -- the pressing half of the feature, because a CSV nothing can
        // open is a CSV that was never recorded.
        val sw = tuneDiagSwitch()
        val sh = tuneDiagShare()
        val canShare = diag.file != null && diag.rows > 0
        tunePaint.color = if (diag.active) 0x33FF8A8A.toInt() else 0x26FFFFFF
        canvas.drawRoundRect(sw, sw.height() / 2f, sw.height() / 2f, tunePaint)
        tunePaint.color = if (canShare) 0x26FFFFFF else 0x14FFFFFF
        canvas.drawRoundRect(sh, sh.height() / 2f, sh.height() / 2f, tunePaint)
        tuneText.textAlign = Paint.Align.CENTER
        tuneText.color = if (diag.active) 0xFFFF8A8A.toInt() else 0xFF8FA8FF.toInt()
        canvas.drawText(
            context.getString(
                if (diag.active) R.string.sandbox_tune_diag_stop
                else R.string.sandbox_tune_diag_start
            ),
            sw.centerX(), sw.centerY() + 4f * density, tuneText,
        )
        tuneText.color = if (canShare) 0xFF8FA8FF.toInt() else 0x55FFFFFF
        canvas.drawText(
            context.getString(R.string.sandbox_tune_diag_share),
            sh.centerX(), sh.centerY() + 4f * density, tuneText,
        )
        tuneText.textAlign = Paint.Align.LEFT
        // The label is dropped rather than overlapped when the rail has left the panel too
        // narrow for it: it names the block, and a block name printed on top of its own
        // button is worse than no name at all.
        val diagLabel = context.getString(R.string.sandbox_tune_diag)
        if (sh.left - (box.left + tunePad) > tuneText.measureText(diagLabel) + 6f * density) {
            tuneText.color = 0x66FFFFFF
            canvas.drawText(diagLabel, box.left + tunePad, sw.centerY() + 4f * density, tuneText)
        }

        // The file's full path, and how much is in it. Not decoration: it is the fallback
        // the whole feature needs, because the share sheet is another app's screen and this
        // is the only line on the phone that says where the data actually is.
        val saved = diag.file
        var pathY = box.top + DIAG_PATH_TOP * density
        if (saved == null) {
            tuneText.color = 0x66FFFFFF
            canvas.drawText(
                context.getString(R.string.sandbox_tune_diag_hint),
                box.left + tunePad, pathY, tuneText,
            )
        } else {
            tunePath.color = 0x88FFFFFF.toInt()
            for (line in diagPathLines(saved.absolutePath, box.width() - tunePad * 2f)) {
                canvas.drawText(line, box.left + tunePad, pathY, tunePath)
                pathY += DIAG_LINE_H * density
            }
            // Red for both ways this recording has ended by itself -- the cap and a write
            // that failed -- because in both of them the number is final and the user did
            // not ask for it to be.
            tuneText.color =
                if (diag.full || diag.failed) 0xFFFF8A8A.toInt() else 0x99FFFFFF.toInt()
            canvas.drawText(
                context.getString(
                    if (diag.full) R.string.sandbox_tune_diag_rows_full
                    else R.string.sandbox_tune_diag_rows,
                    diag.rows,
                ),
                box.left + tunePad, pathY, tuneText,
            )
        }
    }

    /**
     * A finger landing on the panel, which takes it before the bench does.
     *
     * The panel is drawn on top of the pet, so it has to win the finger the same way a prop
     * wins over a bone: without this, dragging the slider would drag whatever bone is under
     * it. It deliberately does not touch lastTapAt either, so a tap here cannot arm the
     * double-tap reset.
     */
    private fun tuneDown(id: Int, x: Float, y: Float): Boolean {
        if (tuneTab().contains(x, y)) {
            tuning = !tuning
            tunePointer = id
            tuneDrag = TUNE_DRAG_NONE
            invalidate()
            return true
        }
        if (!tuning || !tunePanel().contains(x, y)) return false
        tunePointer = id
        tuneDrag = TUNE_DRAG_NONE
        when {
          // The recorder's two, also on the press: starting a record has to happen on the
            // frame the finger meant it, and stopping it and opening a share sheet is a
            // thing the user is waiting for rather than dragging.
            tuneDiagSwitch().contains(x, y) -> toggleDiagnostics()
            tuneDiagShare().contains(x, y) -> shareDiagnostics()
        }
        return true
    }

    /** That finger letting go. It never held a bone or a prop, so there is nothing to drop. */
    private fun tuneUp(id: Int) {
        if (id != tunePointer) return
        tunePointer = -1
        tuneDrag = TUNE_DRAG_NONE
    }

    // -- input --------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rag = ragdoll ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Before the double tap, the grab and the pan: the panel is a control drawn
                // on top of the bench, and a finger that lands on it is not doing any of the
                // three. It does not count towards the double-tap reset either.
                if (tuneDown(event.getPointerId(0), event.x, event.y)) {
                    lastFrameNs = System.nanoTime()
                    postInvalidateOnAnimation()
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - lastTapAt < 300) {
                    resetWorld()
                    lastTapAt = 0L
                    return true
                }
                lastTapAt = now
                // A piece that came off is a thing on the bench now, so it is picked up
                // before the figure and the props are asked: it is drawn on top of them, and
                // the finger means what it is on.
                val piece = debrisAt(event.x, event.y)
                if (piece != null) {
                    heldDebris = piece
                    debrisPointer = event.getPointerId(0)
                    val at = toWorld(event.x, event.y)
                    fingerOnDebris = at
                    holdOffset = piece.position - at
                    piece.settled = false
                    onInfo?.invoke(context.getString(R.string.sandbox_piece_held))
                    lastFrameNs = System.nanoTime()
                    postInvalidateOnAnimation()
                    return true
                }
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
                val id = event.getPointerId(index)
                val piece = heldDebris
                if (piece != null && id != debrisPointer) {
                    // A second finger on a piece that is being carried turns it. It is the
                    // same finger pair as a pinch, and it means the other thing: the first
                    // finger already said what it is holding.
                    twistPointer = id
                    twistAt = atan2(
                        event.getY(index) - vy(piece.position.y),
                        event.getX(index) - vx(piece.position.x),
                    )
                } else if (!tuneDown(id, event.getX(index), event.getY(index))) {
                    // A finger on the panel is the panel's, not the bench's.
                    beginGrab(rag, id, event.getX(index), event.getY(index))
                }
                panning = false
                pinchSpan = if (heldBones.isEmpty() && heldProp == null && piece == null) {
                    span(event)
                } else {
                    0f
                }
                lastFrameNs = System.nanoTime()
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                var grabbing = false
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    // The panel's finger, handled inside the loop rather than by leaving it
                    // early: a second finger has to be able to keep dragging the pet while
                    // the first one holds the slider. That is what tuning looks like.
                    if (id == tunePointer) {
                        // The panel's finger: it has nothing to drag any more (the sliders it
                        // used to work are gone with the knobs they set), but it still must
                        // not move the pet or the camera.
                        grabbing = true
                        continue
                    }
                    if (id == twistPointer && heldDebris != null) {
                        val d = heldDebris!!
                        val now = atan2(
                            event.getY(i) - vy(d.position.y),
                            event.getX(i) - vx(d.position.x),
                        )
                        d.angle += normalizeAngle(now - twistAt)
                        twistAt = now
                        d.spin = 0f
                        grabbing = true
                        continue
                    }
                    if (id == debrisPointer && heldDebris != null) {
                        fingerOnDebris = toWorld(event.getX(i), event.getY(i))
                        grabbing = true
                        continue
                    }
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
                if (event.getPointerId(event.actionIndex) == twistPointer) twistPointer = -1
                // Both are no-ops for the panel's finger: it holds nothing to let go of, and
                // endGrab returns immediately for a pointer that never took a bone.
                tuneUp(event.getPointerId(event.actionIndex))
                endGrab(rag, event.getPointerId(event.actionIndex))
                pinchSpan = 0f
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Not an early return: a second finger may have been holding a bone or a prop,
                // and this is the last finger up. The panel's own finger has nothing to drop.
                tuneUp(event.getPointerId(event.actionIndex))
                heldProp?.let { releaseProp(it) }
                heldProp = null
                propPointer = -1
                // Letting go of a piece hands it whatever speed the finger had, which
                // stepDebris has been reading off its own positions. Nothing else to do:
                // the piece is already in the list the world steps.
                heldDebris = null
                debrisPointer = -1
                twistPointer = -1
                fingerOnDebris = null

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
                //
                // Where the two numbers come from, because a tap that does not register is
                // invisible from the outside -- the rule simply does not run, and what that
                // looks like is "it works sometimes":
                //
                //  * TAP_MS used to be 300, measured from finger DOWN. A deliberate tap on a
                //    phone takes 200-400ms, so 300 threw away a good share of them. 500 is
                //    Android's own long-press threshold, which is the line where a press
                //    stops being a tap at all.
                //  * TAP_SLOP is 12dp, wider than the platform's own 8dp touch slop, because
                //    this one has to be crossed by a finger that is also holding a bone.
                //
                // And when a tap is thrown away the bench SAYS SO on the status line: this is
                // the one event in the app that otherwise leaves no trace anywhere.
                if (tapped != null) {
                    val held = System.currentTimeMillis() - tapAt
                    val moved = hypot(event.x - tapX, event.y - tapY)
                    if (held < TAP_MS && moved < TAP_SLOP_DP * density) {
                        fire(GameEvent(EventType.CLICK, part = tapped))
                    } else {
                        val why = if (held >= TAP_MS) {
                            context.getString(
                                R.string.sandbox_tap_too_slow, held / 1000f, TAP_MS / 1000f,
                            )
                        } else {
                            context.getString(R.string.sandbox_tap_moved, moved.toInt())
                        }
                        onInfo?.invoke(why)
                    }
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
        // A part with grabbable = false is still there, still solid, still part of the
        // figure -- a finger simply goes through it, the way it goes through a prop it is
        // not allowed to pick up.
        val grip = rag.grabAt(p) ?: return false
        if (!rag.canGrab(grip.bone.name)) return false
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

        /** How many signals one event may set off before the rest are dropped. See drainSignals. */
        private const val MAX_SIGNALS_PER_FRAME = 32
        /** How much of the window, at each edge, a followed pet may not go past. */
        private const val FOLLOW_MARGIN = 0.10f
        /** How much of the distance to a wandering pet the window closes per frame. */
        private const val FOLLOW_EASE = 0.10f
        /** Above this the release is a throw, below it the pet was simply put down. */
        private const val THROW_SPEED = 700f

        /** Air drag on a falling piece, and the slide speed under which it stops for good. */
        private const val DEBRIS_DRAG = 0.5f
        private const val DEBRIS_REST = 12f

        /**
         * How hard a piece pushes the body: px/s of impulse per px of overlap, and the cap.
         *
         * 10 px/s per px, capped at 90, is a nudge rather than a shove: a piece lying on the
         * pet moves it about as fast as a finger brushing it, and one landing on it from a
         * height makes it flinch. Both numbers are a feel, not a measurement -- there is no
         * test that can say what a severed arm landing on a pet should do.
         */
        private const val PIECE_PUSH = 10f
        private const val PIECE_PUSH_MAX = 90f
        private const val SHOT_SPEED = 2600f

        /**
         * How long a press may last, counted from finger DOWN, and still be "被点一下".
         *
         * It was 300, which is under what a deliberate tap takes on a phone, so taps went
         * missing at random and the rules they should have fired looked broken. 500 is
         * Android's own long-press threshold: the point where a press is a hold.
         */
        private const val TAP_MS = 500L

        /** How far a finger may wander and still be a tap, in dp. The platform's slop is 8. */
        private const val TAP_SLOP_DP = 12f

        /**
         * The tuning readout's window, in seconds, and the ring it is kept in.
         *
         * Two seconds is the Python's window for the same measurement, so the numbers on the
         * phone can be read against the numbers in the diagnosis. 512 frames is that window
         * at 120 Hz and then some; the window itself is enforced by time, not by count, so a
         * phone that drops to 30 fps still shows two seconds rather than four.
         */
        private const val JITTER_WINDOW = 2.0f
        private const val JITTER_SAMPLES = 512

        /** How tall the panel is, so that [tuneTop] can tell whether it fits. See tunePanel. */
        private const val TUNE_PANEL_H = 318f

        /**
         * The raw record's own block, in dp below the panel's top. See the drawing in
         * [drawTune] and the geometry in [tuneDiagSwitch] and [diagPathLines].
         *
         * The path gets [DIAG_PATH_LINES] lines of 12dp at 8dp monospaced, which is two
         * lines for the length getExternalFilesDir actually returns on a phone and three for
         * the narrowest bench this panel fits on. It shrinks rather than runs over: this is
         * the line the user reads the file's location off, so it has to be all there.
         */
        private const val DIAG_PATH_TOP = 270f
        private const val DIAG_LINE_H = 12f
        private const val DIAG_PATH_LINES = 3

        /** Where the CSVs go: getExternalFilesDir(null), then this. See DragDiagRecorder. */
        private const val DIAG_DIR = "diag"
        private const val DIAG_PREFIX = "drag-"

        /** A name that sorts by time and cannot collide with the next recording. */
        private const val DIAG_STAMP = "yyyyMMdd-HHmmss"

        /**
         * The columns, in the order [recordFrame] appends them.
         *
         * One line, and one place: a header written from a second list of names is a file
         * whose columns mean what the header says only until somebody adds one to the row.
         * The names carry their own units, because the first thing anyone does with this file
         * is open the first ten lines of it and look.
         */
        private const val DIAG_HEADER =
            "frame,t,dt_ms,steps_this_frame,target_x,target_y,grip_x,grip_y,pin_bone," +
                "held_offset,turn_total_deg,integ_deg,ground_deg,pins_deg,carry_deg," +
                "stiffness,gravity_scale,bones_touching_floor,hanging"

        /** What the panel's finger is on: the gain slider, the rate slider, or a switch. */
        private const val TUNE_DRAG_NONE = 0

        /**
         * How many frames of step time the peak beside the current value is taken over.
         *
         * One second at 60 Hz: long enough that a single hitched frame is still in it when
         * the eye arrives, short enough that a hitch a minute ago is not presented as the
         * state of the bench.
         */
        private const val STEP_SAMPLES = 64

        /**
         * The four things one step does to a joint, in the order the table prints them and
         * the order phaseTurn, phaseNow, phaseRate and phaseLabel are indexed in.
         *
         * Ragdoll.PROBE_* is the same four on the physics side, and measureJitter copies
         * them across one line per phase. These are private and separate on purpose: the
         * numbers have to be written out twice somewhere, and a second set here is what
         * makes a reordering in Ragdoll show up as a wrong number rather than as a silent
         * rename in a file nobody reread.
         */
        private const val PHASE_INTEGRATOR = 0
        private const val PHASE_GROUND = 1
        private const val PHASE_PINS = 2
        private const val PHASE_CARRY = 3
        private const val PHASE_COUNT = 4

        /** Where a phase row's signed number is right-aligned, as a share of the panel's width. */
        private const val PHASE_VALUE_COLUMN = 0.60f

        /**
         * The rate a phase has to reach before the panel marks it. Half: from there it
         * reverses on more of the window's moving frames than it does not, which is the
         * relay. Below it the row is a joint that is simply not moving smoothly yet.
         *
         * Every row over the line is marked rather than only the highest, and the reference
         * says why: a drag puts the pin and the integrator BOTH at 90-100% -- the pin's step
         * is carried on as velocity by the frame after it -- so a single "worst" marker
         * would point at one of them and quietly call the other innocent.
         */
        private const val PHASE_CULPRIT = 50f
    }
}
