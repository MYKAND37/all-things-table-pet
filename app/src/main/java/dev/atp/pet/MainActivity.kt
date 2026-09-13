package dev.atp.pet

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.atp.pet.ui.SkeletonView

/**
 * The app shell: a fixed rail of four areas on the left, a frosted pane on the right.
 *
 * Only 测试场 does anything so far. It hosts the rigging bench, which loads the 6.5-head
 * character package and lets a finger drag its joints -- the on-device half of the
 * skeleton work. The other areas are still placeholders.
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
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val title = findViewById<TextView>(R.id.contentTitle)
        placeholder = findViewById(R.id.placeholder)
        skeletonView = findViewById(R.id.skeletonView)
        status = findViewById(R.id.skeletonStatus)

        val items = menuIds.map { findViewById<TextView>(it) }
        items.forEach { item ->
            item.setOnClickListener {
                items.forEach { it.isSelected = false }
                item.isSelected = true
                title.text = item.text
                show(item.id == R.id.menuSandbox)
            }
        }

        skeletonView.onInfo = { status.text = it }

        // The bench is the first thing worth looking at, so open on it.
        items.firstOrNull()?.let {
            it.isSelected = true
            title.text = it.text
        }
        show(true)

        try {
            skeletonView.load("characters/female_6_5/character.json")
        } catch (e: Exception) {
            status.text = "character package failed to load: " + e.message
        }
    }

    private fun show(sandbox: Boolean) {
        placeholder.visibility = if (sandbox) View.GONE else View.VISIBLE
        skeletonView.visibility = if (sandbox) View.VISIBLE else View.GONE
        status.visibility = if (sandbox) View.VISIBLE else View.GONE
    }
}
