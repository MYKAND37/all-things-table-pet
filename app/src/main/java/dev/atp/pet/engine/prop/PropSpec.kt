package dev.atp.pet.engine.prop

import org.json.JSONArray
import org.json.JSONObject

/**
 * The kinds of prop, which differ only in how they are used.
 *
 * Everything else about a prop — how heavy it is, how hard it hits, what it does when it
 * lands — is the rule set's business, not the prop's. A prop raises an event; what the
 * character does about it is written in the logic panel. That split is the whole reason
 * the list stays short: a hammer and a cushion are the same prop with different rules.
 *
 * The last three are the anchor family, and they share one thing the first four do not:
 * they are PLACED rather than thrown. Nothing about them is settled by physics — a stake,
 * a nail and a rope are all somebody deciding where a thing goes.
 */
enum class PropKind(val id: String, val label: String, val hint: String) {
    HOLD("hold", "持续使用", "拖到角色身上按住，会一直触发"),
    DEVICE("device", "装置", "放在桌上不动，角色碰到才触发"),
    THROW("throw", "投掷", "拖起来甩出去，砸到哪算哪"),
    SHOT("shot", "射击", "拖出方向松手打出一发，道具留在原地"),
    ANCHOR("anchor", "锚点", "放在桌上不动，角色的部位碰到就被拴住；把锚点拿起来就松开"),
    PIN("pin", "钉子", "点一下钉住：点空白处钉在桌上，点部位就把部位钉在那儿；再点一下拔掉"),
    ROPE("rope", "连绳", "点两个点连起来：可以把部位拴在桌上，也能把两个部位系在一起；点绳子取下"),
    SEGMENT("segment", "绳段", "拖出一个长方形画一截绳子，点端头再接一截；绳子是实体，能站能撞");

    companion object {
        fun of(id: String): PropKind = values().firstOrNull { it.id == id } ?: THROW

        /**
         * Kinds that are placed by POINTING, not thrown: the world never moves them.
         *
         * A nail and a rope have no business falling — gravity has no opinion about where a
         * nail goes, and a rope that slid off the table would stop being the thing that was
         * tied. They are also not discs: a rope is a line and a nail is a point, so the
         * circle the rest of the props collide with would be a lie. Both are therefore
         * spawned [Prop.planted] and handled by the bench instead of the prop physics.
         */
        fun isPointed(kind: PropKind): Boolean = kind == PIN || kind == ROPE || kind == SEGMENT
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
    /** 锚点专用：绳子有多长。身体在这个半径里自由活动，超了就被拉回来。 */
    val ropeLength: Float = 420f,
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

    /**
     * The pattern a prop leaves behind it, inside that prop's own folder.
     *
     * On the PROP rather than on the character, because the prop is the thing that moves: a
     * hammer dragged across the table leaves the same mark whoever is holding it.
     */
    const val TRAIL_FILE = "trail.png"

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
                ropeLength = o.optDouble("rope", 420.0).toFloat(),
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
                    .put("rope", s.ropeLength.toDouble())
            )
        }
        return arr.toString(2)
    }
}
