package com.example.plex_tv_app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * An explicit top-to-bottom list of the focusable rows on a page. Up/Down walk this list
 * instead of letting Android guess the "nearest" thing on screen, which can jump to the
 * wrong place (like the side nav).
 */
class FocusChain {
    private val positions = mutableMapOf<FocusRequester, Float>()
    private var focused: FocusRequester? = null

    /** The rows in on-screen order, top to bottom. */
    val rows: List<FocusRequester> get() = positions.entries.sortedBy { it.value }.map { it.key }

    /** Index of the highlighted row in [rows], or -1. */
    val focusedIndex: Int get() = focused?.let { rows.indexOf(it) } ?: -1

    internal fun place(row: FocusRequester, y: Float) {
        positions[row] = y
    }

    internal fun focus(row: FocusRequester) {
        focused = row
    }

    internal fun remove(row: FocusRequester) {
        positions.remove(row)
        if (focused == row) focused = null
    }
}

/** The chain the rows being composed belong to (null = not in one). */
val LocalFocusChain = compositionLocalOf<FocusChain?> { null }

/** Adds this row to the current [FocusChain], if there is one. */
@Composable
fun Modifier.inChain(): Modifier {
    val chain = LocalFocusChain.current ?: return this
    val row = remember { FocusRequester() }
    DisposableEffect(chain, row) { onDispose { chain.remove(row) } }
    return this
        .focusRequester(row)
        .onGloballyPositioned { chain.place(row, it.positionInRoot().y) }
        .onFocusChanged { if (it.isFocused) chain.focus(row) }
}
