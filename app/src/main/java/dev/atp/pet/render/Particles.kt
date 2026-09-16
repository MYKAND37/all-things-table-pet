package dev.atp.pet.render

import android.graphics.Canvas
import android.graphics.Paint
import dev.atp.pet.engine.math.Vec2
import dev.atp.pet.engine.particle.ParticleKinds
import dev.atp.pet.engine.particle.ParticleSpec
import kotlin.math.max
import kotlin.random.Random

/**
 * The splashes a rule can ask for.
 *
 * These are particles, not a fluid: they fall, they land, and the ones that should leave a
 * mark leave one. A real simulation (pressure, flow, pooling) is a different project, and
 * the thing a table pet actually needs is "it got hit and something came off".
 */
//: What a kind of particle IS -- its colour, its size and its three switches -- lives in
//: dev.atp.pet.engine.particle.ParticleSpec, because it is written into the character's file
//: and edited in 粒子管理. This file only knows how to move them.
object ParticlesDefaults {
    /** Mirrors ParticleKinds.DEFAULTS, for the callers that just want a colour to draw. */
    val KINDS: List<ParticleSpec> get() = ParticleKinds.DEFAULTS
}

/**
 * Every particle and every mark on the floor.
 *
 * Positions are world coordinates, so this scales and pans with the rest of the scene and
 * needs no transform of its own.
 */
class Particles {

    private class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        val maxLife: Float,
        val size: Float,
        val colour: Int,
        val gravity: Float,
        val stains: Boolean,
    )

    private class Stain(
        var x: Float,
        val y: Float,
        var r: Float,
        val colour: Int,
        var life: Float,
        val maxLife: Float,
    )

    private val particles = ArrayList<Particle>()
    private val stains = ArrayList<Stain>()
    private val random = Random(7717)

    /**
     * The kinds this character declares, kept here the same way the bench keeps its liquids:
     * a spray has to look its colour up by name, and 粒子管理 edits the list without
     * reloading the bench (which would throw away whatever is mid-fall on it).
     */
    private var kinds: List<ParticleSpec> = ParticleKinds.DEFAULTS

    fun setKinds(list: List<ParticleSpec>) {
        kinds = list
    }

    /** The kinds as they are right now, for a screen that wants to show them. */
    fun kinds(): List<ParticleSpec> = kinds

    val size: Int get() = particles.size
    val stainCount: Int get() = stains.size

    fun clear() {
        particles.clear()
        stains.clear()
    }

    /**
     * Spray from a point. [count] is what the rule asked for, so a rule that says 14 gets
     * 14 whatever the frame rate is doing.
     */
    fun burst(kindId: String, at: Vec2, count: Int) {
        val kind = ParticleKinds.of(kindId, kinds)
        val n = count.coerceIn(0, 60)
        for (i in 0 until n) {
            if (particles.size >= MAX_PARTICLES) break
            val angle = random.nextFloat() * TWO_PI
            val speed = 120f + random.nextFloat() * 520f
            val life = 0.5f + random.nextFloat() * 0.9f
            particles.add(
                Particle(
                    x = at.x + (random.nextFloat() - 0.5f) * 24f,
                    y = at.y + (random.nextFloat() - 0.5f) * 24f,
                    vx = kotlin.math.cos(angle) * speed,
                    vy = kotlin.math.sin(angle) * speed - 260f,
                    life = life,
                    maxLife = life,
                    size = (3f + random.nextFloat() * 5f) * kind.size,
                    colour = kind.colour,
                    // The switch, not a special case for two of the six names: floating was
                    // hardcoded for stars and hearts until a kind could be edited.
                    gravity = if (kind.gravity) FALLING else FLOATING,
                    stains = kind.stains,
                )
            )
        }
    }

    fun step(dt: Float, floor: Float) {
        var i = 0
        while (i < particles.size) {
            val p = particles[i]
            p.life -= dt
            p.vy += p.gravity * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= max(0f, 1f - 0.6f * dt)
            if (p.life <= 0f) {
                particles.removeAt(i)
                continue
            }
            if (p.y >= floor) {
                if (p.stains) mark(p.x, floor, p.size, p.colour)
                particles.removeAt(i)
                continue
            }
            i++
        }
        var j = 0
        while (j < stains.size) {
            val s = stains[j]
            s.life -= dt
            s.r += 1.6f * dt
            if (s.life <= 0f) stains.removeAt(j) else j++
        }
    }

    /** A mark is left where the drop landed, which is the floor, not where it started. */
    private fun mark(x: Float, y: Float, size: Float, colour: Int) {
        if (stains.size >= MAX_STAINS) stains.removeAt(0)
        val life = 5f + random.nextFloat() * 4f
        stains.add(Stain(x, y, size * 0.9f, colour, life, life))
    }

    fun draw(canvas: Canvas, paint: Paint) {
        for (s in stains) {
            paint.style = Paint.Style.FILL
            paint.alpha = ((s.life / s.maxLife).coerceIn(0f, 1f) * 90f).toInt()
            paint.color = s.colour
            canvas.drawCircle(s.x, s.y, s.r, paint)
        }
        for (p in particles) {
            paint.style = Paint.Style.FILL
            paint.alpha = ((p.life / p.maxLife).coerceIn(0f, 1f) * 255f).toInt()
            paint.color = p.colour
            canvas.drawCircle(p.x, p.y, p.size, paint)
        }
        paint.alpha = 255
    }

    companion object {
        private const val MAX_PARTICLES = 400
        private const val MAX_STAINS = 60
        private const val TWO_PI = 6.2831855f

        /** How hard a particle that HAS gravity is pulled, and what a floating one gets. */
        const val FALLING = 1300f
        const val FLOATING = 140f
    }
}
