package dev.atp.pet

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.core.content.FileProvider
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.data.Settings
import dev.atp.pet.data.SettingsStore
import dev.atp.pet.data.CharacterStore
import dev.atp.pet.engine.event.EventType
import dev.atp.pet.engine.fluid.LiquidSpec
import dev.atp.pet.engine.logic.ActionKind
import dev.atp.pet.engine.logic.ActionSpec
import dev.atp.pet.engine.logic.CompareOp
import dev.atp.pet.engine.logic.ConditionSpec
import dev.atp.pet.engine.logic.Joins
import dev.atp.pet.engine.logic.LogicSpec
import dev.atp.pet.engine.logic.RuleSpec
import dev.atp.pet.engine.logic.Shapes
import dev.atp.pet.engine.logic.Subjects
import dev.atp.pet.engine.prop.PropKind
import dev.atp.pet.engine.prop.PropSpec
import dev.atp.pet.engine.skeleton.BoneSpec
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.LayerSpec
import dev.atp.pet.engine.skeleton.NodeSpec
import dev.atp.pet.engine.skeleton.RigEdit
import dev.atp.pet.engine.skeleton.SwapRuleSpec
import dev.atp.pet.engine.logic.StateSpec
import dev.atp.pet.engine.state.StatSpec
import dev.atp.pet.engine.particle.ParticleKinds
import dev.atp.pet.engine.particle.ParticleSpec
import dev.atp.pet.ui.PaintBoardView
import dev.atp.pet.ui.PartAlignView
import dev.atp.pet.ui.LogicGraphView
import dev.atp.pet.ui.PhysicsSandboxView
import dev.atp.pet.ui.PosePreview
import dev.atp.pet.ui.SkeletonView
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt


/**
 * 随机组那个选择器里「新建一组」那一项的代号。
 *
 * A sentinel rather than a null: an empty id already means "leave the group", and a picker
 * whose two special rows are null and null is a picker nobody can read.
 */

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
        PLACEHOLDER, SANDBOX, PET_LIST, PET_PARTS, PET_PART_FILES, PET_RIG, PART_ALIGN, PET_DEPTH,
        PET_PROPS, PET_LOGIC, LIQUIDS, PARTICLES, SETTINGS
    }

    /**
     * The four folders 逻辑管理 is divided into, in the order they are shown.
     *
     * Everything that can hold rules is one of these four, and until this existed they were all
     * one flat row of chips: the character, every prop, every liquid, and every bone of the rig
     * -- twenty-odd of them, in no order that said which was which. A row like that is not a
     * list, it is a heap, and the one thing it cannot show is the thing the screen is about:
     * 让手流汗 is a rule about the hand, and 火花 落地 is a rule about sparks.
     */
    private enum class LogicFolder { CHARACTER, PROPS, LIQUIDS, PARTICLES }

    private var logicFolder = LogicFolder.CHARACTER

    private lateinit var store: CharacterStore
    private var characters: List<CharacterFolder> = emptyList()
    private var summoned: CharacterFolder? = null
    private var opened: CharacterFolder? = null
    private var awaitingBone: String? = null

    /** Set while importing a variant: the bone whose geometry to fit, and the file key. */
    private var awaitingVariant: Pair<String, String>? = null
    private var railCollapsed = false
    private var stiffnessStep = 0

    /** Depth editing state, back-to-front. */
    private lateinit var depthScroll: View
    private lateinit var depthList: LinearLayout
    private var depthLayers = mutableListOf<LayerSpec>()

    /** The edited character's states, for the depth editor to choose from. */
    private var depthStates: List<StateSpec> = emptyList()
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
    private lateinit var rigLimitMin: View
    private lateinit var rigLimitMax: View
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
    private lateinit var liquidScroll: View
    private lateinit var liquidList: LinearLayout
    private lateinit var particleScroll: View
    private lateinit var particleList: LinearLayout
    private lateinit var settingsScroll: View
    private lateinit var settingsList: LinearLayout
    private lateinit var liquidBar: LinearLayout

    /** The app's own switches. Loaded once at startup, written back on every change. */
    private var settings: Settings = Settings.DEFAULT
    private lateinit var settingsStore: SettingsStore
    private lateinit var partFilesScroll: View
    private lateinit var partFilesList: android.widget.LinearLayout
    private lateinit var partList: LinearLayout
    private lateinit var propListScroll: View
    private lateinit var propList: LinearLayout
    private lateinit var logicPane: View
    private lateinit var logicGraph: LogicGraphView
    private lateinit var logicFolderBar: LinearLayout
    private lateinit var logicBar: LinearLayout

    /** Props are shared by every character, and edited in memory until saved. */
    private var props: MutableList<PropSpec> = mutableListOf()
    private var awaitingProp: String? = null

    /** The summoned character's rules, as edited. Rebuilt on every change; saved on every change. */
    private var logicStats: MutableList<StatSpec> = mutableListOf()
    private var logicRules: MutableList<RuleSpec> = mutableListOf()
    private var logicStates: MutableList<StateSpec> = mutableListOf()
    private var logicLiquids: MutableList<LiquidSpec> = mutableListOf()
    private var logicParticles: MutableList<ParticleSpec> = mutableListOf()

    /** Whose logic the pane is editing: the character, or a prop, or a liquid. See Subjects. */
    private var logicSubject: String = Subjects.PET
    private lateinit var skeletonView: SkeletonView
    private lateinit var rigRow: View
    private lateinit var rigBonePanel: LinearLayout
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
        liquidScroll = findViewById(R.id.liquidScroll)
        liquidList = findViewById(R.id.liquidList)
        particleScroll = findViewById(R.id.particleScroll)
        particleList = findViewById(R.id.particleList)
        settingsScroll = findViewById(R.id.settingsScroll)
        settingsList = findViewById(R.id.settingsList)
        liquidBar = findViewById(R.id.liquidBar)
        partFilesScroll = findViewById(R.id.partFilesScroll)
        partFilesList = findViewById(R.id.partFilesList)
        partList = findViewById(R.id.partList)
        propListScroll = findViewById(R.id.propListScroll)
        propList = findViewById(R.id.propList)
        logicPane = findViewById(R.id.logicPane)
        logicGraph = findViewById(R.id.logicGraph)
        logicFolderBar = findViewById(R.id.logicFolderBar)
        logicBar = findViewById(R.id.logicBar)
        logicGraph.onTap = { rule, node ->
            when (node.role) {
                LogicGraphView.Node.WHEN -> askRuleSettings(rule)
                LogicGraphView.Node.IF -> askCondition(rule, node.index)
                LogicGraphView.Node.CONNECTOR -> flipJoin(rule, node.index)
                LogicGraphView.Node.ADD -> addModule(rule, node.index)
                else -> askAction(rule, node.index, node.role == LogicGraphView.Node.ELSE)
            }
        }
        skeletonView = findViewById(R.id.skeletonView)
        rigRow = findViewById(R.id.rigRow)
        rigBonePanel = findViewById(R.id.rigBonePanel)
        rigBar = findViewById(R.id.rigBarScroll)
        rigMode = findViewById(R.id.rigMode)
        rigAddBone = findViewById(R.id.rigAddBone)
        rigBoneList = findViewById(R.id.rigBoneList)
        rigSaveBones = findViewById(R.id.rigSaveBones)
        rigSavePose = findViewById(R.id.rigSavePose)
        rigLimitMin = findViewById(R.id.rigLimitMin)
        rigLimitMax = findViewById(R.id.rigLimitMax)
        rigLimitMin.setOnClickListener { captureLimit(asMax = false) }
        rigLimitMax.setOnClickListener { captureLimit(asMax = true) }
        findViewById<View>(R.id.rigMode).setOnClickListener { toggleRigMode() }
        findViewById<View>(R.id.rigAddBone).setOnClickListener { askNewBone() }
        // 加节点 works like 加骨骼: arm it, then point at the canvas. See SkeletonView.
        findViewById<View>(R.id.rigAddNode).setOnClickListener {
            skeletonView.beginNodePlacement()
            hideBonePanelsForPlacement()
        }
        findViewById<View>(R.id.rigBoneList).setOnClickListener { showBoneList() }
        findViewById<View>(R.id.rigSaveBones).setOnClickListener { saveBones() }
        findViewById<View>(R.id.rigReset).setOnClickListener { skeletonView.resetPose() }
        findViewById<View>(R.id.rigSavePose).setOnClickListener { askPoseName() }
        // Adding, deleting or reparenting a bone redraws the list it was done from.
        skeletonView.onRigChanged = {
            refreshBoneList()
            buildRigBonePanel()
        }
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
        // The bench records the drag frames itself; getting the file off the phone is this
        // activity's job, because a share sheet is started from an Activity and not from a
        // View. See shareFile.
        sandboxView.onShareFile = { shareFile(it) }
        // 加节点 on the canvas: one tap, one node, written to the character file straight away.
        // The node screen used to leave it in the editor's memory, which is why 加节点 looked
        // like it did nothing at all.
        skeletonView.onNodePlaced = {
            if (persistRig()) {
                Toast.makeText(this, R.string.rig_node_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.rig_save_failed, Toast.LENGTH_SHORT).show()
            }
            buildRigBonePanel()
        }
        // 变身：规则说"变成谁"，这里去把它找出来换上去。找不到就说出来 —— 一个改了 id 或
        // 删掉了角色的规则，否则表现成"这个规则什么都不做"，而那是最难查的一种。
        sandboxView.onMorph = { id ->
            val folder = characters.firstOrNull { it.id == id }
            if (folder == null) {
                Toast.makeText(this, getString(R.string.morph_missing, id), Toast.LENGTH_LONG)
                    .show()
            } else {
                // Only move if the new character can actually be read. A 变身 into a folder
                // whose JSON is broken would leave an empty bench where a pet used to be, and
                // "my pet disappeared" is a worse outcome than "that rule did nothing".
                val previous = summoned
                summoned = folder
                if (!reloadSandbox(folder) && previous != null) {
                    summoned = previous
                    reloadSandbox(previous)
                }
                buildPetChooser()
            }
        }

        // ── 这份清单就是「哪些项能点」。列进来的才会挂上 setOnClickListener，也只有它们
        //    才走得到 select()。布局里多一项、这里少一项，就是一个点了没反应的按钮 ——
        //    menuParticles 就这么漏过一次：布局里有它，select() 里也有它的分支，但它从没
        //    进过这份清单，所以监听器从没挂上，点下去什么都不发生，也没有任何地方说为什么。
        //    顺序要跟布局一致：menuItems.first() 是启动时默认选中的那一项。
        menuItems = listOf(
            findViewById(R.id.menuSandbox),
            findViewById(R.id.menuPets),
            findViewById(R.id.menuProps),
            findViewById(R.id.menuLogic),
            findViewById(R.id.menuLiquids),
            findViewById(R.id.menuParticles),
            findViewById(R.id.menuSettings),
        )
        menuItems.forEach { item ->
            item.tag = item.text.toString()
            item.setOnClickListener { select(item) }
        }
        findViewById<View>(R.id.railHeader).setOnClickListener { setRail(!railCollapsed) }

        store.ensureSeeded()
        settingsStore = SettingsStore(this)
        settings = settingsStore.load()
        sandboxView.applySettings(settings)
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
        if (summoned != null) reloadSandbox(summoned!!)
    }

    /**
     * Rebuild the bench from disk.
     *
     * Every edit ends here — a part, a rig, a rule, a prop — because the bench only reads
     * and the files are the single source of truth. A second in-memory copy of the
     * character is a second thing that can disagree with itself.
     */
    private fun reloadSandbox(folder: CharacterFolder): Boolean {
        sandboxView.setPoseNames(store.loadPoses(folder.id).associate { it.name to it.angles })
        // The bench starts at the stiffness 全局设置 asks for, and the chip row is moved to
        // match: two places that say what the stiffness is would otherwise disagree the moment
        // somebody changed the setting.
        sandboxView.stiffness = settings.defaultStiffness
        stiffnessStep = STIFFNESS_VALUES
            .indices.minByOrNull { abs(STIFFNESS_VALUES[it] - settings.defaultStiffness) } ?: 0
        val loaded = sandboxView.load(
            folder, store.loadLogic(folder.id), store.loadProps(), store.propsDir,
            store.loadObjectLogic(folder),
        )
        if (!loaded) {
            // The bench is empty on purpose, so say so: an empty bench and a pet that has
            // gone missing look exactly alike from here. See CharacterSpec.parseOrNull.
            Toast.makeText(
                this, getString(R.string.character_unreadable, folder.id), Toast.LENGTH_LONG,
            ).show()
        }
        sandboxView.applySettings(settings)
        buildPetChooser()
        return loaded
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
            R.id.menuLiquids -> {
                // Liquid has its own door now. It used to be a bar inside 逻辑管理, which is
                // where a liquid's RULES belong and not where a liquid belongs: a colour and a
                // viscosity have nothing to do with the rule that spills them.
                openLiquids()
                show(Pane.LIQUIDS)
            }
            R.id.menuParticles -> {
                openParticles()
                show(Pane.PARTICLES)
            }
            R.id.menuSettings -> {
                buildSettingsPane()
                show(Pane.SETTINGS)
            }
            else -> show(Pane.PLACEHOLDER)
        }
    }

    private fun show(pane: Pane) {
        placeholder.visibility = if (pane == Pane.PLACEHOLDER) View.VISIBLE else View.GONE
        sandboxPane.visibility = if (pane == Pane.SANDBOX) View.VISIBLE else View.GONE
        petListScroll.visibility = if (pane == Pane.PET_LIST) View.VISIBLE else View.GONE
        partListScroll.visibility = if (pane == Pane.PET_PARTS) View.VISIBLE else View.GONE
        partFilesScroll.visibility = if (pane == Pane.PET_PART_FILES) View.VISIBLE else View.GONE
        rigRow.visibility = if (pane == Pane.PET_RIG) View.VISIBLE else View.GONE
        alignPane.visibility = if (pane == Pane.PART_ALIGN) View.VISIBLE else View.GONE
        depthScroll.visibility = if (pane == Pane.PET_DEPTH) View.VISIBLE else View.GONE
        propListScroll.visibility = if (pane == Pane.PET_PROPS) View.VISIBLE else View.GONE
        logicPane.visibility = if (pane == Pane.PET_LOGIC) View.VISIBLE else View.GONE
        liquidScroll.visibility = if (pane == Pane.LIQUIDS) View.VISIBLE else View.GONE
        particleScroll.visibility = if (pane == Pane.PARTICLES) View.VISIBLE else View.GONE
        settingsScroll.visibility = if (pane == Pane.SETTINGS) View.VISIBLE else View.GONE
        rigBar.visibility = if (pane == Pane.PET_RIG) View.VISIBLE else View.GONE
        if (pane != Pane.PET_RIG && rigBoneMode) {
            rigBoneMode = false
            skeletonView.setBoneEditMode(false)
            applyRigMode()
        }
        statusLine.visibility =
            if (pane == Pane.SANDBOX || pane == Pane.PET_RIG || pane == Pane.PART_ALIGN ||
                pane == Pane.PET_PROPS || pane == Pane.PET_LOGIC || pane == Pane.PET_PART_FILES ||
                pane == Pane.LIQUIDS || pane == Pane.PARTICLES || pane == Pane.SETTINGS
            ) View.VISIBLE else View.GONE

        when (pane) {
            Pane.SANDBOX -> statusLine.text = "拖起来甩出去 · 双击复位 · 上面选桌宠"
            Pane.PET_LIST -> statusLine.text = ""
            Pane.PET_PARTS -> statusLine.text = ""
            Pane.PET_PART_FILES -> statusLine.text = getString(R.string.part_files_hint)
            Pane.PET_RIG -> rigHint()
            Pane.PART_ALIGN -> Unit
            Pane.PET_DEPTH -> statusLine.text = getString(R.string.depth_hint)
            Pane.PET_PROPS -> statusLine.text = getString(R.string.props_subtitle)
            Pane.PET_LOGIC -> statusLine.text =
                if (logicSubject == Subjects.PET) {
                    getString(R.string.logic_rules_hint)
                } else {
                    // Whose logic this is, said again on the way in: the subject chips are one
                    // row among many and it is the one thing about this screen that changes
                    // what everything else means.
                    getString(R.string.logic_subject_hint)
                }
            Pane.LIQUIDS -> statusLine.text = getString(R.string.liquid_subtitle)
            Pane.PARTICLES -> statusLine.text = getString(R.string.particle_subtitle)
            Pane.SETTINGS -> statusLine.text = getString(R.string.settings_hint)
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

    /**
     * Hand one of the bench's diagnostic files to whatever the user wants to send it with.
     *
     * The 导出诊断 recorder writes its CSV under getExternalFilesDir, which since Android 11
     * is a shelf no file manager will show and MTP exposes only sometimes: writing the file
     * is not the same as getting it, and this is the half that gets it. The URI has to come
     * from the FileProvider declared in the manifest -- a file:// URI is a
     * FileUriExposedException on anything modern -- and the read grant is per-URI, which is
     * what lets the receiving app open a file it has no permission to see.
     *
     * Every failure ends in the same place on purpose: the panel prints the full path beside
     * the button, so the answer to "it did not work" is a path the user can still act on.
     */
    private fun shareFile(file: File) {
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            // Not under the provider's <external-files-path>, so there is nothing to hand out.
            Toast.makeText(this, R.string.sandbox_tune_share_failed, Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.sandbox_tune_share_title)))
        } catch (e: ActivityNotFoundException) {
            // A phone with no app that takes a file at all. Rare, and it still has the path.
            Toast.makeText(this, R.string.sandbox_tune_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** The states a bone can have a drawing for: the character's own, and the part's own. */
    private fun partsStates(folder: CharacterFolder, bone: String): List<StateSpec> =
        store.loadLogic(folder.id).states +
            (store.loadObjectLogic(folder)[Subjects.part(bone)]?.states ?: emptyList())

    /**
     * The character whose subjects are being edited: the one on the bench, or the one whose
     * screen is open. A part's rules and a liquid's rules live inside a character's folder, so
     * "whose" is a real question and this is the one place that answers it.
     */
    private fun editingFolder(): CharacterFolder? = summoned ?: opened

    /**
     * Every switch on the bench, as (tag, label).
     *
     * A global state is just its name; a part's own is tagged with the bone, which is the same
     * tag its drawings carry -- so the chip, the layer and the engine all agree about which
     * "出汗" they mean. See Subjects.stateTag.
     */
    private fun benchStates(): List<Pair<String, String>> {
        val folder = summoned ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (state in store.loadLogic(folder.id).states) out.add(state.id to state.name)
        for (bone in boneNames(folder)) {
            val own = store.loadObjectLogic(folder)[Subjects.part(bone)]?.states ?: continue
            for (state in own) {
                out.add(
                    Subjects.stateTag(bone, state.id) to
                        state.name + "·" + boneLabel(bone).ifEmpty { bone },
                )
            }
        }
        return out
    }

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

        // The switches the character owns, right here on the bench.
        //
        // Half of what a state is for is being flipped by hand while you look at it: there
        // is no way to write a rule about a mechanical arm you have never seen fitted, and
        // no way to see it fitted except by writing a rule. So they are chips, and tapping
        // one turns it on.
        // ...the character's own states, and every part's own. 全局状态和局部状态: a hand that
        // sweats and a character that sweats are two switches, and both are worth flipping
        // from here -- a chip says which is which by naming the part.
        val states = benchStates()
        for ((tag, state) in states) {
            val on = sandboxView.stateOn(tag)
            val chip = label((if (on) "● " else "○ ") + state, 12f, if (on) INK else MUTED)
            chip.background = getDrawable(
                if (on) R.drawable.menu_item_selected else R.drawable.menu_item_idle
            )
            chip.setPadding(dp(10), dp(6), dp(10), dp(6))
            val sp2 = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            sp2.marginEnd = dp(6)
            chip.layoutParams = sp2
            chip.setOnClickListener {
                sandboxView.toggleState(tag)
                buildPetChooser()
                buildLogicPaneIfOpen()
            }
            petChooser.addView(chip)
        }

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
            .setView(scrolling(box))
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
        val spec = CharacterSpec.parseOrNull(folder.specText())
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

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val nameBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            nameBox.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
            nameBox.addView(label(folder.id, 15f, INK))
            nameBox.addView(
                label(
                    bones.size.toString() + " bones · " + folder.partCount(bones) + " parts drawn",
                    11f, MUTED,
                )
            )
            header.addView(nameBox)

            // A package is the one thing that had no way out. Everything inside it can be
            // deleted one piece at a time, which is no help when the whole thing was an
            // experiment.
            val remove = label(getString(R.string.action_delete), 11f, MUTED)
            remove.setPadding(dp(10), dp(6), dp(10), dp(6))
            remove.setOnClickListener { confirmDeletePet(folder, bones.size) }
            header.addView(remove)
            card.addView(header)

            card.setOnClickListener { openPet(folder) }
            petList.addView(card)
        }
    }

    /**
     * Delete a whole package.
     *
     * Deliberately the most explicit confirmation in the app: this takes the rig, the
     * drawings, the rules and the saved actions with it, and no part of that is kept
     * anywhere else. Reinstalling the app brings back the bundled one and nothing else.
     */
    private fun confirmDeletePet(folder: CharacterFolder, bones: Int) {
        val parts = folder.partCount(boneNames(folder))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_delete) + " · " + folder.id)
            .setMessage(getString(R.string.pet_delete_confirm, folder.id, bones, parts))
            .setPositiveButton(R.string.depth_remove) { _, _ ->
                store.deleteCharacter(folder.id)
                if (summoned?.id == folder.id) summoned = null
                if (opened?.id == folder.id) opened = null
                reloadCharacters()
                if (summoned == null && characters.isNotEmpty()) {
                    summoned = characters.first()
                    reloadSandbox(characters.first())
                }
                buildPetList()
                buildPetChooser()
                buildLogicPaneIfOpen()
                Toast.makeText(this, R.string.pet_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    /** Refresh the logic pane when it is the one on screen and its character just went. */
    private fun buildLogicPaneIfOpen() {
        if (logicPane.visibility == View.VISIBLE) openLogic()
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

        // 节点 one button from the parts, not four screens deep inside the bone list: this is
        // where somebody is thinking about parts, and "a name for a place on a bone" is a
        // thought that belongs next to them. The bone list still has the same section.
        val nodes = label(
            getString(R.string.rig_node_list) + " (" + skeletonView.rigNodes().size + ")", 12f, INK,
        )
        nodes.setPadding(dp(12), dp(8), dp(12), dp(8))
        nodes.background = getDrawable(R.drawable.menu_item_idle)
        nodes.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(8) }
        nodes.setOnClickListener { askNodeList(folder) }

        val entries = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        entries.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }

        val rig = label(getString(R.string.pet_edit_rig), 12f, INK)
        rig.setPadding(dp(12), dp(8), dp(12), dp(8))
        rig.background = getDrawable(R.drawable.menu_item_selected)
        rig.setOnClickListener {
            skeletonView.load(folder)
            rigBoneMode = false
            skeletonView.setBoneEditMode(false)
            applyRigMode()
            buildRigBonePanel()
            show(Pane.PET_RIG)
        }
        entries.addView(rig)
        entries.addView(nodes)
        partList.addView(entries)

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

    /** The bone whose folder the 部位文件夹 pane is showing. */
    private var partFolderBone: String = ""

    /**
     * A bone's folder: every drawing it owns, in one place, with the state each one belongs
     * to written on it.
     *
     * This is the answer to "a part should open into a folder holding its base drawing and
     * the drawings for each state, and I should be able to SEE it while managing it". Before
     * this the drawings were invisible: you imported one, and after that the only evidence
     * it existed was that the pet looked different.
     */
    private fun openPartFiles(folder: CharacterFolder, bone: String) {
        partFolderBone = bone
        opened = folder
        buildPartFiles(folder)
        show(Pane.PET_PART_FILES)
    }

    private fun buildPartFiles(folder: CharacterFolder) {
        val bone = partFolderBone
        partFilesList.removeAllViews()

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = label(getString(R.string.pet_back), 13f, INK)
        back.setPadding(dp(12), dp(6), dp(12), dp(6))
        back.background = getDrawable(R.drawable.menu_item_idle)
        back.setOnClickListener {
            buildPartList(folder)
            show(Pane.PET_PARTS)
        }
        header.addView(back)
        header.addView(
            label("  " + bone + "  ·  " + getString(R.string.part_files_title), 15f, INK)
        )
        partFilesList.addView(header)

        // The rules this part has of its own. Same screen as the character's rules, different
        // subject: it only hears what happens to THIS part (or to the whole figure), which is
        // what makes 让手流汗 a rule about the hand rather than a rule that keeps asking.
        if (bone != null) {
            val rules = label(getString(R.string.part_own_rules, boneLabel(bone).ifEmpty { bone }), 12f, INK)
            rules.setPadding(dp(12), dp(9), dp(12), dp(9))
            rules.background = getDrawable(R.drawable.menu_item_selected)
            rules.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            rules.setOnClickListener {
                openLogic(Subjects.part(bone))
                show(Pane.PET_LOGIC)
            }
            partFilesList.addView(rules)
        }

        if (boneLabel(bone).isNotEmpty()) {
            partFilesList.addView(label(boneLabel(bone), 11f, MUTED, top = 6, bottom = 8))
        }

        // Which state each drawing is for comes out of the LAYERS, because that is where the
        // decision actually lives: a file on its own does not know when it is drawn.
        val layers = CharacterSpec.parseOrNull(folder.specText())
            ?.layers?.filter { it.bone == bone } ?: emptyList()
        fun layerOf(artKey: String) = layers.firstOrNull { it.artKey == artKey }

        // Both levels: the character's own states, and the ones THIS part declares. A drawing
        // can be shown while "穿着" is on, or while this hand's own "出汗" is on -- and the
        // two may share a name, which is why addVariant tags the layer with the bone.
        val states = partsStates(folder, bone)
        val drawings = store.partDrawings(folder.id, bone)
        if (drawings.isEmpty()) {
            partFilesList.addView(label(getString(R.string.part_files_none), 11f, MUTED, bottom = 10))
        }

        /**
         * Pick a new drawing for one of a bone's slots.
         *
         * [create] adds the layer as well, and is only true for a slot that does not exist
         * yet: replacing the drawing of a state that already has one must not leave a second
         * layer pointing at the same file, which draws the same picture twice and shows up
         * as a duplicate in 图层与深度.
         */
        fun importInto(artKey: String, state: String, create: Boolean) {
            awaitingBone = bone
            awaitingVariant = if (state.isEmpty()) null else bone to artKey
            opened = folder
            if (create && state.isNotEmpty() && !store.addVariant(folder.id, bone, state)) {
                Toast.makeText(this, R.string.rig_save_failed, Toast.LENGTH_SHORT).show()
                return
            }
            pickImage.launch(arrayOf("image/*"))
        }

        for (drawing in drawings) {
            val layer = layerOf(drawing.artKey)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.menu_item_idle)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                isFocusable = true
            }
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }

            val box = dp(56)
            val thumb = android.widget.ImageView(this)
            thumb.layoutParams = LinearLayout.LayoutParams(box, box).apply { marginEnd = dp(10) }
            thumb.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            thumb.setImageBitmap(thumbnail(drawing.file, box))
            row.addView(thumb)

            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
            text.addView(label(drawing.file.name, 12f, INK))
            val whenText = when {
                layer == null -> getString(R.string.part_files_unused)
                layer.state.isEmpty() -> getString(R.string.part_files_always)
                layer.state.startsWith("!") ->
                    getString(R.string.part_files_when_off, stateLabel(layer.state.substring(1)))
                else -> getString(R.string.part_files_when_on, stateLabel(layer.state))
            }
            text.addView(label(whenText, 10f, MUTED))
            row.addView(text)

            if (layer != null && layer.state == "" && states.isNotEmpty()) {
                val fit = label(getString(R.string.part_variant), 11f, MUTED)
                fit.setPadding(dp(8), dp(6), dp(8), dp(6))
                fit.setOnClickListener { askAddVariant(folder, bone) }
                row.addView(fit)
            }

            val del = label(getString(R.string.part_files_delete), 11f, MUTED)
            del.setPadding(dp(8), dp(6), dp(8), dp(6))
            del.setOnClickListener {
                store.deleteDrawing(folder.id, bone, drawing.artKey)
                buildPartFiles(folder)
                reloadSummoned(folder)
                if (rigRow.visibility == View.VISIBLE) skeletonView.load(folder)
            }
            row.addView(del)

            // Tapping the row replaces that drawing -- the same file, new picture.
            row.setOnClickListener { importInto(drawing.artKey, drawing.state, create = false) }
            partFilesList.addView(row)
        }

        // The modules that are not there yet: one for the base drawing, and one per state
        // that has no drawing of its own.
        val have = drawings.map { it.artKey }.toSet()
        if (bone !in have) {
            partFilesList.addView(
                addTile(getString(R.string.part_files_add)) { importInto(bone, "", create = false) }
            )
        }
        for (state in states) {
            val key = store.variantKey(bone, state.id)
            if (key in have) continue
            partFilesList.addView(
                addTile(getString(R.string.part_files_add_state, state.name)) {
                    importInto(key, state.id, create = true)
                }
            )
        }
    }

    /** A drawing small enough to put in a list. Sampling is a power of two, as ever. */
    private fun thumbnail(file: File, box: Int): android.graphics.Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= box && bounds.outHeight / (sample * 2) >= box) {
            sample *= 2
        }
        android.graphics.BitmapFactory.decodeFile(
            file.path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (e: Exception) {
        null
    }

    private fun addTile(text: String, onClick: () -> Unit): View {
        val view = label(text, 12f, INK)
        view.setPadding(dp(12), dp(10), dp(12), dp(10))
        view.background = getDrawable(R.drawable.menu_item_selected)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
        view.setOnClickListener { onClick() }
        return view
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

        val count = store.partDrawings(folder.id, bone).size
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(bone, 13f, INK))
        text.addView(
            label(
                boneLabel(bone) + " · " +
                    if (count == 0) "未导入" else count.toString() + " 张图",
                10f, MUTED,
            )
        )
        val tp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        text.layoutParams = tp
        row.addView(text)

        if (has) {
            val variant = label(getString(R.string.part_variant), 11f, MUTED)
            variant.setPadding(dp(10), dp(6), dp(10), dp(6))
            variant.setOnClickListener { askAddVariant(folder, bone) }
            row.addView(variant)
        }

        // Into the folder, not straight into the photo picker: a part with a base drawing
        // and two states has three pictures, and importing has to be able to say which one
        // it is replacing.
        row.setOnClickListener { openPartFiles(folder, bone) }
        return row
    }

    /**
     * A second drawing for a bone, shown only while a state is on.
     *
     * This is how a mechanical arm happens: the same bone, the same rig, two files, and a
     * state that says which one is fitted.
     */
    private fun askAddVariant(folder: CharacterFolder, bone: String) {
        val states = partsStates(folder, bone)
        if (states.isEmpty()) {
            Toast.makeText(this, R.string.part_no_states, Toast.LENGTH_SHORT).show()
            return
        }
        pickList(
            title = getString(R.string.part_variant) + " · " + bone,
            options = states.map { it.id to it.name },
            hint = getString(R.string.part_variant_hint),
            current = null,
        ) { state ->
            awaitingVariant = bone to (bone + "__" + state)
            awaitingBone = bone
            opened = folder
            if (store.addVariant(folder.id, bone, state)) {
                pickImage.launch(arrayOf("image/*"))
            } else {
                Toast.makeText(this, R.string.rig_save_failed, Toast.LENGTH_SHORT).show()
            }
            true
        }
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
        val variant = awaitingVariant
        awaitingVariant = null
        alignView.load(folder, bone, bitmap, variant?.second ?: bone)
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
        val parsed = CharacterSpec.parseOrNull(folder.specText()) ?: return

        depthLayers = parsed.layers.sortedBy { it.z }.toMutableList()
        // A bone with artwork but no layer entry is never drawn at all. The shoulders were
        // exactly that for a while, and "everything shows except these two" is a hard
        // thing to guess from the code, so anything missing is appended at the front where
        // it is at least visible.
        for (b in parsed.bones.map { it.name }) {
            if (depthLayers.none { it.bone == b } && folder.partFile(b).isFile) {
                depthLayers.add(LayerSpec(b, 0))
            }
        }
        depthStates = store.loadLogic(folder.id).states
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
        val frontFirst = depthLayers.reversed()
        for ((i, layer) in frontFirst.withIndex()) {
            val realIndex = depthLayers.size - 1 - i
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
            name.addView(label(layer.bone, 13f, INK))
            // The artwork is what actually draws, and a variant is a different file on the
            // same bone -- so the row has to say which file, or two rows look identical.
            name.addView(
                label(
                    (if (layer.art.isEmpty()) boneLabel(layer.bone) else layer.art) +
                        (if (layer.art.isEmpty()) "" else "  ·  " + boneLabel(layer.bone)),
                    10f, MUTED,
                )
            )
            name.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(name)

            // The state chip: which switch this part belongs to. Tapping it is how a
            // drawing becomes "the shirt" without the app having to know what a shirt is.
            val stateChip = label(stateChipText(layer), 10f, MUTED)
            stateChip.setPadding(dp(8), dp(6), dp(8), dp(6))
            stateChip.background = getDrawable(R.drawable.menu_item_idle)
            stateChip.setOnClickListener { askPartState(realIndex) }
            row.addView(stateChip)

            val up = label("▲", 13f, INK)
            up.setPadding(dp(10), dp(4), dp(10), dp(4))
            up.setOnClickListener {
                if (realIndex < depthLayers.size - 1) {
                    val b = depthLayers.removeAt(realIndex)
                    depthLayers.add(realIndex + 1, b)
                    buildDepthPane()
                }
            }
            val down = label("▼", 13f, INK)
            down.setPadding(dp(10), dp(4), dp(10), dp(4))
            down.setOnClickListener {
                if (realIndex > 0) {
                    val b = depthLayers.removeAt(realIndex)
                    depthLayers.add(realIndex - 1, b)
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
    /**
     * The bone picker beside the canvas.
     *
     * Finding one hand among nineteen bones by tapping at a picture is guesswork at phone
     * size. A column of names that zooms the canvas to the one you touched turns a hunt
     * into a tap, and it doubles as the answer to "what is this rig actually made of".
     */
    private fun buildRigBonePanel() {
        if (!::rigBonePanel.isInitialized) return
        rigBonePanel.removeAllViews()
        rigBonePanel.addView(label(getString(R.string.rig_pick_bone), 9f, MUTED, bottom = 6))
        val bones = skeletonView.rigBones()
        val depth = RigEdit.depths(bones)
        for (b in bones) {
            val zh = boneLabel(b.name)
            val row = label(
                "  ".repeat(depth[b.name] ?: 0) + if (zh.isEmpty()) b.name else zh,
                11f, INK,
            )
            row.setPadding(dp(5), dp(8), dp(5), dp(8))
            row.background = getDrawable(
                if (skeletonView.selected == b.name) R.drawable.menu_item_selected
                else R.drawable.menu_item_idle
            )
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(3) }
            row.setOnClickListener {
                skeletonView.focusOn(b.name)
                buildRigBonePanel()
            }
            rigBonePanel.addView(row)
        }
    }

    private fun stateChipText(layer: LayerSpec): String {
        if (layer.state.isEmpty()) return getString(R.string.depth_state_none)
        val off = layer.state.startsWith("!")
        // The tag says which level it is, so "which state is this" is answerable without
        // guessing: a bone prefix means that part's own state, no prefix means the
        // character's. See Subjects.tagBone.
        val bone = Subjects.tagBone(layer.state)
        val id = Subjects.tagState(layer.state)
        val known = if (bone.isEmpty()) {
            depthStates.any { it.id == id }
        } else {
            store.loadObjectLogic(editingFolder())[Subjects.part(bone)]?.states
                ?.any { it.id == id } == true
        }
        return getString(R.string.depth_state) + (if (off) "不" else "") +
            (if (known) stateLabel(layer.state) else id + "（已不存在）")
    }

    /**
     * Put a part on a state, or take it off one.
     *
     * The parts are drawn by the depth editor's own order, and the state just says whether
     * they are drawn at all — so "the clothes" is a list of bones this app never has to
     * understand. It only has to draw what the switch says.
     */
    private fun askPartState(index: Int) {
        val layer = depthLayers.getOrNull(index) ?: return
        val options = mutableListOf("" to getString(R.string.depth_state_none))
        // Both levels: the character's own states under their names, and this bone's own
        // under a tag that says so. A layer belongs to one bone, so the only local states it
        // can mean are that bone's -- offering every part's would be offering a rule the
        // drawing can never satisfy.
        val own = store.loadObjectLogic(editingFolder())[Subjects.part(layer.bone)]
            ?.states ?: emptyList()
        for (s in depthStates + own) {
            val tag = if (own.any { it.id == s.id }) Subjects.stateTag(layer.bone, s.id) else s.id
            val name = if (tag == s.id) s.name else s.name + "·" + boneLabel(layer.bone)
            options.add(tag to getString(R.string.depth_state) + name)
            options.add("!" + tag to getString(R.string.depth_state_not) + name)
        }
        pickList(
            title = getString(R.string.depth_state) + " · " + layer.bone,
            options = options,
            hint = getString(R.string.depth_state_hint),
            current = layer.state,
        ) { id ->
            depthLayers[index] = layer.copy(state = id)
            buildDepthPane()
            true
        }
    }

    private fun withDescendants(folder: CharacterFolder, bone: String): List<String> {
        val parsed = CharacterSpec.parseOrNull(folder.specText()) ?: return listOf(bone)
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
        val ok = store.saveDepth(folder.id, depthLayers, depthRules)
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
        // The capture belongs with posing: it turns the angle the finger just made into the
        // joint's range. In bone mode the pose is at rest, so there is nothing to capture.
        rigLimitMin.visibility = if (rigBoneMode) View.GONE else View.VISIBLE
        rigLimitMax.visibility = if (rigBoneMode) View.GONE else View.VISIBLE
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

    /**
     * Take the angle a joint is posed at and write it into that joint's range.
     *
     * The loop this replaces: drag the arm in 摆姿势, guess the angle in degrees, open 骨骼列表 →
     * 属性, type it into one of two boxes, save, go to the bench, and find out. Here the pose IS
     * the answer -- the same drag, one button, and the range is drawn on the canvas while it
     * happens (see SkeletonView.drawRange).
     *
     * Only the end being set moves: 「设为最小」 leaves the maximum alone. If that makes the
     * range backwards -- a new minimum above the old maximum -- RigEdit.limits swaps them,
     * which is the same rule the 属性 dialog uses for a range typed backwards.
     */
    private fun captureLimit(asMax: Boolean) {
        val name = skeletonView.selected
        val deg = name?.let { skeletonView.currentDegrees(it) }
        if (name == null || deg == null) {
            Toast.makeText(this, R.string.rig_limit_no_pick, Toast.LENGTH_SHORT).show()
            return
        }
        val bone = skeletonView.rigBones().firstOrNull { it.name == name } ?: return
        val pair = if (asMax) RigEdit.limits(bone.minAngle, deg)
        else RigEdit.limits(deg, bone.maxAngle)
        bone.minAngle = pair.first
        bone.maxAngle = pair.second
        saveBones()
        statusLine.text = getString(
            R.string.rig_limit_set,
            boneLabel(name).ifEmpty { name },
            getString(if (asMax) R.string.rig_limit_max_word else R.string.rig_limit_min_word),
            Math.round(deg),
            Math.round(bone.minAngle),
            Math.round(bone.maxAngle),
        )
        // The POSE is deliberately left standing: the bone line sitting inside the fan is how
        // the user sees which end they just set. 双击复位 puts it back.
        skeletonView.invalidate()
    }

    /**
     * Get out of the way of a tap that is about to mean "here".
     *
     * 加骨骼 already does this by hiding the panel; a node is placed with ONE tap, so anything
     * that is open and eats a tap -- a dialog, a list -- would swallow the answer.
     */
    private fun hideBonePanelsForPlacement() {
        boneDialog?.dismiss()
    }

    /**
     * Write the rig to disk, nodes included.
     *
     * The node screen used to change the editor's copy and stop there: the node only existed on
     * disk after somebody opened 骨架 and pressed 保存骨骼, which nobody has any reason to do
     * after adding a node somewhere else. That is exactly the shape of "这个功能不能用".
     */
    private fun persistRig(): Boolean {
        val folder = opened ?: return false
        val bones = skeletonView.rigBones()
        if (RigEdit.problem(bones) != null) return false
        val ok = store.saveRig(
            folder.id, bones, skeletonView.rigLayers(), skeletonView.renames(),
            skeletonView.rigNodes(),
        )
        if (ok) reloadSummoned(folder)
        return ok
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
        val ok = store.saveRig(
            folder.id, bones, skeletonView.rigLayers(), skeletonView.renames(),
            skeletonView.rigNodes(),
        )
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
            .setView(scrolling(box))
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
        fillNodeList(box, bones)

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

    /**
     * The nodes, in the same list as the bones.
     *
     * They belong here rather than behind their own button: "这一节的指尖" is one thought, and a
     * rig where the points are somewhere else is a rig where nobody remembers they exist.
     */
    /** The folder the node screen is about, and how it asks for a tap on the canvas. */
    private var nodeFolderId: String = ""
    private var nodePlacement: ((String) -> Unit)? = null

    private fun fillNodeList(
        box: LinearLayout,
        bones: List<BoneSpec>,
        refresh: () -> Unit = { refreshBoneList() },
    ) {
        val nodes = skeletonView.rigNodes()
        box.addView(
            label(getString(R.string.rig_node_list) + " (" + nodes.size + ")", 12f, INK, top = 16)
        )
        box.addView(label(getString(R.string.rig_node_hint), 10f, MUTED, top = 4, bottom = 6))
        if (nodes.isEmpty()) box.addView(label(getString(R.string.rig_no_nodes), 10f, MUTED))

        for (n in nodes) {
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
            text.addView(label(n.name, 13f, INK))
            val bone = bones.firstOrNull { it.name == n.bone }
            val place = when {
                bone == null -> "?"
                RigEdit.placeOf(bone, n.at) == "tip" -> getString(R.string.rig_node_tip)
                RigEdit.placeOf(bone, n.at) == "mid" -> getString(R.string.rig_node_mid)
                else -> getString(R.string.rig_node_joint)
            }
            val worn = props.firstOrNull { it.id == n.prop }?.name
            text.addView(
                label(
                    getString(R.string.rig_node_on) + " " + n.bone + " · " + place +
                        " · r" + n.radius.toInt() +
                        (worn?.let { " · " + it } ?: ""),
                    10f, MUTED,
                )
            )
            row.addView(text)

            val edit = label(getString(R.string.rig_attributes), 11f, MUTED)
            edit.setPadding(dp(7), dp(6), dp(7), dp(6))
            edit.setOnClickListener { askNode(n) }
            row.addView(edit)

            val remove = label(getString(R.string.action_delete), 11f, MUTED)
            remove.setPadding(dp(8), dp(6), dp(8), dp(6))
            remove.setOnClickListener {
                skeletonView.deleteNode(n.name)
                persistRig()
                refresh()
            }
            row.addView(remove)
            box.addView(row)
        }

        val place = label(getString(R.string.rig_node_place_on_canvas), 13f, INK)
        place.setPadding(dp(14), dp(11), dp(14), dp(11))
        place.background = getDrawable(R.drawable.menu_item_selected)
        place.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
        place.setOnClickListener { nodePlacement?.invoke(nodeFolderId) }
        box.addView(place)

        val add = label(getString(R.string.rig_node_add), 12f, INK)
        add.setPadding(dp(12), dp(8), dp(12), dp(8))
        add.background = getDrawable(R.drawable.menu_item_idle)
        add.setOnClickListener { askNode(null) }
        box.addView(add)
    }

    /**
     * 节点: the names for places on the bones, on their own screen.
     *
     * The bones are left out on purpose. They are already a list of their own with their own
     * button, and the question this screen answers is "where can a rule point at a PLACE" --
     * nineteen bones in front of that answer is what made the button unreachable in the first
     * place, before the scrolling was fixed.
     */
    private fun askNodeList(folder: CharacterFolder) {
        // The editor's own copy of the rig, because that is what saves the nodes: the part list
        // can be opened without ever having gone through 看骨架.
        skeletonView.load(folder)
        nodeFolderId = folder.id
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.rig_node_list))
            .setView(scrolling(box))
            .setNegativeButton(R.string.action_close, null)
            .create()
        // Placing on the canvas needs the canvas: the dialog gets out of the way first, and the
        // rig screen is where the figure is.
        nodePlacement = place@{ folderId ->
            dialog.dismiss()
            val target = characters.firstOrNull { it.id == folderId } ?: opened
                ?: return@place
            skeletonView.load(target)
            rigBoneMode = false
            skeletonView.setBoneEditMode(false)
            applyRigMode()
            buildRigBonePanel()
            show(Pane.PET_RIG)
            skeletonView.beginNodePlacement()
        }
        fun fill() {
            box.removeAllViews()
            fillNodeList(box, skeletonView.rigBones()) {
                fill()
                buildPartList(folder)
            }
        }
        fill()
        dialog.show()
    }

    /**
     * One node: a name, a bone, which end of it, how big it is, and what it is wearing.
     *
     * The bone is picked off a list rather than typed, because a node whose bone does not exist
     * is a name nothing can ever touch ("挂在 hand_L 上" is not something a person should have
     * to spell correctly). The place is three chips, and it is stored as a DISTANCE so that a
     * bone resized later takes its tip node along with it.
     */
    private fun askNode(existing: NodeSpec?) {
        val bones = skeletonView.rigBones()
        if (bones.isEmpty()) {
            Toast.makeText(this, R.string.rig_bone_list_empty, Toast.LENGTH_SHORT).show()
            return
        }
        var bone = existing?.bone ?: bones.first().name
        var place = existing?.let { n -> bones.firstOrNull { it.name == n.bone }?.let { RigEdit.placeOf(it, n.at) } }
            ?: "joint"
        var prop = existing?.prop ?: ""

        val nameInput = EditText(this).apply {
            setText(existing?.name ?: RigEdit.freeNodeName(bones, skeletonView.rigNodes()))
            setSelection(text.length)
            hint = getString(R.string.rig_node_name)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        box.addView(nameInput)
        box.addView(label(getString(R.string.rig_node_hint), 10f, MUTED, top = 6))

        box.addView(label(getString(R.string.rig_node_bone), 11f, MUTED, top = 12, bottom = 6))
        val boneChip = label(boneLabel(bone).ifEmpty { bone }, 12f, INK)
        boneChip.setPadding(dp(12), dp(8), dp(12), dp(8))
        boneChip.background = getDrawable(R.drawable.menu_item_idle)
        boneChip.setOnClickListener {
            pickList(
                title = getString(R.string.rig_node_bone),
                options = bones.map { it.name to (boneLabel(it.name).ifEmpty { it.name }) },
                hint = "",
                current = bone,
            ) { id ->
                bone = id
                boneChip.text = boneLabel(id).ifEmpty { id }
                true
            }
        }
        box.addView(boneChip)

        box.addView(label(getString(R.string.rig_node_place), 11f, MUTED, top = 12, bottom = 6))
        val placeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val placeViews = mutableListOf<TextView>()
        for ((id, text) in listOf(
            "joint" to getString(R.string.rig_node_joint),
            "mid" to getString(R.string.rig_node_mid),
            "tip" to getString(R.string.rig_node_tip),
        )) {
            val chip = label(text, 12f, INK)
            chip.setPadding(dp(12), dp(8), dp(12), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                place = id
                paintChips(placeViews, listOf("joint", "mid", "tip"), { place })
            }
            placeViews.add(chip)
            placeRow.addView(chip)
        }
        box.addView(placeRow)

        val (radiusRow, radiusOf) = stepperRow(
            getString(R.string.rig_node_radius), existing?.radius ?: 26f, 4f, 4f, 200f,
        ) { it.toInt().toString() }
        box.addView(radiusRow)
        box.addView(label(getString(R.string.rig_node_radius_hint), 10f, MUTED, top = 2))

        box.addView(label(getString(R.string.rig_node_prop), 11f, MUTED, top = 12, bottom = 6))
        val propChip = label(
            props.firstOrNull { it.id == prop }?.name ?: getString(R.string.rig_node_none), 12f, INK,
        )
        propChip.setPadding(dp(12), dp(8), dp(12), dp(8))
        propChip.background = getDrawable(R.drawable.menu_item_idle)
        propChip.setOnClickListener {
            pickList(
                title = getString(R.string.rig_node_prop),
                options = listOf("" to getString(R.string.rig_node_none)) +
                    props.map { it.id to it.name },
                hint = "",
                current = prop,
            ) { id ->
                prop = id
                propChip.text = props.firstOrNull { it.id == id }?.name
                    ?: getString(R.string.rig_node_none)
                true
            }
        }
        box.addView(propChip)
        box.addView(label(getString(R.string.rig_node_prop_hint), 10f, MUTED, top = 4))

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.rig_node_new else R.string.rig_node_edit)
            .setView(scrolling(box))
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val boneSpec = bones.firstOrNull { it.name == bone } ?: return@setPositiveButton
                val name = nameInput.text.toString().trim()
                    .ifEmpty { RigEdit.freeNodeName(bones, skeletonView.rigNodes()) }
                val ok = skeletonView.saveNode(
                    existing?.name, name, bone, RigEdit.placeAt(boneSpec, place),
                    radiusOf(), prop,
                )
                if (!ok) Toast.makeText(this, R.string.rig_save_failed, Toast.LENGTH_SHORT).show()
                persistRig()
                refreshBoneList()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
        paintChips(placeViews, listOf("joint", "mid", "tip"), { place })
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

        val attrs = label(getString(R.string.rig_attributes), 11f, MUTED)
        attrs.setPadding(dp(7), dp(6), dp(7), dp(6))
        attrs.setOnClickListener { askBoneAttributes(folder, bone) }
        row.addView(attrs)

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
     * What a joint is allowed to do, and what it collides as.
     *
     * The range of motion is the one that changes how the pet FEELS: the limits are what a
     * knee stops at, and every one of them was measured off a reference sheet and then
     * never looked at again. A shoulder that can swing a hundred and seventy degrees and one
     * that can swing ninety are two different animals, and the only way to find out which
     * one you want is to drag the arm and see where it stops.
     *
     * The collider is the other half: it is what the floor, the props and the liquid see,
     * and it is where "why does it hover above the ground" and "why does it sink into the
     * table" come from.
     *
     * Mass is deliberately not here. A bone's mass is derived from its collider's area, so
     * a fatter collider is a heavier limb, and offering a separate weight would be offering
     * two answers to one question.
     */
    private fun askBoneAttributes(folder: CharacterFolder, bone: BoneSpec) {
        var minAngle = bone.minAngle
        var maxAngle = bone.maxAngle
        var type = bone.colliderType
        var radius = bone.colliderRadius
        var collides = bone.collides
        var grabbable = bone.grabbable

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        box.addView(
            label(
                getString(R.string.rig_limits_hint, boneLabel(bone.name).ifEmpty { bone.name }),
                11f, MUTED, bottom = 8,
            )
        )

        val fromInput = EditText(this).apply {
            setText(trim(minAngle))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(dp(14), dp(10), dp(14), dp(10))
            hint = getString(R.string.rig_limit_from)
        }
        val toInput = EditText(this).apply {
            setText(trim(maxAngle))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(dp(14), dp(10), dp(14), dp(10))
            hint = getString(R.string.rig_limit_to)
        }
        box.addView(fromInput)
        box.addView(toInput)

        // 刚度：这个关节自己的弹簧，乘在全局那个上面。全局还留在测试场上（"松垮/僵硬"），
        // 所以这里是"这一节比别的节硬多少倍"。
        val (stiffRow, stiffnessOf) = stepperRow(
            getString(R.string.rig_stiffness), bone.stiffness, 0.1f, 0f, 3f,
        ) { "%.1f×".format(it) }
        box.addView(stiffRow)
        box.addView(label(getString(R.string.rig_stiffness_hint), 10f, MUTED, top = 2))

        box.addView(label(getString(R.string.rig_collider), 11f, MUTED, top = 12, bottom = 6))
        val typeChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val typeViews = mutableListOf<TextView>()
        for ((id, text) in listOf(
            "capsule" to getString(R.string.rig_collider_capsule),
            "circle" to getString(R.string.rig_collider_circle),
        )) {
            val chip = label(text, 12f, INK)
            chip.setPadding(dp(12), dp(8), dp(12), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                type = id
                paintChips(typeViews, listOf("capsule", "circle"), { type })
            }
            typeViews.add(chip)
            typeChips.addView(chip)
        }
        box.addView(typeChips)

        val radiusInput = EditText(this).apply {
            setText(if (radius <= 0f) "" else trim(radius))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            hint = getString(R.string.rig_collider_radius)
        }
        box.addView(radiusInput)
        box.addView(label(getString(R.string.rig_collider_hint), 10f, MUTED, top = 6))

        // The two switches. They are what turns a part into scenery: a ribbon that should not
        // shove the table, or a part that exists only so a rule can name it.
        box.addView(label(getString(R.string.rig_part_switches), 11f, MUTED, top = 14, bottom = 6))
        val switchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val collideChip = label(getString(R.string.rig_part_collides), 12f, INK)
        val grabChip = label(getString(R.string.rig_part_grabbable), 12f, INK)
        for (chip in listOf(collideChip, grabChip)) {
            chip.setPadding(dp(10), dp(8), dp(10), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
            switchRow.addView(chip)
        }
        // A switch, not a chip among equals: there is nothing to compare it against, so it
        // is drawn as on or off rather than as the selected one of a set.
        fun paintPartSwitches() {
            for ((chip, on) in listOf(collideChip to collides, grabChip to grabbable)) {
                chip.background = getDrawable(
                    if (on) R.drawable.menu_item_selected else R.drawable.menu_item_idle
                )
                chip.setTextColor(if (on) INK else MUTED)
            }
        }
        collideChip.setOnClickListener {
            collides = !collides
            paintPartSwitches()
        }
        grabChip.setOnClickListener {
            grabbable = !grabbable
            paintPartSwitches()
        }
        box.addView(switchRow)
        box.addView(label(getString(R.string.rig_part_switches_hint), 10f, MUTED, top = 6))

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.rig_attributes) + " · " + bone.name)
            .setView(scrolling(box))
            .setPositiveButton(R.string.depth_save) { _, _ ->
                fun degrees(text: String, fallback: Float): Float =
                    text.trim().toFloatOrNull()?.coerceIn(-360f, 360f) ?: fallback
                val lo = degrees(fromInput.text.toString(), minAngle)
                val hi = degrees(toInput.text.toString(), maxAngle)
                // A range written backwards is a joint that cannot move at all, which reads
                // as a broken rig rather than as a typo. Swapping is what they meant, and the
                // rule lives in RigEdit.limits so that this dialog and 「设为最小/最大」
                // cannot drift apart about it.
                val pair = RigEdit.limits(lo, hi)
                bone.minAngle = pair.first
                bone.maxAngle = pair.second
                bone.colliderType = if (type == "circle") "circle" else "capsule"
                bone.colliderRadius =
                    radiusInput.text.toString().trim().toFloatOrNull()?.coerceIn(0f, 400f) ?: 0f
                bone.stiffness = stiffnessOf()
                bone.collides = collides
                bone.grabbable = grabbable
                saveBones()
                Toast.makeText(this, R.string.rig_attributes_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .create()
        dialog.show()
        paintChips(typeViews, listOf("capsule", "circle"), { type })
        paintPartSwitches()
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
            .setView(scrolling(box))
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

        // 拖尾, on the row rather than only inside the editor: it needs the prop to EXIST (the
        // pattern is a file beside the prop's own), so it belongs where the prop already does.
        // Inside the editor it was one "save, close, find the prop, open it again" too far.
        val trail = label(
            if (store.propTrail(spec.id).isFile) getString(R.string.prop_trail_edit)
            else getString(R.string.prop_trail_draw),
            11f, MUTED,
        )
        trail.setPadding(dp(8), dp(6), dp(8), dp(6))
        trail.setOnClickListener {
            askPropTrail(spec.id, spec.name) { buildPropList() }
        }
        card.addView(trail)

        // 画绳子, for a rope: the drawing the app lays along the simulated chain. Same board,
        // same place on the row, because it is the same kind of thing as a trail -- a picture
        // this prop wears, in this prop's folder.
        if (spec.kindOf() == PropKind.ROPE) {
            val rope = label(
                if (store.propRope(spec.id).isFile) getString(R.string.prop_rope_edit)
                else getString(R.string.prop_rope_draw),
                11f, MUTED,
            )
            rope.setPadding(dp(8), dp(6), dp(8), dp(6))
            rope.setOnClickListener {
                askPropRope(spec.id, spec.name) { buildPropList() }
            }
            card.addView(rope)
        }

        // The way in from this end. 道具管理 is where a prop is made, and "does this one use
        // logic of its own" is a question that comes up here rather than three screens away.
        val logic = label(getString(R.string.menu_logic), 11f, MUTED)
        logic.setPadding(dp(8), dp(6), dp(8), dp(6))
        logic.setOnClickListener {
            openLogic(Subjects.prop(spec.id))
            show(Pane.PET_LOGIC)
        }
        card.addView(logic)

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
        val (ropeRow, ropeOf) = stepperRow(
            getString(R.string.prop_rope), existing?.ropeLength ?: 0f, 100f, 0f, 3000f,
        ) { if (it <= 0f) getString(R.string.prop_rope_auto) else it.toInt().toString() }
        val (elasticRow, elasticOf) = stepperRow(
            getString(R.string.prop_elastic), existing?.elastic ?: 1f, 0.2f, 0.2f, 3f,
        ) { "%.1f×".format(it) }

        // 拖尾：拖着一个「持续使用」的道具走，身后留下的图案。画一个就是画一笔 ——
        // 用户画的是**一小段图案**，它沿着路径重复贴、渐淡。
        val trailLabel = label(
            if (existing?.let { store.propTrail(it.id).isFile } == true) {
                getString(R.string.prop_trail_edit)
            } else {
                getString(R.string.prop_trail_draw)
            },
            12f, INK, top = 10,
        )
        trailLabel.setPadding(dp(2), dp(8), dp(2), dp(8))
        trailLabel.setOnClickListener {
            val id = existing?.id
            if (id == null) {
                Toast.makeText(this, R.string.prop_trail_first, Toast.LENGTH_SHORT).show()
            } else {
                askPropTrail(id, existing.name)
            }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        box.addView(nameInput)
        box.addView(label(getString(R.string.prop_kind), 11f, MUTED, top = 10, bottom = 6))

        // Wrapped by hand, three to a row: the list outgrew one row when the anchor family
        // arrived, and a chip that is off the edge of the dialog is a kind nobody can pick.
        val chipViews = mutableListOf<TextView>()
        val hintView = label(PropKind.of(kind).hint, 10f, MUTED, top = 6, bottom = 4)
        for (row in PropKind.values().toList().chunked(3)) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (option in row) {
                val chip = label(option.label, 11f, INK)
                chip.setPadding(dp(9), dp(7), dp(9), dp(7))
                chip.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(5) }
                chip.setOnClickListener {
                    kind = option.id
                    hintView.text = option.hint
                    // The rope length only means anything for a stake, and a row that is
                    // always there but usually ignored is a row people stop reading.
                    // 绳长和弹性只对绳子有意义: a row that is always there but usually ignored
                    // is a row people stop reading.
                    val rope = option == PropKind.ROPE
                    ropeRow.visibility = if (rope) View.VISIBLE else View.GONE
                    elasticRow.visibility = if (rope) View.VISIBLE else View.GONE
                    paintChips(chipViews, PropKind.values().map { it.id }, { kind })
                }
                chipViews.add(chip)
                chips.addView(chip)
            }
            box.addView(chips)
        }
        box.addView(hintView)
        box.addView(radiusRow)
        box.addView(forceRow)
        box.addView(ropeRow)
        box.addView(elasticRow)
        box.addView(label(getString(R.string.prop_elastic_hint), 10f, MUTED, top = 2))
        // Moved up next to the kind for the same reason: at the bottom of a form it was off the
        // screen, and a New prop has no id yet -- the row in 道具管理 is the one that always works.
        box.addView(trailLabel)
        box.addView(label(getString(R.string.prop_trail_hint), 10f, MUTED, bottom = 4))

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.prop_new else R.string.prop_name)
            .setView(scrolling(box))
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
                        ropeLength = ropeOf(),
                        elastic = elasticOf(),
                    )
                )
                store.saveProps(props)
                buildPropList()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
        paintChips(chipViews, PropKind.values().map { it.id }, { kind })
        val ropeKind = kind == PropKind.ROPE.id
        ropeRow.visibility = if (ropeKind) View.VISIBLE else View.GONE
        elasticRow.visibility = if (ropeKind) View.VISIBLE else View.GONE
    }

    /**
     * 画拖尾: the pattern a 持续使用 prop drags behind it.
     *
     * The same board as a particle's shape, for the same reason -- a brush mark can only be
     * reproduced by its own pixels -- and stored beside the prop's own logic.json, because a
     * prop's rules and a prop's trail are both "what this thing does".
     */
    private fun askPropTrail(id: String, name: String, after: () -> Unit = {}) {
        val board = PaintBoardView(this)
        board.colour = 0x992B2A33.toInt()
        board.brushWidth = 8f
        board.load(store.propTrail(id))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        board.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220),
        )
        box.addView(board)
        box.addView(label(getString(R.string.prop_trail_board_hint), 10f, MUTED, top = 8, bottom = 6))
        paintPaletteRow(board, 0xFF8A5A2B.toInt(), box)

        val widthRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var width = 8f
        val widthViews = mutableListOf<TextView>()
        for ((w, text) in listOf("3" to getString(R.string.paint_thin),
                                 "8" to getString(R.string.paint_mid),
                                 "16" to getString(R.string.paint_fat))) {
            val chip = label(text, 11f, INK)
            chip.setPadding(dp(11), dp(7), dp(11), dp(7))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                width = w.toFloat()
                board.brushWidth = width
                paintChips(widthViews, listOf("3", "8", "16"), { width.toInt().toString() })
            }
            widthViews.add(chip)
            widthRow.addView(chip)
        }
        box.addView(widthRow)
        paintChips(widthViews, listOf("3", "8", "16"), { "8" })

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val eraserChip = label(getString(R.string.paint_eraser), 11f, INK)
        eraserChip.setPadding(dp(11), dp(7), dp(11), dp(7))
        eraserChip.setOnClickListener {
            board.erasing = !board.erasing
            eraserChip.alpha = if (board.erasing) 0.45f else 1f
        }
        tools.addView(eraserChip)
        tools.addView(small(getString(R.string.paint_undo)) { board.undo() })
        tools.addView(small(getString(R.string.paint_clear)) { board.clear() })
        tools.addView(small(getString(R.string.paint_remove)) {
            val file = store.propTrail(id)
            if (!file.exists() || file.delete()) {
                sandboxView.refreshTrails()
                after()
                Toast.makeText(this, R.string.prop_trail_removed, Toast.LENGTH_SHORT).show()
            }
        })
        box.addView(tools)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.prop_trail_title) + " · " + name)
            .setView(scrolling(box))
            .setPositiveButton(R.string.depth_save) { _, _ ->
                // 空画板 = 没有拖尾，和粒子那边同一个道理：存一张全透明的图等于把这一笔
                // 变成一个什么都不留下的东西，而那不是"清空画布"的意思。
                val saved = if (board.isEmpty) {
                    val file = store.propTrail(id)
                    !file.exists() || file.delete()
                } else {
                    board.saveTo(store.propTrail(id))
                }
                if (saved) {
                    sandboxView.refreshTrails()
                    after()
                } else {
                    Toast.makeText(this, R.string.paint_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    /**
     * 画绳子: one short piece of rope, drawn by hand.
     *
     * A piece, not a whole rope: the app lays this drawing along the simulated chain one tile
     * per link, so the same picture makes a rope of any length. Asked for here rather than in a
     * sandbox dialog because it is a property of the PROP -- the same rope prop always looks
     * like the same rope.
     */
    private fun askPropRope(id: String, name: String, after: () -> Unit = {}) {
        val board = PaintBoardView(this)
        board.colour = 0xFF8A6B4A.toInt()
        board.brushWidth = 12f
        board.load(store.propRope(id))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        board.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220),
        )
        box.addView(board)
        box.addView(label(getString(R.string.prop_rope_hint), 10f, MUTED, top = 8, bottom = 6))
        paintPaletteRow(board, 0xFF8A6B4A.toInt(), box)

        val widthRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var width = 12f
        val widthViews = mutableListOf<TextView>()
        for ((w, text) in listOf("4" to getString(R.string.paint_thin),
                                 "12" to getString(R.string.paint_mid),
                                 "26" to getString(R.string.paint_fat))) {
            val chip = label(text, 11f, INK)
            chip.setPadding(dp(11), dp(7), dp(11), dp(7))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                width = w.toFloat()
                board.brushWidth = width
                paintChips(widthViews, listOf("4", "12", "26"), { width.toInt().toString() })
            }
            widthViews.add(chip)
            widthRow.addView(chip)
        }
        box.addView(widthRow)
        paintChips(widthViews, listOf("4", "12", "26"), { "12" })

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val eraserChip = label(getString(R.string.paint_eraser), 11f, INK)
        eraserChip.setPadding(dp(11), dp(7), dp(11), dp(7))
        eraserChip.setOnClickListener {
            board.erasing = !board.erasing
            eraserChip.alpha = if (board.erasing) 0.45f else 1f
        }
        tools.addView(eraserChip)
        tools.addView(small(getString(R.string.paint_undo)) { board.undo() })
        tools.addView(small(getString(R.string.paint_clear)) { board.clear() })
        tools.addView(small(getString(R.string.paint_remove)) {
            val file = store.propRope(id)
            if (!file.exists() || file.delete()) {
                sandboxView.refreshRopes()
                after()
                Toast.makeText(this, R.string.prop_rope_removed, Toast.LENGTH_SHORT).show()
            }
        })
        box.addView(tools)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.prop_rope_title) + " · " + name)
            .setView(scrolling(box))
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val saved = if (board.isEmpty) {
                    val file = store.propRope(id)
                    !file.exists() || file.delete()
                } else {
                    board.saveTo(store.propRope(id))
                }
                if (saved) {
                    sandboxView.refreshRopes()
                    after()
                } else {
                    Toast.makeText(this, R.string.paint_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
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
        openLogic(logicSubject)
    }

    /**
     * Load one subject's logic into the editor.
     *
     * The editor is one editor and the subject is a chip, because the rules are the same
     * rules: a prop that burns down and a character that bleeds are written in the same
     * three boxes, and giving them two screens would mean maintaining two of everything and
     * learning two of everything.
     */
    private fun openLogic(subject: String) {
        logicSubject = subject
        val folder = summoned
        val spec = when {
            // An object's logic does not need a character to be summoned: it lives in the
            // shared props directory, and 道具管理 is a perfectly good place to open it from.
            subject != Subjects.PET -> store.loadObjectLogic(folder)[subject]
                ?: LogicSpec.parseObject(LogicSpec.OBJECT_DEFAULT)
            folder == null -> LogicSpec(emptyList(), emptyList())   // nothing to edit yet
            else -> store.loadLogic(folder.id)
        }
        logicStats = spec.stats.toMutableList()
        logicRules = spec.rules.toMutableList()
        logicStates = spec.states.toMutableList()
        // Liquids and particles are DECLARED by the character; a prop's file and a part's file
        // have none of their own. Their definitions come from the character even while another
        // subject's rules are being written -- otherwise the 喷液体 and 喷粒子 pickers are empty
        // exactly where they are wanted, and "a rule on the hand that sprays sweat" cannot be
        // written at all. The RULES still belong to the subject; only the vocabulary is shared.
        val declared = folder?.let { store.loadLogic(it.id) }
        logicLiquids = (declared?.liquids ?: spec.liquids).toMutableList()
        logicParticles = (declared?.particles ?: spec.particles).toMutableList()
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
        val spec = LogicSpec(
            logicStats.toList(), logicRules.toList(),
            logicStates.toList(), logicLiquids.toList(), logicParticles.toList(),
        )
        if (logicSubject == Subjects.PET) {
            store.saveLogic(folder.id, spec)
        } else {
            // The subject's own file keeps its own vocabulary: the editor was showing the
            // character's liquids and particles so the pickers had something in them, and
            // copying those into a prop's file would be the app making declarations nobody
            // asked for. Its rules and stats are what this file is for.
            val own = store.loadObjectLogic(folder)[logicSubject]
            store.saveObjectLogic(
                folder, logicSubject,
                LogicSpec(
                    stats = spec.stats, rules = spec.rules, states = spec.states,
                    liquids = own?.liquids ?: emptyList(),
                    particles = own?.particles ?: emptyList(),
                ),
            )
        }
    }

    /**
     * Hand the graph its boxes, and the bar its buttons.
     *
     * Every box is text plus the indices needed to find the thing again when it is tapped;
     * the engine's model is untouched. A rule with no conditions gets a "总是" box rather
     * than a gap, because a chain with a hole in it reads as a mistake.
     */
    private fun buildLogicPane() {
        val graph = mutableListOf<List<LogicGraphView.Node>>()
        val counts = mutableListOf<Int>()
        for ((ri, rule) in logicRules.withIndex()) {
            val row = mutableListOf<LogicGraphView.Node>()
            val where = if (rule.part.isEmpty()) "" else " · " + partText(rule.part)
            val who = if (rule.about.isEmpty()) "" else " · " + aboutText(rule.about)
            val with = if (rule.branches.isEmpty()) "" else " · 并行 " + (rule.branches.size + 1) + " 支"
            row.add(
                LogicGraphView.Node(
                    LogicGraphView.Node.WHEN,
                    listOf(EventType.of(rule.on).label + who + with + where), ri,
                )
            )

            // 如果 is a list of MODULES: each clause is a box, and the connector between two
            // of them is a box of its own that can be flipped. A rule with no clause at all
            // still reads as one, and says so.
            if (rule.conditions.isEmpty()) {
                row.add(LogicGraphView.Node(LogicGraphView.Node.IF, listOf("总是"), -1))
            } else {
                for ((ci, c) in rule.conditions.withIndex()) {
                    if (ci > 0) {
                        row.add(
                            LogicGraphView.Node(
                                LogicGraphView.Node.CONNECTOR, listOf(Joins.label(c.join)), ci,
                            )
                        )
                    }
                    row.add(LogicGraphView.Node(LogicGraphView.Node.IF, listOf(conditionText(c)), ci))
                }
            }
            row.add(
                LogicGraphView.Node(
                    LogicGraphView.Node.ADD, listOf(getString(R.string.logic_module_if)),
                    LogicGraphView.Node.ADD_CONDITION,
                )
            )

            for ((ai, a) in rule.actions.withIndex()) {
                row.add(LogicGraphView.Node(LogicGraphView.Node.THEN, listOf(actionText(a)), ai))
            }
            row.add(
                LogicGraphView.Node(
                    LogicGraphView.Node.ADD, listOf(getString(R.string.logic_module_action)),
                LogicGraphView.Node.ADD_ACTION,
                )
            )

            // The else branch continues the same line rather than branching geometrically.
            // A row that reads left to right is still unambiguous, and a real fork would
            // need a layout that reserves space for the shorter side -- which is a lot of
            // machinery for a box that says 否则 on it.
            if (rule.elseActions.isEmpty()) {
                row.add(
                    LogicGraphView.Node(
                        LogicGraphView.Node.ADD, listOf(getString(R.string.logic_module_else)),
                        LogicGraphView.Node.ADD_ELSE,
                    )
                )
            } else {
                row.add(LogicGraphView.Node(LogicGraphView.Node.ELSE, listOf("都不成立时"), -1))
                for ((ai, a) in rule.elseActions.withIndex()) {
                    row.add(LogicGraphView.Node(LogicGraphView.Node.ELSE, listOf(actionText(a)), ai))
                }
                row.add(
                    LogicGraphView.Node(
                        LogicGraphView.Node.ADD, listOf(getString(R.string.logic_module_action)),
                        LogicGraphView.Node.ADD_ELSE,
                    )
                )
            }
            graph.add(row)
            counts.add(rule.branches.size)
            // One row per 并行分支, immediately under the rule it forks from: the graph draws the
            // fork downwards, so "below" is what the picture means.
            for ((bi, branch) in rule.branches.withIndex()) {
                val bRow = mutableListOf<LogicGraphView.Node>()
                // A branch with a 当 of its own gets a WHEN box like any other detector; one
                // that hangs off the group's shows the fork's own label instead.
                if (branch.ownDetector) {
                    val where = if (branch.part.isEmpty()) "" else " · " + partText(branch.part)
                    bRow.add(
                        LogicGraphView.Node(
                            LogicGraphView.Node.WHEN,
                            listOf(EventType.of(branch.on).label + where), -1,
                        )
                    )
                }
                bRow.add(
                    LogicGraphView.Node(
                        LogicGraphView.Node.ELSE,
                        listOf(getString(R.string.logic_branch) + " " + (bi + 2), "都会响"),
                        -1,
                    )
                )
                for ((ai, a) in branch.actions.withIndex()) {
                    bRow.add(
                        LogicGraphView.Node(LogicGraphView.Node.THEN, listOf(actionText(a)), ai)
                    )
                }
                bRow.add(
                    LogicGraphView.Node(
                        LogicGraphView.Node.ADD, listOf(getString(R.string.logic_module_action)),
                        LogicGraphView.Node.ADD_ELSE,
                    )
                )
                graph.add(bRow)
                counts.add(0)
            }
        }
        // 每一行的随机组：图用它把同组的行括起来（并行分支）。
        // How many branch rows follow each rule row, so the graph can draw the fork: one
        // detector, arrows going down and splitting to each executor.
        logicGraph.setRules(graph, counts)
        buildLiquidBar()

        logicBar.removeAllViews()
        logicFolderBar.removeAllViews()

        // 第一层：四个文件夹。点一个就换一层，并把打开的主体收回那一类里。
        for (folder in LogicFolder.values()) {
            val c = chip(folderLabel(folder), folder == logicFolder, 13f)
            c.setOnClickListener {
                if (folder != logicFolder) {
                    logicFolder = folder
                    settleFolder()
                    openLogic(logicSubject)
                }
            }
            logicFolderBar.addView(c)
        }

        // 第二层：这一类里具体写给谁。角色逻辑有"全局 / 局部"两组，因为整具身体和
        // 单独一根骨头是两种不同的东西；另外三类各自只有一组。
        val groups = folderSubjects(logicFolder)
        val anySubject = groups.any { it.second.isNotEmpty() }
        for ((groupLabel, subjects) in groups) {
            if (subjects.isEmpty()) continue
            // A group label only earns its space when there is more than one group to tell
            // apart: on 道具逻辑 a lone "道具" in front of a row of props is noise.
            if (groupLabel.isNotEmpty() && groups.count { it.second.isNotEmpty() } > 1) {
                logicBar.addView(label(groupLabel, 11f, MUTED))
            }
            for ((id, text) in subjects) {
                val c = chip(text, id == logicSubject)
                c.setOnClickListener { openLogic(id) }
                logicBar.addView(c)
            }
        }
        if (!anySubject) logicBar.addView(label(getString(R.string.logic_folder_empty), 11f, MUTED))

        // The spill bar belongs to the liquids and is shown with them. It used to sit under
        // every folder, which is the pile this screen was rearranged to get rid of: a row of
        // liquid chips is not something to read while writing a rule about a spark.
        liquidBar.visibility = if (logicFolder == LogicFolder.LIQUIDS) View.VISIBLE else View.GONE

        logicBar.addView(small(getString(R.string.logic_add_rule)) {
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
        })
        logicBar.addView(small(getString(R.string.logic_stats)) { showStatsDialog() })
        logicBar.addView(small(getString(R.string.logic_states)) { showStatesDialog() })
        logicBar.addView(small(getString(R.string.logic_liquids)) {
            openLiquids()
            show(Pane.LIQUIDS)
        })
        logicBar.addView(small(getString(R.string.logic_reset)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.logic_reset)
                .setMessage(R.string.logic_reset_confirm)
                .setPositiveButton(R.string.depth_remove) { _, _ ->
                    val folder = summoned
                    if (folder != null) {
                        store.forgetLogic(folder.id)
                        openLogic()
                    }
                }
                .setNegativeButton(R.string.depth_cancel, null)
                .show()
        })
    }

    /**
     * The liquid bar: one chip per liquid, under the rule bar.
     *
     * Tapping a chip spills some of it on the bench, because "does blood read as blood at
     * this size" is a question you answer by looking. The 液体 button above is where the
     * colours and the names are edited.
     */
    private fun buildLiquidBar() {
        liquidBar.removeAllViews()
        for (liquid in logicLiquids) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = getDrawable(R.drawable.menu_item_idle)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                isFocusable = true
            }
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
            chip.addView(swatch(liquid.colour, 12))
            chip.addView(label(liquid.name, 12f, INK))
            chip.setOnClickListener {
                sandboxView.spill(liquid.id)
                Toast.makeText(this, getString(R.string.liquid_spilled, liquid.name), Toast.LENGTH_SHORT).show()
            }
            chip.setOnLongClickListener {
                openLiquids()
                show(Pane.LIQUIDS)
                true
            }
            liquidBar.addView(chip)
        }
        val drops = sandboxView.dropCount()
        liquidBar.addView(
            label(
                if (drops == 0) getString(R.string.liquid_none)
                else getString(R.string.liquid_drops, drops),
                10f, MUTED,
            )
        )
        liquidBar.addView(small(getString(R.string.logic_add_liquid)) { askEditLiquid(null) { buildLogicPane() } })
    }

    // subjectOptions() used to live here: one flat list of every subject, which is what the
    // bar drew. It is folderSubjects(folder) now, because a single row cannot say that
    // hand_L is a part of the character and 火花 is a kind of particle -- and the one thing
    // this screen is about is which of those a rule is written on.

    /**
     * The subjects inside one folder, as (group label, subjects).
     *
     * Groups rather than one row, because under 角色逻辑 there are two levels and they are not
     * the same kind of thing: 全局 is the figure itself and 局部 is one bone. Both are "the
     * character" and a rule about one is not a rule about the other, so the row says which is
     * which instead of leaving it to the chip's colour.
     *
     * The liquids and the particles come from the CHARACTER's file even while a prop's logic is
     * being edited: both are declared by the character, and a prop whose own file has none would
     * otherwise hide every liquid and every particle from this screen and make them unreachable.
     */
    private fun folderSubjects(folder: LogicFolder): List<Pair<String, List<Pair<String, String>>>> =
        when (folder) {
            LogicFolder.CHARACTER -> listOf(
                getString(R.string.logic_scope_global) to
                    listOf(Subjects.PET to getString(R.string.logic_subject_pet)),
                getString(R.string.logic_scope_local) to
                    (summoned?.let { boneNames(it) } ?: emptyList()).map {
                        Subjects.part(it) to getString(R.string.logic_subject_part, boneLabel(it))
                    } +
                    (summoned?.let { nodeNames(it) } ?: emptyList()).map {
                        Subjects.part(it) to getString(R.string.logic_subject_part, it)
                    },
            )
            LogicFolder.PROPS -> listOf(
                "" to props.map {
                    Subjects.prop(it.id) to getString(R.string.logic_subject_prop, it.name)
                },
            )
            LogicFolder.LIQUIDS -> listOf(
                "" to (summoned?.let { store.loadLogic(it.id).liquids } ?: emptyList()).map {
                    Subjects.liquid(it.id) to getString(R.string.logic_subject_liquid, it.name)
                },
            )
            LogicFolder.PARTICLES -> listOf(
                "" to (summoned?.let { store.loadLogic(it.id).particles } ?: emptyList()).map {
                    Subjects.particle(it.id) to getString(R.string.logic_subject_particle, it.name)
                },
            )
        }

    /** Every subject in a folder, whichever group it is in. */
    private fun folderSubjectIds(folder: LogicFolder): List<String> =
        folderSubjects(folder).flatMap { (_, subjects) -> subjects.map { it.first } }

    /**
     * Keep the open subject inside the open folder.
     *
     * Switching folders has to land somewhere, and landing on the subject from the folder you
     * just left would leave the graph showing rules that the row above no longer offers -- the
     * screen would say "粒子逻辑" and be editing the hand.
     */
    private fun settleFolder() {
        val here = folderSubjectIds(logicFolder)
        if (logicSubject !in here) logicSubject = here.firstOrNull() ?: Subjects.PET
    }

    private fun folderLabel(folder: LogicFolder): String = getString(
        when (folder) {
            LogicFolder.CHARACTER -> R.string.logic_folder_character
            LogicFolder.PROPS -> R.string.logic_folder_props
            LogicFolder.LIQUIDS -> R.string.logic_folder_liquids
            LogicFolder.PARTICLES -> R.string.logic_folder_particles
        }
    )

    /** A chip: one tappable piece of a bar, lit when it is the one that is open. */
    private fun chip(text: String, lit: Boolean, size: Float = 12f): TextView {
        val v = label(text, size, if (lit) INK else MUTED)
        v.background = getDrawable(
            if (lit) R.drawable.menu_item_selected else R.drawable.menu_item_idle
        )
        v.setPadding(dp(10), dp(6), dp(10), dp(6))
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(5) }
        return v
    }

    /** The 当 box: everything about the rule that is not a node of its own. */
    /** What a prop or a particle is called, for the rows that have to say one out loud. */
    private fun aboutText(id: String): String =
        props.firstOrNull { it.id == id }?.name
            ?: logicParticles.firstOrNull { it.id == id }?.name
            ?: id

    /**
     * 关于哪个：任何东西，或者某一件道具 / 某一种粒子。
     *
     * One picker for both registries rather than two, because a rule's event carries one or
     * the other and never both -- 被道具碰到 names a prop, a particle's 落地 names a kind.
     */
    private fun askRuleAbout(index: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val options = mutableListOf("" to getString(R.string.logic_about_any))
        options.addAll(props.map { it.id to getString(R.string.logic_subject_prop, it.name) })
        options.addAll(
            logicParticles.map { it.id to getString(R.string.logic_subject_particle, it.name) }
        )
        pickList(
            getString(R.string.logic_about),
            options,
            getString(R.string.logic_about_hint),
            rule.about,
        ) { id ->
            logicRules[index] = rule.copy(about = id)
            saveLogic()
            buildLogicPane()
            true
        }
    }

    private fun askRuleSettings(index: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.logic_rule_settings) + " " + (index + 1))
            .setView(scrolling(box))
            .setNegativeButton(R.string.action_close, null)
            .create()

        fun row(text: String, onClick: () -> Unit) {
            val view = label(text, 13f, INK)
            view.setPadding(dp(12), dp(11), dp(12), dp(11))
            view.background = getDrawable(R.drawable.menu_item_idle)
            view.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
            view.setOnClickListener {
                dialog.dismiss()
                onClick()
            }
            box.addView(view)
        }

        row(getString(R.string.logic_when) + EventType.of(rule.on).label) { askRuleEvent(index) }
        if (rule.on == EventType.EMIT.id) {
            row(
                getString(R.string.logic_pick_signal) + "：" +
                    if (rule.part.isEmpty()) getString(R.string.logic_any_signal) else rule.part
            ) { askRuleSignal(index) }
        } else {
            row(
                getString(R.string.logic_pick_part) + "：" +
                    if (rule.part.isEmpty()) getString(R.string.logic_pick_any_part)
                    else partText(rule.part)
            ) { askRulePart(index) }
        }
        // 「事件侦测器：应能选取主体」——事件里一直带着是哪个道具/哪种粒子（日志现在也写出来了），
        // 这一行是让规则能**点名**它。只在真能带着主语的几种事件上出现，别的时候是噪音。
        if (rule.on == EventType.PROP_HIT.id || rule.on == EventType.IMPACT.id ||
            rule.on == EventType.LANDED.id
        ) {
            row(
                getString(R.string.logic_about) + "：" +
                    if (rule.about.isEmpty()) getString(R.string.logic_about_any)
                    else aboutText(rule.about)
            ) { askRuleAbout(index) }
        }
        // 「并行逻辑也有完整的侦测器和执行器」: this rule's own 当 is the group's detector, its own
        // 就 is the first executor, and every branch below is another executor forked off the
        // same detector -- all of them run. See RuleSpec.branches.
        row(
            getString(R.string.logic_branch) + "：" + (rule.branches.size + 1) + " 支" +
                if (rule.branches.isEmpty()) " · " + getString(R.string.logic_branch_none) else ""
        ) { addBranch(index) }
        for ((bi, b) in rule.branches.withIndex()) {
            row(
                getString(R.string.logic_branch) + " " + (bi + 2) + " · " +
                    (if (b.ownDetector) getString(R.string.logic_when) + EventType.of(b.on).label
                    else getString(R.string.logic_branch_same_when)) +
                    " · " + b.actions.size + " 个动作"
            ) { askBranch(index, bi) }
        }
        if (rule.branches.isNotEmpty()) {
            row(getString(R.string.logic_branch_remove)) {
                removeBranch(index, rule.branches.size - 1)
            }
        }
        row(getString(R.string.logic_cooldown, trim(rule.cooldown))) { askCooldown(index) }
        row((if (rule.once) "✓ " else "") + getString(R.string.logic_once)) {
            logicRules[index] = rule.copy(once = !rule.once)
            saveLogic()
            buildLogicPane()
        }
        if (rule.elseActions.isEmpty()) {
            row(getString(R.string.logic_add_else)) {
                logicRules[index] = rule.copy(
                    elseActions = listOf(ActionSpec("say", text = "……")),
                )
                saveLogic()
                buildLogicPane()
            }
        } else {
            row(getString(R.string.logic_remove_else)) {
                logicRules[index] = rule.copy(elseActions = emptyList())
                saveLogic()
                buildLogicPane()
            }
        }
        row(getString(R.string.logic_delete_rule)) {
            logicRules.removeAt(index)
            saveLogic()
            buildLogicPane()
        }
        dialog.show()
    }

    private fun showStatsDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.logic_stats)
            .setView(scrolling(box))
            .setNegativeButton(R.string.action_close, null)
            .create()
        fun fill() {
            box.removeAllViews()
            box.addView(label(getString(R.string.logic_stats_hint), 10f, MUTED, bottom = 8))
            for (stat in logicStats.toList()) {
                box.addView(statRow(stat) { dialog.dismiss(); showStatsDialog() })
            }
            val add = label(getString(R.string.logic_add_stat), 12f, INK)
            add.setPadding(dp(12), dp(9), dp(12), dp(9))
            add.background = getDrawable(R.drawable.menu_item_selected)
            add.setOnClickListener {
                dialog.dismiss()
                askEditStat(null) { showStatsDialog() }
            }
            box.addView(add)
        }
        fill()
        dialog.show()
    }

    private fun showStatesDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.logic_states)
            .setView(scrolling(box))
            .setNegativeButton(R.string.action_close, null)
            .create()
        fun fill() {
            box.removeAllViews()
            box.addView(label(getString(R.string.logic_states_hint), 10f, MUTED, bottom = 8))
            for (state in logicStates.toList()) {
                box.addView(stateRow(state) { dialog.dismiss(); showStatesDialog() })
            }
            val add = label(getString(R.string.logic_add_state), 12f, INK)
            add.setPadding(dp(12), dp(9), dp(12), dp(9))
            add.background = getDrawable(R.drawable.menu_item_selected)
            add.setOnClickListener {
                dialog.dismiss()
                askEditState(null) { showStatesDialog() }
            }
            box.addView(add)
        }
        fill()
        dialog.show()
    }


    /** A round swatch, so a liquid can be recognised before its name is read. */
    private fun swatch(colour: Int, size: Int): View {
        val view = View(this)
        val shape = android.graphics.drawable.GradientDrawable().apply {
            setColor(colour)
            this.shape = android.graphics.drawable.GradientDrawable.OVAL
        }
        view.background = shape
        view.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
            marginEnd = dp(8)
        }
        return view
    }

    /**
     * 全局设置: the switches that belong to the app.
     *
     * Written back the moment anything is touched, like every other editor here, and handed
     * straight to the bench as well so a change is visible without leaving the screen. The file
     * is small and flat on purpose: anything that belongs to a pet, a prop, a liquid or a rule
     * already has a home of its own, and a second home for it here would be two answers to one
     * question.
     */
    private fun buildSettingsPane() {
        settingsList.removeAllViews()
        settingsList.addView(label(getString(R.string.menu_settings), 17f, INK, bottom = 4))
        settingsList.addView(label(getString(R.string.settings_hint), 11f, MUTED, bottom = 12))

        fun put(next: Settings) {
            settings = next
            settingsStore.save(next)
            sandboxView.applySettings(next)
            buildSettingsPane()
        }

        fun section(title: Int, hint: Int) {
            settingsList.addView(label(getString(title), 13f, INK, top = 12, bottom = 4))
            settingsList.addView(label(getString(hint), 10f, MUTED, bottom = 6))
        }

        fun toggle(title: Int, value: Boolean, onChange: (Boolean) -> Unit) {
            val row = label(
                (if (value) "✓  " else "○  ") + getString(title),
                13f, if (value) INK else MUTED,
            )
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            row.background = getDrawable(
                if (value) R.drawable.menu_item_selected else R.drawable.menu_item_idle
            )
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(5) }
            row.setOnClickListener { onChange(!value) }
            settingsList.addView(row)
        }

        // Gravity. Five stops rather than a slider: it is a dial you set once and then judge by
        // watching, and a slider invites fiddling with a number nobody can read off a phone.
        section(R.string.settings_gravity, R.string.settings_gravity_hint)
        val gravityChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val gravityViews = mutableListOf<TextView>()
        val gravities = listOf(0.5f to "飘", 0.75f to "轻", 1f to "标准", 1.5f to "重", 2f to "很重")
        for ((value, name) in gravities) {
            val chip = label(name, 12f, INK)
            chip.setPadding(dp(12), dp(8), dp(12), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener { put(settings.copy(gravityScale = value)) }
            gravityViews.add(chip)
            gravityChips.addView(chip)
        }
        settingsList.addView(gravityChips)
        settingsList.addView(
            label(
                getString(R.string.settings_current, "%.2f".format(settings.gravityScale)),
                10f, MUTED, top = 6, bottom = 2,
            )
        )

        section(R.string.settings_stiffness, R.string.settings_stiffness_hint)
        val stiffChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val stiffViews = mutableListOf<TextView>()
        for ((i, value) in STIFFNESS_VALUES.withIndex()) {
            val chip = label(STIFFNESS_LABELS[i], 12f, INK)
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener { put(settings.copy(defaultStiffness = value)) }
            stiffViews.add(chip)
            stiffChips.addView(chip)
        }
        settingsList.addView(stiffChips)

        section(R.string.settings_view, R.string.settings_view_hint)
        toggle(R.string.settings_show_grid, settings.showGrid) { put(settings.copy(showGrid = it)) }
        toggle(R.string.settings_show_ground, settings.showGround) { put(settings.copy(showGround = it)) }
        toggle(R.string.settings_show_balance, settings.showBalance) { put(settings.copy(showBalance = it)) }
        toggle(R.string.settings_show_bones, settings.showBones) { put(settings.copy(showBones = it)) }
        toggle(R.string.settings_follow, settings.followPet) { put(settings.copy(followPet = it)) }

        section(R.string.settings_effects, R.string.settings_effects_hint)
        toggle(R.string.settings_particles, settings.particles) { put(settings.copy(particles = it)) }
        toggle(R.string.settings_liquid, settings.liquid) { put(settings.copy(liquid = it)) }

        val reset = label(getString(R.string.settings_reset), 12f, MUTED)
        reset.setPadding(dp(12), dp(10), dp(12), dp(10))
        reset.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) }
        reset.setOnClickListener {
            settingsStore.forget()
            settings = Settings.DEFAULT
            sandboxView.applySettings(settings)
            Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
            buildSettingsPane()
        }
        settingsList.addView(reset)

        // The chosen chips, painted last so every listener above can simply rebuild the pane.
        paintChips(gravityViews, gravities.map { it.first.toString() }, {
            gravities.firstOrNull { g -> kotlin.math.abs(g.first - settings.gravityScale) < 0.01f }
                ?.first?.toString() ?: ""
        })
        paintChips(stiffViews, STIFFNESS_VALUES.map { it.toString() }, {
            STIFFNESS_VALUES.firstOrNull { v -> kotlin.math.abs(v - settings.defaultStiffness) < 0.01f }
                ?.toString() ?: ""
        })
    }

    /**
     * 液体管理: the liquids themselves, with a tap that pours one out.
     *
     * This used to be a dialog behind a button inside 逻辑管理, which is where a liquid's
     * RULES belong and not where a liquid belongs: a colour and a viscosity have nothing to
     * do with the rule that spills them, and a dialog that only edits definitions gives
     * somebody no way to find out what "黏度 0.8" looks like. So it is its own door, and every
     * row can spill itself onto the bench.
     */
    private fun openLiquids() {
        val folder = summoned
        logicLiquids = if (folder == null) {
            mutableListOf()
        } else {
            store.loadLogic(folder.id).liquids.toMutableList()
        }
        sandboxView.setLiquids(logicLiquids.toList())
        buildLiquidList()
    }

    private fun buildLiquidList() {
        liquidList.removeAllViews()
        liquidList.addView(label(getString(R.string.menu_liquids), 17f, INK, bottom = 4))
        liquidList.addView(label(getString(R.string.liquid_subtitle), 11f, MUTED, bottom = 4))
        liquidList.addView(label(getString(R.string.logic_liquids_hint), 10f, MUTED, bottom = 10))

        if (summoned == null) {
            liquidList.addView(label(getString(R.string.liquid_need_pet), 12f, MUTED))
            return
        }

        val drops = sandboxView.dropCount()
        val bench = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        bench.addView(
            label(
                if (drops == 0) getString(R.string.liquid_none_on_bench)
                else getString(R.string.liquid_on_bench, drops),
                11f, MUTED,
            )
        )
        if (drops > 0) {
            val clear = small(getString(R.string.liquid_clear)) {
                sandboxView.clearLiquid()
                Toast.makeText(this, R.string.liquid_cleared, Toast.LENGTH_SHORT).show()
                buildLiquidList()
            }
            bench.addView(clear)
        }
        liquidList.addView(bench)

        for (liquid in logicLiquids.toList()) {
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
            ).apply { bottomMargin = dp(6) }
            row.addView(swatch(liquid.colour, 16))

            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
            text.addView(label(liquid.name, 14f, INK))
            text.addView(
                label(
                    "代号 " + liquid.id + " · 黏度 " + "%.2f".format(liquid.viscosity) +
                        " · " + (if (liquid.viscosity >= 0.6f) "抱团" else if (liquid.viscosity <= 0.2f) "摊开" else "半稠"),
                    10f, MUTED,
                )
            )
            row.addView(text)
            row.setOnClickListener { askEditLiquid(liquid) { refreshLiquids() } }

            val pour = label(getString(R.string.liquid_spill_now), 11f, INK)
            pour.setPadding(dp(8), dp(6), dp(8), dp(6))
            pour.setOnClickListener {
                sandboxView.spill(liquid.id)
                Toast.makeText(this, getString(R.string.liquid_spilled, liquid.name), Toast.LENGTH_SHORT).show()
                buildLiquidList()
            }
            row.addView(pour)

            val logic = label(getString(R.string.menu_logic), 11f, MUTED)
            logic.setPadding(dp(8), dp(6), dp(8), dp(6))
            logic.setOnClickListener {
                openLogic(Subjects.liquid(liquid.id))
                show(Pane.PET_LOGIC)
            }
            row.addView(logic)

            val remove = label(getString(R.string.action_delete), 11f, MUTED)
            remove.setPadding(dp(8), dp(6), dp(8), dp(6))
            remove.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.action_delete) + " · " + liquid.name)
                    .setMessage(getString(R.string.liquid_delete_confirm, liquid.name))
                    .setPositiveButton(R.string.depth_remove) { _, _ ->
                        logicLiquids.removeAll { it.id == liquid.id }
                        saveLiquids()
                        refreshLiquids()
                    }
                    .setNegativeButton(R.string.depth_cancel, null)
                    .show()
            }
            row.addView(remove)
            liquidList.addView(row)
        }

        val add = label(getString(R.string.liquid_new), 13f, INK)
        add.setPadding(dp(14), dp(11), dp(14), dp(11))
        add.background = getDrawable(R.drawable.menu_item_selected)
        add.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }
        add.setOnClickListener { askEditLiquid(null) { refreshLiquids() } }
        liquidList.addView(add)
    }

    /**
     * Write the liquid list where liquids live: the character's own file.
     *
     * Liquids are declared by a character -- they are the things its rules can spill -- so this
     * always writes the character's file, whichever subject the rule editor happens to be
     * pointed at. The rest of that file is read back and left exactly as it was, because the
     * rule editor may be holding unsaved edits for a wholly different subject.
     */
    private fun saveLiquids() {
        val folder = summoned ?: return
        // The bench keeps its own copy of the list to look colours up by name; see setLiquids.
        sandboxView.setLiquids(logicLiquids.toList())
        if (logicSubject == Subjects.PET) {
            saveLogic()
            return
        }
        val spec = store.loadLogic(folder.id)
        store.saveLogic(
            folder.id,
            // Named rather than positional: this constructor has grown a list per subsystem,
            // and a positional call here is how a save silently drops the one that was added.
            LogicSpec(
                stats = spec.stats, rules = spec.rules, states = spec.states,
                liquids = logicLiquids.toList(), particles = spec.particles,
            ),
        )
    }

    /** Rebuild whichever of the two panes that show liquids is on screen. */
    private fun refreshLiquids() {
        if (logicPane.visibility == View.VISIBLE) buildLogicPane()
        if (liquidScroll.visibility == View.VISIBLE) buildLiquidList()
    }


    private fun askEditLiquid(existing: LiquidSpec?, after: (() -> Unit)? = null) {
        var colour = existing?.colour ?: LIQUID_PALETTE[0]
        var collides = existing?.collides ?: true
        val idInput = EditText(this).apply {
            setText(existing?.id ?: nextLiquidId())
            hint = getString(R.string.logic_stat_id)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val nameInput = EditText(this).apply {
            setText(existing?.name ?: "")
            hint = getString(R.string.logic_stat_name)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val chipViews = mutableListOf<View>()
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in LIQUID_PALETTE) {
            val chip = View(this)
            chip.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(c)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), 0x33000000)
            }
            chip.layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(6)
            }
            chip.setOnClickListener {
                colour = c
                paintSwatches(chipViews, LIQUID_PALETTE, colour)
            }
            chipViews.add(chip)
            chips.addView(chip)
        }

        val (viscosityRow, viscosityOf) = stepperRow(
            getString(R.string.logic_liquid_viscosity), existing?.viscosity ?: 0f, 0.1f, 0f, 1f,
        ) { "%.2f".format(it) }

        // The same one-chip switch the particle editor uses, for the same reason: it is one
        // yes/no about what this stuff IS, and the wording carries the state.
        val collideChip = label(getString(R.string.liquid_collides_on), 12f, INK)
        collideChip.setPadding(dp(10), dp(8), dp(10), dp(8))
        fun paintCollide() {
            collideChip.background = getDrawable(
                if (collides) R.drawable.menu_item_selected else R.drawable.menu_item_idle
            )
            collideChip.setTextColor(if (collides) INK else MUTED)
            collideChip.text = getString(
                if (collides) R.string.liquid_collides_on else R.string.liquid_collides_off
            )
        }
        collideChip.setOnClickListener { collides = !collides; paintCollide() }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        box.addView(idInput)
        box.addView(nameInput)
        box.addView(label(getString(R.string.logic_liquid_colour), 11f, MUTED, top = 8, bottom = 6))
        box.addView(chips)
        box.addView(viscosityRow)
        box.addView(label(getString(R.string.logic_liquid_kind), 11f, MUTED, top = 10, bottom = 6))
        box.addView(collideChip)
        box.addView(label(getString(R.string.logic_liquid_collides_hint), 10f, MUTED, top = 6))

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.logic_add_liquid else R.string.logic_liquids)
            .setView(scrolling(box))
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val id = RigEdit.sanitise(idInput.text.toString()).ifEmpty { nextLiquidId() }
                val name = nameInput.text.toString().trim().ifEmpty { id }
                logicLiquids.removeAll { it.id == id || (existing != null && it.id == existing.id) }
                logicLiquids.add(
                    LiquidSpec(id, name, colour, viscosityOf(), collides = collides)
                )
                saveLiquids()
                refreshLiquids()
                after?.invoke()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
        paintSwatches(chipViews, LIQUID_PALETTE, colour)
    }

    private fun paintSwatches(views: List<View>, colours: List<Int>, current: Int) {
        for ((i, view) in views.withIndex()) {
            val picked = colours[i] == current
            (view.background as? android.graphics.drawable.GradientDrawable)?.setStroke(
                dp(if (picked) 3 else 1),
                if (picked) INK else 0x33000000,
            )
        }
    }


    // ── 粒子管理 ──────────────────────────────────────────────────────────────

    /**
     * 粒子管理: the particles themselves, with a tap that sprays one.
     *
     * The same shape as 液体管理, and for the same reason: a particle is a colour and a few
     * switches, and "does this read as sweat at this size" is a question you answer by looking
     * rather than by editing a file. The difference is what the switches are: a particle can
     * be told to ignore gravity (a star drifts, dust falls) and whether it leaves a mark where
     * it lands, which between them are the whole personality of a puff of dust.
     */
    private fun openParticles() {
        val folder = summoned
        logicParticles = if (folder == null) {
            mutableListOf()
        } else {
            store.loadLogic(folder.id).particles.toMutableList()
        }
        sandboxView.setParticles(logicParticles.toList())
        buildParticleList()
    }

    private fun refreshParticles() {
        if (particleScroll.visibility == View.VISIBLE) buildParticleList()
    }

    private fun buildParticleList() {
        particleList.removeAllViews()
        particleList.addView(label(getString(R.string.menu_particles), 17f, INK, bottom = 4))
        particleList.addView(label(getString(R.string.particle_subtitle), 11f, MUTED, bottom = 12))

        if (summoned == null) {
            particleList.addView(label(getString(R.string.particle_need_pet), 12f, MUTED))
            return
        }

        val alive = sandboxView.particleCount()
        val marks = sandboxView.stainCount()
        val bench = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        bench.addView(
            label(
                if (alive == 0) getString(R.string.particle_none_on_bench)
                else getString(R.string.particle_on_bench, alive) + " · " + marks + " 个印子",
                11f, MUTED,
            )
        )
        if (alive > 0 || marks > 0) {
            bench.addView(small(getString(R.string.particle_clear)) {
                sandboxView.clearParticles()
                Toast.makeText(this, R.string.particle_cleared, Toast.LENGTH_SHORT).show()
                buildParticleList()
            })
        }
        particleList.addView(bench)

        for (particle in logicParticles.toList()) {
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
            ).apply { bottomMargin = dp(6) }
            row.addView(swatch(particle.colour, 16))

            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
            text.addView(label(particle.name, 14f, INK))
            text.addView(
                label(
                    "代号 " + particle.id +
                        " · " + getString(
                            if (particle.gravity) R.string.particle_gravity_on
                            else R.string.particle_gravity_off
                        ) +
                        " · " + getString(
                            if (particle.stains) R.string.particle_stains_on
                            else R.string.particle_stains_off
                        ) +
                        " · 大小 " + "%.2f".format(particle.size) + "×",
                    10f, MUTED,
                )
            )
            row.addView(text)
            row.setOnClickListener { askEditParticle(particle) { refreshParticles() } }

            // 画图案：这一种粒子长什么样。画过就用画的那个，没画过就还是原来的圆点。
            val art = label(getString(R.string.particle_draw), 11f, INK)
            art.setPadding(dp(8), dp(6), dp(8), dp(6))
            art.setOnClickListener { askParticleArt(particle) }
            row.addView(art)

            val spray = label(getString(R.string.particle_burst), 11f, INK)
            spray.setPadding(dp(8), dp(6), dp(8), dp(6))
            spray.setOnClickListener {
                sandboxView.spray(particle.id)
                Toast.makeText(
                    this, getString(R.string.particle_sprayed, particle.name), Toast.LENGTH_SHORT,
                ).show()
                buildParticleList()
            }
            row.addView(spray)

            val remove = label(getString(R.string.action_delete), 11f, MUTED)
            remove.setPadding(dp(8), dp(6), dp(8), dp(6))
            remove.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.action_delete) + " · " + particle.name)
                    .setMessage(getString(R.string.particle_delete_confirm, particle.name))
                    .setPositiveButton(R.string.depth_remove) { _, _ ->
                        logicParticles.removeAll { it.id == particle.id }
                        saveParticles()
                        refreshParticles()
                    }
                    .setNegativeButton(R.string.depth_cancel, null)
                    .show()
            }
            row.addView(remove)
            particleList.addView(row)
        }

        val add = label(getString(R.string.particle_new), 13f, INK)
        add.setPadding(dp(14), dp(11), dp(14), dp(11))
        add.background = getDrawable(R.drawable.menu_item_selected)
        add.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        add.setOnClickListener { askEditParticle(null) { refreshParticles() } }
        particleList.addView(add)
    }

    /**
     * Add or edit one kind of particle.
     *
     * The two switches are the point of the screen, so they are chips you flip rather than
     * numbers you type: 受重力 / 不受重力 and 留印子 / 不留印子. The size is a multiplier, because
     * "the same spark, but bigger" is what somebody actually wants.
     */
    private fun askEditParticle(existing: ParticleSpec?, after: (() -> Unit)? = null) {
        var colour = existing?.colour ?: LIQUID_PALETTE[0]
        var gravity = existing?.gravity ?: true
        var stains = existing?.stains ?: false

        val idInput = EditText(this).apply {
            setText(existing?.id ?: nextParticleId())
            hint = getString(R.string.logic_stat_id)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val nameInput = EditText(this).apply {
            setText(existing?.name ?: "")
            hint = getString(R.string.logic_stat_name)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val chipViews = mutableListOf<View>()
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in LIQUID_PALETTE) {
            val chip = View(this)
            chip.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(c)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), 0x33000000)
            }
            chip.layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(6)
            }
            chip.setOnClickListener {
                colour = c
                paintSwatches(chipViews, LIQUID_PALETTE, colour)
            }
            chipViews.add(chip)
            chips.addView(chip)
        }

        val (sizeRow, sizeOf) = stepperRow(
            getString(R.string.particle_size), existing?.size ?: 1f, 0.25f, 0.25f, 4f,
        ) { "%.2f×".format(it) }

        val gravityChip = label(getString(R.string.particle_gravity_on), 12f, INK)
        val stainChip = label(getString(R.string.particle_stains_on), 12f, INK)
        val switchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (chip in listOf(gravityChip, stainChip)) {
            chip.setPadding(dp(10), dp(8), dp(10), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
            switchRow.addView(chip)
        }
        fun paintSwitches() {
            for ((chip, on) in listOf(gravityChip to gravity, stainChip to stains)) {
                chip.background = getDrawable(
                    if (on) R.drawable.menu_item_selected else R.drawable.menu_item_idle
                )
                chip.setTextColor(if (on) INK else MUTED)
            }
            gravityChip.text = getString(
                if (gravity) R.string.particle_gravity_on else R.string.particle_gravity_off
            )
            stainChip.text = getString(
                if (stains) R.string.particle_stains_on else R.string.particle_stains_off
            )
        }
        gravityChip.setOnClickListener { gravity = !gravity; paintSwitches() }
        stainChip.setOnClickListener { stains = !stains; paintSwitches() }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        box.addView(idInput)
        box.addView(nameInput)
        box.addView(label(getString(R.string.particle_colour), 11f, MUTED, top = 8, bottom = 6))
        box.addView(chips)
        box.addView(sizeRow)
        box.addView(label(getString(R.string.menu_particles), 11f, MUTED, top = 10, bottom = 6))
        box.addView(switchRow)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.particle_add else R.string.menu_particles)
            .setView(scrolling(box))
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val id = RigEdit.sanitise(idInput.text.toString()).ifEmpty { nextParticleId() }
                val name = nameInput.text.toString().trim().ifEmpty { id }
                logicParticles.removeAll { it.id == id || (existing != null && it.id == existing.id) }
                logicParticles.add(
                    ParticleSpec(id, name, colour, sizeOf(), gravity = gravity, stains = stains)
                )
                saveParticles()
                refreshParticles()
                after?.invoke()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
        paintSwatches(chipViews, LIQUID_PALETTE, colour)
        paintSwitches()
    }

    /**
     * Save the particle list, and hand the bench the new one.
     *
     * Nothing is reloaded: the bench keeps its own copy of the kinds so a spray can look its
     * colour up by name, and rebuilding the world to pick up a colour would throw away
     * whatever is mid-fall on it. See PhysicsSandboxView.setParticles.
     */
    private fun saveParticles() {
        val folder = summoned ?: return
        sandboxView.setParticles(logicParticles.toList())
        // Particles are DECLARED by the character, and 粒子管理 is a character screen -- so this
        // writes the character's own file whatever subject the logic pane happened to be showing
        // last. (It used to route by logicSubject, which meant opening a part's rules and then
        // editing a particle wrote the character's file anyway -- but only sometimes.)
        val spec = store.loadLogic(folder.id)
        store.saveLogic(
            folder.id,
            LogicSpec(
                stats = spec.stats, rules = spec.rules, states = spec.states,
                liquids = spec.liquids, particles = logicParticles.toList(),
            ),
        )
    }

    private fun nextParticleId(): String {
        val taken = logicParticles.map { it.id }.toSet()
        var n = 1
        while (("p" + n) in taken) n++
        return "p" + n
    }

    private fun nextLiquidId(): String {
        val taken = logicLiquids.map { it.id }.toSet()
        var n = 1
        while (("liquid" + n) in taken) n++
        return "liquid" + n
    }

    private fun statRow(stat: StatSpec, onChanged: () -> Unit): View {
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
        row.setOnClickListener { askEditStat(stat) { onChanged() } }

        val remove = label(getString(R.string.action_delete), 11f, MUTED)
        remove.setPadding(dp(8), dp(6), dp(8), dp(6))
        remove.setOnClickListener {
            logicStats.removeAll { it.id == stat.id }
            saveLogic()
            buildLogicPane()
            onChanged()
        }
        row.addView(remove)
        return row
    }

    private fun stateRow(state: StateSpec, onChanged: () -> Unit): View {
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
        // The tag, not the bare id: a part's own state and a global one with the same name
        // are two switches, and the drawings are counted per switch. See Subjects.stateTag.
        val art = sandboxView.stateLayerCount(
            if (Subjects.isPart(logicSubject)) {
                Subjects.stateTag(Subjects.partId(logicSubject), state.id)
            } else {
                state.id
            },
        )
        val rules = logicRules.count { rule ->
            rule.conditions.any { it.kind == "state" && it.state == state.id } ||
                (rule.actions + rule.elseActions).any { it.state == state.id }
        }
        text.addView(label(state.name, 14f, INK))
        text.addView(
            label(
                "代号 " + state.id + " · 一开始是" +
                    getString(if (state.initial) R.string.logic_state_on else R.string.logic_state_off) +
                    " · " + getString(R.string.state_use_layers, art) +
                    " · " + getString(R.string.state_use_rules, rules),
                10f, MUTED,
            )
        )
        row.addView(text)
        row.setOnClickListener { showStateUse(state, onChanged) }

        val remove = label(getString(R.string.action_delete), 11f, MUTED)
        remove.setPadding(dp(8), dp(6), dp(8), dp(6))
        remove.setOnClickListener {
            logicStates.removeAll { it.id == state.id }
            saveLogic()
            buildLogicPane()
            onChanged()
        }
        row.addView(remove)
        return row
    }

    /**
     * Everything a state is for, in one place.
     *
     * The complaint this answers is "the state system only adds states and then what". A
     * state is three things — a drawing that shows only while it is on, a fact a rule can
     * read, and a switch something can flip — and none of the three is discoverable from a
     * dialog that only lets you rename it. So the row opens this instead.
     */
    private fun showStateUse(state: StateSpec, onChanged: () -> Unit) {
        val folder = summoned ?: opened
        val actions = mutableListOf<Pair<String, () -> Unit>>()

        actions.add(getString(R.string.state_use_flip) to {
            sandboxView.toggleState(state.id)
            val on = sandboxView.stateOn(state.id)
            Toast.makeText(
                this,
                state.name + getString(if (on) R.string.logic_state_on else R.string.logic_state_off),
                Toast.LENGTH_SHORT,
            ).show()
            buildPetChooser()
            onChanged()
        })

        actions.add(getString(R.string.state_use_edit) to { askEditState(state) { onChanged() } })

        if (folder != null) {
            actions.add(getString(R.string.state_use_art) to {
                val hits = boneNames(folder).filter { bone ->
                    store.partDrawings(folder.id, bone)
                        .any { it.artKey == store.variantKey(bone, state.id) }
                }
                if (hits.isEmpty()) {
                    Toast.makeText(this, R.string.state_use_art_none, Toast.LENGTH_LONG).show()
                } else {
                    pickList(
                        title = getString(R.string.state_use_art),
                        options = hits.map { it to (boneLabel(it).ifEmpty { it }) },
                        hint = "",
                        current = null,
                    ) { bone ->
                        openPartFiles(folder, bone)
                        true
                    }
                }
            })
        }

        actions.add(getString(R.string.state_use_rule) to {
            logicRules.add(
                RuleSpec(
                    on = EventType.CLICK.id,
                    part = "",
                    conditions = listOf(
                        ConditionSpec(kind = "state", stat = "", op = "on", value = 0f, state = state.id)
                    ),
                    actions = listOf(ActionSpec("say", text = "……")),
                    cooldown = 0.5f,
                    once = false,
                )
            )
            saveLogic()
            openLogic()
        })

        actions.add(getString(R.string.action_delete) to {
            logicStates.removeAll { it.id == state.id }
            saveLogic()
            buildLogicPane()
            onChanged()
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.state_use_title, state.name))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun askEditState(existing: StateSpec?, after: (() -> Unit)? = null) {
        var initial = existing?.initial ?: false
        val idInput = EditText(this).apply {
            setText(existing?.id ?: nextStateId())
            hint = getString(R.string.logic_state_id)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val nameInput = EditText(this).apply {
            setText(existing?.name ?: "")
            hint = getString(R.string.logic_state_name)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipViews = mutableListOf<TextView>()
        for (labelText in listOf(getString(R.string.logic_state_off), getString(R.string.logic_state_on))) {
            val chip = label(labelText, 12f, INK)
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                initial = labelText == getString(R.string.logic_state_on)
                paintChips(chipViews, listOf("off", "on"), { if (initial) "on" else "off" })
            }
            chipViews.add(chip)
            chips.addView(chip)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        box.addView(idInput)
        box.addView(nameInput)
        box.addView(label(getString(R.string.logic_state_initial), 11f, MUTED, top = 8, bottom = 6))
        box.addView(chips)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.logic_add_state else R.string.logic_states)
            .setView(scrolling(box))
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val id = RigEdit.sanitise(idInput.text.toString()).ifEmpty { nextStateId() }
                val name = nameInput.text.toString().trim().ifEmpty { id }
                logicStates.removeAll { it.id == id || (existing != null && it.id == existing.id) }
                logicStates.add(StateSpec(id, name, initial))
                saveLogic()
                buildLogicPane()
                after?.invoke()
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
        paintChips(chipViews, listOf("off", "on"), { if (initial) "on" else "off" })
    }

    private fun nextStateId(): String {
        val taken = logicStates.map { it.id }.toSet()
        var n = 1
        while (("state" + n) in taken) n++
        return "state" + n
    }

    private fun directionText(id: String): String {
        val entry = DIRECTIONS.firstOrNull { it.first == id } ?: return ""
        return getString(entry.second)
    }

    private fun liquidName(id: String): String =
        logicLiquids.firstOrNull { it.id == id }?.name ?: id

    private fun stateName(id: String): String =
        logicStates.firstOrNull { it.id == id }?.name ?: id

    /**
     * What to call a state a LAYER names.
     *
     * A layer's tag is either a global state's id or "<bone>:<id>" for a part's own, so this
     * strips the tag, finds the name wherever it is declared, and says which part it belongs
     * to when it belongs to one -- "出汗·手" reads better than two chips that both say 出汗.
     */
    private fun stateLabel(tag: String): String {
        val bone = Subjects.tagBone(tag)
        val id = Subjects.tagState(tag)
        val name = if (bone.isEmpty()) {
            stateName(id)
        } else {
            val own = store.loadObjectLogic(editingFolder())[Subjects.part(bone)]?.states
            (own?.firstOrNull { it.id == id }?.name ?: id) + "·" + boneLabel(bone).ifEmpty { bone }
        }
        return name
    }

    private fun pickState(title: String, onPick: (String) -> Unit) {
        pickList(
            title = title,
            options = logicStates.map { it.id to it.name },
            hint = getString(R.string.logic_no_states),
            current = null,
        ) { id ->
            onPick(id)
            true
        }
    }

    private fun askEditStat(existing: StatSpec?, after: (() -> Unit)? = null) {
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
            .setView(scrolling(box))
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val id = RigEdit.sanitise(idInput.text.toString()).ifEmpty { nextStatId() }
                val name = nameInput.text.toString().trim().ifEmpty { id }
                logicStats.removeAll { it.id == id || (existing != null && it.id == existing.id) }
                logicStats.add(StatSpec(id, name, valueOf(), 0f, maxOf()))
                saveLogic()
                buildLogicPane()
                after?.invoke()
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

    /** One node of the flow. Role picks the colour: 0 = 当, 1 = 如果, 2 = 就. */

    /** The line between two nodes. It exists so the chain reads as a chain. */

    /**
     * The board a kind of particle is drawn on.
     *
     * Everything the user makes here is 128 pixels wide and used at the size of a spark, so the
     * board is small on purpose -- and the tools are three brush widths and a row of colours
     * rather than a colour wheel, because at this size a wheel is a way to spend five minutes
     * choosing between two browns.
     */
    private fun askParticleArt(particle: ParticleSpec) {
        val folder = summoned ?: return
        val board = PaintBoardView(this)
        board.colour = particle.colour
        board.brushWidth = 4f
        board.load(folder.particleArt(particle.id))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        board.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220),
        )
        box.addView(board)
        box.addView(
            label(
                getString(R.string.particle_draw_hint, particle.name),
                10f, MUTED, top = 8, bottom = 6,
            )
        )

        // 粗细
        val widthRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var width = 4f
        val widthViews = mutableListOf<TextView>()
        for ((id, text) in listOf("2" to getString(R.string.paint_thin),
                                  "4" to getString(R.string.paint_mid),
                                  "10" to getString(R.string.paint_fat))) {
            val chip = label(text, 11f, INK)
            chip.setPadding(dp(11), dp(7), dp(11), dp(7))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                width = id.toFloat()
                board.brushWidth = width
                paintChips(widthViews, listOf("2", "4", "10"), { width.toInt().toString() })
            }
            widthViews.add(chip)
            widthRow.addView(chip)
        }
        box.addView(widthRow)
        box.addView(label(getString(R.string.paint_width), 10f, MUTED, top = 2))
        paintChips(widthViews, listOf("2", "4", "10"), { "4" })

        // 颜色：粒子自己的颜色排第一，因为「和刚才一样」是最常想要的那个。用的是液体
        // 那排色块的同两个帮手（swatch + paintSwatches），所以"选中"的样子在这里和
        // 在液体那边是同一种笔画，而不是两套。
        val boardColours = listOf(particle.colour) + PAINT_COLOURS
        var colour = particle.colour
        val colourRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val swatchViews = mutableListOf<View>()
        for (c in boardColours) {
            val view = swatch(c, 30)
            view.setOnClickListener {
                colour = c
                board.colour = c
                // 挑了颜色就是离开橡皮：一个还在擦的笔刷配一个新颜色，什么都不会发生。
                board.erasing = false
                paintSwatches(swatchViews, boardColours, colour)
            }
            swatchViews.add(view)
            colourRow.addView(view)
        }
        box.addView(colourRow)
        paintSwatches(swatchViews, boardColours, colour)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val eraserChip = label(getString(R.string.paint_eraser), 11f, INK)
        eraserChip.setPadding(dp(11), dp(7), dp(11), dp(7))
        eraserChip.setOnClickListener {
            board.erasing = !board.erasing
            // A toggle that looks the same either way is a toggle nobody can read, and this one
            // changes what the next stroke does.
            eraserChip.alpha = if (board.erasing) 0.45f else 1f
        }
        tools.addView(eraserChip)
        tools.addView(small(getString(R.string.paint_undo)) { board.undo() })
        tools.addView(small(getString(R.string.paint_clear)) { board.clear() })
        tools.addView(small(getString(R.string.paint_remove)) {
            if (store.clearParticleArt(folder, particle.id)) {
                sandboxView.refreshParticleShapes(folder)
                Toast.makeText(this, R.string.paint_removed, Toast.LENGTH_SHORT).show()
            }
        })
        box.addView(tools)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.particle_draw) + " · " + particle.name)
            .setView(scrolling(box))
            .setPositiveButton(R.string.depth_save) { _, _ ->
                // A blank board means "no shape", not "an invisible shape". Saving the
                // transparent canvas would make every one of these particles disappear, and
                // that is not what clearing the board is asking for.
                val saved = if (board.isEmpty) {
                    store.clearParticleArt(folder, particle.id)
                } else {
                    board.saveTo(folder.particleArt(particle.id))
                }
                if (saved) {
                    sandboxView.refreshParticleShapes(folder)
                } else {
                    Toast.makeText(this, R.string.paint_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.depth_cancel, null)
            .show()
    }

    /**
     * 颜色, for a board: the kind's own colour first, then a row of the usual ones.
     *
     * First because "the same as it already is" is the commonest answer, and the row is shared
     * by all three boards (particles, trails, ropes) so that picking a colour looks the same
     * wherever you are picking it.
     */
    private fun paintPaletteRow(board: PaintBoardView, first: Int, box: LinearLayout) {
        val colours = listOf(first) + PAINT_COLOURS
        var colour = first
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<View>()
        for (c in colours) {
            val view = swatch(c, 26)
            view.setOnClickListener {
                colour = c
                board.colour = c
                // 挑了颜色就是离开橡皮: a brush that is still erasing does nothing with a colour.
                board.erasing = false
                paintSwatches(views, colours, colour)
            }
            views.add(view)
            row.addView(view)
        }
        box.addView(row)
        paintSwatches(views, colours, colour)
    }

    /** The colours a board offers, after the kind's own. */
    private val PAINT_COLOURS = listOf(
        0xFF2B2A33.toInt(), 0xFFE2653C.toInt(), 0xFFF0B429.toInt(),
        0xFF3E9B4F.toInt(), 0xFF2C7BE5.toInt(), 0xFF8E5AC8.toInt(),
        0xFFFFFFFF.toInt(),
    )

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
        when (c.kind) {
            "state" -> stateName(c.state) + getString(
                if (c.op == "off") R.string.logic_state_is_off else R.string.logic_state_is_on
            )
            "chance" -> getString(R.string.logic_cond_chance_text, trim(c.value))
            else -> statName(c.stat) + " " + opSymbol(c.op) + " " + trim(c.value)
        }

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
        "random" -> statName(a.stat) + " 随机 " +
            trim(minOf(a.value, a.value2)) + ".." + trim(maxOf(a.value, a.value2))
        "pose" -> "摆动作 " + a.text
        "morph" -> "变身 " + a.text
        "clearPose" -> "松开动作"
        "spawn" -> "生成道具 " + propName(a.prop)
        "burst" -> "喷" + ParticleKinds.of(a.text, logicParticles).name
        "pour" -> "流" + liquidName(a.text) +
            (if (Shapes.of(a.shape) == Shapes.SCATTER) "（乱撒）" else "（柱状）") +
            " " + a.value.toInt() + "/秒 · " + trim(a.value2) + "秒"
        "stream" -> "持续喷" + ParticleKinds.of(a.text, logicParticles).name +
            " " + a.value.toInt() + "/秒 · " + trim(a.value2) + "秒"
        "impulse" -> "推" + directionText(a.text) + " " +
            (if (a.bone.isEmpty()) "被打到的部位" else partText(a.bone))
        "break" -> "隐藏 " + (if (a.bone.isEmpty()) "被打到的部位" else partText(a.bone))
        "show" -> "显示 " + (if (a.bone.isEmpty()) "被打到的部位" else partText(a.bone))
        "detach" -> "断开 " + (if (a.bone.isEmpty()) "被打到的部位" else partText(a.bone))
        "rejoin" -> "接回 " + (if (a.bone.isEmpty()) "被打到的部位" else partText(a.bone))
        "wait" -> "等 " + trim(a.value) + " 秒"
        "stateOn" -> "打开「" + stateName(a.state) + "」"
        "stateOff" -> "关闭「" + stateName(a.state) + "」"
        "stateToggle" -> "切换「" + stateName(a.state) + "」"
        "spill" -> "喷" + liquidName(a.text) + " " + a.value.toInt()
        "emit" -> "发信号：" + a.text
        "pushProp" -> "推" + propName(a.prop) + " " + directionText(a.text) + " " + a.value.toInt()
        "clear" -> if (a.text == "liquid" || Subjects.isLiquid(logicSubject)) {
            "清掉液体"
        } else {
            "清掉" + propName(a.prop)
        }
        else -> a.kind
    }

    /**
     * Flip the connector in front of a clause: 而且 becomes 或者 and back.
     *
     * This is the whole of putting a rule's IF together. Everything either side of the
     * connector is a module that was added on its own, and the connector is the only thing
     * that says how they go together — so it gets a box of its own and a tap of its own.
     */
    private fun flipJoin(index: Int, condIndex: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val clause = rule.conditions.getOrNull(condIndex) ?: return
        val conditions = rule.conditions.toMutableList()
        conditions[condIndex] = clause.copy(join = Joins.flip(clause.join))
        putRule(index, rule.copy(conditions = conditions))
    }

    /**
     * Add a module to a rule: another 如果, another thing to do, or the 否则 branch.
     *
     * It lands with a default value and the editor opens straight away, because "add a
     * module" and "say what it does" are one action as far as anybody using this is
     * concerned.
     */
    private fun addModule(index: Int, what: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        when (what) {
            LogicGraphView.Node.ADD_CONDITION -> {
                val conditions = rule.conditions.toMutableList()
                conditions.add(
                    ConditionSpec(
                        kind = "stat",
                        stat = logicStats.firstOrNull()?.id ?: "",
                        op = ">=",
                        value = 0f,
                        join = Joins.AND,
                    )
                )
                putRule(index, rule.copy(conditions = conditions))
                askCondition(index, conditions.size - 1)
            }

            LogicGraphView.Node.ADD_ELSE -> {
                val actions = rule.elseActions.toMutableList()
                actions.add(ActionSpec("say", text = "……"))
                putRule(index, rule.copy(elseActions = actions))
                askAction(index, actions.size - 1, isElse = true)
            }

            else -> {
                val actions = rule.actions.toMutableList()
                actions.add(ActionSpec("say", text = "……"))
                putRule(index, rule.copy(actions = actions))
                askAction(index, actions.size - 1)
            }
        }
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
            if (id == EventType.EMIT.id) {
                // The one event that is not an event: it is another rule's 就 calling this
                // one's 当, and that is not something the list of events can say.
                Toast.makeText(this, R.string.logic_signal_hint, Toast.LENGTH_LONG).show()
            }
            true
        }
    }

    /** A signal is a name, not a bone: same box, different question. */
    private fun askRuleSignal(index: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        askText(getString(R.string.logic_pick_signal), rule.part) { text ->
            putRule(index, rule.copy(part = text.trim()))
        }
    }

    private fun askRulePart(index: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val parts = summoned?.let { partNames(it) } ?: emptyList()
        val options = mutableListOf("" to getString(R.string.logic_pick_any_part))
        options.addAll(parts)
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
        // Two things a character can be asked about: a number it carries, and a fact about
        // it. The kind chips switch between them; the rest of the dialog is the same shape.
        var kind = when (existing?.kind) {
            "state" -> "state"
            "chance" -> "chance"
            else -> "stat"
        }
        var stat = existing?.stat ?: logicStats.firstOrNull()?.id ?: ""
        var op = existing?.op ?: ">="
        var state = existing?.state ?: logicStates.firstOrNull()?.id ?: ""
        var stateOn = existing?.op != "off"
        var join = Joins.of(existing?.join ?: Joins.AND)

        // Declared before the chips that switch between them: those listeners close over
        // these two, and a local has to exist before anything can capture it.
        val statBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stateBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chanceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val kindChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val kindViews = mutableListOf<TextView>()
        for ((id, text) in listOf(
            "stat" to getString(R.string.logic_cond_stat),
            "state" to getString(R.string.logic_cond_state),
            "chance" to getString(R.string.logic_cond_chance),
        )) {
            val chip = label(text, 12f, INK)
            chip.setPadding(dp(12), dp(8), dp(12), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                kind = id
                paintChips(kindViews, listOf("stat", "state", "chance"), { kind })
                statBox.visibility = if (kind == "stat") View.VISIBLE else View.GONE
                stateBox.visibility = if (kind == "state") View.VISIBLE else View.GONE
                chanceBox.visibility = if (kind == "chance") View.VISIBLE else View.GONE
            }
            kindViews.add(chip)
            kindChips.addView(chip)
        }

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

        statBox.addView(label(getString(R.string.logic_pick_stat), 11f, MUTED, bottom = 6))
        statBox.addView(statChips)
        statBox.addView(label(getString(R.string.logic_pick_op), 11f, MUTED, top = 10, bottom = 6))
        statBox.addView(opChips)
        statBox.addView(valueRow)

        // The dice. A percentage, and the sentence it makes on the graph is the whole
        // explanation: "有 30% 的概率". Rolled every time the rule is considered, which is
        // why the hint says so -- on a 每隔一会儿 rule it is one roll per interval, and on
        // 被打到 it is one roll per hit.
        val (chanceRow, chanceOf) = stepperRow(
            getString(R.string.logic_pick_percent),
            (existing?.takeIf { it.kind == "chance" }?.value) ?: 30f,
            5f, 0f, 100f,
        ) { trim(it) + "%" }
        chanceBox.addView(label(getString(R.string.logic_cond_chance_hint), 11f, MUTED, bottom = 6))
        chanceBox.addView(chanceRow)

        val stateChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val stateViews = mutableListOf<TextView>()
        for (s in logicStates) {
            val chip = label(s.name, 12f, INK)
            chip.setPadding(dp(10), dp(8), dp(10), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                state = s.id
                paintChips(stateViews, logicStates.map { it.id }, { state })
            }
            stateViews.add(chip)
            stateChips.addView(chip)
        }
        val onOffChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val onOffViews = mutableListOf<TextView>()
        for ((id, text) in listOf(
            "on" to getString(R.string.logic_state_on),
            "off" to getString(R.string.logic_state_off),
        )) {
            val chip = label(text, 12f, INK)
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                stateOn = id == "on"
                paintChips(onOffViews, listOf("on", "off"), { if (stateOn) "on" else "off" })
            }
            onOffViews.add(chip)
            onOffChips.addView(chip)
        }

        stateBox.addView(label(getString(R.string.logic_pick_state), 11f, MUTED, bottom = 6))
        stateBox.addView(stateChips)
        if (logicStates.isEmpty()) {
            stateBox.addView(label(getString(R.string.logic_no_states), 11f, MUTED, bottom = 4))
        }
        stateBox.addView(label(getString(R.string.logic_state_is), 11f, MUTED, top = 10, bottom = 6))
        stateBox.addView(onOffChips)

        // The connector, first, because it is the one thing about a clause that is about the
        // clause's NEIGHBOURS rather than about the clause. The first clause has nothing
        // before it to join to, so it is not offered one.
        val joinChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val joinViews = mutableListOf<TextView>()
        for (id in listOf(Joins.AND, Joins.OR)) {
            val chip = label(Joins.label(id), 12f, INK)
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(5) }
            chip.setOnClickListener {
                join = id
                paintChips(joinViews, listOf(Joins.AND, Joins.OR), { join })
            }
            joinViews.add(chip)
            joinChips.addView(chip)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        if (condIndex > 0) {
            box.addView(label(getString(R.string.logic_join), 11f, MUTED, bottom = 6))
            box.addView(joinChips)
        }
        box.addView(label(getString(R.string.logic_cond_kind), 11f, MUTED, top = 10, bottom = 6))
        box.addView(kindChips)
        box.addView(statBox)
        box.addView(stateBox)
        box.addView(chanceBox)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.logic_cond_kind)
            .setView(scrolling(box))
            .setPositiveButton(R.string.depth_save) { _, _ ->
                val conditions = rule.conditions.toMutableList()
                val spec = when (kind) {
                    "state" -> ConditionSpec(
                        kind = "state", stat = "", op = if (stateOn) "on" else "off",
                        value = 0f, state = state, join = join,
                    )
                    "chance" -> ConditionSpec(
                        kind = "chance", stat = "", op = "", value = chanceOf(), join = join,
                    )
                    else -> ConditionSpec(
                        kind = "stat", stat = stat, op = op, value = valueOf(), join = join,
                    )
                }
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
        paintChips(kindViews, listOf("stat", "state", "chance"), { kind })
        paintChips(statViews, logicStats.map { it.id }, { stat })
        paintChips(opViews, CompareOp.values().map { it.id }, { op })
        paintChips(stateViews, logicStates.map { it.id }, { state })
        paintChips(onOffViews, listOf("on", "off"), { if (stateOn) "on" else "off" })
        paintChips(joinViews, listOf(Joins.AND, Joins.OR), { join })
        statBox.visibility = if (kind == "stat") View.VISIBLE else View.GONE
        stateBox.visibility = if (kind == "state") View.VISIBLE else View.GONE
        chanceBox.visibility = if (kind == "chance") View.VISIBLE else View.GONE
    }

    private fun askAction(index: Int, actionIndex: Int, isElse: Boolean = false) {
        val rule = logicRules.getOrNull(index) ?: return
        val branch = if (isElse) rule.elseActions else rule.actions
        val existing = branch.getOrNull(actionIndex)
        pickList(
            title = getString(R.string.logic_pick_action),
            options = ActionKind.values().map { it.id to it.label },
            hint = "",
            current = existing?.kind,
            onDelete = if (actionIndex >= 0) {
                {
                    val actions = branch.toMutableList()
                    if (actionIndex in actions.indices) actions.removeAt(actionIndex)
                    putRule(
                        index,
                        if (isElse) rule.copy(elseActions = actions) else rule.copy(actions = actions),
                    )
                }
            } else {
                null
            },
        ) { id ->
            val kind = ActionKind.of(id)
            when (kind.needs) {
                "text" -> askText(getString(R.string.logic_pick_text), existing?.text ?: "") { text ->
                    putAction(index, actionIndex, isElse, ActionSpec(kind.id, text = text))
                }
                "stat" -> pickStat(getString(R.string.logic_pick_stat)) { stat ->
                    askSigned(getString(R.string.logic_pick_value), existing?.value ?: 10f) { v ->
                        putAction(index, actionIndex, isElse, ActionSpec(kind.id, stat = stat, value = v))
                    }
                }
                "statValue" -> pickStat(getString(R.string.logic_pick_stat)) { stat ->
                    askSigned(getString(R.string.logic_pick_value), existing?.value ?: 100f) { v ->
                        putAction(index, actionIndex, isElse, ActionSpec(kind.id, stat = stat, value = v))
                    }
                }
                // A range: two numbers, asked for one after the other. The engine sorts them,
                // so typing the big one first is a range rather than an empty one.
                "statRange" -> pickStat(getString(R.string.logic_pick_stat)) { stat ->
                    askSigned(getString(R.string.logic_pick_from), existing?.value ?: 0f) { lo ->
                        askSigned(getString(R.string.logic_pick_to), existing?.value2 ?: 100f) { hi ->
                            putAction(
                                index, actionIndex, isElse,
                                ActionSpec(kind.id, stat = stat, value = lo, value2 = hi),
                            )
                        }
                    }
                }
                "character" -> pickList(
                    getString(R.string.logic_pick_character),
                    characters.map { it.id to it.id },
                    getString(R.string.logic_no_characters),
                    existing?.text,
                ) { id ->
                    putAction(index, actionIndex, isElse, ActionSpec(kind.id, text = id))
                    true
                }
                "pose" -> pickList(
                    getString(R.string.logic_pick_pose),
                    summoned?.let { store.loadPoses(it.id).map { p -> p.name to p.name } } ?: emptyList(),
                    getString(R.string.logic_no_poses),
                    existing?.text,
                ) { name ->
                    putAction(index, actionIndex, isElse, ActionSpec(kind.id, text = name))
                    true
                }
                "prop" -> pickList(
                    getString(R.string.logic_pick_prop),
                    props.map { it.id to it.name },
                    getString(R.string.sandbox_props_empty),
                    existing?.prop,
                ) { propId ->
                    putAction(index, actionIndex, isElse, ActionSpec(kind.id, prop = propId))
                    true
                }
                "burst" -> pickList(
                    getString(R.string.logic_pick_burst),
                    logicParticles.map { it.id to it.name },
                    "",
                    existing?.text,
                ) { burstId ->
                    putAction(index, actionIndex, isElse, ActionSpec(kind.id, text = burstId, value = existing?.value ?: 10f))
                    true
                }
                // 流液体：柱状还是乱撒 → 选哪种液体 → 每秒多少滴 → 流多少秒。
                "liquidStream" -> pickList(
                    getString(R.string.logic_pick_shape),
                    listOf(
                        Shapes.COLUMN to getString(R.string.logic_shape_column),
                        Shapes.SCATTER to getString(R.string.logic_shape_scatter),
                    ),
                    "",
                    Shapes.of(existing?.shape ?: ""),
                ) { shape ->
                    pickList(
                        getString(R.string.logic_pick_liquid),
                        logicLiquids.map { it.id to it.name },
                        getString(R.string.logic_no_liquids),
                        existing?.text,
                    ) { liquidId ->
                        askNumber(
                            getString(R.string.logic_pick_rate), existing?.value ?: 20f, 1f, 200f,
                        ) { rate ->
                            askNumber(
                                getString(R.string.logic_pick_seconds),
                                existing?.value2 ?: 2f, 0.1f, 60f,
                            ) { seconds ->
                                putAction(
                                    index, actionIndex, isElse,
                                    ActionSpec(
                                        kind.id, text = liquidId, value = rate,
                                        value2 = seconds, shape = shape,
                                    ),
                                )
                            }
                        }
                        true
                    }
                    // The outer picker's answer, for the same reason as the inner one: a
                    // dialog that took a choice says so, and this flow took one.
                    true
                }
                // 持续喷粒子：同上，喷的是粒子。
                "burstStream" -> pickList(
                    getString(R.string.logic_pick_burst),
                    logicParticles.map { it.id to it.name },
                    "",
                    existing?.text,
                ) { burstId ->
                    askNumber(
                        getString(R.string.logic_pick_rate), existing?.value ?: 10f, 1f, 200f,
                    ) { rate ->
                        askNumber(
                            getString(R.string.logic_pick_seconds), existing?.value2 ?: 2f, 0.1f, 60f,
                        ) { seconds ->
                            putAction(
                                index, actionIndex, isElse,
                                ActionSpec(kind.id, text = burstId, value = rate, value2 = seconds),
                            )
                        }
                    }
                    true
                }
                "bone" -> pickBoneName(getString(R.string.logic_pick_bone), existing?.bone) { bone ->
                    putAction(index, actionIndex, isElse, ActionSpec(kind.id, bone = bone))
                }
                "boneValue" -> pickBoneName(getString(R.string.logic_pick_bone), existing?.bone) { bone ->
                    askSigned(getString(R.string.logic_pick_value), existing?.value ?: 400f) { v ->
                        pickList(
                            getString(R.string.logic_pick_direction),
                            DIRECTIONS.map { it.first to getString(it.second) },
                            "",
                            existing?.text ?: "up",
                        ) { dir ->
                            putAction(
                                index, actionIndex, isElse,
                                ActionSpec(kind.id, text = dir, bone = bone, value = v),
                            )
                            true
                        }
                    }
                }
                "seconds" -> askNumber(getString(R.string.logic_pick_value), existing?.value ?: 0.5f, 0f, 30f) { v ->
                    putAction(index, actionIndex, isElse, ActionSpec(kind.id, value = v))
                }
                // 跳到规则：目标就是上面那张表的第几条。自己不在选项里——一条规则跳到它自己
                // 身上什么也不会发生（引擎一次事件里每条规则最多跑一次），给了只会让人以为是坏的。
                // 标签带上目标那一条的「当」，因为挑的时候真正在想的是"那条是干嘛的"。
                "rule" -> pickList(
                    getString(R.string.logic_pick_rule),
                    logicRules.indices.filter { it != index }.map {
                        (it + 1).toString() to
                            getString(R.string.logic_rule_n, it + 1) +
                            " · " + EventType.of(logicRules[it].on).label
                    },
                    getString(R.string.logic_no_rules),
                    existing?.rule?.takeIf { it > 0 }?.toString(),
                ) { picked ->
                    putAction(
                        index, actionIndex, isElse,
                        ActionSpec(kind.id, rule = picked.toIntOrNull() ?: 0),
                    )
                    true
                }
                // Pushing a prop: which one, how hard, and which way. The prop is named in
                // the action rather than taken from the rule's subject, because "the pet
                // kicks the ball" is a rule about the pet.
                "propValue" -> pickList(
                    getString(R.string.logic_pick_prop),
                    props.map { it.id to it.name },
                    getString(R.string.sandbox_props_empty),
                    existing?.prop,
                ) { propId ->
                    askSigned(getString(R.string.logic_pick_value), existing?.value ?: 400f) { v ->
                        pickList(
                            getString(R.string.logic_pick_direction),
                            DIRECTIONS.map { it.first to getString(it.second) },
                            "",
                            existing?.text ?: "up",
                        ) { dir ->
                            putAction(
                                index, actionIndex, isElse,
                                ActionSpec(kind.id, text = dir, prop = propId, value = v),
                            )
                            true
                        }
                    }
                    true
                }
                // Clearing: a prop by name, or the liquid the rule itself is about.
                "clearWhat" -> pickList(
                    getString(R.string.logic_pick_prop),
                    props.map { it.id to it.name } +
                        listOf("" to getString(R.string.logic_clear_liquid)),
                    getString(R.string.sandbox_props_empty),
                    existing?.prop,
                ) { propId ->
                    putAction(
                        index, actionIndex, isElse,
                        ActionSpec(kind.id, prop = propId, text = if (propId.isEmpty()) "liquid" else ""),
                    )
                    true
                }
                "state" -> pickState(getString(R.string.logic_pick_state)) { state ->
                    putAction(index, actionIndex, isElse, ActionSpec(kind.id, state = state))
                }
                "liquid" -> pickList(
                    getString(R.string.logic_pick_liquid),
                    logicLiquids.map { it.id to it.name },
                    getString(R.string.logic_no_liquids),
                    existing?.text,
                ) { liquid ->
                    askNumber(
                        getString(R.string.logic_pick_amount),
                        existing?.value ?: 24f, 1f, 200f,
                    ) { amount ->
                        putAction(
                            index, actionIndex, isElse,
                            ActionSpec(kind.id, text = liquid, value = amount),
                        )
                    }
                    true
                }
                else -> putAction(index, actionIndex, isElse, ActionSpec(kind.id))
            }
            true
        }
    }

    /**
     * Which 并行分支 the action editor is writing into, or -1 for the rule's own list.
     *
     * A field rather than a parameter, deliberately: askAction() is one long `when` with a
     * putAction() in every arm (twenty-odd of them), and threading a fifth parameter through
     * all of them is twenty chances to miss one. One action is edited at a time, so there is
     * exactly one value, and askBranch() sets it around the call.
     */
    private var editingBranch = -1

    private fun putAction(
        index: Int,
        actionIndex: Int,
        isElse: Boolean,
        action: ActionSpec,
    ) {
        val rule = logicRules.getOrNull(index) ?: return
        val branch = editingBranch
        if (branch >= 0 && !isElse) {
            val branches = rule.branches.toMutableList()
            if (branch >= branches.size) return
            val list = branches[branch].actions.toMutableList()
            if (actionIndex >= 0 && actionIndex < list.size) {
                list[actionIndex] = action
            } else {
                list.add(action)
            }
            branches[branch] = branches[branch].copy(actions = list)
            putRule(index, rule.copy(branches = branches))
            return
        }
        val actions = (if (isElse) rule.elseActions else rule.actions).toMutableList()
        if (actionIndex >= 0 && actionIndex < actions.size) {
            actions[actionIndex] = action
        } else {
            actions.add(action)
        }
        putRule(
            index,
            if (isElse) rule.copy(elseActions = actions) else rule.copy(actions = actions),
        )
    }

    /** 加一个并行分支: another executor forked off this rule's own 当. */
    private fun addBranch(index: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val branches = rule.branches.toMutableList()
        // A new branch hangs off the group's 当 until somebody gives it one of its own: a branch
        // that arrives already needing to be told WHEN would be a dialog nobody asked for.
        branches.add(BranchSpec(actions = listOf(ActionSpec("say", text = "……"))))
        putRule(index, rule.copy(branches = branches))
    }

    /** 分支自己的当: the event this branch answers, or empty for "同一个当". */
    private fun askBranchEvent(index: Int, branch: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val b = rule.branches.getOrNull(branch) ?: return
        val options = mutableListOf("" to getString(R.string.logic_branch_same_when))
        options.addAll(EventType.values().map { it.id to it.label })
        pickList(
            title = getString(R.string.logic_branch_when),
            options = options,
            hint = getString(R.string.logic_branch_when_hint),
            current = b.on,
        ) { id ->
            val branches = rule.branches.toMutableList()
            branches[branch] = b.copy(on = id)
            putRule(index, rule.copy(branches = branches))
            true
        }
    }

    /** 分支的部位: which part this branch's own detector listens to. */
    private fun askBranchPart(index: Int, branch: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val b = rule.branches.getOrNull(branch) ?: return
        pickBoneName(getString(R.string.logic_pick_part), b.part) { id ->
            val branches = rule.branches.toMutableList()
            branches[branch] = b.copy(part = id)
            putRule(index, rule.copy(branches = branches))
        }
    }

    private fun removeBranch(index: Int, branch: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val branches = rule.branches.toMutableList()
        if (branch !in branches.indices) return
        branches.removeAt(branch)
        putRule(index, rule.copy(branches = branches))
    }

    /**
     * One 并行分支 opened for editing: its actions, and the same editor the rule's own 就 uses.
     *
     * 「并行逻辑也有完整的执行器」 is the whole point: a branch holds an action list exactly like
     * the rule's own and is edited by the same screen, because a second kind of action editor
     * would be a second set of actions that could do less.
     */
    private fun askBranch(index: Int, branch: Int) {
        val rule = logicRules.getOrNull(index) ?: return
        val b = rule.branches.getOrNull(branch) ?: return
        val list = b.actions
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(
            label(
                getString(R.string.logic_branch_row_hint, branch + 2, rule.branches.size + 1),
                11f, MUTED, bottom = 6,
            )
        )
        // 分支自己的当 + 部位: a branch is a line with its own trigger, and this is where it says
        // so. Both rows are on top of the actions because that is the order they happen in.
        val whenRow = label(
            getString(R.string.logic_branch_when) + "：" +
                if (b.ownDetector) EventType.of(b.on).label
                else getString(R.string.logic_branch_same_when),
            13f, INK,
        )
        whenRow.setPadding(dp(12), dp(11), dp(12), dp(11))
        whenRow.background = getDrawable(R.drawable.menu_item_idle)
        whenRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) }
        whenRow.setOnClickListener {
            askBranchEvent(index, branch)
            dialog.dismiss()
        }
        box.addView(whenRow)

        val partRow = label(
            getString(R.string.logic_pick_part) + "：" +
                if (b.part.isEmpty()) getString(R.string.logic_pick_any_part) else partText(b.part),
            13f, INK,
        )
        partRow.setPadding(dp(12), dp(11), dp(12), dp(11))
        partRow.background = getDrawable(R.drawable.menu_item_idle)
        partRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) }
        partRow.setOnClickListener {
            askBranchPart(index, branch)
            dialog.dismiss()
        }
        if (b.ownDetector) box.addView(partRow)

        for ((ai, a) in list.withIndex()) {
            val view = label(
                getString(R.string.logic_module_action) + "：" + actionText(a), 13f, INK,
            )
            view.setPadding(dp(12), dp(11), dp(12), dp(11))
            view.background = getDrawable(R.drawable.menu_item_idle)
            view.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
            view.setOnClickListener {
                editingBranch = branch
                askAction(index, ai)
                editingBranch = -1
            }
            box.addView(view)
        }
        val add = label(getString(R.string.logic_module_action), 13f, INK)
        add.setPadding(dp(12), dp(11), dp(12), dp(11))
        add.background = getDrawable(R.drawable.menu_item_selected)
        add.setOnClickListener {
            editingBranch = branch
            askAction(index, -1)
            editingBranch = -1
        }
        box.addView(add)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.logic_branch) + " " + (branch + 2))
            .setView(scrolling(box))
            .setNegativeButton(R.string.action_close, null)
            .create()
        dialog.show()
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
        val parts = summoned?.let { partNames(it) } ?: emptyList()
        val options = mutableListOf("" to getString(R.string.logic_pick_any_part))
        options.addAll(parts)
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
            .setView(scrolling(box))
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

    private fun boneNames(folder: CharacterFolder): List<String> =
        CharacterSpec.parseOrNull(folder.specText())?.bones?.map { it.name }.orEmpty()

    /**
     * Everything a rule can name as a 部位: the bones, and the nodes on them.
     *
     * One list rather than two, because the question "who was hit" has one answer -- a node
     * that names a fingertip is a better answer than the finger, not a different kind of thing.
     * They are told apart in the label, which is the only place it matters.
     */
    private fun partNames(folder: CharacterFolder): List<Pair<String, String>> {
        val spec = CharacterSpec.parseOrNull(folder.specText())
        val bones = spec?.bones?.map { it.name }.orEmpty()
        val out = bones.map { b ->
            val zh = boneLabel(b)
            b to (if (zh.isEmpty()) b else zh + "   " + b)
        }.toMutableList()
        for (n in spec?.nodes.orEmpty()) {
            out.add(n.name to (getString(R.string.rig_node_list) + " · " + n.name))
        }
        return out
    }

    /** The nodes of the summoned character, for the places that need them by themselves. */
    private fun nodeNames(folder: CharacterFolder): List<String> =
        CharacterSpec.parseOrNull(folder.specText())?.nodes?.map { it.name }.orEmpty()

    /**
     * A dialog body that scrolls.
     *
     * Every dialog in this app is a column of rows and several of them are longer than a phone:
     * the bone list has nineteen bones before it reaches the nodes, and the prop editor has the
     * trail under five other rows. An AlertDialog does NOT scroll its own view, so what happens
     * without this is not "the dialog is cramped" -- it is a button that CANNOT BE REACHED, and
     * from the outside that is a feature that was never written. Two of them were reported as
     * missing for exactly this reason.
     */
    private fun scrolling(view: View): View {
        val scroll = android.widget.ScrollView(this).apply {
            addView(view)
            setPadding(0, 0, 0, dp(4))
        }
        return scroll
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
        /** The colours a liquid can be. A palette, not a picker: eight swatches is a
         *  decision, a colour wheel is a hobby. */
        val LIQUID_PALETTE = listOf(
            0xFFB4212B.toInt(), 0xFFE2557B.toInt(), 0xFFE08A2E.toInt(), 0xFFE8C33C.toInt(),
            0xFF5FA83C.toInt(), 0xFF3D8FD1.toInt(), 0xFF6C4CE0.toInt(), 0xFF23202E.toInt(),
        )

        /** Which way "推一下" pushes. "away" is the only one that needs the event. */
        val DIRECTIONS = listOf(
            "up" to R.string.logic_dir_up,
            "down" to R.string.logic_dir_down,
            "left" to R.string.logic_dir_left,
            "right" to R.string.logic_dir_right,
            "away" to R.string.logic_dir_away,
        )

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
