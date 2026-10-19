package com.example.plex_tv_app.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.atan2
import kotlin.math.sqrt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Lets anything in the app set off confetti (the Birthday party theme). */
val LocalConfetti = staticCompositionLocalOf<ConfettiState?> { null }

private val ConfettiColors = listOf(
    Color(0xFFFF4FB4), Color(0xFFFFD23F), Color(0xFF3DE0FF), Color(0xFF8CFF5A),
    Color(0xFFFF8A3D), Color(0xFFB98CFF), Color(0xFFFFFFFF),
)

private class Piece(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var angle: Float, val spin: Float,
    val color: Color, val w: Float, val h: Float,
    var age: Float = 0f,
)

/** Holds the confetti in the air. Call [burst] to throw some more. */
class ConfettiState {
    private val pieces = ArrayList<Piece>()
    internal var frame by mutableLongStateOf(0L) // redraw trigger
    internal var bursts by mutableIntStateOf(0) // wakes the animation loop
    internal var width = 0f
    internal var height = 0f

    /** Where the highlighted item is on screen, so bursts come from it. */
    private var anchor: LayoutCoordinates? = null

    internal fun setAnchor(coordinates: LayoutCoordinates) {
        anchor = coordinates
    }

    /**
     * An explosion of confetti bursting outwards from the edges of the highlighted item
     * (or from the middle of the screen if nothing has said where it is).
     */
    fun burst(count: Int = 90) {
        if (width == 0f) return
        val rect = anchor?.takeIf { it.isAttached }?.boundsInRoot()
            ?: Rect(Offset(width / 2f, height * 0.55f), 1f)
        val center = rect.center
        repeat(count) {
            // A random point on the item's outline...
            val (sx, sy) = pointOnOutline(rect)
            // ...flying away from its middle, with a little scatter and an upward kick.
            var dx = sx - center.x
            var dy = sy - center.y
            val length = sqrt(dx * dx + dy * dy).takeIf { it > 0.5f } ?: 1f.also { dx = 0f; dy = -1f }
            dx /= length
            dy /= length
            val scatter = (Random.nextFloat() - 0.5f) * 0.8f
            val angle = atan2(dy, dx) + scatter
            val speed = 450f + Random.nextFloat() * 750f
            pieces += Piece(
                x = sx,
                y = sy,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed - 250f,
                angle = Random.nextFloat() * 360f,
                spin = (Random.nextFloat() - 0.5f) * 720f,
                color = ConfettiColors.random(),
                w = 10f + Random.nextFloat() * 10f,
                h = 6f + Random.nextFloat() * 6f,
            )
        }
        bursts++
    }

    private fun pointOnOutline(r: Rect): Pair<Float, Float> {
        val perimeter = 2 * (r.width + r.height)
        if (perimeter < 4f) return r.center.x to r.center.y
        var d = Random.nextFloat() * perimeter
        if (d < r.width) return (r.left + d) to r.top
        d -= r.width
        if (d < r.height) return r.right to (r.top + d)
        d -= r.height
        if (d < r.width) return (r.right - d) to r.bottom
        d -= r.width
        return r.left to (r.bottom - d)
    }

    internal fun step(dt: Float) {
        val it = pieces.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            p.vy += 1700f * dt // gravity
            p.vx *= (1f - 0.9f * dt) // air resistance
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.angle += p.spin * dt
            if (p.age > 3f || p.y > height + 40f) it.remove()
        }
    }

    internal val isEmpty get() = pieces.isEmpty()

    internal fun forEach(block: (x: Float, y: Float, angle: Float, color: Color, w: Float, h: Float, alpha: Float) -> Unit) {
        pieces.forEach { p ->
            val alpha = if (p.age > 2.2f) (1f - (p.age - 2.2f) / 0.8f).coerceIn(0f, 1f) else 1f
            block(p.x, p.y, p.angle, p.color, p.w, p.h, alpha)
        }
    }
}

/**
 * Marks something as a place confetti can burst from: while it's highlighted, a press on it
 * sends the confetti out from its edges. Does nothing outside the Birthday party theme's reach.
 */
@Composable
fun Modifier.confettiAnchor(): Modifier {
    val confetti = LocalConfetti.current ?: return this
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { holder[0] = it }
        .onFocusChanged { state -> if (state.isFocused) holder[0]?.let(confetti::setAnchor) }
}

/** Draws the confetti over everything. Costs nothing when none is flying. */
@Composable
fun ConfettiLayer(state: ConfettiState) {
    LaunchedEffect(state.bursts) {
        var last = 0L
        while (!state.isEmpty) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                state.step(dt)
                state.frame = now
            }
        }
        state.frame = 0L // clear the last frame
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged {
                state.width = it.width.toFloat()
                state.height = it.height.toFloat()
            }
    ) {
        if (state.frame == 0L) return@Canvas
        state.forEach { x, y, angle, color, w, h, alpha ->
            rotate(angle, pivot = Offset(x, y)) {
                drawRect(color.copy(alpha = alpha), topLeft = Offset(x - w / 2, y - h / 2), size = Size(w, h))
            }
        }
    }
}
