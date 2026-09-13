package dev.atp.pet

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.atp.pet.ui.PhysicsSandboxView
import dev.atp.pet.ui.SkeletonView

/**
 * The app shell: a fixed rail of four areas on the left, a frosted pane on the right.
 *
 * The split the project settled on:
 *
 *   测试场    runtime -- physics, motion, and later props and events
 *   桌宠管理  authoring -- the rig editor
 *
 * 道具管理 and 逻辑管理 are still placeholders.
 */
class MainActivity : AppCompatActivity() {

    private val menuIds = listOf(
        R.id.menuSandbox,
        R.id.menuPets,
        R.id.menuProps,
        R.id.menuLogic,
    )

    private lateinit var placeholder: View
    private lateinit var skeletonView: SkeletonView
    private lateinit var sandboxView: PhysicsSandboxView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val title = findViewById<TextView>(R.id.contentTitle)
        placeholder = findViewById(R.id.placeholder)
        skeletonView = findViewById(R.id.skeletonView)
        sandboxView = findViewById(R.id.sandboxView)
        status = findViewById(R.id.skeletonStatus)

        val items = menuIds.map { findViewById<TextView>(it) }
        items.forEach { item ->
            item.setOnClickListener {
                items.forEach { it.isSelected = false }
                item.isSelected = true
                title.text = item.text
                show(item.id)
            }
        }

        skeletonView.onInfo = { status.text = it }
        sandboxView.onInfo = { status.text = it }

        try {
            sandboxView.load(CHARACTER)
            skeletonView.load(CHARACTER)
        } catch (e: Exception) {
            status.text = "character package failed to load: " + e.message
        }

        items.firstOrNull()?.let {
            it.isSelected = true
            title.text = it.text
        }
        show(R.id.menuSandbox)
    }

    private fun show(selected: Int) {
        val sandbox = selected == R.id.menuSandbox
        val editor = selected == R.id.menuPets
        placeholder.visibility = if (sandbox || editor) View.GONE else View.VISIBLE
        sandboxView.visibility = if (sandbox) View.VISIBLE else View.GONE
        skeletonView.visibility = if (editor) View.VISIBLE else View.GONE
        status.visibility = if (sandbox || editor) View.VISIBLE else View.GONE
        status.text = when {
            sandbox -> "拖拽可以抓起来甩出去 · 松手后受重力下落 · 双击复位"
            editor -> "拖关节摆姿势 · 双击复位"
            else -> ""
        }
    }

    private companion object {
        const val CHARACTER = "characters/female_base/character.json"
    }
}
