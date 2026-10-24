// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * Shelves you can browse with a mouse. On a phone you swipe them; on a computer, round arrows
 * appear at the ends while the mouse is over the shelf, and each click slides it most of a width.
 */

@Composable
fun ShelfArrows(state: ScrollableState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var width by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun slide(dir: Int) = scope.launch {
        state.animateScrollBy(dir * width * 0.8f, tween(340, easing = Motion.ease))
    }
    Box(modifier.hoverable(hover).onSizeChanged { width = it.width }) {
        content()
        AnimatedVisibility(
            hovered && state.canScrollBackward,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
            enter = fadeIn(tween(120)), exit = fadeOut(tween(160)),
        ) { ArrowButton(left = true) { slide(-1) } }
        AnimatedVisibility(
            hovered && state.canScrollForward,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
            enter = fadeIn(tween(120)), exit = fadeOut(tween(160)),
        ) { ArrowButton(left = false) { slide(1) } }
    }
}

@Composable
private fun ArrowButton(left: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xCC15161C))
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .pressable(0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Ico(if (left) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight, Color.White, 26.dp)
    }
}

/** A sideways row of cards, with arrows on a computer. Same parameters as LazyRow. */
@Composable
fun DeskLazyRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    ShelfArrows(state, modifier) {
        LazyRow(state = state, contentPadding = contentPadding, horizontalArrangement = horizontalArrangement, content = content)
    }
}

/** A sideways grid (columns of song rows), with arrows on a computer. */
@Composable
fun DeskHorizontalGrid(
    rows: GridCells,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyGridScope.() -> Unit,
) {
    val state = rememberLazyGridState()
    ShelfArrows(state, modifier) {
        LazyHorizontalGrid(rows = rows, state = state, contentPadding = contentPadding, content = content)
    }
}
