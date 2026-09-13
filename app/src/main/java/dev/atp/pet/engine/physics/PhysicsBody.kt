package dev.atp.pet.engine.physics

import dev.atp.pet.engine.math.Vec2

/**
 * A rigid body.
 *
 * Linear only for now: the character falls, lands and slides, but does not tumble.
 * Rotation is deliberately absent rather than half-done — a body that spins needs a
 * real inertia tensor and contact points with torque, and a pet that pivots about
 * nothing looks worse than one that simply stays upright.
 *
 * The collider is an axis-aligned box described by half-extents around [position].
 */
class PhysicsBody(
    var position: Vec2,
    var velocity: Vec2 = Vec2.ZERO,
    val halfExtents: Vec2,
    var restitution: Float = 0.28f,
    /** Fraction of horizontal speed kept per second while touching the ground. */
    var groundFriction: Float = 6.0f,
    /** Fraction of speed lost per second to the air. */
    var drag: Float = 0.35f,
    var gravityScale: Float = 1f,
) {
    /** True while a finger holds it: gravity and contact response are skipped. */
    var held: Boolean = false

    /** True once it has settled; the integrator skips it until something disturbs it. */
    var resting: Boolean = false

    /** Set by the last [PhysicsWorld.step] so the view can report what happened. */
    var grounded: Boolean = false

    val left: Float get() = position.x - halfExtents.x
    val right: Float get() = position.x + halfExtents.x
    val top: Float get() = position.y - halfExtents.y
    val bottom: Float get() = position.y + halfExtents.y

    fun wake() {
        resting = false
    }

    fun stop() {
        velocity = Vec2.ZERO
    }
}
