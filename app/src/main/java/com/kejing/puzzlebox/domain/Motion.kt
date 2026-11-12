package com.kejing.puzzlebox.domain

import kotlin.math.PI
import kotlin.math.sin

/**
 * One leg of a block's animation (design spatialLayout.pieces, D51 "按物理"): eased flight with an optional arc, a fall
 * that speeds up and ends with a small bounce, a sideways shake (rejected), or a hold.
 */
data class Seg(
    val from: Pose,
    val to: Pose,
    val ms: Long,
    val kind: Kind = Kind.FLY,
    /** Extra height at mid-flight (world +Y), metres. */
    val arc: Float = 0f,
    /** Bounce height at the end of a fall (box up), metres; 0 = none (reduced motion). */
    val bounce: Float = 0f,
    val up: V3 = V3.UP,
) {
    enum class Kind { FLY, FALL, SHAKE, HOLD, TURN }

    fun at(t: Float): Pose {
        val u = t.coerceIn(0f, 1f)
        return when (kind) {
            Kind.FLY -> {
                val e = 1f - (1f - u) * (1f - u) * (1f - u)
                val p = from.p.lerp(to.p, e) + V3(0f, arc * sin(PI.toFloat() * u), 0f)
                Pose(p, from.q.slerp(to.q, e))
            }
            Kind.FALL -> {
                // gravity-like (accelerating) until 85 %, then a small bounce along the box's up
                val fallEnd = if (bounce > 0f) 0.85f else 1f
                if (u < fallEnd) { val v = u / fallEnd; Pose(from.p.lerp(to.p, v * v), to.q) }
                else Pose(to.p + up * (bounce * sin(PI.toFloat() * (u - fallEnd) / (1f - fallEnd))), to.q)
            }
            Kind.SHAKE -> Pose(from.p + V3(0.004f * sin(u * 6f * PI.toFloat()), 0f, 0f), from.q)
            Kind.HOLD -> from
            Kind.TURN -> { val e = 1f - (1f - u) * (1f - u); Pose(from.p.lerp(to.p, e), from.q.slerp(to.q, e)) }
        }
    }
}

/** A block's animation: legs played one after another from [startedAt] (uptime ms). */
data class Motion(val segs: List<Seg>, val startedAt: Long) {
    val totalMs: Long get() = segs.sumOf { it.ms }
    fun done(now: Long) = now - startedAt >= totalMs
    val end: Pose get() = segs.last().to

    /** The pose [elapsed] ms after the start (clamped). */
    fun at(elapsed: Long): Pose {
        var t = elapsed.coerceAtLeast(0)
        for (s in segs) {
            if (t < s.ms) return s.at(t.toFloat() / s.ms)
            t -= s.ms
        }
        return segs.last().let { if (it.kind == Seg.Kind.SHAKE || it.kind == Seg.Kind.HOLD) it.from else it.to }
    }

    /** Start of the leg [i] (ms after [startedAt]). */
    fun legStart(i: Int): Long = segs.take(i).sumOf { it.ms }
}
