@file:OptIn(ExperimentalFoundationApi::class)

package com.example.plex_tv_app.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.util.WeakHashMap
import kotlin.math.abs

/*
 * Smooth scrolling for Home, libraries and details pages.
 *
 * Android TV's built-in "scroll to the focused item" is quick and its speed can no longer be
 * changed, so on those pages it's switched off ([NoAutoScroll]) and each row, grid and page
 * glides into place itself with [ScrollEase] when something in it is highlighted.
 */

/**
 * On a one-line title: while [active] (its card is highlighted), slowly scroll the text if it's too
 * long to fit, pausing before it starts and between loops. Otherwise it just ends with "…".
 */
@Composable
fun Modifier.scrollIfLong(active: Boolean): Modifier =
    if (active) basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 900, velocity = 32.dp) else this

/** Text overflow to pair with [scrollIfLong]. */
fun overflowFor(active: Boolean): TextOverflow = if (active) TextOverflow.Clip else TextOverflow.Ellipsis

/** The glide used for all of it. */
val ScrollEase = tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)

/** Turns off the built-in focus scrolling, so ours can run instead. */
val NoAutoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Glides a row/column just far enough to show item [index] fully (no movement if it's already visible). */
suspend fun LazyListState.glideTo(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val end = info.viewportEndOffset - info.afterContentPadding
    val distance = when {
        item.offset < 0 -> item.offset
        item.offset + item.size > end -> item.offset + item.size - end
        else -> 0
    }
    if (distance != 0) animateScrollBy(distance.toFloat(), ScrollEase)
}

/** Same as above, for grids (vertical). */
suspend fun LazyGridState.glideTo(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val end = info.viewportEndOffset - info.afterContentPadding
    val top = item.offset.y
    val bottom = top + item.size.height
    val distance = when {
        top < 0 -> top
        bottom > end -> bottom - end
        else -> 0
    }
    if (distance != 0) animateScrollBy(distance.toFloat(), ScrollEase)
}

/** On an item in a lazy row/column: when it's highlighted, glide it into view. */
@Composable
fun Modifier.glideIntoView(state: LazyListState, index: Int): Modifier {
    val scope = rememberCoroutineScope()
    return onFocusChanged { if (it.isFocused) scope.launch { state.glideTo(index) } }
}

/** On an item in a lazy grid: when it's highlighted, glide it into view. */
@Composable
fun Modifier.glideIntoView(state: LazyGridState, index: Int): Modifier {
    val scope = rememberCoroutineScope()
    return onFocusChanged { if (it.isFocused) scope.launch { state.glideTo(index) } }
}

/** Up/Down bookkeeping for one grid: which card is at which position, and whether a move is under way. */
private class GridNav {
    val requesters = HashMap<Int, FocusRequester>()
    var busy = false
    val scope = MainScope() // outlives the cards, which can scroll away mid-move
    var clock: MonotonicFrameClock? = null // the screen's frame clock, which animations need
}

private val gridNavs = WeakHashMap<LazyGridState, GridNav>()

/**
 * On a card in a vertical grid: glides it into view when highlighted, and makes Up/Down go to the
 * card directly above/below — even when you press quickly and that row isn't on screen yet (the
 * grid glides a row first, then highlights it, ignoring presses in between). Without this, fast
 * presses make Android jump several rows and land on the first/last card of a row.
 *
 * Up on the top row is left to the page (e.g. to go to its tabs or filters). Down on the last row
 * stays put.
 */
@Composable
fun Modifier.gridItem(state: LazyGridState, index: Int): Modifier {
    val nav = remember(state) { gridNavs.getOrPut(state) { GridNav() } }
    val requester = remember { FocusRequester() }
    nav.requesters[index] = requester
    val itemScope = rememberCoroutineScope()
    if (nav.clock == null) nav.clock = itemScope.coroutineContext[MonotonicFrameClock]
    DisposableEffect(index, requester) {
        onDispose { if (nav.requesters[index] === requester) nav.requesters.remove(index) }
    }

    fun isShown(i: Int) = state.layoutInfo.visibleItemsInfo.any { it.index == i }

    fun focus(i: Int): Boolean = isShown(i) && nav.requesters[i]?.let { runCatching { it.requestFocus() }.isSuccess } == true

    return this
        .focusRequester(requester)
        .glideIntoView(state, index)
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val down = when (e.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> true
                android.view.KeyEvent.KEYCODE_DPAD_UP -> false
                else -> return@onPreviewKeyEvent false
            }
            if (nav.busy) return@onPreviewKeyEvent true // still moving to the last row asked for
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val current = visible.firstOrNull { it.index == index } ?: return@onPreviewKeyEvent false
            val columns = visible.groupBy { it.row }.maxOf { it.value.size }.coerceAtLeast(1)
            val total = info.totalItemsCount
            var target = index + if (down) columns else -columns
            if (target < 0) return@onPreviewKeyEvent false // top row: the page decides
            if (target >= total) {
                // A short last row below: go to its last card. Already on the last row: stay.
                if (index / columns < (total - 1) / columns) target = total - 1 else return@onPreviewKeyEvent true
            }
            if (focus(target)) return@onPreviewKeyEvent true

            // That row isn't on screen yet: glide one row, then highlight the card there.
            val neighbour = visible.firstOrNull { it.row == current.row + if (down) 1 else -1 }
            val pitch = neighbour?.let { abs(it.offset.y - current.offset.y) }?.takeIf { it > 0 }
                ?: (current.size.height * 1.15f).toInt()
            val clock = nav.clock ?: return@onPreviewKeyEvent false
            nav.busy = true
            nav.scope.launch(clock) {
                try {
                    state.animateScrollBy(if (down) pitch.toFloat() else -pitch.toFloat(), ScrollEase)
                    repeat(20) {
                        if (focus(target)) return@launch
                        withFrameNanos { }
                    }
                    state.scrollToItem(target) // as a last resort
                    withFrameNanos { }
                    focus(target)
                } finally {
                    nav.busy = false
                }
            }
            true
        }
}

/** For a page that scrolls as a whole: glides whole sections (a title plus its row) into view. */
class SectionScroller(val scroll: ScrollState) {
    internal var content: LayoutCoordinates? = null

    internal suspend fun show(section: LayoutCoordinates?, margin: Float) {
        val content = content ?: return
        if (section == null || !section.isAttached || !content.isAttached) return
        val top = content.localPositionOf(section, Offset.Zero).y - margin
        val bottom = top + section.size.height + margin * 2
        val viewTop = scroll.value.toFloat()
        val viewport = scroll.viewportSize.toFloat()
        val target = when {
            top < viewTop -> top
            bottom > viewTop + viewport -> bottom - viewport
            else -> return
        }
        scroll.animateScrollTo(target.toInt().coerceIn(0, scroll.maxValue), ScrollEase)
    }
}

/** On the scrolled content itself, so sections can be measured against it. */
fun Modifier.sectionContent(scroller: SectionScroller): Modifier =
    onGloballyPositioned { scroller.content = it }

/** On a section: when anything inside it is highlighted, glide the whole section into view. */
@Composable
fun Modifier.section(scroller: SectionScroller, margin: Float = 24f): Modifier {
    val scope = rememberCoroutineScope()
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { holder[0] = it }
        .onFocusChanged { if (it.hasFocus) scope.launch { scroller.show(holder[0], margin) } }
}
