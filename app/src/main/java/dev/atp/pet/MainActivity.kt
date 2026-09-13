package dev.atp.pet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.atp.pet.data.CharacterFolder
import dev.atp.pet.data.CharacterStore
import dev.atp.pet.engine.skeleton.CharacterSpec
import dev.atp.pet.engine.skeleton.SwapRuleSpec
import dev.atp.pet.ui.PartAlignView
import dev.atp.pet.ui.PhysicsSandboxView
import dev.atp.pet.ui.SkeletonView
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
        statusLine.visibility =
            if (pane == Pane.SANDBOX || pane == Pane.PET_RIG || pane == Pane.PART_ALIGN)
                View.VISIBLE
            else View.GONE

        when (pane) {
            Pane.SANDBOX -> statusLine.text = "拖起来甩出去 · 双击复位 · 上面选桌宠"
            Pane.PET_LIST -> statusLine.text = ""
            Pane.PET_PARTS -> statusLine.text = ""
            Pane.PET_RIG -> statusLine.text = "拖关节摆姿势 · 双击复位"
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
                sandboxView.load(folder)
                buildPetChooser()
            }
            petChooser.addView(chip)
        }
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