package dev.atp.pet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.atp.pet.R
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.BoneSpec
import dev.atp.pet.engine.skeleton.IkChainSpec
import dev.atp.pet.engine.skeleton.LayerSpec
import dev.atp.pet.engine.skeleton.NodeSpec
import dev.atp.pet.engine.skeleton.RigEdit
import dev.atp.pet.engine.skeleton.Skeleton
import dev.atp.pet.engine.skeleton.TwoBoneIK
import dev.atp.pet.render.PartLibrary
import dev.atp.pet.render.PartRenderer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** A draggable end: the end of a two-bone IK chain, or a bone aimed directly. */
private class Handle(val bone: Bone, val chain: IkChainSpec?)

/** Which end of a bone the bone editor is holding. */
private class JointHandle(val boneName: String, val tail: Boolean)

/**
 * The rig editor.
 *
 * Two modes, because they are two different jobs:
 *
 *   摆姿势 — drag a limb's end and the chain is solved, so the figure can be posed.
 *   改骨骼 — drag a joint or a bone's tip and the RIG itself moves, so the skeleton can be
 *            fitted to artwork that was not drawn to the template.
 *
 * In bone mode the pose is reset first. Head and tail are stored in canvas coordinates,
 * which is also exactly the rest pose, so editing only makes sense with nothing posed —
 * and it means a drag lands where the user put it instead of somewhere rotated.
 */
class SkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var folder: CharacterFolder? = null
    private var spec: CharacterSpec? = null
    private var skeleton: Skeleton? = null
    private var renderer: PartRenderer? = null
    private var library: PartLibrary? = null
    private val handles = mutableListOf<Handle>()

    /** One scratch rect for the range arcs, so a redraw does not allocate. */
    private val rangeRect = RectF()

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /** The scale that fits the whole canvas; the zoom range is relative to it. */
    private var fitScale = 1f
    private var zoomed = false
    private var panning = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var pinchSpan = 0f

    /** The bone the side panel last picked, drawn with a ring so it can be found. */
    var selected: String? = null
        private set

    private var active: Handle? = null
    private var heldJoint: JointHandle? = null
    private var lastTapAt = 0L

    /** Bone mode: joints and tips become draggable and the rig can be refitted. */
    var editBones = false
        private set

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF2C7BE5.toInt()
    }
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFFE2653C.toInt()
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xFF171528.toInt()
    }

    /** Nodes: a dot for where the point is, a ring for how far away it can be touched. */
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    /**
     * The arc a joint is allowed to move through: a translucent fan for the range, and two
     * lines for the ends. See [drawRange] for why it is centred on the REST direction.
     */
    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x222C7BE5
    }
    private val rangeEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x882C7BE5.toInt()
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33000000
    }
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xFF20A66B.toInt()
    }
    private val pendingDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF20A66B.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = 0xCC222233.toInt()
    }

    var onInfo: ((String) -> Unit)? = null

    fun load(folder: CharacterFolder) {
        this.folder = folder
        renamed.clear()
        selected = null
        zoomed = false
        cancelAddBone()
        library?.release()
        // Must be dropped as well as released: rebuild() reuses the stored library when
        // there is one, and reusing a released one hands the renderer recycled bitmaps.
        library = null
        // A spec that will not parse leaves the editor empty and says so on the line that is
        // already there for it, rather than taking the app down on the way in.
        val parsed = CharacterSpec.parseOrNull(folder.specText())
        if (parsed == null) {
            spec = null
            skeleton = null
            onInfo?.invoke(context.getString(R.string.character_unreadable, folder.id))
            invalidate()
            return
        }
        spec = parsed
        rebuild(parsed)

        onInfo?.invoke(
            parsed.id + "  ·  " + parsed.bones.size + " bones  ·  " +
                (library?.size ?: 0) + " parts"
        )
    }

    /** Re-bake the rig from the spec. Called after any edit to the geometry. */
    private fun rebuild(parsed: CharacterSpec) {
        val built = parsed.buildSkeleton()
        // The editor's skeleton is a throwaway copy, so the joints here may be dragged past
        // their own stops. That is the whole point of 「设为最大」: you cannot widen a range
        // with a limb that refuses to go outside it. The fan is drawn from the file's numbers,
        // so a limb outside the fan is exactly the picture of "this is what I want to change".
        // See Bone.ignoreLimits; the solver on the bench clamps regardless.
        for (b in built.bones) b.ignoreLimits = true
        built.update()
        skeleton = built

        val loaded = library ?: folder?.let {
            PartLibrary.load(it.partsDir, parsed.bones.map { b -> b.name })
        }
        library = loaded
        renderer = if (loaded == null || loaded.isEmpty) null else PartRenderer(
            built,
            loaded,
            parsed.drawOrder(),
            parsed.swaps,
        )

        handles.clear()
        val alive = built.bones.map { it.name }.toSet()
        val chainByLower = parsed.ikChains
            // The rig editor can delete either end of a chain, and a handle that aims at a
            // bone which is no longer there throws the moment it is touched.
            .filter { it.upper in alive && it.lower in alive }
            .associateBy { it.lower }
        for (b in built.bones) {
            if (b.springy) continue
            handles.add(Handle(b, chainByLower[b.name]))
        }
        requestLayout()
        invalidate()
    }

    fun setBoneEditMode(on: Boolean) {
        if (on == editBones) return
        editBones = on
        active = null
        heldJoint = null
        if (!on) cancelAddBone()
        if (on) skeleton?.reset()
        invalidate()
    }

    // ── rig surgery ─────────────────────────────────────────────────────────

    /** A bone being drawn: named and parented already, waiting for its two taps. */
    private var pendingName: String? = null
    private var pendingParent: String? = null
    private var pendingHead: Vec2? = null

    /** Fired whenever the rig's shape changes, so the host can refresh what it shows. */
    var onRigChanged: (() -> Unit)? = null

    private val renamed = HashMap<String, String>()

    val addingBone: Boolean get() = pendingName != null

    /** The bones as they stand right now, edits included and parents first. */
    fun rigBones(): List<BoneSpec> = spec?.bones?.toList() ?: emptyList()

    /**
     * The named points on the rig, as they stand right now.
     *
     * Edited in place like the bones are: the spec's list is the one the built [Skeleton] was
     * handed, so a node added here is a node the bench can feel without anything being rebuilt.
     */
    fun rigNodes(): List<NodeSpec> = spec?.nodes?.toList() ?: emptyList()

    /** Add a node, or change one that is already there. False when the name cannot be used. */
    fun saveNode(
        original: String?,
        name: String,
        bone: String,
        at: Float,
        radius: Float,
        prop: String,
    ): Boolean {
        val parsed = spec ?: return false
        val others = parsed.nodes.filter { it.name != original }
        val mine = NodeSpec(name, bone, at, radius, prop)
        val problem = RigEdit.nodeProblem(parsed.bones, others + mine)
        if (problem != null) {
            onInfo?.invoke(problem)
            return false
        }
        val existing = original?.let { old -> parsed.nodes.firstOrNull { it.name == old } }
        if (existing == null) parsed.nodes.add(mine) else {
            existing.name = name
            existing.bone = bone
            existing.at = at
            existing.radius = radius
            existing.prop = prop
        }
        onInfo?.invoke(if (existing == null) "加了节点 " + name else "改好了 " + name)
        onRigChanged?.invoke()
        return true
    }

    fun deleteNode(name: String): Boolean {
        val parsed = spec ?: return false
        val node = parsed.nodes.firstOrNull { it.name == name } ?: return false
        parsed.nodes.remove(node)
        onInfo?.invoke("删掉了节点 " + name)
        onRigChanged?.invoke()
        return true
    }

    /** A point on a bone, in canvas coordinates, for the editor to draw a node at. */
    fun nodePointOf(node: NodeSpec): Vec2 {
        val bone = spec?.bones?.firstOrNull { it.name == node.bone } ?: return Vec2.ZERO
        return Vec2(
            bone.head.x + (bone.tail.x - bone.head.x) * placeFraction(bone, node.at),
            bone.head.y + (bone.tail.y - bone.head.y) * placeFraction(bone, node.at),
        )
    }

    private fun placeFraction(bone: BoneSpec, at: Float): Float {
        val len = hypot(bone.tail.x - bone.head.x, bone.tail.y - bone.head.y)
        return if (len < 1e-3f) 0f else (at / len).coerceIn(0f, 1f)
    }

    /** Draw order, back to front, including any bone that has just been added. */
    fun rigLayers(): List<String> = spec?.layers?.sortedBy { it.z }?.map { it.bone } ?: emptyList()

    fun rootName(): String? = spec?.bones?.firstOrNull { it.parentName == null }?.name

    /**
     * Draw a new bone: the next two taps on the canvas are its head and its tail.
     *
     * Two taps rather than a form, because a bone is a thing in the drawing. The only
     * honest way to say where it goes is to point at the picture it has to fit.
     */
    fun startAddBone(name: String, parentName: String?) {
        pendingName = name
        pendingParent = parentName
        pendingHead = null
        heldJoint = null
        active = null
        // Those two taps must not read as a double tap: the second one would reset the
        // pose and eat the bone.
        lastTapAt = 0L
        onInfo?.invoke("点一下「" + name + "」的起点（关节）")
        invalidate()
    }

    fun cancelAddBone() {
        if (pendingName == null && pendingHead == null) return
        pendingName = null
        pendingParent = null
        pendingHead = null
        invalidate()
    }

    /**
     * Hang a bone off a different parent.
     *
     * Nothing moves. The head is stored in canvas coordinates and the parent-relative form
     * is derived when the rig is baked, so changing the parent changes what the bone
     * follows, not where it sits — which is the whole reason the rig is authored this way.
     */
    fun reparentBone(name: String, parentName: String?): Boolean {
        val parsed = spec ?: return false
        val bone = parsed.bones.firstOrNull { it.name == name } ?: return false
        if (parentName != null && parsed.bones.none { it.name == parentName }) return false
        if (parentName != null && RigEdit.descendants(parsed.bones, name).contains(parentName)) {
            onInfo?.invoke("不能接到自己的下级上，那样会成环")
            return false
        }
        if (parentName == null && parsed.bones.any { it.name != name && it.parentName == null }) {
            onInfo?.invoke("已经有根骨骼了，一棵骨架只能有 1 根")
            return false
        }
        bone.parentName = parentName
        // Parents must come first in the list, and the new one may well be further down.
        val reordered = RigEdit.order(parsed.bones.toList())
        parsed.bones.clear()
        parsed.bones.addAll(reordered)
        rebuild(parsed)
        onInfo?.invoke(name + " 现在挂在 " + (parentName ?: "（根）") + " 下面")
        onRigChanged?.invoke()
        return true
    }

    /**
     * Remove a bone. Its children are re-hung on its parent, so the rest of the figure
     * stays exactly where it is. The root is the one bone that cannot go: its children
     * would have nothing to hang from.
     */
    fun deleteBone(name: String): Boolean {
        val parsed = spec ?: return false
        val bone = parsed.bones.firstOrNull { it.name == name } ?: return false
        if (bone.parentName == null) {
            onInfo?.invoke("根骨骼不能删，它的子骨骼会没有归属")
            return false
        }
        for (b in parsed.bones) if (b.parentName == name) b.parentName = bone.parentName
        parsed.bones.removeAll { it.name == name }
        // The nodes that were places on this bone are places on nothing now. Children get
        // re-hung on the grandparent and keep working; a point in the middle of a limb that is
        // gone has nowhere to be re-hung TO, and a name nothing can touch is worse than a name
        // that is not there.
        val orphaned = parsed.nodes.filter { it.bone == name }
        parsed.nodes.removeAll { it.bone == name }
        if (orphaned.isNotEmpty()) {
            onInfo?.invoke("连同 " + orphaned.size + " 个节点一起删了")
        }
        ensureLayers()
        rebuild(parsed)
        onInfo?.invoke("删掉了 " + name)
        onRigChanged?.invoke()
        return true
    }

    /**
     * Give a bone a different name.
     *
     * The name is also the artwork's file name and the name every rule and drag chain
     * mentions, so a rename is not a string edit: it is remembered here and carried out on
     * disk when the rig is saved, art file and references included.
     */
    fun renameBone(name: String, next: String): Boolean {
        val parsed = spec ?: return false
        if (next.isEmpty()) return false
        if (parsed.bones.any { it.name == next }) {
            onInfo?.invoke("已经有一根骨骼叫 " + next)
            return false
        }
        val bone = parsed.bones.firstOrNull { it.name == name } ?: return false
        renamed[name] = next
        bone.name = next
        // A node names a place ON a bone, so it has to follow the bone's name or it becomes a
        // name pointing at nothing -- which is a rule that silently stops firing.
        for (n in parsed.nodes) if (n.bone == name) n.bone = next
        rebuild(parsed)
        onInfo?.invoke(name + " → " + next + " · 保存骨骼后连图片一起改名")
        onRigChanged?.invoke()
        return true
    }

    /** Bones renamed since the rig was loaded, old name to new. */
    fun renames(): Map<String, String> = HashMap(renamed)

    /** Keep only the root, which is where a rig drawn from nothing starts. */
    fun keepOnlyRoot(): Boolean {
        val parsed = spec ?: return false
        val root = parsed.bones.firstOrNull { it.parentName == null } ?: return false
        parsed.bones.removeAll { it.name != root.name }
        ensureLayers()
        rebuild(parsed)
        onInfo?.invoke("只留下根骨骼 " + root.name)
        onRigChanged?.invoke()
        return true
    }

    /**
     * Keep the draw order in step with the bones.
     *
     * A bone with no layer is a bone that is never drawn, and a layer for a bone that no
     * longer exists is a hole in the order. A new bone goes on top; where it really belongs
     * is the depth editor's question, not this one's.
     */
    private fun ensureLayers() {
        val parsed = spec ?: return
        val alive = parsed.bones.map { it.name }.toSet()
        parsed.layers.removeAll { it.bone !in alive }
        var z = (parsed.layers.maxOfOrNull { it.z } ?: 0) + 10
        for (b in parsed.bones) {
            if (parsed.layers.none { it.bone == b.name }) {
                parsed.layers.add(LayerSpec(b.name, z))
                z += 10
            }
        }
    }

    /** One tap of a bone being drawn: the first sets the head, the second finishes it. */
    private fun placePoint(x: Float, y: Float) {
        val parsed = spec ?: return
        val name = pendingName ?: return
        val start = pendingHead
        if (start == null) {
            pendingHead = toCanvas(x, y)
            onInfo?.invoke("再点一下「" + name + "」的末端")
            invalidate()
            return
        }
        val end = toCanvas(x, y)
        if (hypot(end.x - start.x, end.y - start.y) < 2f) {
            onInfo?.invoke("两点挨在一起了，给「" + name + "」一个方向")
            return
        }
        parsed.bones.add(
            BoneSpec(
                name = name,
                // A new bone is a leaf, so appending it keeps parents ahead of children.
                parentName = pendingParent,
                head = start,
                tail = end,
                minAngle = -180f,
                maxAngle = 180f,
                springy = false,
                stiffness = 0.35f,
                damping = 0.86f,
                gravity = 0f,
                colliderType = "capsule",
                // Zero means "work out a thickness from the figure" at load time.
                colliderRadius = 0f,
            )
        )
        pendingName = null
        pendingParent = null
        pendingHead = null
        ensureLayers()
        rebuild(parsed)
        onInfo?.invoke(name + " 加好了 · 记得「保存骨骼」")
        onRigChanged?.invoke()
    }

    /** The current joint angles, for saving as a pose. */
    fun currentAngles(): Map<String, Float> =
        skeleton?.bones?.associate { it.name to it.rotation } ?: emptyMap()

    fun applyPose(angles: Map<String, Float>) {
        val sk = skeleton ?: return
        sk.reset()
        for (b in sk.bones) {
            angles[b.name]?.let { b.rotation = it }
        }
        sk.update()
        invalidate()
    }

    fun resetPose() {
        skeleton?.reset()
        invalidate()
    }

    /**
     * The angle this joint is posed at right now, in DEGREES, or null if there is no such bone.
     *
     * Degrees because that is the unit the 属性 dialog and `BoneSpec.minAngle` are in, and the
     * only reason this exists is to be written straight into one of them: 「设为最小 / 最大」
     * takes the pose the user just made and makes it the range. Radians never leave the solver.
     */
    fun currentDegrees(name: String): Float? =
        skeleton?.find(name)?.let { Math.toDegrees(it.rotation.toDouble()).toFloat() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = spec ?: return
        val pad = 8f * density
        fitScale = min((w - pad * 2) / s.canvasWidth, (h - pad * 2) / s.canvasHeight)
        // Only refit while nobody has zoomed: a rotation should not throw away the view
        // somebody just spent a minute getting to.
        if (!zoomed || oldw == 0) {
            scale = fitScale
            offsetX = (w - s.canvasWidth * scale) / 2f
            offsetY = (h - s.canvasHeight * scale) / 2f
        }
    }

    /**
     * Zoom to a bone and put it in the middle.
     *
     * Finding one hand among nineteen joints by tapping at a picture is guesswork on a
     * phone; a name that zooms is not.
     */
    fun focusOn(name: String) {
        val sk = skeleton ?: return
        val bone = sk.find(name) ?: return
        selected = name
        val p = bone.worldPosition
        scale = (fitScale * ZOOM_ON_PICK).coerceIn(fitScale * 0.25f, fitScale * 12f)
        offsetX = width / 2f - p.x * scale
        offsetY = height / 2f - p.y * scale
        zoomed = true
        invalidate()
        onInfo?.invoke(name + " · " + boneLabel(name))
    }

    private fun boneLabel(name: String): String = when {
        name.startsWith("shoulder") -> "肩"
        name.startsWith("upperarm") -> "上臂"
        name.startsWith("forearm") -> "前臂"
        name.startsWith("hand") -> "手"
        name.startsWith("thigh") -> "大腿"
        name.startsWith("shin") -> "小腿"
        name.startsWith("foot") -> "脚"
        else -> ""
    }

    /** Drag the end of a limb: a two-bone chain is solved, a single bone is aimed. */
    private fun dragHandle(h: Handle, sk: Skeleton, event: MotionEvent) {
        val target = toCanvas(event.x, event.y)
        val chain = h.chain
        if (chain != null) {
            TwoBoneIK.drag(sk, sk.require(chain.upper), sk.require(chain.lower), target, chain.bend)
        } else {
            h.bone.aimAt(target)
            sk.update()
        }
        // What you just dragged is what the panel is about from now on: the range readout and
        // 「设为最小 / 设为最大」 both act on `selected`, and posing is where the angle that
        // gets captured comes from. Without this the capture would need a second trip to the
        // bone list to say which joint was meant -- after the drag that posed it.
        selected = h.bone.name
        invalidate()
    }

    /**
     * The arc this joint may move through, and where it is right now.
     *
     * Angles are the ones the 属性 dialog shows: measured from the bone's REST direction,
     * because that is exactly what `minAngle` / `maxAngle` are offsets from. So the fan does
     * NOT swing when the joint is posed — the bone line moves inside it. That is the whole
     * picture this is for: 能转到哪儿 and 现在在哪儿, at the same time, instead of typing two
     * numbers and going to the bench to find out what they meant.
     *
     * The radius is the bone's own length, so the arc reaches where the tip reaches, and a
     * floor keeps a very short bone's arc from disappearing under the joint dot.
     */
    private fun drawRange(canvas: Canvas, b: Bone) {
        val rest = b.worldRotation - b.rotation
        val lo = rest + b.minAngle
        val hi = rest + b.maxAngle
        val cx = vx(b.worldPosition)
        val cy = vy(b.worldPosition)
        val r = (b.length * scale).coerceAtLeast(MIN_RANGE_RADIUS * density)

        rangeRect.set(cx - r, cy - r, cx + r, cy + r)
        val start = Math.toDegrees(lo.toDouble()).toFloat()
        val sweep = Math.toDegrees((hi - lo).toDouble()).toFloat()
        canvas.drawArc(rangeRect, start, sweep, true, rangePaint)
        canvas.drawArc(rangeRect, start, sweep, true, rangeEdgePaint)
        // The two ends as lines rather than as arc caps: an edge you can line the limb up
        // against is the thing being adjusted here.
        canvas.drawLine(cx, cy, cx + cos(lo) * r, cy + sin(lo) * r, rangeEdgePaint)
        canvas.drawLine(cx, cy, cx + cos(hi) * r, cy + sin(hi) * r, rangeEdgePaint)

        val now = Math.toDegrees(b.rotation.toDouble()).toFloat()
        val text = context.getString(
            R.string.rig_range_text,
            Math.round(lo * 180 / PI).toInt(),
            Math.round(hi * 180 / PI).toInt(),
        )
        canvas.drawText(text, cx + 10f * density, cy - 8f * density, textPaint)
        if (abs(now) >= 0.5f) {
            canvas.drawText(
                context.getString(R.string.rig_range_now, Math.round(now)),
                cx + 10f * density, cy + 5f * density, textPaint,
            )
        }
    }

    private fun span(e: MotionEvent): Float {        if (e.pointerCount < 2) return 0f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }

    private fun midX(e: MotionEvent) = if (e.pointerCount >= 2) (e.getX(0) + e.getX(1)) / 2f else e.x
    private fun midY(e: MotionEvent) = if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2f else e.y

    private fun vx(p: Vec2) = offsetX + p.x * scale
    private fun vy(p: Vec2) = offsetY + p.y * scale
    private fun toCanvas(x: Float, y: Float) = Vec2((x - offsetX) / scale, (y - offsetY) / scale)

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val sk = skeleton ?: return

        canvas.drawRect(
            offsetX, offsetY,
            offsetX + s.canvasWidth * scale, offsetY + s.canvasHeight * scale,
            framePaint
        )

        renderer?.let {
            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scale)
            it.draw(canvas)
            canvas.restore()
        }

        val fade = renderer != null
        linePaint.alpha = if (fade) 110 else 255
        jointPaint.alpha = if (fade) 110 else 255

        for (b in sk.bones) {
            val hx = vx(b.worldPosition)
            val hy = vy(b.worldPosition)
            val tip = b.tipPosition()
            val tx = vx(tip)
            val ty = vy(tip)
            val paint = if (b.springy) linePaint else linePaint
            paint.color = colourFor(b.name)
            canvas.drawLine(hx, hy, tx, ty, paint)

            jointPaint.color = paint.color
            canvas.drawCircle(hx, hy, 4.5f * density, jointPaint)
            canvas.drawCircle(hx, hy, 2f * density, jointPaint)
        }

        // The nodes: a dot where they are and a ring at how far away something can touch them.
        // Drawn before the picked bone's ring so that a selected bone is still the loudest
        // thing on the canvas.
        for (n in s.nodes) {
            val q = nodePointOf(n)
            val px = vx(q)
            val py = vy(q)
            nodeRingPaint.color = if (n.prop.isEmpty()) 0x556E6A62 else 0x552B7A8A
            canvas.drawCircle(px, py, n.radius * scale, nodeRingPaint)
            nodePaint.color = if (n.prop.isEmpty()) 0xCC6E6A62.toInt() else 0xCC2B7A8A.toInt()
            canvas.drawCircle(px, py, 5f * density, nodePaint)
        }

        // The bone the side panel picked, ringed at both ends: the canvas is zoomed in far
        // enough by then that "somewhere in that arm" is not a useful answer.
        selected?.let { pick ->
            sk.find(pick)?.let { b ->
                canvas.drawCircle(vx(b.worldPosition), vy(b.worldPosition), 15f * density, selectPaint)
                val t = b.tipPosition()
                canvas.drawCircle(vx(t), vy(t), 11f * density, selectPaint)
                drawRange(canvas, b)
            }
        }

        if (editBones) {
            // Joints are blue and fixed points of the rig; tips are orange and set how far
            // the artwork reaches. They are the two things worth being able to move.
            for (b in sk.bones) {
                val h = b.worldPosition
                val t = b.tipPosition()
                canvas.drawCircle(vx(h), vy(h), 9f * density, headPaint)
                canvas.drawCircle(vx(t), vy(t), 9f * density, tailPaint)
            }
            val held = heldJoint
            if (held != null) {
                val b = sk.find(held.boneName)
                if (b != null) {
                    val p = if (held.tail) b.tipPosition() else b.worldPosition
                    canvas.drawCircle(vx(p), vy(p), 13f * density, textPaint)
                }
            }
            // The head of a bone that is half drawn, so the second tap has something to
            // aim away from.
            pendingHead?.let {
                canvas.drawCircle(vx(it), vy(it), 11f * density, pendingPaint)
                canvas.drawCircle(vx(it), vy(it), 4f * density, pendingDot)
            }
        } else {
            for (h in handles) {
                val tip = h.bone.tipPosition()
                handlePaint.color =
                    if (h === active) 0xFF000000.toInt() else 0x88000000.toInt()
                val r = (if (h.chain != null) 7f else 5f) * density
                canvas.drawCircle(vx(tip), vy(tip), r, handlePaint)
            }
        }

        canvas.drawText(
            when {
                pendingName != null && pendingHead == null ->
                    "加骨骼 " + pendingName + " · 点一下起点（关节）"
                pendingName != null ->
                    "加骨骼 " + pendingName + " · 再点一下末端"
                editBones -> "蓝点 = 关节 · 橙点 = 末端 · 双指缩放"
                else -> "拖关节摆姿势 · 双指缩放 · 双击复位"
            },
            10f * density, 16f * density, textPaint
        )
    }

    private fun colourFor(name: String): Int = when {
        name.startsWith("shoulder") || name.startsWith("upperarm") ||
            name.startsWith("forearm") || name.startsWith("hand") ->
            if (name.endsWith("_L")) 0xFFE25A8C.toInt() else 0xFFE88C3C.toInt()
        name.startsWith("thigh") || name.startsWith("shin") || name.startsWith("foot") ->
            if (name.endsWith("_L")) 0xFF46AA6E.toInt() else 0xFF3C96B4.toInt()
        else -> 0xFF7C5CE0.toInt()
    }

    private fun pickHandle(x: Float, y: Float): Handle? {
        var best: Handle? = null
        var bestDist = 34f * density
        for (h in handles) {
            val tip = h.bone.tipPosition()
            val d = hypot(vx(tip) - x, vy(tip) - y)
            if (d < bestDist) {
                bestDist = d
                best = h
            }
        }
        return best
    }

    private fun pickJoint(x: Float, y: Float): JointHandle? {
        val sk = skeleton ?: return null
        var best: JointHandle? = null
        var bestDist = 30f * density
        for (b in sk.bones) {
            val h = b.worldPosition
            val d1 = hypot(vx(h) - x, vy(h) - y)
            if (d1 < bestDist) {
                bestDist = d1
                best = JointHandle(b.name, false)
            }
            val t = b.tipPosition()
            val d2 = hypot(vx(t) - x, vy(t) - y)
            if (d2 < bestDist) {
                bestDist = d2
                best = JointHandle(b.name, true)
            }
        }
        return best
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sk = skeleton ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A bone being drawn owns both taps.
                if (pendingName != null) {
                    placePoint(event.x, event.y)
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - lastTapAt < 300) {
                    sk.reset()
                    active = null
                    heldJoint = null
                    invalidate()
                    onInfo?.invoke("复位")
                    lastTapAt = 0L
                    return true
                }
                lastTapAt = now

                if (editBones) {
                    heldJoint = pickJoint(event.x, event.y)
                    if (heldJoint != null) return true
                } else {
                    active = pickHandle(event.x, event.y)
                    if (active != null) return true
                }
                // Nothing under the finger: the finger is moving the canvas, which is how
                // a corner of a 1024x2048 drawing gets looked at on a phone at all.
                panning = true
                lastPanX = event.x
                lastPanY = event.y
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                active = null
                heldJoint = null
                panning = false
                pinchSpan = span(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val s = span(event)
                    if (pinchSpan > 1f && s > 1f) {
                        val focusX = midX(event)
                        val focusY = midY(event)
                        val before = toCanvas(focusX, focusY)
                        scale = (scale * (s / pinchSpan)).coerceIn(fitScale * 0.25f, fitScale * 12f)
                        // Keep the point between the fingers where it is.
                        offsetX = focusX - before.x * scale
                        offsetY = focusY - before.y * scale
                        zoomed = true
                    }
                    pinchSpan = s
                    invalidate()
                    return true
                }
                if (editBones && heldJoint != null) {
                    moveJoint(heldJoint!!, toCanvas(event.x, event.y))
                    return true
                }
                if (!editBones && active != null) {
                    dragHandle(active!!, sk, event)
                    return true
                }
                if (panning) {
                    offsetX += event.x - lastPanX
                    offsetY += event.y - lastPanY
                    lastPanX = event.x
                    lastPanY = event.y
                    zoomed = true
                    invalidate()
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = null
                heldJoint = null
                panning = false
                pinchSpan = 0f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Move one end of a bone and re-bake.
     *
     * Dragging a joint carries the whole limb below it, because children are stored
     * relative to their parent's joint — which is what "fit this skeleton to my drawing"
     * needs. Dragging a tip only changes how far the bone reaches, which is what decides
     * where the artwork ends and where the IK aims.
     */
    private fun moveJoint(held: JointHandle, to: Vec2) {
        val parsed = spec ?: return
        val entry = parsed.bones.firstOrNull { it.name == held.boneName } ?: return
        if (held.tail) entry.tail = to else entry.head = to
        rebuild(parsed)
        onInfo?.invoke(
            held.boneName + if (held.tail) " 末端 → " else " 关节 → " +
                to.x.toInt() + ", " + to.y.toInt()
        )
    }

    private companion object {
        /** How far a pick in the side panel zooms in. Enough to see a hand, not a pixel. */
        const val ZOOM_ON_PICK = 2.5f

        /** The shortest arc [drawRange] will draw, so a stub of a bone still shows its range. */
        const val MIN_RANGE_RADIUS = 26f

        const val PI = Math.PI.toFloat()
    }

    fun release() {
        library?.release()
        library = null
        renderer = null
    }
}