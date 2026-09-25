package dev.atp.pet.engine.skeleton

import dev.atp.pet.engine.anim.Anim
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
    // All four are mutable: the bone editor moves a joint, reparents a limb and deletes a
    // bone outright. Head and tail are in canvas coordinates, which is also exactly the
    // rest pose, which is why editing works in canvas space and needs no pose to be undone
    // first -- and why reparenting never moves anything. Nothing is re-derived from the
    // parent until the rig is baked again.
    var name: String,
    var parentName: String?,
    var head: Vec2,
    var tail: Vec2,
    // These five are mutable too: the attribute editor is where a joint's range of motion
    // and its collider are decided, and both of them are things you tune by watching the
    // figure move rather than by typing numbers into a file.
    var minAngle: Float,
    var maxAngle: Float,
    val springy: Boolean,
    var stiffness: Float,
    val damping: Float,
    val gravity: Float,
    /** "capsule" runs along the bone, "circle" sits at its midpoint. */
    var colliderType: String,
    /** Radius in canvas pixels; zero means "derive a default from the head height". */
    var colliderRadius: Float,
    /**
     * Whether the world can feel this part, and whether a finger can take hold of it.
     *
     * Two switches rather than one because they answer different questions. A hair ribbon that
     * should not shove the table around is collides = false and still perfectly grabbable; a
     * bone that exists so a rule can name it -- what 让手流汗 hangs off -- is grabbable = false
     * and still there as far as the floor is concerned. Both default to true, so a file written
     * before they existed behaves exactly as it always did.
     */
    var collides: Boolean = true,
    var grabbable: Boolean = true,
)

/**
 * A point on a bone that the rules can name.
 *
 * A 部位 is a bone, and a bone is a whole limb: you cannot say "the tip of the middle finger"
 * or "the joint at the shoulder" and have the rules do something about it. A node is that
 * point. It has no length, no joint and nothing of its own to solve -- it is a name for a
 * place on the figure, and everything it can do follows from being one:
 *
 *  * the world can feel it, so 被道具碰到 and 被点一下 can name it;
 *  * a finger can take hold of it, which pulls the bone it sits on at exactly that spot;
 *  * a rope or a nail can be tied to it, for the same reason;
 *  * a rule can hide it, and hiding a node hides what is worn at it;
 *  * and it can carry a prop, which is what "手里拿着东西" is.
 *
 * [at] is a distance along the bone from its JOINT, in canvas pixels, so 0 is the joint
 * itself and the bone's own length is its tip -- the two ends the user asked for, and
 * everything between them for free. It is stored as a number rather than as "head/tip"
 * because a bone that is resized afterwards should move its tip node with it, not leave it
 * hanging in the air where the old tip was.
 */
data class NodeSpec(
    var name: String,
    var bone: String,
    var at: Float = 0f,
    /**
     * How near something has to be to touch it. A node has no length, so this is the whole of
     * its size -- and it is deliberately its own number rather than the bone's: a fingertip
     * sensor and a shoulder joint want to be felt from different distances.
     */
    var radius: Float = 26f,
    /** A prop carried at this point, by id. Empty means nothing is worn here. */
    var prop: String = "",
    /**
     * 手指能不能抓住这个点（1.26.0）。
     *
     * 关掉之后它**照样存在**：道具还挂在这儿、绳子还系在这儿、物理照样看得见它 —— 只是
     * 手指按上去不再把这一节拽走。给的是"这一节我不想让人拖"的场合（比如一整条手臂挂在
     * 肩上时，指尖那个点会抢走手指），而不是"把这个点删掉"。
     */
    var draggable: Boolean = true,
)

/** A limb the user can drag by its end, solved as a two-segment chain. */
data class IkChainSpec(val upper: String, val lower: String, val bend: Float)

/**
 * One part in the draw order.
 *
 * [state] is an optional name from the character's own logic: the part is drawn only while
 * that state is on. That is what "穿衣服 / 不穿衣服" is — the same rig, wearing a different
 * set of artwork — and it costs one layer field rather than a whole second character.
 */
data class LayerSpec(
    val bone: String,
    val z: Int,
    /**
     * Drawn only while these switches are as written — **all of them** (they are joined with
     * `+`, and a leading "!" flips one). That is what makes a replacement work: the plain arm
     * says `!mech` and the mechanical one says `mech`, and exactly one of them is ever drawn.
     *
     * 为什么是**一串**（1.26.0）：一根骨头可以有好几套替换图（三件衣服），而"平时那张"
     * 必须在**它们任何一个**开着的时候都让位。一个 `state` 只能写一个开关，于是老实现里
     * 只有第一次替换真的生效 —— 用户报的「导入了三个部位状态结果只显示第一个」就是它。
     */
    val state: String = "",
    /** Which artwork file, without .png. Empty means "the one named after the bone". */
    val art: String = "",
    /**
     * 同一根骨头上，**只有最高那一档会画**（1.26.0）。
     *
     * 「当多个状态开启时优先显示哪个」：三件替换衣服同时开着时，画权重最大的那一件。
     * 默认 0 —— 也就是所有旧数据、以及"叠加"那一类图（它们和底图同档，所以照旧一起画）。
     */
    val prio: Int = 0,
) {
    val artKey: String get() = if (art.isEmpty()) bone else art

    /** 这一层写着的开关，拆成一个个（和动画帧那一半同一个分隔符）。 */
    val states: List<String> get() = Anim.statesOf(state)

    /**
     * Does this layer draw, given the states that are on? Unknown states read as off.
     *
     * 一串开关是**与**：每一个都要满足（`!a+!b` = "a 关着而且 b 也关着"）。
     */
    fun visible(states: Map<String, Boolean>): Boolean {
        if (state.isEmpty()) return true
        for (one in this.states) {
            val on = states[one.removePrefix("!")] == true
            if (one.startsWith("!")) {
                if (on) return false
            } else if (!on) {
                return false
            }
        }
        return true
    }
}

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
    /** The ART sheet the drawings were made on. The room the pet moves in is taller. */
    val canvasHeight: Float,
    val headHeight: Float,
    /** Mutable, and ordered parents-first: that is the order [buildSkeleton] requires. */
    val bones: MutableList<BoneSpec>,
    /** Points on those bones the rules can name. See [NodeSpec]. Empty in older files. */
    val nodes: MutableList<NodeSpec>,
    val ikChains: List<IkChainSpec>,
    /** Mutable: a bone that is added or deleted changes the draw order with it. */
    val layers: MutableList<LayerSpec>,
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
    /**
     * The roof of the room, in canvas coordinates. 0 is the artwork's own top edge and is what
     * every character had until the owner asked for more air; a negative number lifts it.
     *
     * The floor had a depth of its own and the ceiling did not, so a pet could be thrown up
     * exactly as far as the drawing was tall -- about two body lengths -- and then stopped
     * against a roof nobody had drawn.
     */
    val ceilingY: Float,
    /** Width of the play area. Wider than the canvas: the pet needs room to be thrown. */
    val worldWidth: Float,
    /**
     * How far below the artwork's own ground line the floor is, in px.
     *
     * The bones stay in the art canvas' coordinates — that is what the alignment view, the
     * rig editor and the saved rig file all speak, and shifting them would make a rig drift
     * down the canvas a little every time it was saved. The figure is stood on the real floor
     * by moving its root down by this much instead. See [ROOM_AIR].
     */
    val standOffset: Float,
) {


    /** Back to front. Everything that draws asks for this rather than sorting layers. */
    fun drawOrder(): List<LayerSpec> = layers.sortedBy { it.z }

    /**
     * 一根骨头在世界里的碰撞半径（像素）：写了就用写的，空着就按身高自动算。
     *
     * **这是唯一一处判断**，而且求解器（Ragdoll）和骨骼编辑器（SkeletonView 画碰撞范围）
     * 都调它 —— 编辑器画出来的圆必须就是道具真正撞上的那个圆，不然那个功能就是在骗人。
     */
    fun colliderRadiusOf(bone: BoneSpec): Float =
        if (bone.colliderRadius > 0f) bone.colliderRadius else headHeight * AUTO_COLLIDER_RATIO


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

        val skeleton = Skeleton(root ?: error("character '" + id + "' has no root bone"))
        // Nodes are handed over rather than rebuilt: they are names for places on the bones,
        // and the bones are already here. A node whose bone was deleted comes along as a node
        // pointing at nothing -- nodePoint answers the rig's origin for it, which is a place
        // the rules can name and nothing can ever touch, and the editor is where that gets
        // noticed. Dropping it silently would be the worse of the two.
        skeleton.nodes = nodes
        return skeleton
    }

    companion object {
        /**
         * How much air the room has above the ground line, as a multiple of the figure's height.
         *
         * A room one figure tall is enough to stand in and nothing else. Picking the pet up by
         * one ankle turns it over, and an inverted figure needs its whole length BELOW the hand
         * holding it — so the hand has to go up by about a figure's height, and on a phone the
         * finger has only the screen to travel across. The taller the room, the smaller the
         * piece of the screen one pixel of lift costs, which is the whole reason this is a room
         * and not a floor. Mirrors ROOM_AIR in tools/skeleton_tool.py.
         */
        const val ROOM_AIR = 1.0f

        /** A room never gets less air than this, however small the figure is. */
        const val MIN_AIR = 720f

        /**
         * 没写半径时，碰撞半径和身高成这个比例。
         *
         * 0.18 是量出来的老数（Ragdoll 从第一版就用它）：比一根前臂细、比一根手指粗，
         * 让「人形」在道具面前既不漏也不糊。求解器和骨骼编辑器共用 [colliderRadiusOf]。
         */
        const val AUTO_COLLIDER_RATIO = 0.18f

        /** What a head is, as a fraction of the figure, when a file does not say. */
        const val HEAD_OF_FIGURE = 0.163f

        private fun vec(a: JSONArray) = Vec2(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())

        /**
         * A number out of the file, or the fallback. Never NaN.
         *
         * This exists because of a bug that shipped: org.json's ONE-ARGUMENT optDouble answers
         * NaN for a key that is not in the object, and NaN is not null — so the obvious
         * "optDouble(key) ?: fallback" hands NaN to the caller and the elvis never fires. Here
         * that turned ROOM_AIR — a value derived from the figure and never written to a file
         * — into NaN, which made the floor NaN, every bone's position NaN, and the pet
         * invisible: nothing on the bench at all except the HUD, which is drawn in screen
         * coordinates and does not care.
         *
         * A number that is present but not finite is treated as missing, for the same reason:
         * a physics engine fed a NaN is a physics engine that draws nothing.
         */
        private fun num(o: JSONObject?, key: String, fallback: Float): Float {
            if (o == null || !o.has(key)) return fallback
            val v = try {
                o.optDouble(key, fallback.toDouble()).toFloat()
            } catch (e: Exception) {
                return fallback
            }
            return if (v.isFinite()) v else fallback
        }

        /** The same, for a number inside an array: limits are written as a two-element list. */
        private fun num(a: JSONArray?, index: Int, fallback: Float): Float {
            if (a == null || index >= a.length()) return fallback
            val v = try {
                a.optDouble(index, fallback.toDouble()).toFloat()
            } catch (e: Exception) {
                return fallback
            }
            return if (v.isFinite()) v else fallback
        }

        private fun strings(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            return (0 until a.length()).map { a.getString(it) }
        }

        /**
         * The same parse, for the callers that have a screen to keep alive.
         *
         * Every screen that shows a character reads this file, and a file can be half-written
         * by a full disk, truncated by a crash, or hand-edited into nonsense. parse() stays
         * strict -- it is the one place that decides what a valid character IS -- and the
         * screens use this instead, because the alternative is an app that closes on startup
         * with no way back in short of clearing its data.
         */
        fun parseOrNull(text: String): CharacterSpec? = try {
            parse(text)
        } catch (e: Exception) {
            null
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
                    minAngle = num(lim, 0, -180f),
                    maxAngle = num(lim, 1, 180f),
                    springy = b.optBoolean("spring", false),
                    // THIS joint's spring, as a multiple of the character's stiffness: 1.0
                    // is "as stiff as the slider says", 0 is limp whatever the slider says.
                    //
                    // It defaults to 1.0 because the field is finally read (see
                    // Ragdoll.step): until 1.10.0 the number was parsed and then ignored, so
                    // a file that wrote 0.35 got a joint that behaved exactly like a joint
                    // that wrote nothing. A hand-edited 0.35 now really is 35%.
                    stiffness = num(phys, "stiffness", 1f),
                    damping = num(phys, "damping", 0.86f),
                    gravity = num(phys, "gravity", 0f),
                    colliderType = col?.optString("type", "capsule") ?: "capsule",
                    colliderRadius = num(col, "radius", 0f),
                    // Absent means yes: every file written before these existed has bones that
                    // collide and can be picked up, and that is what they have to keep.
                    collides = b.optBoolean("collides", true),
                    grabbable = b.optBoolean("grabbable", true),
                )
            }.toMutableList()

            // Absent in every file written before nodes existed, and that has to mean "none"
            // rather than "work something out": there is nothing to derive a node from.
            val nodesArr = o.optJSONArray("nodes")
            val nodes = (0 until (nodesArr?.length() ?: 0)).map { i ->
                val n = nodesArr!!.getJSONObject(i)
                NodeSpec(
                    name = n.getString("name"),
                    bone = n.getString("bone"),
                    at = n.optDouble("at", 0.0).toFloat(),
                    radius = n.optDouble("radius", 26.0).toFloat(),
                    prop = n.optString("prop", ""),
                    // 缺键 = 能拖 = 老行为。
                    draggable = n.optBoolean("draggable", true),
                )
            }.toMutableList()

            val chainsArr = o.optJSONArray("ikChains")
            val chains = (0 until (chainsArr?.length() ?: 0)).map { i ->
                val c = chainsArr!!.getJSONObject(i)
                IkChainSpec(c.getString("upper"), c.getString("lower"), num(c, "bend", 1f))
            }

            val layersArr = o.optJSONArray("layers")
            val layers = (0 until (layersArr?.length() ?: 0)).map { i ->
                val l = layersArr!!.getJSONObject(i)
                LayerSpec(
                    l.getString("bone"), l.getInt("z"),
                    l.optString("state", ""), l.optString("art", ""),
                    l.optInt("prio", 0),
                )
            }.toMutableList()

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

            // A character drawn against the old, one-figure-tall template gets the deeper
            // room too, which is why this is computed here and not left to each file. The
            // value is derived, never written back: a rig round-trips through the editor in
            // art coordinates and gains nothing.
            // How tall the figure is, off the authored joints. Two things are derived from it
            // and both of them go wrong SILENTLY: the air above the floor (below), and the head
            // height, which is the size of everything the physics has no other number for --
            // how thick a bone is by default, and how far a finger may be from a bone and still
            // take hold of it.
            val rigTop = bones.minOf { kotlin.math.min(it.head.y, it.tail.y) }
            val rigBottom = bones.maxOf { kotlin.math.max(it.head.y, it.tail.y) }
            val rigSpan = kotlin.math.max(1f, rigBottom - rigTop)

            val artFloor = num(phys, "floorY", canvas.getDouble("height").toFloat())
            // ROOM_AIR is never written into a character file -- it is derived from the figure
            // every time -- and that is exactly why this read is dangerous: a key that is not
            // there has to fall through to the derivation, and org.json's one-argument
            // optDouble answers NaN for a missing key rather than null. See num().
            val air = if (phys != null && phys.has("roomAir")) {
                num(phys, "roomAir", MIN_AIR)
            } else {
                kotlin.math.max(MIN_AIR, rigSpan * ROOM_AIR)
            }

            return CharacterSpec(
                swaps = swaps,
                id = o.optString("id", "unnamed"),
                canvasWidth = canvasW,
                canvasHeight = canvas.getDouble("height").toFloat(),
                // A file with no head height is a file whose pet cannot be picked up: the grab
                // tolerance is measured in head heights, so zero means no finger is ever close
                // enough to a bone to take hold of it. Derived from the rig instead, at the
                // proportion the template uses (276 of 1690).
                headHeight = num(o, "headHeight", 0f).let {
                    if (it > 0f) it else rigSpan * HEAD_OF_FIGURE
                },
                bones = bones,
                nodes = nodes,
                ikChains = chains,
                layers = layers,
                centreX = num(o, "centreX", canvasW / 2f),
                headTop = num(o, "headTopY", 0f),
                totalHeight = num(props, "totalHeightPx", 0f),
                bodyWidth = num(phys, "bodyWidth", canvasW * 0.32f),
                gravity = num(phys, "gravity", 2400f),
                floorY = artFloor + air,
                ceilingY = num(phys, "ceilingY", 0f),
                worldWidth = num(phys, "worldWidth", canvasW * 3f),
                standOffset = air,
            )
        }
    }
}