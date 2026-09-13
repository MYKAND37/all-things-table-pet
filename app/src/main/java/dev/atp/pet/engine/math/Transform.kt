package dev.atp.pet.engine.math

/**
 * Translation + rotation (+ uniform scale).
 *
 * The engine has exactly one rule for how a joint's placement is derived:
 *
 *     world(bone) = world(parent) ∘ local(bone)
 *
 * Nothing else is allowed to invent a world transform — that invariant is what keeps
 * dragging, physics and rendering from drifting apart.
 */
data class Transform(
    val position: Vec2 = Vec2.ZERO,
    val rotation: Float = 0f,
    val scale: Float = 1f,
) {
    fun compose(child: Transform): Transform = Transform(
        position = position + (child.position * scale).rotated(rotation),
        rotation = rotation + child.rotation,
        scale = scale * child.scale,
    )

    /** Bring a point from this frame into the parent frame. */
    fun apply(point: Vec2): Vec2 = position + (point * scale).rotated(rotation)

    /** Local +x axis, expressed in the parent frame. */
    fun direction(): Vec2 = Vec2.of(rotation)

    fun inverse(): Transform {
        val invScale = if (scale == 0f) 1f else 1f / scale
        val invRotation = -rotation
        val p = (Vec2.ZERO - position).rotated(invRotation) * invScale
        return Transform(p, invRotation, invScale)
    }

    companion object {
        val IDENTITY = Transform()
    }
}
