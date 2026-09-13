package dev.atp.pet

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The app shell: a fixed rail of four areas on the left, a frosted pane on the right.
 *
 * Deliberately empty of behaviour. Tapping an entry only moves the highlight and retitles
 * the pane — enough to see the shell is alive, nothing more. Wiring each area to a real
 * screen comes later.
 */
class MainActivity : AppCompatActivity() {

    private val menuIds = listOf(
        R.id.menuSandbox,
        R.id.menuPets,
        R.id.menuProps,
        R.id.menuLogic,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val title = findViewById<TextView>(R.id.contentTitle)
        val items = menuIds.map { findViewById<TextView>(it) }

        items.forEach { item ->
            item.setOnClickListener {
                items.forEach { it.isSelected = false }
                item.isSelected = true
                title.text = item.text
            }
        }

        // Open on 测试场, so the shell never starts with nothing highlighted.
        items.firstOrNull()?.let {
            it.isSelected = true
            title.text = it.text
        }
    }
}
