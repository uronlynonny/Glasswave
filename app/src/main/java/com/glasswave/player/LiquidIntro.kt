// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The opening: the liquid glass logo. A glass drop lands, opens into the Glasswave tile with the
 * wave inside, the glass melts away and the wave stays, then everything opens up into the app.
 * It plays from a recording (res/raw/intro.webp, 60 frames a second) with the phone's own animated
 * image player, so it starts at once. Tap to skip. (Android 8 and 9 before Pie: a short black
 * moment instead, as they can't play animated pictures.)
 */
@Composable
internal fun Intro(onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    val ctx = LocalContext.current
    val out = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    var picture by remember { mutableStateOf<Drawable?>(null) }
    fun leave() {
        if (leaving) return
        leaving = true
        scope.launch {
            out.animateTo(1f, tween(if (Settings.animations) 300 else 1))
            done()
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) { delay(300); leave(); return@LaunchedEffect }
        val d = withContext(Dispatchers.IO) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.resources, R.raw.intro)) }.getOrNull()
        }
        val anim = d as? AnimatedImageDrawable
        if (anim == null) { leave(); return@LaunchedEffect }
        picture = anim
        if (!Settings.animations) {
            // Animations off: the finished logo stays still, briefly.
            delay(500)
            leave()
            return@LaunchedEffect
        }
        anim.repeatCount = 0
        anim.registerAnimationCallback(object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                // Hold the wave a moment, then open into the app.
                scope.launch { delay(250); leave() }
            }
        })
        anim.start()
        // In case the end is never reported, don't keep the app waiting.
        delay(4000)
        leave()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = 1f - out.value
                val s = 1f + 0.08f * out.value
                scaleX = s
                scaleY = s
            }
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null) { leave() },
        contentAlignment = Alignment.Center,
    ) {
        picture?.let { p ->
            AndroidView(
                factory = { c -> ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { it.setImageDrawable(p) },
                modifier = Modifier.size(260.dp),
            )
        }
    }
}
