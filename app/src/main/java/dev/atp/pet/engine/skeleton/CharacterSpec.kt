package dev.atp.pet.engine.skeleton

import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.math.normalizeAngle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A bone as authored: head and tail in canvas pixels, angles in degrees. */
data class BoneSpec(
    val name: String,
    val parentName: String?,
    val head: Vec2,
    val tail: Vec2,
    val minAngle: Float,
    val maxAngle: Float,
    val springy: Boolean,
    val stiffness: Float,
    val damping: Float,
    val gravity: Float,
    /** "capsule" runs along the bone, "circle" sits at its midpoint. */
    val colliderType: String,
    /** Radius in canvas pixels; zero means "derive a default from the head height". */
    val colliderRadius: Float,
)

/** A limb the user can drag by its end, solved as a two-segment chain. */
data class IkChainSpec(val upper: String, val lower: String, val bend: Float)

data class LayerSpec(val bone: String, val z: Int)

/**
 * A depth rule: when [triggerBone] reaches past [referenceBone], the [parts] are drawn the
 * other side of [behind].
 *
 * This is what lets one arm be in front of the chest in one pose and behind the back in
 * another. A single fixed order cannot do both, which is exactly the problem the depth
 * editor exists to solve.
 */
data class SwapRuleSpec(
    val parts: List<String>,
    val behind: List<String>,
    /** false = put [parts] behind [behind], true = in front of it. */
    val toFront: Boolean,
    /** "tipAbove" or "tipBelow". */
    val triggerType: String,
    val triggerBone: String,
    val referenceBone: String,
)

/**
 * A character package: the skeleton, the drag chains and the draw order.
 *
 * Authored as joint positions, because that is what a person can measure off artwork.
 * [buildSkeleton] converts that into the parent-relative form the runtime needs.
 */
class CharacterSpec(
    val id: String,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val headHeight: Float,
    val bones: List<BoneSpec>,
    val ikChains: List<IkChainSpec>,
    val layers: List<LayerSpec>,
    val swaps: List<SwapRuleSpec>,
    /** Centre line of the figure in canvas coordinates. */
    val centreX: Float,
    /** Top of the skull; the figure occupies headTop..headTop+totalHeight. */
    val headTop: Float,
    val totalHeight: Float,
    /** Collider width for physics: the body, deliberately excluding the arms. */
    val bodyWidth: Float,
    val gravity: Float,
    val floorY: Float,
    /** Width of the play area. Wider than the canvas: the pet needs room to be thrown. */
    val worldWidth: Float,
) {

    /**
     * Bake authored head/tail pairs into parent-relative rest transforms.
     *
     * For a child, the head offset is rotated back by the parent's own rest angle, and
     * the rest rotation is the difference of the two authored world angles. Getting this
     * wrong is invisible in the rest pose and wrong the moment anything rotates, so the
     * Python toolkit asserts the round trip on every run.
     */
    fun buildSkeleton(): Skeleton {
        val specByName = bones.associateBy { it.name }
        val worldRest = bones.associate { it.name to atan2(it.tail.y - it.head.y, it.tail.x - it.head.x) }
        val built = LinkedHashMap<String, Bone>()
        var root: Bone? = null

        for (s in bones) {
            val parentSpec = s.parentName?.let {
                specByName[it] ?: error("bone '" + s.name + "' names an unknown parent '" + it + "'")
            }
            val parentBone = parentSpec?.let {
                built[it.name] ?: error("bone '" + s.name + "' appears before its parent '" + it.name + "'")
            }

            val dx = s.tail.x - s.head.x
            val dy = s.tail.y - s.head.y
            val length = hypot(dx, dy)
            require(length > 0f) { "bone '" + s.name + "' has zero length" }

            val restPos: Vec2
            val restRot: Float
            if (parentSpec == null) {
                restPos = s.head
                restRot = worldRest.getValue(s.name)
            } else {
                val parentWorld = worldRest.getValue(parentSpec.name)
                val ox = s.head.x - parentSpec.head.x
                val oy = s.head.y - parentSpec.head.y
                val a = -parentWorld
                restPos = Vec2(ox * cos(a) - oy * sin(a), ox * sin(a) + oy * cos(a))
                restRot = normalizeAngle(worldRest.getValue(s.name) - parentWorld)
            }

            val bone = Bone(
                name = s.name,
                restPosition = restPos,
                restRotation = restRot,
                length = length,
                // Authored in degrees because that is how a human reasons about a joint;
                // the runtime works in radians.
                minAngle = Math.toRadians(s.minAngle.toDouble()).toFloat(),
                maxAngle = Math.toRadians(s.maxAngle.toDouble()).toFloat(),
                springy = s.springy,
                stiffness = s.stiffness,
                damping = s.damping,
                gravity = s.gravity,
            )
            built[s.name] = bone
            if (parentBone != null) parentBone.attach(bone) else root = bone
        }

        return Skeleton(root ?: error("character '" + id + "' has no root bone"))
    }

    companion object {
        private fun vec(a: JSONArray) = Vec2(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())

        private fun strings(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            return (0 until a.length()).map { a.getString(it) }
        }

        fun parse(text: String): CharacterSpec {
            val o = JSONObject(text)
            val canvas = o.getJSONObject("canvas")

            val arr = o.getJSONArray("bones")
            val bones = (0 until arr.length()).map { i ->
                val b = arr.getJSONObject(i)
                val lim = b.optJSONArray("limits")
                val phys = b.optJSONObject("physics")
                val col = b.optJSONObject("collider")
                BoneSpec(
                    name = b.getString("name"),
                    parentName = if (b.isNull("parent")) null else b.getString("parent"),
                    head = vec(b.getJSONArray("head")),
                    tail = vec(b.getJSONArray("tail")),
                    minAngle = lim?.optDouble(0)?.toFloat() ?: -180f,
                    maxAngle = lim?.optDouble(1)?.toFloat() ?: 180f,
                    springy = b.optBoolean("spring", false),
                    stiffness = phys?.optDouble("stiffness")?.toFloat() ?: 0.35f,
                    damping = phys?.optDouble("damping")?.toFloat() ?: 0.86f,
                    gravity = phys?.optDouble("gravity")?.toFloat() ?: 0f,
                    colliderType = col?.optString("type", "capsule") ?: "capsule",
                    colliderRadius = col?.optDouble("radius")?.toFloat() ?: 0f,
                )
            }

            val chainsArr = o.optJSONArray("ikChains")
            val chains = (0 until (chainsArr?.length() ?: 0)).map { i ->
                val c = chainsArr!!.getJSONObject(i)
                IkChainSpec(c.getString("upper"), c.getString("lower"), c.optDouble("bend", 1.0).toFloat())
            }

            val layersArr = o.optJSONArray("layers")
            val layers = (0 until (layersArr?.length() ?: 0)).map { i ->
                val l = layersArr!!.getJSONObject(i)
                LayerSpec(l.getString("bone"), l.getInt("z"))
            }

            val swapsArr = o.optJSONArray("layerSwaps")
            val swaps = (0 until (swapsArr?.length() ?: 0)).map { i ->
                val s = swapsArr!!.getJSONObject(i)
                val trigger = s.optJSONObject("trigger")
                SwapRuleSpec(
                    parts = strings(s.optJSONArray("parts")),
                    behind = strings(s.optJSONArray("behind")),
                    toFront = s.optString("to", "behind") == "front",
                    triggerType = trigger?.optString("type", "tipAbove") ?: "tipAbove",
                    triggerBone = trigger?.optString("bone", "") ?: "",
                    referenceBone = trigger?.optString("reference", "") ?: "",
                )
            }

            val canvasW = canvas.getDouble("width").toFloat()
            val props = o.optJSONObject("proportions")
            val phys = o.optJSONObject("physics")

            return CharacterSpec(
                swaps = swaps,
                id = o.optString("id", "unnamed"),
                canvasWidth = canvasW,
                canvasHeight = canvas.getDouble("height").toFloat(),
                headHeight = o.optDouble("headHeight", 0.0).toFloat(),
                bones = bones,
                ikChains = chains,
                layers = layers,
                centreX = o.optDouble("centreX", (canvasW / 2f).toDouble()).toFloat(),
                headTop = o.optDouble("headTopY", 0.0).toFloat(),
                totalHeight = props?.optDouble("totalHeightPx")?.toFloat() ?: 0f,
                bodyWidth = phys?.optDouble("bodyWidth")?.toFloat() ?: (canvasW * 0.32f),
                gravity = phys?.optDouble("gravity")?.toFloat() ?: 2400f,
                floorY = phys?.optDouble("floorY")?.toFloat() ?: canvas.getDouble("height").toFloat(),
                worldWidth = phys?.optDouble("worldWidth")?.toFloat() ?: (canvasW * 3f),
            )
        }
    }
}