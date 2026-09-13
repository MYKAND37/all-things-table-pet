package dev.atp.pet.engine.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A 2D point/vector. Screen convention: +x right, +y **down**. */
data class Vec2(val x: Float, val y: Float) {

    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun div(s: Float) = Vec2(x / s, y / s)

    fun length() = sqrt(x * x + y * y)
    fun lengthSquared() = x * x + y * y

    /** Rotate about the origin. Positive turns from +x toward +y, i.e. clockwise on screen. */
    fun rotated(radians: Float): Vec2 {
        val c = cos(radians)
        val s = sin(radians)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    fun normalized(): Vec2 {
        val len = length()
        return if (len < 1e-6f) ZERO else Vec2(x / len, y / len)
    }

    companion object {
        val ZERO = Vec2(0f, 0f)
        fun of(radians: Float) = Vec2(cos(radians), sin(radians))
    }
}

/** Angle of the vector [from]→[to]. */
fun angleBetween(from: Vec2, to: Vec2): Float {
    val d = to - from
    return kotlin.math.atan2(d.y, d.x)
}

/** Wrap into (-PI, PI]. */
fun normalizeAngle(radians: Float): Float {
    var a = radians
    val twoPi = (Math.PI * 2.0).toFloat()
    while (a > Math.PI) a -= twoPi
    while (a <= -Math.PI) a += twoPi
    return a
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
