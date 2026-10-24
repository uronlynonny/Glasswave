package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * "Back" on desktop: the Esc key and the mouse's back button. Like on Android, the most recently
 * added handler that's switched on gets it.
 */
object BackDispatcher {
    class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    internal fun add(e: Entry) { entries += e }
    internal fun remove(e: Entry) { entries -= e }

    /** Goes back one step. False if nothing wanted it. */
    fun back(): Boolean {
        val e = entries.lastOrNull { it.enabled } ?: return false
        e.onBack()
        return true
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    val entry = remember { BackDispatcher.Entry(enabled) { current() } }
    SideEffect { entry.enabled = enabled }
    DisposableEffect(entry) {
        BackDispatcher.add(entry)
        onDispose { BackDispatcher.remove(entry) }
    }
}
