package dev.atp.pet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.skeleton.CharacterSpec
import kotlin.math.min

/**
 * A stick figure of the character holding one saved action.
 *
 * An action list is a list of names, and a name says nothing about what the pose actually
 * looks like a month after it was saved. The skeleton alone is enough to draw one — no
 * bitmaps, no part library — so every row in the list can show what it does.
 *
 * The figure is fitted to the thumbnail by its own bounds rather than by the canvas, so a
 * crouch that never leaves the lower half of the canvas still fills the box.
 */
object PosePreview {

    /** Anything the spec can throw (bad JSON, a missing bone) just means no picture. */
    fun render(
        spec: CharacterSpec,
        angles: Map<String, Float>,
        px: Int,
        colour: Int,
    ): Bitmap? = try {
        val skeleton = spec.buildSkeleton()
        for (bone in skeleton.bones) angles[bone.name]?.let { bone.rotation = it }
        skeleton.update()
        draw(skeleton.bones.map { it.worldPosition to it.tipPosition() }, px, colour)
    } catch (e: Exception) {
        null
    }

    private fun draw(
        segments: List<Pair<Vec2, Vec2>>,
        px: Int,
        colour: Int,
    ): Bitmap? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for ((head, tip) in segments) {
            if (head.x < minX) minX = head.x
            if (head.y < minY) minY = head.y
            if (tip.x < minX) minX = tip.x
            if (tip.y < minY) minY = tip.y
            if (head.x > maxX) maxX = head.x
            if (head.y > maxY) maxY = head.y
            if (tip.x > maxX) maxX = tip.x
            if (tip.y > maxY) maxY = tip.y
        }
        val spanX = maxX - minX
        val spanY = maxY - minY
        if (!spanX.isFinite() || !spanY.isFinite()) return null
        if (spanX < 1f && spanY < 1f) return null

        val box = px.toFloat()
        val pad = box * 0.1f
        val scale = min((box - pad * 2f) / spanX.coerceAtLeast(1f), (box - pad * 2f) / spanY.coerceAtLeast(1f))
        val offsetX = (box - spanX * scale) / 2f - minX * scale
        val offsetY = (box - spanY * scale) / 2f - minY * scale

        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = (box / 18f).coerceAtLeast(1.5f)
            this.color = colour
        }
        val joint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = colour
        }
        for ((head, tip) in segments) {
            canvas.drawLine(
                offsetX + head.x * scale, offsetY + head.y * scale,
                offsetX + tip.x * scale, offsetY + tip.y * scale,
                line,
            )
        }
        // A joint dot on every head: without them a bent limb reads as one long bone.
        for ((head, _) in segments) {
            canvas.drawCircle(offsetX + head.x * scale, offsetY + head.y * scale, line.strokeWidth * 0.6f, joint)
        }
        return bitmap
    }
}
