package dev.atp.pet.engine.prop

import org.json.JSONArray
import org.json.JSONObject

/**
 * The four kinds of prop, which differ only in how they are used.
 *
 * Everything else about a prop — how heavy it is, how hard it hits, what it does when it
 * lands — is the rule set's business, not the prop's. A prop raises an event; what the
 * character does about it is written in the logic panel. That split is the whole reason
 * four kinds is enough: a hammer and a cushion are the same prop with different rules.
 */
enum class PropKind(val id: String, val label: String, val hint: String) {
    HOLD("hold", "持续使用", "拖到角色身上按住，会一直触发"),
    DEVICE("device", "装置", "放在桌上不动，角色碰到才触发"),
    THROW("throw", "投掷", "拖起来甩出去，砸到哪算哪"),
    SHOT("shot", "射击", "拖出方向松手打出一发，道具留在原地");

    companion object {
        fun of(id: String): PropKind = values().firstOrNull { it.id == id } ?: THROW
    }
}

/**
 * A thing on the table.
 *
 * [radius] is the same number for the drawing and for the collision: a prop whose picture
 * is bigger than the circle it hits with looks broken, and one whose circle is bigger
 * looks haunted.
 */
data class PropSpec(
    val id: String,
    val name: String,
    val kind: String,
    /** Canvas pixels. */
    val radius: Float,
    /** Multiplies the magnitude the rules see, and how hard it shoves the figure. */
    val force: Float,
    /** 1 = normal fall. Bullets get less so they fly flat. */
    val gravityScale: Float = 1f,
    /** Bullets and other spawned things clean themselves up. Placed props never do. */
    val transient: Boolean = false,
) {
    fun kindOf(): PropKind = PropKind.of(kind)

    /** The projectile a 射击 prop fires, derived so the editor never has to define one. */
    fun bullet(): PropSpec = copy(
        id = id,
        kind = PropKind.THROW.id,
        radius = (radius * 0.4f).coerceAtLeast(6f),
        force = force * 1.8f,
        gravityScale = 0.35f,
        transient = true,
    )
}

object PropSpecs {

    fun parse(text: String): List<PropSpec> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("id", "prop" + i)
            PropSpec(
                id = id,
                name = o.optString("name", id),
                kind = o.optString("kind", PropKind.THROW.id),
                radius = o.optDouble("radius", 60.0).toFloat(),
                force = o.optDouble("force", 1.0).toFloat(),
                gravityScale = o.optDouble("gravity", 1.0).toFloat(),
                transient = o.optBoolean("transient", false),
            )
        }
    }

    fun toJson(specs: List<PropSpec>): String {
        val arr = JSONArray()
        for (s in specs) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("kind", s.kind)
                    .put("radius", s.radius.toDouble())
                    .put("force", s.force.toDouble())
                    .put("gravity", s.gravityScale.toDouble())
                    .put("transient", s.transient)
            )
        }
        return arr.toString(2)
    }
}
