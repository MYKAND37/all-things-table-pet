package dev.atp.pet

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
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
import dev.atp.pet.engine.event.EventType
import dev.atp.pet.engine.logic.ActionKind
import dev.atp.pet.engine.logic.ActionSpec
import dev.atp.pet.engine.logic.CompareOp
import dev.atp.pet.engine.logic.ConditionSpec
import dev.atp.pet.engine.logic.LogicSpec
import dev.atp.pet.engine.logic.RuleSpec
import dev.atp.pet.engine.prop.PropKind
import dev.atp.pet.engine.prop.PropSpec
import dev.atp.pet.engine.skeleton.BoneSpec
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.RigEdit
import dev.atp.pet.engine.skeleton.SwapRuleSpec
import dev.atp.pet.engine.state.StatSpec
import dev.atp.pet.render.ParticleKind
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
        PLACEHOLDER, SANDBOX, PET_LIST, PET_PARTS, PET_RIG, PART_ALIGN, PET_DEPTH,
        PET_PROPS, PET_LOGIC
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
    private lateinit var propListScroll: View
    private lateinit var propList: LinearLayout
    private lateinit var logicScroll: View
    private lateinit var logicList: LinearLayout

    /** Props are shared by every character, and edited in memory until saved. */
    private var props: MutableList<PropSpec> = mutableListOf()
    private var awaitingProp: String? = null

    /** The summoned character's rules, as edited. Rebuilt on every change; saved on every change. */
    private var logicStats: MutableList<StatSpec> = mutableListOf()
    private var logicRules: MutableList<RuleSpec> = mutableListOf()
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
        propListScroll = findViewById(R.id.propListScroll)
        propList = findViewById(R.id.propList)
        logicScroll = findViewById(R.id.logicScroll)
        logicList = findViewById(R.id.logicList)
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
        props = store.loadProps().toMutableList()
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
        summoned?.let { reloadSandbox(it) }
    }

    /**
     * Rebuild the bench from disk.
     *
     * Every edit ends here — a part, a rig, a rule, a prop — because the bench only reads
     * and the files are the single source of truth. A second in-memory copy of the
     * character is a second thing that can disagree with itself.
     */
    private fun reloadSandbox(folder: CharacterFolder) {
        sandboxView.setPoseNames(store.loadPoses(folder.id).associate { it.name to it.angles })
        sandboxView.load(folder, store.loadLogic(folder.id), store.loadProps(), store.propsDir)
    }

    /** Reload the bench, but only if that is the character currently on it. */
    private fun reloadSummoned(folder: CharacterFolder) {
        if (summoned?.id == folder.id) reloadSandbox(folder)
    }

    private fun select(item: TextView) {
        menuItems.forEach { it.isSelected = false }
        item.isSelected = true
        contentTitle.text = item.text
        when (item.id) {
            R.id.menuSandbox -> {
                // Rebuilt on the way in, so the bench is never showing stale rules.
                summoned?.let { reloadSandbox(it) }
                show(Pane.SANDBOX)
            }
            R.id.menuPets -> show(Pane.PET_LIST)
            R.id.menuProps -> {
                openProps()
                show(Pane.PET_PROPS)
            }
            R.id.menuLogic -> {
                openLogic()
                show(Pane.PET_LOGIC)
            }
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
        propListScroll.visibility = if (pane == Pane.PET_PROPS) View.VISIBLE else View.GONE
        logicScroll.visibility = if (pane == Pane.PET_LOGIC) View.VISIBLE else View.GONE
        rigBar.visibility = if (pane == Pane.PET_RIG) View.VISIBLE else View.GONE
        if (pane != Pane.PET_RIG && rigBoneMode) {
            rigBoneMode = false
            skeletonView.setBoneEditMode(false)
            applyRigMode()
        }
        statusLine.visibility =
            if (pane == Pane.SANDBOX || pane == Pane.PET_RIG || pane == Pane.PART_ALIGN ||
                pane == Pane.PET_PROPS || pane == Pane.PET_LOGIC
            ) View.VISIBLE else View.GONE

        when (pane) {
            Pane.SANDBOX -> statusLine.text = "拖起来甩出去 · 双击复位 · 上面选桌宠"
            Pane.PET_LIST -> statusLine.text = ""
            Pane.PET_PARTS -> statusLine.text = ""
            Pane.PET_RIG -> rigHint()
            Pane.PART_ALIGN -> Unit
            Pane.PET_DEPTH -> statusLine.text = getString(R.string.depth_hint)
            Pane.PET_PROPS -> statusLine.text = getString(R.string.props_subtitle)
            Pane.PET_LOGIC -> statusLine.text = getString(R.string.logic_rules_hint)
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

        // Props are put on the table from here and defined in 道具管理: the bench is where
        // you find out that a hammer is too small, and that is not the moment to be
        // filling in a form.
        val propChip = label(getString(R.string.sandbox_props) + " (" + props.size + ")", 12f, INK)
        propChip.background = getDrawable(R.drawable.menu_item_selected)
        propChip.setPadding(dp(12), dp(6), dp(12), dp(6))
        propChip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(10) }
        propChip.setOnClickListener { showPropPicker() }
        petChooser.addView(propChip)

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
                reloadSandbox(folder)
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
                reloadSummoned(folder)
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
        // Prop art is not attached to a bone, so it needs no alignment step: the picture
        // is simply the thing, and where its pixels are is where it is drawn.
        if (awaitingProp != null) {
            onPropPicked(uri)
            return
        }
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
        reloadSummoned(folder)
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
        reloadSummoned(folder)
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
        val ok = store.saveRig(folder.id, bones, skeletonView.rigLayers(), skeletonView.renames())
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
        reloadSummoned(folder)
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

        val rename = label(getString(R.string.action_rename), 11f, MUTED)
        rename.setPadding(dp(7), dp(6), dp(7), dp(6))
        rename.setOnClickListener { askRenameBone(bone.name) }
        row.addView(rename)

        val reparent = label(getString(R.string.rig_change_parent), 11f, MUTED)
        reparent.setPadding(dp(7), dp(6), dp(7), dp(6))
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

    /**
     * A bone's name is also its artwork's file name and the name every rule mentions, so
     * this goes through the rig editor rather than being a text edit: the file and the
     * references are renamed with it when the rig is saved.
     */
    private fun askRenameBone(name: String) {
        val input = EditText(this).apply {
            setText(name)
            setSelection(text.length)
            hint = getString(R.string.rig_bone_name)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_rename) + " · " + name)
            .setView(input)
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val next = RigEdit.sanitise(input.text.toString())
                if (next.isEmpty() || next == name) return@setPositiveButton
                if (skeletonView.renameBone(name, next)) refreshBoneList()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
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

    // ── 道具管理 ────────────────────────────────────────────────────────────

    private fun openProps() {
        props = store.loadProps().toMutableList()
        buildPropList()
    }

    private fun buildPropList() {
        propList.removeAllViews()
        propList.addView(label(getString(R.string.props_subtitle), 11f, MUTED, bottom = 10))
        if (props.isEmpty()) {
            propList.addView(label(getString(R.string.prop_none), 12f, MUTED, bottom = 12))
        }
        for (spec in props.toList()) propList.addView(propRow(spec))

        val add = label(getString(R.string.prop_new), 13f, INK)
        add.setPadding(dp(14), dp(11), dp(14), dp(11))
        add.background = getDrawable(R.drawable.menu_item_selected)
        add.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
        add.setOnClickListener { askEditProp(null) }
        propList.addView(add)
    }

    private fun propRow(spec: PropSpec): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.menu_item_idle)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }

        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        )
        text.addView(label(spec.name + "   " + spec.kindOf().label, 14f, INK))
        val art = if (store.propArtFile(spec.id).isFile) "有图" else "没图"
        text.addView(
            label(
                "半径 " + spec.radius.toInt() + " · 力度 " + "%.2f".format(spec.force) + " · " + art,
                10f, MUTED,
            )
        )
        card.addView(text)
        card.setOnClickListener { askEditProp(spec) }

        val picture = label(getString(R.string.prop_change_art), 11f, MUTED)
        picture.setPadding(dp(8), dp(6), dp(8), dp(6))
        picture.setOnClickListener {
            awaitingProp = spec.id
            pickImage.launch(arrayOf("image/*"))
        }
        card.addView(picture)

        val remove = label(getString(R.string.action_delete), 11f, MUTED)
        remove.setPadding(dp(8), dp(6), dp(8), dp(6))
        remove.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.action_delete) + " · " + spec.name)
                .setMessage(getString(R.string.prop_delete_confirm, spec.name))
                .setPositiveButton(R.string.depth_remove) { _, _ ->
                    store.deleteProp(spec.id)
                    openProps()
                }
                .setNegativeButton(R.string.depth_cancel, null)
                .show()
        }
        card.addView(remove)
        return card
    }

    /** One dialog for the whole prop: a name, how it is used, and how big and how hard. */
    private fun askEditProp(existing: PropSpec?) {
        var kind = existing?.kind ?: PropKind.THROW.id

        val nameInput = EditText(this).apply {
            setText(existing?.name ?: "新道具")
            setSelection(text.length)
            hint = getString(R.string.prop_name)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val (radiusRow, radiusOf) = stepperRow(
            getString(R.string.prop_radius), existing?.radius ?: 60f, 10f, 10f, 400f,
        ) { it.toInt().toString() }
        val (forceRow, forceOf) = stepperRow(
            getString(R.string.prop_force), existing?.force ?: 1f, 0.25f, 0.25f, 5f,
        ) { "%.2f".format(it) }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        box.addView(nameInput)
        box.addView(label(getString(R.string.prop_kind), 11f, MUTED, top = 10, bottom = 6))

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipViews = mutableListOf<TextView>()
        val hintView = label(PropKind.of(kind).hint, 10f, MUTED, top = 6, bottom = 4)
        for (option in PropKind.values()) {
            val chip = label(option.label, 11f, INK)
            chip.setPadding(dp(9), dp(7), dp(9), dp(7))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                kind = option.id
                hintView.text = option.hint
                paintChips(chipViews, PropKind.values().map { it.id }, { kind })
            }
            chipViews.add(chip)
            chips.addView(chip)
        }
        box.addView(chips)
        box.addView(hintView)
        box.addView(radiusRow)
        box.addView(forceRow)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.prop_new else R.string.prop_name)
            .setView(box)
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { "道具" }
                val id = existing?.id ?: freePropId(name)
                props.removeAll { it.id == id }
                props.add(
                    PropSpec(
                        id = id,
                        name = name,
                        kind = kind,
                        radius = radiusOf(),
                        force = forceOf(),
                        // Kept from the old entry: it is what tells a fired bullet from
                        // the launcher it came out of.
                        gravityScale = existing?.gravityScale ?: 1f,
                        transient = existing?.transient ?: false,
                    )
                )
                store.saveProps(props)
                buildPropList()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
        paintChips(chipViews, PropKind.values().map { it.id }, { kind })
    }

    /** The first unused id, so two props can share a display name without sharing a file. */
    private fun freePropId(name: String): String {
        val base = RigEdit.sanitise(name).ifEmpty { "prop" }
        val taken = props.map { it.id }.toSet()
        if (base !in taken) return base
        var n = 2
        while ((base + "_" + n) in taken) n++
        return base + "_" + n
    }

    private fun onPropPicked(uri: Uri?) {
        val id = awaitingProp ?: return
        awaitingProp = null
        if (uri == null) return
        val bitmap = decodeForAlign(uri)
        if (bitmap == null) {
            Toast.makeText(this, getString(R.string.prop_import_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val ok = store.savePropArt(id, bitmap)
        bitmap.recycle()
        Toast.makeText(
            this,
            getString(if (ok) R.string.prop_saved else R.string.rig_save_failed),
            Toast.LENGTH_SHORT,
        ).show()
        buildPropList()
    }

    /** Put one on the table. Props are used in the bench and defined here. */
    private fun showPropPicker() {
        if (props.isEmpty()) {
            Toast.makeText(this, R.string.sandbox_props_empty, Toast.LENGTH_SHORT).show()
            return
        }
        pickList(
            title = getString(R.string.sandbox_props),
            options = props.map { it.id to (it.name + "   " + it.kindOf().label) },
            hint = "",
            current = null,
        ) { id ->
            sandboxView.spawnProp(id)
            true
        }
    }

    // ── 逻辑管理 ────────────────────────────────────────────────────────────

    // ── 逻辑管理：数值 ──────────────────────────────────────────────────────

    /**
     * Pull the summoned character's rules into the editor.
     *
     * The pane edits a copy and writes it back on every change, so there is no save button
     * to forget — the file and the screen cannot drift apart.
     */
    private fun openLogic() {
        val folder = summoned
        if (folder == null) {
            logicStats = mutableListOf()
            logicRules = mutableListOf()
        } else {
            val spec = store.loadLogic(folder.id)
            logicStats = spec.stats.toMutableList()
            logicRules = spec.rules.toMutableList()
        }
        buildLogicPane()
    }

    /**
     * Written on every change, so there is no save button to forget.
     *
     * The bench deliberately does NOT rebuild here: editing a rule should not throw away
     * the pet that is mid-fall with three props on the table. It picks the new rules up on
     * the way back into the test bench, which is the next thing the user is going to do.
     */
    private fun saveLogic() {
        val folder = summoned ?: return
        store.saveLogic(folder.id, LogicSpec(logicStats.toList(), logicRules.toList()))
    }

    private fun buildLogicPane() {
        logicList.removeAllViews()
        logicList.addView(
            label((summoned?.id ?: "?") + " 的规则与数值", 13f, INK, bottom = 2)
        )
        logicList.addView(label(getString(R.string.logic_saved), 10f, MUTED, bottom = 10))

        logicList.addView(label(getString(R.string.logic_stats), 15f, INK, bottom = 4))
        logicList.addView(label(getString(R.string.logic_stats_hint), 11f, MUTED, bottom = 8))
        for (stat in logicStats.toList()) logicList.addView(statRow(stat))
        val addStat = label(getString(R.string.logic_add_stat), 12f, INK)
        addStat.setPadding(dp(12), dp(9), dp(12), dp(9))
        addStat.background = getDrawable(R.drawable.menu_item_selected)
        addStat.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) }
        addStat.setOnClickListener { askEditStat(null) }
        logicList.addView(addStat)

        logicList.addView(label(getString(R.string.logic_rules), 15f, INK, top = 16, bottom = 4))
        logicList.addView(label(getString(R.string.logic_rules_hint), 11f, MUTED, bottom = 10))
        if (logicRules.isEmpty()) {
            logicList.addView(label(getString(R.string.logic_none), 12f, MUTED, bottom = 12))
        }
        for ((index, rule) in logicRules.withIndex()) logicList.addView(ruleCard(index, rule))

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val addRule = label(getString(R.string.logic_add_rule), 13f, INK)
        addRule.setPadding(dp(14), dp(11), dp(14), dp(11))
        addRule.background = getDrawable(R.drawable.menu_item_selected)
        addRule.setOnClickListener {
            logicRules.add(
                RuleSpec(
                    on = EventType.CLICK.id,
                    part = "",
                    conditions = emptyList(),
                    actions = listOf(ActionSpec("say", text = "……")),
                    cooldown = 0.5f,
                    once = false,
                )
            )
            saveLogic()
            buildLogicPane()
        }
        footer.addView(addRule)

        val reset = label(getString(R.string.logic_reset), 12f, MUTED)
        reset.setPadding(dp(14), dp(11), dp(14), dp(11))
        reset.background = getDrawable(R.drawable.menu_item_idle)
        reset.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
        reset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.logic_reset)
                .setMessage(R.string.logic_reset_confirm)
                .setPositiveButton(R.string.depth_remove) { _, _ ->
                    val folder = summoned ?: return@setPositiveButton
                    store.forgetLogic(folder.id)
                    openLogic()
                }
                .setNegativeButton(R.string.depth_cancel, null)
                .show()
        }
        footer.addView(reset)
        footer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
        logicList.addView(footer)
    }

    private fun statRow(stat: StatSpec): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.menu_item_idle)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            isClickable = true
            isFocusable = true
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(5) }

        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        )
        text.addView(label(stat.name, 14f, INK))
        text.addView(
            label(
                "代号 " + stat.id + " · 初值 " + stat.initial.toInt() +
                    " · 范围 " + stat.min.toInt() + "~" + stat.max.toInt(),
                10f, MUTED,
            )
        )
        row.addView(text)
        row.setOnClickListener { askEditStat(stat) }

        val remove = label(getString(R.string.action_delete), 11f, MUTED)
        remove.setPadding(dp(8), dp(6), dp(8), dp(6))
        remove.setOnClickListener {
            logicStats.removeAll { it.id == stat.id }
            saveLogic()
            buildLogicPane()
        }
        row.addView(remove)
        return row
    }

    private fun askEditStat(existing: StatSpec?) {
        val idInput = EditText(this).apply {
            setText(existing?.id ?: nextStatId())
            hint = getString(R.string.logic_stat_id)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val nameInput = EditText(this).apply {
            setText(existing?.name ?: "")
            hint = getString(R.string.logic_stat_name)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val (valueRow, valueOf) = stepperRow(
            getString(R.string.logic_stat_value), existing?.initial ?: 100f, 10f, -999f, 999f,
        ) { it.toInt().toString() }
        val (maxRow, maxOf) = stepperRow(
            getString(R.string.logic_stat_max), existing?.max ?: 100f, 10f, 1f, 9999f,
        ) { it.toInt().toString() }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        box.addView(idInput)
        box.addView(nameInput)
        box.addView(valueRow)
        box.addView(maxRow)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.logic_add_stat else R.string.logic_stats)
            .setView(box)
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val id = RigEdit.sanitise(idInput.text.toString()).ifEmpty { nextStatId() }
                val name = nameInput.text.toString().trim().ifEmpty { id }
                logicStats.removeAll { it.id == id || (existing != null && it.id == existing.id) }
                logicStats.add(StatSpec(id, name, valueOf(), 0f, maxOf()))
                saveLogic()
                buildLogicPane()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    private fun nextStatId(): String {
        val taken = logicStats.map { it.id }.toSet()
        var n = 1
        while (("S" + n) in taken) n++
        return "S" + n
    }

    // ── 逻辑管理：规则 ──────────────────────────────────────────────────────

    /**
     * One rule, drawn as the chain it is: 当 → 如果 → 就.
     *
     * It is a diagram rather than a form on purpose. The thing that is hard about a rule is
     * not any one field, it is seeing the order the parts fire in — and a row of dropdowns
     * hides exactly that.
     */
    private fun ruleCard(index: Int, rule: RuleSpec): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.menu_item_idle)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }

        card.addView(
            node(0, getString(R.string.logic_when) + EventType.of(rule.on).label) {
                askRuleEvent(index)
            }
        )
        if (rule.conditions.isEmpty()) {
            card.addView(link())
            card.addView(node(1, getString(R.string.logic_always)) { askCondition(index, -1) })
        } else {
            for ((ci, cond) in rule.conditions.withIndex()) {
                card.addView(link())
                card.addView(node(1, "如果：" + conditionText(cond)) { askCondition(index, ci) })
            }
        }
        for ((ai, act) in rule.actions.withIndex()) {
            card.addView(link())
            card.addView(node(2, "就：" + actionText(act)) { askAction(index, ai) })
        }

        // Two rows: six chips do not fit across a phone, and a LinearLayout does not wrap.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(small(getString(R.string.logic_add_if)) { askCondition(index, -1) })
        controls.addView(small(getString(R.string.logic_add_then)) { askAction(index, -1) })
        controls.addView(
            small(if (rule.part.isEmpty()) "部位 全身" else "部位 " + partText(rule.part)) {
                askRulePart(index)
            }
        )
        controls.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        card.addView(controls)

        val switches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        switches.addView(small(getString(R.string.logic_cooldown, trim(rule.cooldown))) { askCooldown(index) })
        switches.addView(
            small((if (rule.once) "✓ " else "") + getString(R.string.logic_once)) {
                logicRules[index] = rule.copy(once = !rule.once)
                saveLogic()
                buildLogicPane()
            }
        )
        switches.addView(
            small(getString(R.string.logic_delete_rule)) {
                logicRules.removeAt(index)
                saveLogic()
                buildLogicPane()
            }
        )
        switches.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(5) }
        card.addView(switches)
        return card
    }

    /** One node of the flow. Role picks the colour: 0 = 当, 1 = 如果, 2 = 就. */
    private fun node(role: Int, text: String, onClick: () -> Unit): View {
        val view = label(text, 12f, INK)
        view.setPadding(dp(12), dp(10), dp(12), dp(10))
        view.background = getDrawable(
            when (role) {
                0 -> R.drawable.node_when
                1 -> R.drawable.node_if
                else -> R.drawable.node_then
            }
        )
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        view.setOnClickListener { onClick() }
        return view
    }

    /** The line between two nodes. It exists so the chain reads as a chain. */
    private fun link(): View {
        val line = View(this)
        line.setBackgroundColor(0x556C4CE0)
        line.layoutParams = LinearLayout.LayoutParams(dp(2), dp(14)).apply {
            marginStart = dp(18)
            gravity = Gravity.START
        }
        return line
    }

    private fun small(text: String, onClick: () -> Unit): TextView {
        val view = label(text, 10f, MUTED)
        view.setPadding(dp(8), dp(7), dp(8), dp(7))
        view.background = getDrawable(R.drawable.menu_item_selected)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(5) }
        view.setOnClickListener { onClick() }
        return view
    }

    private fun conditionText(c: ConditionSpec): String =
        statName(c.stat) + " " + opSymbol(c.op) + " " + trim(c.value)

    private fun opSymbol(op: String): String = when (op) {
        ">=" -> "≥"
        "<=" -> "≤"
        else -> op
    }

    private fun trim(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v)

    private fun statName(id: String): String =
        logicStats.firstOrNull { it.id == id }?.name ?: id

    private fun propName(id: String): String =
        props.firstOrNull { it.id == id }?.name ?: id

    private fun partText(part: String): String = boneLabel(part).ifEmpty { part }

    private fun actionText(a: ActionSpec): String = when (a.kind) {
        "say" -> "说「" + a.text + "」"
        "add" -> statName(a.stat) + " " + (if (a.value >= 0f) "+" else "") + trim(a.value)
        "set" -> statName(a.stat) + " 设为 " + trim(a.value)
        "pose" -> "摆动作 " + a.text
        "clearPose" -> "松开动作"
        "spawn" -> "生成道具 " + propName(a.prop)
        "burst" -> "喷" + ParticleKind.of(a.text).label
        "impulse" -> "推一下 " + (if (a.bone.isEmpty()) "被打到的部位" else partText(a.bone))
        "break" -> "打坏 " + (if (a.bone.isEmpty()) "被打到的部位" else partText(a.bone))
        "wait" -> "等 " + trim(a.value) + " 秒"
        else -> a.kind
    }

    private fun putRule(index: Int, rule: RuleSpec) {
        if (index !in logicRules.indices) return
        logicRules[index] = rule
        saveLogic()
        buildLogicPane()
    }

    private fun askRuleEvent(index: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        pickList(
            title = getString(R.string.logic_pick_event),
            options = EventType.values().map { it.id to it.label },
            hint = "",
            current = rule.on,
        ) { id ->
            putRule(index, rule.copy(on = id))
            true
        }
    }

    private fun askRulePart(index: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val bones = summoned?.let { boneNames(it) } ?: emptyList()
        val options = mutableListOf("" to getString(R.string.logic_pick_any_part))
        for (b in bones) {
            val zh = boneLabel(b)
            options.add(b to (if (zh.isEmpty()) b else zh + "   " + b))
        }
        pickList(
            title = getString(R.string.logic_pick_part),
            options = options,
            hint = getString(R.string.logic_pick_part_hint),
            current = rule.part,
        ) { id ->
            putRule(index, rule.copy(part = id))
            true
        }
    }

    private fun askCooldown(index: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        askNumber(getString(R.string.logic_cooldown_title), rule.cooldown, 0f, 60f) { v ->
            putRule(index, rule.copy(cooldown = v))
        }
    }

    /** [condIndex] of -1 means "add another", anything else replaces that one. */
    private fun askCondition(index: Int, condIndex: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val existing = rule.conditions.getOrNull(condIndex)
        var stat = existing?.stat ?: logicStats.firstOrNull()?.id ?: ""
        var op = existing?.op ?: ">="

        val statChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val statViews = mutableListOf<TextView>()
        for (s in logicStats) {
            val chip = label(s.name, 12f, INK)
            chip.setPadding(dp(10), dp(8), dp(10), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                stat = s.id
                paintChips(statViews, logicStats.map { it.id }, { stat })
            }
            statViews.add(chip)
            statChips.addView(chip)
        }

        val opChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val opViews = mutableListOf<TextView>()
        for (o in CompareOp.values()) {
            val chip = label(opSymbol(o.id), 12f, INK)
            chip.setPadding(dp(11), dp(8), dp(11), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                op = o.id
                paintChips(opViews, CompareOp.values().map { it.id }, { op })
            }
            opViews.add(chip)
            opChips.addView(chip)
        }

        val (valueRow, valueOf) = stepperRow(
            getString(R.string.logic_pick_value), existing?.value ?: 50f, 5f, 0f, 999f,
        ) { trim(it) }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        box.addView(label(getString(R.string.logic_pick_stat), 11f, MUTED, bottom = 6))
        box.addView(statChips)
        box.addView(label(getString(R.string.logic_pick_op), 11f, MUTED, top = 10, bottom = 6))
        box.addView(opChips)
        box.addView(valueRow)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.logic_pick_stat)
            .setView(box)
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val conditions = rule.conditions.toMutableList()
                val spec = ConditionSpec(kind = "stat", stat = stat, op = op, value = valueOf())
                if (condIndex in conditions.indices) conditions[condIndex] = spec else conditions.add(spec)
                putRule(index, rule.copy(conditions = conditions))
            }
            .setNegativeButton(R.string.depth_cancel, null)
        if (condIndex >= 0) {
            builder.setNeutralButton(R.string.depth_remove) { _, _ ->
                val conditions = rule.conditions.toMutableList()
                if (condIndex in conditions.indices) conditions.removeAt(condIndex)
                putRule(index, rule.copy(conditions = conditions))
            }
        }
        builder.show()
        paintChips(statViews, logicStats.map { it.id }, { stat })
        paintChips(opViews, CompareOp.values().map { it.id }, { op })
    }

    private fun askAction(index: Int, actionIndex: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val existing = rule.actions.getOrNull(actionIndex)
        pickList(
            title = getString(R.string.logic_pick_action),
            options = ActionKind.values().map { it.id to it.label },
            hint = "",
            current = existing?.kind,
            onDelete = if (actionIndex >= 0) {
                {
                    val actions = rule.actions.toMutableList()
                    if (actionIndex in actions.indices) actions.removeAt(actionIndex)
                    putRule(index, rule.copy(actions = actions))
                }
            } else {
                null
            },
        ) { id ->
            val kind = ActionKind.of(id)
            when (kind.needs) {
                "text" -> askText(getString(R.string.logic_pick_text), existing?.text ?: "") { text ->
                    putAction(index, actionIndex, ActionSpec(kind.id, text = text))
                }
                "stat" -> pickStat(getString(R.string.logic_pick_stat)) { stat ->
                    askSigned(getString(R.string.logic_pick_value), existing?.value ?: 10f) { v ->
                        putAction(index, actionIndex, ActionSpec(kind.id, stat = stat, value = v))
                    }
                }
                "statValue" -> pickStat(getString(R.string.logic_pick_stat)) { stat ->
                    askSigned(getString(R.string.logic_pick_value), existing?.value ?: 100f) { v ->
                        putAction(index, actionIndex, ActionSpec(kind.id, stat = stat, value = v))
                    }
                }
                "pose" -> pickList(
                    getString(R.string.logic_pick_pose),
                    summoned?.let { store.loadPoses(it.id).map { p -> p.name to p.name } } ?: emptyList(),
                    getString(R.string.logic_no_poses),
                    existing?.text,
                ) { name ->
                    putAction(index, actionIndex, ActionSpec(kind.id, text = name))
                    true
                }
                "prop" -> pickList(
                    getString(R.string.logic_pick_prop),
                    props.map { it.id to it.name },
                    getString(R.string.sandbox_props_empty),
                    existing?.prop,
                ) { propId ->
                    putAction(index, actionIndex, ActionSpec(kind.id, prop = propId))
                    true
                }
                "burst" -> pickList(
                    getString(R.string.logic_pick_burst),
                    ParticleKind.values().map { it.id to it.label },
                    "",
                    existing?.text,
                ) { burstId ->
                    putAction(index, actionIndex, ActionSpec(kind.id, text = burstId, value = existing?.value ?: 10f))
                    true
                }
                "bone" -> pickBoneName(getString(R.string.logic_pick_bone), existing?.bone) { bone ->
                    putAction(index, actionIndex, ActionSpec(kind.id, bone = bone))
                }
                "boneValue" -> pickBoneName(getString(R.string.logic_pick_bone), existing?.bone) { bone ->
                    askSigned(getString(R.string.logic_pick_value), existing?.value ?: 400f) { v ->
                        putAction(index, actionIndex, ActionSpec(kind.id, bone = bone, value = v))
                    }
                }
                "seconds" -> askNumber(getString(R.string.logic_pick_value), existing?.value ?: 0.5f, 0f, 30f) { v ->
                    putAction(index, actionIndex, ActionSpec(kind.id, value = v))
                }
                else -> putAction(index, actionIndex, ActionSpec(kind.id))
            }
            true
        }
    }

    private fun putAction(index: Int, actionIndex: Int, action: ActionSpec) {
        val rule = logicRules.getOrNull(index) ?: return
        val actions = rule.actions.toMutableList()
        if (actionIndex >= 0 && actionIndex < actions.size) {
            actions[actionIndex] = action
        } else {
            actions.add(action)
        }
        putRule(index, rule.copy(actions = actions))
    }

    private fun pickStat(title: String, onPick: (String) -> Unit) {
        pickList(
            title = title,
            options = logicStats.map { it.id to (it.name + "   " + it.id) },
            hint = "",
            current = null,
        ) { id ->
            onPick(id)
            true
        }
    }

    private fun pickBoneName(title: String, current: String?, onPick: (String) -> Unit) {
        val bones = summoned?.let { boneNames(it) } ?: emptyList()
        val options = mutableListOf("" to getString(R.string.logic_pick_any_part))
        for (b in bones) {
            val zh = boneLabel(b)
            options.add(b to (if (zh.isEmpty()) b else zh + "   " + b))
        }
        pickList(title, options, "", current) { id ->
            onPick(id)
            true
        }
    }

    private fun askText(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.depth_save) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    private fun askSigned(title: String, initial: Float, onOk: (Float) -> Unit) {
        val input = EditText(this).apply {
            setText(trim(initial))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.depth_save) { _, _ ->
                onOk(input.text.toString().trim().toFloatOrNull() ?: initial)
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    private fun askNumber(title: String, initial: Float, min: Float, max: Float, onOk: (Float) -> Unit) {
        val input = EditText(this).apply {
            setText(trim(initial))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.depth_save) { _, _ ->
                onOk((input.text.toString().trim().toFloatOrNull() ?: initial).coerceIn(min, max))
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * A flat picker: pick one row and the dialog closes.
     *
     * [onPick] answers whether the choice was taken. Returning false leaves the list open,
     * so a name that collided can be corrected without losing the rest of the form.
     */
    private fun pickList(
        title: String,
        options: List<Pair<String, String>>,
        hint: String,
        current: String?,
        onDelete: (() -> Unit)? = null,
        onPick: (String) -> Boolean,
    ) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (hint.isNotEmpty()) box.addView(label(hint, 11f, MUTED, bottom = 6))

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(R.string.depth_cancel, null)
            .create()
        if (onDelete != null) {
            dialog.setButton(
                DialogInterface.BUTTON_NEUTRAL,
                getString(R.string.depth_remove),
                DialogInterface.OnClickListener { _, _ ->
                    dialog.dismiss()
                    onDelete()
                },
            )
        }

        for ((id, text) in options) {
            val row = label(text, 13f, INK)
            row.setPadding(dp(12), dp(11), dp(12), dp(11))
            row.background = getDrawable(
                if (id == current) R.drawable.menu_item_selected else R.drawable.menu_item_idle
            )
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
            row.setOnClickListener { if (onPick(id)) dialog.dismiss() }
            box.addView(row)
        }
        dialog.show()
    }

    /**
     * A [－ value ＋] row.
     *
     * The value is held in the closure rather than read back off the label, so what gets
     * saved is what the row is holding and not what the formatting happened to show.
     */
    private fun stepperRow(
        title: String,
        initial: Float,
        step: Float,
        min: Float,
        max: Float,
        format: (Float) -> String,
    ): Pair<View, () -> Float> {
        var current = initial
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }

        val name = label(title, 12f, INK)
        name.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        )
        row.addView(name)

        val shown = label(format(current), 12f, INK)
        shown.setPadding(dp(10), dp(6), dp(10), dp(6))

        val minus = label("－", 14f, INK)
        minus.setPadding(dp(13), dp(6), dp(13), dp(6))
        minus.background = getDrawable(R.drawable.menu_item_idle)
        minus.setOnClickListener {
            current = (current - step).coerceIn(min, max)
            shown.text = format(current)
        }
        row.addView(minus)
        row.addView(shown)

        val plus = label("＋", 14f, INK)
        plus.setPadding(dp(13), dp(6), dp(13), dp(6))
        plus.background = getDrawable(R.drawable.menu_item_idle)
        plus.setOnClickListener {
            current = (current + step).coerceIn(min, max)
            shown.text = format(current)
        }
        row.addView(plus)
        return row to { current }
    }

    /** Repaint a row of chips against whichever one is currently chosen. */
    private fun paintChips(chips: List<TextView>, ids: List<String>, current: () -> String) {
        val now = current()
        for ((i, chip) in chips.withIndex()) {
            val on = i < ids.size && ids[i] == now
            chip.background = getDrawable(
                if (on) R.drawable.menu_item_selected else R.drawable.menu_item_idle
            )
            chip.setTextColor(if (on) INK else MUTED)
        }
    }

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