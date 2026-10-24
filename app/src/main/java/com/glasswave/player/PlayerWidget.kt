// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.Player
import coil.imageLoader
import coil.request.ImageRequest

/** The home-screen widget. Android asks it to redraw when it's added or the phone restarts. */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widget.push(context)
}

/** The widget's buttons. They work while Glasswave's player is running; otherwise they open the app. */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = PlaybackService.livePlayer
        if (p == null) {
            Widget.push(context) // shows the "open the app" buttons again
            return
        }
        when (intent.action) {
            Widget.PLAY -> if (p.isPlaying) p.pause() else {
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.play()
            }
            Widget.NEXT -> p.seekToNext()
            Widget.PREV -> p.seekToPrevious()
        }
    }
}

/**
 * Keeps the widget showing what's playing: cover, title, artist and a play / pause button in the
 * cover's colour. The player service calls [update] when the song or play state changes.
 * The last song is remembered so the widget still shows it after a restart.
 */
object Widget {
    const val PLAY = "com.glasswave.player.widget.PLAY"
    const val NEXT = "com.glasswave.player.widget.NEXT"
    const val PREV = "com.glasswave.player.widget.PREV"

    private var title: String? = null
    private var artist: String? = null
    private var art: String? = null
    private var playing = false
    private var live = false
    private var cover: Bitmap? = null
    private var coverFor: String? = null

    private fun component(c: Context) = ComponentName(c, PlayerWidget::class.java)
    private fun hasWidgets(c: Context) = AppWidgetManager.getInstance(c).getAppWidgetIds(component(c)).isNotEmpty()

    fun update(c: Context, title: String?, artist: String?, art: String?, playing: Boolean, live: Boolean) {
        if (!hasWidgets(c)) return
        this.title = title; this.artist = artist; this.art = art; this.playing = playing; this.live = live
        c.getSharedPreferences("widget", Context.MODE_PRIVATE).edit()
            .putString("title", title).putString("artist", artist).putString("art", art).apply()
        if (art != coverFor) {
            coverFor = art
            cover = null
            if (art != null) {
                val req = ImageRequest.Builder(c).data(art).size(240).allowHardware(false)
                    .target { d ->
                        if (coverFor == art) {
                            cover = (d as? BitmapDrawable)?.bitmap
                            push(c)
                        }
                    }
                    .build()
                c.imageLoader.enqueue(req)
            }
        }
        push(c)
    }

    fun push(c: Context) {
        if (!hasWidgets(c)) return
        if (title == null) {
            val prefs = c.getSharedPreferences("widget", Context.MODE_PRIVATE)
            title = prefs.getString("title", null)
            artist = prefs.getString("artist", null)
        }
        val v = RemoteViews(c.packageName, R.layout.widget_player)
        v.setTextViewText(R.id.w_title, title ?: c.getString(R.string.widget_idle_title))
        v.setTextViewText(R.id.w_artist, artist ?: c.getString(R.string.widget_idle_artist))
        cover?.let { v.setImageViewBitmap(R.id.w_cover, it) } ?: v.setImageViewResource(R.id.w_cover, R.drawable.ic_foreground)
        v.setImageViewResource(R.id.w_play_icon, if (playing && live) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        v.setInt(R.id.w_play_bg, "setColorFilter", CoverTint.accent.toArgb())

        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.w_root, open)
        // With the player running the buttons control it; otherwise they open Glasswave.
        fun action(a: String, code: Int) = if (live) PendingIntent.getBroadcast(
            c, code, Intent(c, WidgetActions::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ) else open
        v.setOnClickPendingIntent(R.id.w_play, action(PLAY, 1))
        v.setOnClickPendingIntent(R.id.w_next, action(NEXT, 2))
        v.setOnClickPendingIntent(R.id.w_prev, action(PREV, 3))
        runCatching { AppWidgetManager.getInstance(c).updateAppWidget(component(c), v) }
    }
}
