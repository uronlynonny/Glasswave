// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What Flow says. Flow has no page of its own: it comes in on the normal player, says a line
 * (starting a set, moving to the next segment, answering what you asked for), and steps back.
 * While it talks, the player shows Flow's visualizer where the cover is, with the line on it
 * (see FlowPresence); with the player closed, the line shows as the usual short note.
 *
 * Every line goes through [flow], and nothing else makes Flow talk. There is no voice today, but
 * each line already carries the words a voice would say ([Line.spoken]: the same sentence without
 * marks that only make sense on screen), and [voice] is the one place to plug a voice in: set it,
 * and every new line is handed to it as Flow starts saying it.
 */
object FlowTalk {
    /** One thing Flow says. [strong] is the part of [text] shown bold (a segment's name). */
    data class Line(val id: Long, val text: String, val spoken: String, val strong: String? = null) {
        /** How long saying it takes: the words come up on screen over this time. */
        val sayMs: Long get() = (text.length * 42L).coerceIn(900L, 6000L)
    }

    /** The line Flow is saying right now (the player shows its visualizer and the line), or null. */
    var speaking by mutableStateOf<Line?>(null)
        private set

    /** The last thing Flow said (tap Flow's name in the player to have it said again). */
    var last by mutableStateOf<Line?>(null)
        private set

    /** While the full player is on screen, Flow talks there instead of in the small note at the bottom. */
    var playerOpen by mutableStateOf(false)

    /** Asks the app to open the full player as soon as there's a song in it (pressing Flow on Home does). */
    var openPlayer by mutableStateOf(false)

    /** A voice for Flow, when there is one: called with each line as Flow starts saying it. */
    var voice: ((Line) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var done: Job? = null
    private val waiting = ArrayDeque<Line>()
    private var nextId = 1L
    private var switching = false
    /** Whether Flow has played a set since the app opened (so a second one is "a fresh set"). */
    private var played = false
    private var greeted = false

    /** Flow says [text]. With the player closed, [note] (or the line itself) shows as the usual short note. */
    private fun flow(text: String, strong: String? = null, note: String? = text) {
        val line = Line(nextId++, text, spokenForm(text), strong)
        say(line)
        if (!playerOpen && note != null) Menus.say(note)
    }

    /**
     * Lines are said one after another, never over each other. A line stays up long enough to read
     * (and for as long as a set is still being put together); with another one waiting it makes
     * way sooner, but never before a moment has passed.
     */
    private fun say(line: Line) {
        waiting += line
        if (done?.isActive == true) return
        done = scope.launch {
            while (waiting.isNotEmpty()) {
                val now = waiting.removeFirst()
                last = now
                speaking = now
                runCatching { voice?.invoke(now) }
                var held = 0L
                while (held < now.sayMs + 2200L || Flow.building) {
                    if (waiting.isNotEmpty() && held >= minOf(now.sayMs, 1500L)) break
                    delay(100L)
                    held += 100L
                }
            }
            speaking = null
        }
    }

    /** Says the last line again (Flow's name in the player). */
    fun again() {
        val line = last ?: return
        if (speaking == null) say(line.copy(id = nextId++))
    }

    /** Read aloud, quote marks and dots that stand for a pause are only in the way. */
    private fun spokenForm(text: String) = text.replace("“", "").replace("”", "").replace("…", ".").replace(" · ", ", ")

    private fun hello(): String {
        if (greeted) return ""
        greeted = true
        return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> listOf("Morning. ", "Good morning. ", "Early start. ")
            in 12..16 -> listOf("Afternoon. ", "Hey. ", "Good afternoon. ")
            in 17..21 -> listOf("Evening. ", "Good evening. ", "Hey. ")
            else -> listOf("Up late. ", "Late one. ", "Still up. ")
        }.random()
    }

    /* ---------------- What Flow says, and when ---------------- */

    /** A set made from your own listening is being put together. [afterRequest]: it follows one you asked for. */
    fun starting(afterRequest: Boolean) {
        flow(
            hello() + when {
                afterRequest -> "Back to your mix. Give me a second."
                played -> listOf("Starting a fresh set from what you play.", "A new set, coming up.").random()
                else -> listOf("Pulling your set together.", "Leave it to me. Pulling your set together.").random()
            },
            note = null,
        )
    }

    /** You asked for something: Flow goes looking. */
    fun looking(q: String) {
        val what = q.replaceFirstChar { it.uppercase() }
        flow(hello() + listOf("$what. Let me see what I've got.", "$what, coming up. One second.", "$what. Give me a moment.").random(), what, note = null)
    }

    /** The set is ready and its first segment plays. [asked] is what you asked for, if you did. */
    fun opened(seg: Flow.Segment, asked: String?) {
        switching = false
        played = true
        val first = seg.tracks.firstOrNull()
        if (asked != null) {
            val start = if (first != null) "Starting with ${first.title} by ${first.artist.substringBefore(",").trim()}. " else ""
            flow(listOf("Here we go. ", "Got it. ", "Found it. ").random() + start + "I'll keep to that mood.", first?.title, "Flow · ${seg.title}")
        } else {
            flow(listOf("First up, ", "We start with ", "Kicking off with ").random() + "${seg.title}. ${seg.blurb}.", seg.title, "Flow · ${seg.title}")
        }
    }

    /** The music moved on to another segment (by itself, or because you asked to switch). */
    fun segment(seg: Flow.Segment) {
        val lead = if (switching) listOf("Switching. ", "Changing lanes. ", "Sure. ").random() + "Now "
        else listOf("Sliding into ", "Next, ", "Moving on to ", "Now for ").random()
        switching = false
        flow("$lead${seg.title}. ${seg.blurb}.", seg.title, "Flow · ${seg.title}")
    }

    /** You pressed "Switch it up". */
    fun switchAsked() { switching = true }

    /** "Switch it up" with nothing lined up yet. */
    fun notReady() {
        switching = false
        flow("Still lining up what's next. Give me a moment.", note = "Lining up more…")
    }

    /** There wasn't enough to build a set from. */
    fun nothingToGoOn() {
        openPlayer = false
        flow("I need a few songs to go on first. Play some, then come back to me.", note = "Play a few songs first, then try Flow")
    }

    /** Nothing matched what you asked for. */
    fun nothingFound(q: String) {
        openPlayer = false
        flow("I couldn't find music for “$q”. Try other words.")
    }

    /** The internet (or YouTube Music) let Flow down. */
    fun failed() {
        switching = false
        openPlayer = false
        flow("I couldn't get that started. Check your internet and ask me again.", note = "Couldn't start Flow. Check your internet.")
    }

    /** You played something else, so Flow stepped back (nothing is said: the music you picked just plays). */
    fun steppedBack() {
        switching = false
        waiting.clear()
        done?.cancel()
        speaking = null
    }
}
