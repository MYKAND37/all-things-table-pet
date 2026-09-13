package dev.atp.pet.render

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import dev.atp.pet.engine.math.Transform
import dev.atp.pet.engine.skeleton.Skeleton
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws part images attached to bones.
 *
 * The whole trick is one line of algebra. A part is authored in the character's canvas
 * space, and at rest it must appear exactly where the artist put it. So the transform
 * for a bone is
 *
 *     canvas -> world  =  world(bone now)  composed with  inverse(world(bone at rest))
 *
 * At rest that is the identity, which is why no calibration step exists anywhere in this
 * project. When the bone turns, the part turns with it about the bone's own joint.
 */
class PartRenderer(
    private val skeleton: Skeleton,
    private val library: PartLibrary,
    /** Bone names in back-to-front draw order. */
    drawOrder: List<String>,
) {
    private val restWorld = HashMap<String, Transform>()
    private val order = drawOrder.filter { library.parts.containsKey(it) }
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        // Capture the rest pose once, before anything has been posed. The skeleton may
        // already be displaced, so pin it to identity for the measurement and put it back.
        val savedRoot = skeleton.rootTransform
        skeleton.rootTransform = Transform.IDENTITY
        skeleton.reset()
        for (bone in skeleton.bones) {
            restWorld[bone.name] = bone.worldTransform
        }
        skeleton.rootTransform = savedRoot
        skeleton.update()
    }

    fun draw(canvas: Canvas) {
        for (name in order) {
            val part = library.parts[name] ?: continue
            val bone = skeleton.find(name) ?: continue
            val rest = restWorld[name] ?: continue

            val t = bone.worldTransform.compose(rest.inverse())

            // The crop offset is applied to the bitmap BEFORE the bone transform, so it
            // has to be rotated and scaled into place by hand.
            val c = cos(t.rotation) * t.scale
            val s = sin(t.rotation) * t.scale
            val tx = t.position.x + part.offsetX * c - part.offsetY * s
            val ty = t.position.y + part.offsetX * s + part.offsetY * c

            matrix.setValues(
                floatArrayOf(
                    c, -s, tx,
                    s, c, ty,
                    0f, 0f, 1f,
                )
            )
            canvas.drawBitmap(part.bitmap, matrix, paint)
        }
    }
}
