package dev.atp.pet

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.data.CharacterStore
import dev.atp.pet.engine.skeleton.BoneSpec
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.RigEdit
import dev.atp.pet.engine.skeleton.SwapRuleSpec
import dev.atp.pet.ui.PartAlignView
import dev.atp.pet.ui.PhysicsSandboxView
import dev.atp.pet.ui.PosePreview
import dev.atp.pet.ui.SkeletonView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The shell.
 *
 *   测试场    summon one of the packages and throw it around
 *   桌宠管理  a folder of packages; open one to import artwork for its bones
 *
 * Imports land as <bone>.png inside the package's parts directory, which is the same
 * thing an artist exporting by hand would produce. The app owns the name; the picked
 * file's own name is never used.
 */
class MainActivity : AppCompatActivity() {

    private enum class Pane {
        PLACEHOLDER, SANDBOX, PET_LIST, PET_PARTS, PET_RIG, PART_ALIGN, PET_DEPTH
    }

    private lateinit var store: CharacterStore
    private var characters: List<CharacterFolder> = emptyList()
    private var summoned: CharacterFolder? = null
    private var opened: CharacterFolder? = null
    private var awaitingBone: String? = null
    private var railCollapsed = false
    private var stiffnessStep = 0

    /** Depth editing state, back-to-front. */
    private lateinit var depthScroll: View
    private lateinit var depthList: LinearLayout
    private var depthBones = mutableListOf<String>()
    private var depthRules = mutableListOf<SwapRuleSpec>()
    private var wizardStage = 0
    private var wizardPart: String? = null
    private var wizardRef: String? = null
    private var wizardAbove = true

    /** Rig editor. */
    private lateinit var rigBar: View
    private lateinit var rigMode: TextView
    private lateinit var rigAddBone: View
    private lateinit var rigBoneList: View
    private lateinit var rigSaveBones: View
    private lateinit var rigSavePose: View
    private var rigBoneMode = false

    /** The open bone list, so an edit can redraw it where it stands. */
    private var boneDialog: AlertDialog? = null
    private lateinit var boneListBox: LinearLayout

    /** Which saved action the sandbox is holding, if any. */
    private var activePose: String? = null

    /** The open action list, so a rename or a delete can redraw it in place. */
    private var actionDialog: AlertDialog? = null

    private lateinit var sidebar: LinearLayout
    private lateinit var railHint: TextView
    private lateinit var placeholder: View
    private lateinit var contentTitle: TextView
    private lateinit var sandboxPane: View
    private lateinit var sandboxView: PhysicsSandboxView
    private lateinit var petChooser: LinearLayout
    private lateinit var petListScroll: View
    private lateinit var petList: LinearLayout
    private lateinit var partListScroll: View
    private lateinit var partList: LinearLayout
    private lateinit var skeletonView: SkeletonView
    private lateinit var alignPane: View
    private lateinit var alignView: PartAlignView
    private lateinit var statusLine: TextView
    private lateinit var menuItems: List<TextView>

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> onImagePicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = CharacterStore(this)

        sidebar = findViewById(R.id.sidebar)
        railHint = findViewById(R.id.railHint)
        placeholder = findViewById(R.id.placeholder)
        contentTitle = findViewById(R.id.contentTitle)
        sandboxPane = findViewById(R.id.sandboxPane)
        sandboxView = findViewById(R.id.sandboxView)
        petChooser = findViewById(R.id.petChooser)
        petListScroll = findViewById(R.id.petListScroll)
        petList = findViewById(R.id.petList)
        partListScroll = findViewById(R.id.partListScroll)
        partList = findViewById(R.id.partList)
        skeletonView = findViewById(R.id.skeletonView)
        rigBar = findViewById(R.id.rigBarScroll)
        rigMode = findViewById(R.id.rigMode)
        rigAddBone = findViewById(R.id.rigAddBone)
        rigBoneList = findViewById(R.id.rigBoneList)
        rigSaveBones = findViewById(R.id.rigSaveBones)
        rigSavePose = findViewById(R.id.rigSavePose)
        findViewById<View>(R.id.rigMode).setOnClickListener { toggleRigMode() }
        findViewById<View>(R.id.rigAddBone).setOnClickListener { askNewBone() }
        findViewById<View>(R.id.rigBoneList).setOnClickListener { showBoneList() }
        findViewById<View>(R.id.rigSaveBones).setOnClickListener { saveBones() }
        findViewById<View>(R.id.rigReset).setOnClickListener { skeletonView.resetPose() }
        findViewById<View>(R.id.rigSavePose).setOnClickListener { askPoseName() }
        // Adding, deleting or reparenting a bone redraws the list it was done from.
        skeletonView.onRigChanged = { refreshBoneList() }
        applyRigMode()

        depthScroll = findViewById(R.id.depthScroll)
        depthList = findViewById(R.id.depthList)
        alignPane = findViewById(R.id.alignPane)
        alignView = findViewById(R.id.alignView)
        statusLine = findViewById(R.id.statusLine)

        alignView.onInfo = { statusLine.text = it }
        findViewById<View>(R.id.alignCancel).setOnClickListener {
            alignView.release()
            opened?.let { buildPartList(it) }
            show(Pane.PET_PARTS)
        }
        findViewById<View>(R.id.alignCanvas).setOnClickListener { alignView.fitToCanvas() }
        findViewById<View>(R.id.alignBone).setOnClickListener { alignView.fitToBone() }
        findViewById<View>(R.id.alignMinus).setOnClickListener { alignView.nudgeScale(1f / 1.15f) }
        findViewById<View>(R.id.alignPlus).setOnClickListener { alignView.nudgeScale(1.15f) }
        findViewById<View>(R.id.alignConfirm).setOnClickListener { confirmImport() }

        skeletonView.onInfo = { statusLine.text = it }
        sandboxView.onInfo = { statusLine.text = it }

        menuItems = listOf(
            findViewById(R.id.menuSandbox),
            findViewById(R.id.menuPets),
            findViewById(R.id.menuProps),
            findViewById(R.id.menuLogic),
        )
        menuItems.forEach { item ->
            item.tag = item.text.toString()
            item.setOnClickListener { select(item) }
        }
        findViewById<View>(R.id.railHeader).setOnClickListener { setRail(!railCollapsed) }

        store.ensureSeeded()
        reloadCharacters()

        menuItems.first().isSelected = true
        select(menuItems.first())
    }

    // ── data ────────────────────────────────────────────────────────────────

    private fun reloadCharacters() {
        characters = store.list()
        summoned = characters.firstOrNull()
        buildPetChooser()
        buildPetList()
        summoned?.let { sandboxView.load(it) }
    }

    private fun select(item: TextView) {
        menuItems.forEach { it.isSelected = false }
        item.isSelected = true
        contentTitle.text = item.text
        when (item.id) {
            R.id.menuSandbox -> show(Pane.SANDBOX)
            R.id.menuPets -> show(Pane.PET_LIST)
            else -> show(Pane.PLACEHOLDER)
        }
    }

    private fun show(pane: Pane) {
        placeholder.visibility = if (pane == Pane.PLACEHOLDER) View.VISIBLE else View.GONE
        sandboxPane.visibility = if (pane == Pane.SANDBOX) View.VISIBLE else View.GONE
        petListScroll.visibility = if (pane == Pane.PET_LIST) View.VISIBLE else View.GONE
        partListScroll.visibility = if (pane == Pane.PET_PARTS) View.VISIBLE else View.GONE
        skeletonView.visibility = if (pane == Pane.PET_RIG) View.VISIBLE else View.GONE
        alignPane.visibility = if (pane == Pane.PART_ALIGN) View.VISIBLE else View.GONE
        depthScroll.visibility = if (pane == Pane.PET_DEPTH) View.VISIBLE else View.GONE
        rigBar.visibility = if (pane == Pane.PET_RIG) View.VISIBLE else View.GONE
        if (pane != Pane.PET_RIG && rigBoneMode) {
            rigBoneMode = false
            skeletonView.setBoneEditMode(false)
            applyRigMode()
        }
        statusLine.visibility =
            if (pane == Pane.SANDBOX || pane == Pane.PET_RIG || pane == Pane.PART_ALIGN)
                View.VISIBLE
            else View.GONE

        when (pane) {
            Pane.SANDBOX -> statusLine.text = "拖起来甩出去 · 双击复位 · 上面选桌宠"
            Pane.PET_LIST -> statusLine.text = ""
            Pane.PET_PARTS -> statusLine.text = ""
            Pane.PET_RIG -> rigHint()
            Pane.PART_ALIGN -> Unit
            Pane.PET_DEPTH -> statusLine.text = getString(R.string.depth_hint)
            Pane.PLACEHOLDER -> statusLine.text = ""
        }
    }

    private fun setRail(collapsed: Boolean) {
        railCollapsed = collapsed
        val width = (if (collapsed) 54 else 128) * resources.displayMetrics.density
        sidebar.layoutParams = (sidebar.layoutParams as LinearLayout.LayoutParams).apply {
            this.width = width.roundToInt()
        }
        railHint.visibility = if (collapsed) View.GONE else View.VISIBLE
        railHint.text = getString(if (collapsed) R.string.rail_expand else R.string.rail_collapse)
        menuItems.forEach { item ->
            val full = item.tag as String
            item.text = if (collapsed) full.take(1) else full
        }
    }

    // ── 测试场 ──────────────────────────────────────────────────────────────

    private fun buildPetChooser() {
        petChooser.removeAllViews()
        if (characters.isEmpty()) {
            petChooser.addView(label(getString(R.string.pets_title) + ": none", 12f, MUTED))
            return
        }
        // Stiffness is the dial between a limp ragdoll and one that holds a pose. It is
        // the one physics number worth having on screen while the feel is being tuned.
        val stiffChip = label(STIFFNESS_LABELS[stiffnessStep], 12f, INK)
        stiffChip.background = getDrawable(R.drawable.menu_item_selected)
        stiffChip.setPadding(dp(12), dp(6), dp(12), dp(6))
        val sp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        sp.marginEnd = dp(10)
        stiffChip.layoutParams = sp
        stiffChip.setOnClickListener {
            stiffnessStep = (stiffnessStep + 1) % STIFFNESS_VALUES.size
            sandboxView.stiffness = STIFFNESS_VALUES[stiffnessStep]
            buildPetChooser()
        }
        petChooser.addView(stiffChip)

        // Actions live behind one button rather than a row of chips: a character can have
        // any number of them, and the list needs room to show what each one looks like.
        val poses = summoned?.let { store.loadPoses(it.id) } ?: emptyList()
        val action = label(
            if (activePose == null) getString(R.string.sandbox_actions) + " (" + poses.size + ")"
            else getString(R.string.sandbox_actions) + " · " + activePose,
            12f, INK,
        )
        action.background = getDrawable(R.drawable.menu_item_selected)
        action.setPadding(dp(12), dp(6), dp(12), dp(6))
        val ap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        ap.marginEnd = dp(10)
        action.layoutParams = ap
        action.setOnClickListener { showActionList() }
        petChooser.addView(action)

        for (folder in characters) {
            val chip = label(folder.id, 12f, if (folder == summoned) INK else MUTED)
            chip.background = getDrawable(
                if (folder == summoned) R.drawable.menu_item_selected else R.drawable.menu_item_idle
            )
            chip.setPadding(dp(12), dp(6), dp(12), dp(6))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = dp(6)
            chip.layoutParams = params
            chip.setOnClickListener {
                summoned = folder
                activePose = null
                sandboxView.load(folder)
                buildPetChooser()
            }
            petChooser.addView(chip)
        }
    }

    /**
     * The action list.
     *
     * 摆姿势 saves actions; this is where they get used. Every row is one tap from the pet
     * doing it, and renaming and deleting live here too: this is where a name stops meaning
     * anything, three weeks after it was saved.
     */
    private fun showActionList() {
        val folder = summoned ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_list_title))
            .setView(box)
            .setNegativeButton(R.string.action_close, null)
            .setOnDismissListener { actionDialog = null }
            .create()
        actionDialog = dialog
        fillActionList(box, folder)
        dialog.show()
    }

    private fun fillActionList(box: LinearLayout, folder: CharacterFolder) {
        box.removeAllViews()
        val poses = store.loadPoses(folder.id)
        // The preview is drawn from the skeleton alone; a spec that will not parse just
        // means the list comes up without pictures.
        val spec = try {
            CharacterSpec.parse(folder.specText())
        } catch (e: Exception) {
            null
        }
        actionDialog?.setTitle(getString(R.string.action_list_title) + " (" + poses.size + ")")

        box.addView(
            actionRow(
                folder, box, null, emptyMap(),
                getString(R.string.action_none_hint), spec, activePose == null,
            )
        )
        for (pose in poses) {
            box.addView(
                actionRow(
                    folder, box, pose.name, pose.angles,
                    getString(R.string.action_joints, pose.angles.size), spec,
                    activePose == pose.name,
                )
            )
        }
        if (poses.isEmpty()) {
            box.addView(label(getString(R.string.action_empty), 11f, MUTED, top = 14))
        }
    }

    /** One row of the action list: a picture, a name, and the two things you can do to it. */
    private fun actionRow(
        folder: CharacterFolder,
        box: LinearLayout,
        name: String?,
        angles: Map<String, Float>,
        detail: String,
        spec: CharacterSpec?,
        selected: Boolean,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(
                if (selected) R.drawable.menu_item_selected else R.drawable.menu_item_idle
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.bottomMargin = dp(6)
        row.layoutParams = params

        val side = dp(46)
        val picture = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            spec?.let {
                setImageBitmap(
                    PosePreview.render(
                        it, angles, (side * 2).coerceAtMost(160),
                        if (selected) FIGURE_ON else FIGURE_OFF,
                    )
                )
            }
        }
        picture.layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = dp(10) }
        row.addView(picture)

        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        )
        text.addView(label(name ?: getString(R.string.pose_none), 14f, INK))
        text.addView(label(detail, 10f, MUTED))
        row.addView(text)

        if (name == null) {
            row.addView(label(if (selected) "✓" else "", 15f, INK))
        } else {
            val rename = label(getString(R.string.action_rename), 11f, MUTED)
            rename.setPadding(dp(8), dp(6), dp(8), dp(6))
            rename.setOnClickListener { askRenameAction(folder, box, name, angles) }
            row.addView(rename)

            val remove = label(getString(R.string.action_delete), 11f, MUTED)
            remove.setPadding(dp(8), dp(6), dp(8), dp(6))
            remove.setOnClickListener { confirmDeleteAction(folder, box, name) }
            row.addView(remove)
        }

        row.setOnClickListener {
            activePose = name
            sandboxView.applyPose(if (name == null) null else angles)
            // Holding an action drives the springs to a strength of its own, so the chip
            // has to move with it instead of going on claiming the old setting.
            stiffnessStep = STIFFNESS_VALUES.indices
                .minByOrNull { abs(STIFFNESS_VALUES[it] - sandboxView.stiffness) } ?: 0
            buildPetChooser()
            // Picking is the point of the list; closing it lets the pet be watched.
            actionDialog?.dismiss()
        }
        return row
    }

    private fun askRenameAction(
        folder: CharacterFolder,
        box: LinearLayout,
        name: String,
        angles: Map<String, Float>,
    ) {
        val input = EditText(this).apply {
            setText(name)
            setSelection(name.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val next = input.text.toString().trim()
                if (next.isEmpty() || next == name) return@setPositiveButton
                // Written under the new name first, so a failure cannot lose the action.
                if (store.savePose(folder.id, next, angles)) {
                    store.deletePose(folder.id, name)
                    if (activePose == name) activePose = next
                    Toast.makeText(this, getString(R.string.action_renamed), Toast.LENGTH_SHORT).show()
                }
                fillActionList(box, folder)
                if (summoned?.id == folder.id) buildPetChooser()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    private fun confirmDeleteAction(folder: CharacterFolder, box: LinearLayout, name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_delete) + " · " + name)
            .setPositiveButton(R.string.depth_remove) { _, _ ->
                store.deletePose(folder.id, name)
                if (activePose == name) {
                    activePose = null
                    sandboxView.applyPose(null)
                }
                fillActionList(box, folder)
                buildPetChooser()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    // ── 桌宠管理 ────────────────────────────────────────────────────────────

    private fun buildPetList() {
        petList.removeAllViews()
        petList.addView(label(getString(R.string.pets_subtitle), 12f, MUTED, bottom = 10))

        for (folder in characters) {
            val bones = boneNames(folder)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.menu_item_idle)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.bottomMargin = dp(8)
            card.layoutParams = params

            card.addView(label(folder.id, 15f, INK))
            card.addView(
                label(
                    bones.size.toString() + " bones · " + folder.partCount(bones) + " parts drawn",
                    11f, MUTED,
                )
            )
            card.setOnClickListener { openPet(folder) }
            petList.addView(card)
        }
    }

    private fun openPet(folder: CharacterFolder) {
        opened = folder
        buildPartList(folder)
        show(Pane.PET_PARTS)
    }

    private fun buildPartList(folder: CharacterFolder) {
        partList.removeAllViews()

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = label(getString(R.string.pet_back), 13f, INK)
        back.setPadding(dp(12), dp(6), dp(12), dp(6))
        back.background = getDrawable(R.drawable.menu_item_idle)
        back.setOnClickListener { show(Pane.PET_LIST) }
        header.addView(back)
        header.addView(label("  " + folder.id, 15f, INK))
        partList.addView(header)

        val rig = label(getString(R.string.pet_edit_rig), 12f, INK)
        rig.setPadding(dp(12), dp(8), dp(12), dp(8))
        rig.background = getDrawable(R.drawable.menu_item_selected)
        val rp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        rp.topMargin = dp(10)
        rig.layoutParams = rp
        rig.setOnClickListener {
            skeletonView.load(folder)
            rigBoneMode = false
            skeletonView.setBoneEditMode(false)
            applyRigMode()
            show(Pane.PET_RIG)
        }
        partList.addView(rig)

        val depth = label(getString(R.string.depth_entry), 12f, INK)
        depth.setPadding(dp(12), dp(8), dp(12), dp(8))
        depth.background = getDrawable(R.drawable.menu_item_selected)
        val dep = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        dep.topMargin = dp(8)
        depth.layoutParams = dep
        depth.setOnClickListener { openDepth(folder) }
        partList.addView(depth)

        partList.addView(label(getString(R.string.part_hint), 11f, MUTED, top = 12, bottom = 8))

        for (bone in boneNames(folder)) {
            partList.addView(partRow(folder, bone))
        }
    }

    private fun partRow(folder: CharacterFolder, bone: String): View {
        val has = folder.partFile(bone).isFile
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.menu_item_idle)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.bottomMargin = dp(6)
        row.layoutParams = params

        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(bone, 13f, INK))
        text.addView(label(boneLabel(bone) + " · " + if (has) "已导入" else "未导入", 10f, MUTED))
        val tp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        text.layoutParams = tp
        row.addView(text)

        if (has) {
            val del = label(getString(R.string.part_clear), 11f, MUTED)
            del.setPadding(dp(10), dp(6), dp(10), dp(6))
            del.setOnClickListener {
                store.clearPart(folder.id, bone)
                buildPartList(folder)
                if (summoned?.id == folder.id) summoned?.let { sandboxView.load(it) }
                if (skeletonView.visibility == View.VISIBLE) skeletonView.load(folder)
            }
            row.addView(del)
        }

        row.setOnClickListener {
            awaitingBone = bone
            pickImage.launch(arrayOf("image/*"))
        }
        return row
    }

    private fun onImagePicked(uri: Uri?) {
        val bone = awaitingBone
        val folder = opened
        awaitingBone = null
        if (uri == null || bone == null || folder == null) return

        val bitmap = decodeForAlign(uri)
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.align_failed), Toast.LENGTH_SHORT).show()
            return
        }
        alignView.load(folder, bone, bitmap)
        show(Pane.PART_ALIGN)
    }

    /**
     * Decode the picked file, downsampled to something a phone can hold. A modern camera
     * roll is full of 6000px images, and four of those decoded at full size is an OOM
     * before the alignment screen even appears.
     */
    private fun decodeForAlign(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > 2048) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun confirmImport() {
        val folder = opened
        val bone = alignView.targetBoneName()
        val baked = alignView.compose()
        if (folder == null || bone == null || baked == null) {
            Toast.makeText(this, getString(R.string.align_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val ok = store.savePart(folder.id, bone, baked)
        baked.recycle()
        Toast.makeText(
            this,
            if (ok) getString(R.string.align_saved) + " " + bone else getString(R.string.import_failed),
            Toast.LENGTH_SHORT,
        ).show()

        alignView.release()
        buildPartList(folder)
        if (summoned?.id == folder.id) summoned?.let { sandboxView.load(it) }
        show(Pane.PET_PARTS)
    }

    // ── 图层与深度 ──────────────────────────────────────────────────────────

    private fun openDepth(folder: CharacterFolder) {
        val parsed = try {
            CharacterSpec.parse(folder.specText())
        } catch (e: Exception) {
            null
        } ?: return

        depthBones = parsed.layers.sortedBy { it.z }.map { it.bone }.toMutableList()
        // A bone with artwork but no layer entry is never drawn at all. The shoulders were
        // exactly that for a while, and "everything shows except these two" is a hard
        // thing to guess from the code, so anything missing is appended at the front where
        // it is at least visible.
        for (b in parsed.bones.map { it.name }) {
            if (b !in depthBones && folder.partFile(b).isFile) depthBones.add(b)
        }
        depthRules = parsed.swaps.toMutableList()
        wizardStage = 0
        buildDepthPane()
        show(Pane.PET_DEPTH)
    }

    private fun buildDepthPane() {
        val folder = opened ?: return
        depthList.removeAllViews()

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = label(getString(R.string.pet_back), 13f, INK)
        back.setPadding(dp(12), dp(6), dp(12), dp(6))
        back.background = getDrawable(R.drawable.menu_item_idle)
        back.setOnClickListener {
            wizardStage = 0
            buildPartList(folder)
            show(Pane.PET_PARTS)
        }
        header.addView(back)
        header.addView(label("  " + getString(R.string.depth_title), 15f, INK))
        depthList.addView(header)

        if (wizardStage > 0) {
            buildWizard(folder)
            return
        }

        depthList.addView(label(getString(R.string.depth_hint), 11f, MUTED, top = 10, bottom = 10))

        // Front first: the top of the list is the part drawn last, so it covers the rest.
        val frontFirst = depthBones.reversed()
        for ((i, bone) in frontFirst.withIndex()) {
            val realIndex = depthBones.size - 1 - i
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.menu_item_idle)
                setPadding(dp(12), dp(6), dp(8), dp(6))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(4)
            row.layoutParams = lp

            val name = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            name.addView(label(bone, 13f, INK))
            name.addView(label(boneLabel(bone), 10f, MUTED))
            name.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(name)

            val up = label("▲", 13f, INK)
            up.setPadding(dp(10), dp(4), dp(10), dp(4))
            up.setOnClickListener {
                if (realIndex < depthBones.size - 1) {
                    val b = depthBones.removeAt(realIndex)
                    depthBones.add(realIndex + 1, b)
                    buildDepthPane()
                }
            }
            val down = label("▼", 13f, INK)
            down.setPadding(dp(10), dp(4), dp(10), dp(4))
            down.setOnClickListener {
                if (realIndex > 0) {
                    val b = depthBones.removeAt(realIndex)
                    depthBones.add(realIndex - 1, b)
                    buildDepthPane()
                }
            }
            row.addView(up)
            row.addView(down)
            depthList.addView(row)
        }

        depthList.addView(label(getString(R.string.depth_rules), 13f, INK, top = 18, bottom = 6))
        if (depthRules.isEmpty()) {
            depthList.addView(label(getString(R.string.depth_no_rules), 11f, MUTED, bottom = 6))
        }
        for ((index, rule) in depthRules.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.menu_item_idle)
                setPadding(dp(12), dp(8), dp(8), dp(8))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(4)
            row.layoutParams = lp

            val txt = label(describeRule(rule), 11f, INK)
            txt.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(txt)

            val del = label(getString(R.string.depth_remove), 11f, MUTED)
            del.setPadding(dp(10), dp(4), dp(10), dp(4))
            del.setOnClickListener {
                depthRules.removeAt(index)
                buildDepthPane()
            }
            row.addView(del)
            depthList.addView(row)
        }

        val add = label(getString(R.string.depth_add_rule), 12f, INK)
        add.setPadding(dp(12), dp(8), dp(12), dp(8))
        add.background = getDrawable(R.drawable.menu_item_selected)
        val alp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        alp.topMargin = dp(8)
        add.layoutParams = alp
        add.setOnClickListener {
            wizardStage = 1
            wizardPart = null
            wizardRef = null
            wizardAbove = true
            buildDepthPane()
        }
        depthList.addView(add)

        val save = label(getString(R.string.depth_save), 13f, INK)
        save.setPadding(dp(20), dp(10), dp(20), dp(10))
        save.background = getDrawable(R.drawable.menu_item_selected)
        val slp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        slp.topMargin = dp(16)
        save.layoutParams = slp
        save.setOnClickListener { saveDepth(folder) }
        depthList.addView(save)
    }

    private fun describeRule(rule: SwapRuleSpec): String {
        val when_ = if (rule.triggerType == "tipBelow") "低于" else "高过"
        val where = if (rule.toFront) "前面" else "后面"
        return rule.parts.joinToString("/") + " " + when_ + " " + rule.referenceBone +
            " 时 → 放到 " + rule.behind.joinToString("/") + " " + where
    }

    private fun buildWizard(folder: CharacterFolder) {
        val cancel = label(getString(R.string.depth_cancel), 12f, MUTED)
        cancel.setPadding(dp(12), dp(6), dp(12), dp(6))
        cancel.background = getDrawable(R.drawable.menu_item_idle)
        cancel.setOnClickListener {
            wizardStage = 0
            buildDepthPane()
        }
        depthList.addView(cancel)

        val bones = boneNames(folder)

        if (wizardStage == 1 || wizardStage == 2) {
            val title = if (wizardStage == 1) R.string.depth_pick_part else R.string.depth_pick_ref
            depthList.addView(label(getString(title), 13f, INK, top = 12, bottom = 8))
            for (bone in bones) {
                val row = label(bone + "  " + boneLabel(bone), 12f, INK)
                row.setPadding(dp(12), dp(10), dp(12), dp(10))
                row.background = getDrawable(R.drawable.menu_item_idle)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.bottomMargin = dp(4)
                row.layoutParams = lp
                row.setOnClickListener {
                    if (wizardStage == 1) {
                        wizardPart = bone
                        wizardStage = 2
                    } else {
                        wizardRef = bone
                        wizardStage = 3
                    }
                    buildDepthPane()
                }
                depthList.addView(row)
            }
            return
        }

        if (wizardStage == 3) {
            depthList.addView(label(getString(R.string.depth_pick_when), 13f, INK, top = 12, bottom = 8))
            for (above in listOf(true, false)) {
                val txt = getString(
                    if (above) R.string.depth_when_above else R.string.depth_when_below
                ) + "  (" + (wizardPart ?: "") + (if (above) " ↑ " else " ↓ ") + (wizardRef ?: "") + ")"
                val row = label(txt, 12f, INK)
                row.setPadding(dp(12), dp(10), dp(12), dp(10))
                row.background = getDrawable(R.drawable.menu_item_idle)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.bottomMargin = dp(4)
                row.layoutParams = lp
                row.setOnClickListener {
                    wizardAbove = above
                    wizardStage = 4
                    buildDepthPane()
                }
                depthList.addView(row)
            }
            return
        }

        depthList.addView(label(getString(R.string.depth_pick_where), 13f, INK, top = 12, bottom = 8))
        for (toFront in listOf(false, true)) {
            val txt = getString(
                if (toFront) R.string.depth_where_front else R.string.depth_where_behind
            ) + "  (" + (wizardRef ?: "") + ")"
            val row = label(txt, 12f, INK)
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            row.background = getDrawable(R.drawable.menu_item_idle)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(4)
            row.layoutParams = lp
            row.setOnClickListener {
                val part = wizardPart
                val ref = wizardRef
                if (part != null && ref != null) {
                    depthRules.add(
                        SwapRuleSpec(
                            parts = withDescendants(folder, part),
                            behind = listOf(ref),
                            toFront = toFront,
                            triggerType = if (wizardAbove) "tipAbove" else "tipBelow",
                            triggerBone = part,
                            referenceBone = ref,
                        )
                    )
                }
                wizardStage = 0
                buildDepthPane()
            }
            depthList.addView(row)
        }
    }

    /** A limb is the bone plus everything hanging off it: picking the upper arm means the arm. */
    private fun withDescendants(folder: CharacterFolder, bone: String): List<String> {
        val parsed = try {
            CharacterSpec.parse(folder.specText())
        } catch (e: Exception) {
            return listOf(bone)
        }
        val children = HashMap<String, MutableList<String>>()
        for (b in parsed.bones) {
            b.parentName?.let { children.getOrPut(it) { mutableListOf() }.add(b.name) }
        }
        val out = mutableListOf<String>()
        fun walk(name: String) {
            out.add(name)
            children[name]?.forEach { walk(it) }
        }
        walk(bone)
        return out
    }

    private fun saveDepth(folder: CharacterFolder) {
        val ok = store.saveDepth(folder.id, depthBones, depthRules)
        Toast.makeText(
            this,
            getString(if (ok) R.string.depth_saved else R.string.depth_save_failed),
            Toast.LENGTH_SHORT,
        ).show()
        if (!ok) return
        if (summoned?.id == folder.id) summoned?.let { sandboxView.load(it) }
        skeletonView.load(folder)
        wizardStage = 0
        buildDepthPane()
    }

    // ── 骨骼编辑器 ──────────────────────────────────────────────────────────

    /**
     * Each mode shows only what it can use: posing has no bones to edit, and saving the
     * bones while posing would only write back what is already there.
     */
    private fun applyRigMode() {
        rigMode.text = getString(
            if (rigBoneMode) R.string.rig_mode_bones else R.string.rig_mode_pose
        )
        val editing = if (rigBoneMode) View.VISIBLE else View.GONE
        rigAddBone.visibility = editing
        rigBoneList.visibility = editing
        rigSaveBones.visibility = editing
        rigSavePose.visibility = if (rigBoneMode) View.GONE else View.VISIBLE
    }

    private fun rigHint() {
        statusLine.text = getString(
            if (rigBoneMode) R.string.rig_hint_bones else R.string.rig_hint_pose
        )
    }

    private fun toggleRigMode() {
        rigBoneMode = !rigBoneMode
        skeletonView.setBoneEditMode(rigBoneMode)
        applyRigMode()
        rigHint()
    }

    private fun saveBones() {
        val folder = opened ?: return
        // Half a bone is not a bone: the first tap is only a marker until the second one
        // gives it a direction, and saving now would drop it without saying so.
        if (skeletonView.addingBone) {
            Toast.makeText(this, R.string.rig_bone_unfinished, Toast.LENGTH_SHORT).show()
            return
        }
        val bones = skeletonView.rigBones()
        // The editor refuses to build a rig it cannot bake, so this is the last net rather
        // than the first: if it fires, something got past the editor.
        val problem = RigEdit.problem(bones)
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        val ok = store.saveRig(folder.id, bones, skeletonView.rigLayers())
        Toast.makeText(
            this,
            getString(if (ok) R.string.rig_bones_saved else R.string.rig_save_failed),
            Toast.LENGTH_SHORT,
        ).show()
        if (!ok) return
        // The rig changed shape, so the parts and the physics have to be rebuilt from it.
        skeletonView.load(folder)
        rigBoneMode = false
        skeletonView.setBoneEditMode(false)
        applyRigMode()
        if (summoned?.id == folder.id) summoned?.let { sandboxView.load(it) }
    }

    // ── 加骨骼 / 改父级 / 删骨骼 ────────────────────────────────────────────

    /** A new bone: a name, a parent, then two taps on the canvas to draw it. */
    private fun askNewBone() {
        val bones = skeletonView.rigBones()
        if (bones.isEmpty()) return
        val input = EditText(this).apply {
            setText(RigEdit.freeName(bones))
            setSelection(text.length)
            hint = getString(R.string.rig_bone_name)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        pickBone(
            title = getString(R.string.rig_new_bone),
            bones = bones,
            hint = getString(R.string.rig_new_bone_hint),
            current = null,
            blocked = emptySet(),
            // A rig that already has a root cannot take a second one, so the option is not
            // offered rather than offered and then refused.
            allowRoot = bones.none { it.parentName == null },
            header = input,
        ) { parent ->
            val name = RigEdit.sanitise(input.text.toString())
            when {
                name.isEmpty() -> {
                    Toast.makeText(this, R.string.rig_name_needed, Toast.LENGTH_SHORT).show()
                    false
                }
                bones.any { it.name == name } -> {
                    Toast.makeText(this, R.string.rig_name_taken, Toast.LENGTH_SHORT).show()
                    false
                }
                parent == null && bones.any { it.parentName == null } -> {
                    Toast.makeText(this, R.string.rig_one_root, Toast.LENGTH_SHORT).show()
                    false
                }
                else -> {
                    skeletonView.startAddBone(name, parent)
                    true
                }
            }
        }
    }

    private fun showBoneList() {
        val folder = opened ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.rig_bone_list))
            .setView(box)
            .setNegativeButton(R.string.action_close, null)
            .setOnDismissListener { boneDialog = null }
            .create()
        boneDialog = dialog
        boneListBox = box
        fillBoneList(box, folder)
        dialog.show()
    }

    private fun refreshBoneList() {
        val folder = opened ?: return
        if (boneDialog?.isShowing == true) fillBoneList(boneListBox, folder)
    }

    private fun fillBoneList(box: LinearLayout, folder: CharacterFolder) {
        box.removeAllViews()
        val bones = skeletonView.rigBones()
        boneDialog?.setTitle(getString(R.string.rig_bone_list) + " (" + bones.size + ")")
        box.addView(label(getString(R.string.rig_bone_list_hint), 11f, MUTED, bottom = 8))

        val depth = RigEdit.depths(bones)
        for (b in bones) box.addView(boneRow(folder, box, b, depth[b.name] ?: 0))

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        footer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }

        val add = label(getString(R.string.rig_add_bone), 12f, INK)
        add.setPadding(dp(12), dp(8), dp(12), dp(8))
        add.background = getDrawable(R.drawable.menu_item_selected)
        add.setOnClickListener { askNewBone() }
        footer.addView(add)

        val clear = label(getString(R.string.rig_clear_button), 12f, MUTED)
        clear.setPadding(dp(12), dp(8), dp(12), dp(8))
        clear.background = getDrawable(R.drawable.menu_item_idle)
        clear.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
        clear.setOnClickListener { confirmKeepOnlyRoot() }
        footer.addView(clear)
        box.addView(footer)
    }

    private fun boneRow(
        folder: CharacterFolder,
        box: LinearLayout,
        bone: BoneSpec,
        depth: Int,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.menu_item_idle)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }

        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        )
        val zh = boneLabel(bone.name)
        // Indented by depth: a rig is a tree, and a flat list of nineteen names hides it.
        text.addView(
            label("    ".repeat(depth) + bone.name + if (zh.isEmpty()) "" else "  " + zh, 13f, INK)
        )
        text.addView(
            label(
                if (bone.parentName == null) getString(R.string.rig_bone_root)
                else getString(R.string.rig_bone_parent) + bone.parentName,
                10f, MUTED,
            )
        )
        row.addView(text)

        val reparent = label(getString(R.string.rig_change_parent), 11f, MUTED)
        reparent.setPadding(dp(8), dp(6), dp(8), dp(6))
        reparent.setOnClickListener { askReparent(bone.name) }
        row.addView(reparent)

        if (bone.parentName != null) {
            val remove = label(getString(R.string.action_delete), 11f, MUTED)
            remove.setPadding(dp(8), dp(6), dp(8), dp(6))
            remove.setOnClickListener { confirmDeleteBone(folder, bone.name) }
            row.addView(remove)
        }
        return row
    }

    private fun askReparent(name: String) {
        val bones = skeletonView.rigBones()
        val bone = bones.firstOrNull { it.name == name } ?: return
        pickBone(
            title = getString(R.string.rig_change_parent) + " · " + name,
            bones = bones,
            hint = getString(R.string.rig_change_parent_hint),
            current = bone.parentName,
            // Its own descendants are greyed out: hanging a bone off its own child is a
            // loop, and a loop cannot be baked at all.
            blocked = RigEdit.descendants(bones, name).toSet(),
            allowRoot = bones.none { it.parentName == null && it.name != name },
            header = null,
        ) { parent -> skeletonView.reparentBone(name, parent) }
    }

    private fun confirmDeleteBone(folder: CharacterFolder, name: String) {
        val bones = skeletonView.rigBones()
        val bone = bones.firstOrNull { it.name == name } ?: return
        val parent = bone.parentName
        if (parent == null) {
            Toast.makeText(this, R.string.rig_root_undeletable, Toast.LENGTH_SHORT).show()
            return
        }
        val kids = bones.count { it.parentName == name }
        val message = StringBuilder()
        if (kids > 0) {
            message.append(getString(R.string.rig_delete_kids, kids, parent))
            message.append("\n")
        }
        if (folder.partFile(name).isFile) message.append(getString(R.string.rig_delete_part))

        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_delete) + " · " + name)
        if (message.isNotEmpty()) builder.setMessage(message.toString())
        builder
            .setPositiveButton(R.string.depth_remove) { _, _ -> skeletonView.deleteBone(name) }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    private fun confirmKeepOnlyRoot() {
        val root = skeletonView.rootName() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.rig_clear)
            .setMessage(getString(R.string.rig_clear_confirm, root))
            .setPositiveButton(R.string.depth_remove) { _, _ -> skeletonView.keepOnlyRoot() }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    /**
     * A flat picker for "which bone hangs off which".
     *
     * The list is the rig itself, indented by depth, so the choice is made against the
     * shape it is part of. [onPick] answers whether the choice was taken: anything else
     * leaves the list open, so a name can be corrected without retyping it.
     */
    private fun pickBone(
        title: String,
        bones: List<BoneSpec>,
        hint: String,
        current: String?,
        blocked: Set<String>,
        allowRoot: Boolean,
        header: View?,
        onPick: (String?) -> Boolean,
    ) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header?.let { box.addView(it) }
        box.addView(label(hint, 11f, MUTED, top = if (header == null) 0 else 6, bottom = 6))

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(R.string.depth_cancel, null)
            .create()

        val depth = RigEdit.depths(bones)
        if (allowRoot) {
            box.addView(
                pickRow(null, getString(R.string.rig_bone_none), current, false, dialog, onPick)
            )
        }
        for (b in bones) {
            val zh = boneLabel(b.name)
            val text = "    ".repeat(depth[b.name] ?: 0) + b.name +
                if (zh.isEmpty()) "" else "  " + zh
            box.addView(pickRow(b.name, text, current, b.name in blocked, dialog, onPick))
        }
        dialog.show()
    }

    private fun pickRow(
        name: String?,
        text: String,
        current: String?,
        blocked: Boolean,
        dialog: AlertDialog,
        onPick: (String?) -> Boolean,
    ): View {
        val row = label(text, 13f, if (blocked) MUTED else INK)
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        row.background = getDrawable(
            if (name == current) R.drawable.menu_item_selected else R.drawable.menu_item_idle
        )
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) }
        if (blocked) {
            row.alpha = 0.35f
            row.setOnClickListener {
                Toast.makeText(this, R.string.rig_parent_loop, Toast.LENGTH_SHORT).show()
            }
        } else {
            row.setOnClickListener { if (onPick(name)) dialog.dismiss() }
        }
        return row
    }

    private fun askPoseName() {
        val folder = opened ?: return
        val input = EditText(this).apply {
            hint = getString(R.string.rig_pose_name)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rig_save_pose)
            .setView(input)
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "动作" }
                val ok = store.savePose(folder.id, name, skeletonView.currentAngles())
                Toast.makeText(
                    this,
                    getString(if (ok) R.string.rig_pose_saved else R.string.rig_save_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                if (ok && summoned?.id == folder.id) buildPetChooser()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun boneNames(folder: CharacterFolder): List<String> = try {
        CharacterSpec.parse(folder.specText()).bones.map { it.name }
    } catch (e: Exception) {
        emptyList()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun label(
        text: String,
        sizeSp: Float,
        colour: Int,
        top: Int = 0,
        bottom: Int = 0,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(colour)
        if (top > 0 || bottom > 0) {
            setPadding(0, dp(top), 0, dp(bottom))
        }
    }

    private fun boneLabel(bone: String): String = LABELS[bone] ?: ""

    private companion object {
        val INK = Color.parseColor("#FF171528")
        val MUTED = Color.parseColor("#A6171528")

        /** The stick figure in an action-list row: solid when it is the one being held. */
        val FIGURE_ON = Color.parseColor("#FF5B4BC4")
        val FIGURE_OFF = Color.parseColor("#806E56CF")

        val STIFFNESS_VALUES = floatArrayOf(0f, 0.35f, 0.7f, 1f)
        val STIFFNESS_LABELS = arrayOf("刚度 松垮", "刚度 半软", "刚度 偏硬", "刚度 硬挺")

        val LABELS = mapOf(
            "hip" to "胯", "spine" to "腰", "chest" to "胸", "neck" to "脖子", "head" to "头",
            "shoulder_L" to "左肩", "upperarm_L" to "左上臂", "forearm_L" to "左前臂",
            "hand_L" to "左手",
            "shoulder_R" to "右肩", "upperarm_R" to "右上臂", "forearm_R" to "右前臂",
            "hand_R" to "右手",
            "thigh_L" to "左大腿", "shin_L" to "左小腿", "foot_L" to "左脚",
            "thigh_R" to "右大腿", "shin_R" to "右小腿", "foot_R" to "右脚",
        )
    }
}