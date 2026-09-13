package dev.atp.pet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.skeleton.Bone
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.BoneSpec
import dev.atp.pet.engine.skeleton.IkChainSpec
import dev.atp.pet.engine.skeleton.LayerSpec
import dev.atp.pet.engine.skeleton.RigEdit
import dev.atp.pet.engine.skeleton.Skeleton
import dev.atp.pet.engine.skeleton.TwoBoneIK
import dev.atp.pet.render.PartLibrary
import dev.atp.pet.render.PartRenderer
import kotlin.math.hypot
import kotlin.math.min

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

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

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
        cancelAddBone()
        library?.release()
        // Must be dropped as well as released: rebuild() reuses the stored library when
        // there is one, and reusing a released one hands the renderer recycled bitmaps.
        library = null
        val parsed = CharacterSpec.parse(folder.specText())
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
        built.update()
        skeleton = built

        val loaded = library ?: folder?.let {
            PartLibrary.load(it.partsDir, parsed.bones.map { b -> b.name })
        }
        library = loaded
        renderer = if (loaded == null || loaded.isEmpty) null else PartRenderer(
            built,
            loaded,
            parsed.layers.sortedBy { it.z }.map { it.bone },
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

    val addingBone: Boolean get() = pendingName != null

    /** The bones as they stand right now, edits included and parents first. */
    fun rigBones(): List<BoneSpec> = spec?.bones?.toList() ?: emptyList()

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
        ensureLayers()
        rebuild(parsed)
        onInfo?.invoke("删掉了 " + name)
        onRigChanged?.invoke()
        return true
    }

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = spec ?: return
        val pad = 8f * density
        scale = min((w - pad * 2) / s.canvasWidth, (h - pad * 2) / s.canvasHeight)
        offsetX = (w - s.canvasWidth * scale) / 2f
        offsetY = (h - s.canvasHeight * scale) / 2f
    }

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
                editBones -> "蓝点 = 关节 · 橙点 = 骨骼末端"
                else -> "拖关节摆姿势 · 双击复位"
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
                    return heldJoint != null
                }
                active = pickHandle(event.x, event.y)
                return active != null
            }

            MotionEvent.ACTION_MOVE -> {
                if (editBones) {
                    val held = heldJoint ?: return false
                    moveJoint(held, toCanvas(event.x, event.y))
                    return true
                }
                val h = active ?: return false
                val target = toCanvas(event.x, event.y)
                val chain = h.chain
                if (chain != null) {
                    TwoBoneIK.drag(
                        sk,
                        sk.require(chain.upper),
                        sk.require(chain.lower),
                        target,
                        chain.bend,
                    )
                } else {
                    h.bone.aimAt(target)
                    sk.update()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = null
                heldJoint = null
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

    fun release() {
        library?.release()
        library = null
        renderer = null
    }
}