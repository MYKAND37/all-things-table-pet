package dev.atp.pet.engine.physics

import dev.atp.pet.engine.math.Vec2
import kotlin.math.abs

/** Axis-aligned sandbox limits, in world units. */
data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

/**
 * A very small 2D physics sandbox: gravity, four walls and a floor.
 *
 * Integration runs on a fixed step so behaviour does not change with frame rate — a
 * phone that drops to 30fps must not make the character fall at half speed. Whatever
 * time is left over is carried into the next call.
 */
class PhysicsWorld(
    var gravity: Float = 2400f,
    var bounds: Bounds = Bounds(0f, 0f, 1024f, 2048f),
) {
    private val bodies = mutableListOf<PhysicsBody>()
    private var accumulator = 0f

    /** Below these speeds a body in contact is considered settled. */
    private val sleepSpeed = 7f

    fun add(body: PhysicsBody): PhysicsBody {
        bodies.add(body)
        return body
    }

    fun remove(body: PhysicsBody) {
        bodies.remove(body)
    }

    fun step(deltaSeconds: Float) {
        // A long stall (a paused app, a slow frame) must not teleport the character
        // through the floor, so the frame is clamped before it is spent.
        accumulator += deltaSeconds.coerceIn(0f, 0.05f)
        while (accumulator >= FIXED_STEP) {
            integrate(FIXED_STEP)
            accumulator -= FIXED_STEP
        }
    }

    private fun integrate(dt: Float) {
        for (b in bodies) {
            if (b.held) {
                b.grounded = false
                continue
            }
            if (b.resting) continue

            var vx = b.velocity.x
            var vy = b.velocity.y + gravity * b.gravityScale * dt

            val damp = (1f - b.drag * dt).coerceIn(0f, 1f)
            vx *= damp
            vy *= damp

            var x = b.position.x + vx * dt
            var y = b.position.y + vy * dt

            var grounded = false

            // Floor.
            val floor = bounds.maxY - b.halfExtents.y
            if (y > floor) {
                y = floor
                grounded = true
                if (vy > 40f) {
                    vy = -vy * b.restitution      // a real bounce
                } else {
                    vy = 0f                        // a landing, not a bounce
                }
                vx *= (1f - b.groundFriction * dt).coerceIn(0f, 1f)
            }

            // Ceiling and walls.
            val ceil = bounds.minY + b.halfExtents.y
            if (y < ceil) {
                y = ceil
                vy = if (vy < -40f) -vy * b.restitution else 0f
            }

            val leftLimit = bounds.minX + b.halfExtents.x
            val rightLimit = bounds.maxX - b.halfExtents.x
            if (x < leftLimit) {
                x = leftLimit
                vx = if (vx < -40f) -vx * b.restitution else 0f
            } else if (x > rightLimit) {
                x = rightLimit
                vx = if (vx > 40f) -vx * b.restitution else 0f
            }

            b.position = Vec2(x, y)
            b.velocity = Vec2(vx, vy)
            b.grounded = grounded

            if (grounded && abs(vx) < sleepSpeed && abs(vy) < sleepSpeed) {
                b.velocity = Vec2.ZERO
                b.resting = true
            }
        }
    }

    /** Drop every body to rest and forget any motion. */
    fun clearMotion() {
        bodies.forEach { it.stop(); it.resting = false }
    }

    companion object {
        private const val FIXED_STEP = 1f / 120f
    }
}
