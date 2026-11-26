package de.dateimanager.app

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import java.io.File

/** Läuft als Hintergrunddienst: Musik spielt weiter, auch wenn die App im Hintergrund ist (mit Benachrichtigung). */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }
}

/** Zustand des Players für die Oberfläche. Steuert den Dienst über einen MediaController. */
class PlayerState(private val ctx: Context) {
    var controller: MediaController? = null
    var ready by mutableStateOf(false)
    var hasMedia by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var currentPath by mutableStateOf("")
    var isVideo by mutableStateOf(false)
    var videoOpen by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf(Player.REPEAT_MODE_OFF)
    var index by mutableStateOf(0)
    var count by mutableStateOf(0)
    var videoW by mutableStateOf(0)
    var videoH by mutableStateOf(0)

    private var connecting = false
    private val queue = ArrayList<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@PlayerState.isPlaying = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controller?.let { sync(it) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            shuffle = shuffleModeEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            repeat = repeatMode
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoW = videoSize.width
            videoH = videoSize.height
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = currentPath
            Toast.makeText(ctx, "Kann nicht in der App abgespielt werden, öffne extern", Toast.LENGTH_SHORT).show()
            stop()
            if (p.isNotEmpty()) Fs.open(ctx, File(p))
        }
    }

    /** Gibt den Controller nur zurück, wenn er verbunden ist (liest den Zustand, damit die Oberfläche neu zeichnet). */
    fun ctrl(): MediaController? = if (ready) controller else null

    private fun connect() {
        if (connecting) return
        connecting = true
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(listener)
                ready = true
                sync(c)
                val todo = ArrayList(queue)
                queue.clear()
                for (b in todo) b(c)
            } catch (e: Exception) {
                connecting = false
                Toast.makeText(ctx, "Player nicht verfügbar", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
        } else {
            queue.add(block)
            connect()
        }
    }

    private fun sync(c: MediaController) {
        hasMedia = c.mediaItemCount > 0
        count = c.mediaItemCount
        index = c.currentMediaItemIndex
        val item = c.currentMediaItem
        title = item?.mediaMetadata?.title?.toString() ?: ""
        subtitle = item?.mediaMetadata?.artist?.toString() ?: ""
        currentPath = item?.mediaId ?: ""
        isVideo = currentPath.isNotEmpty() &&
            TypeFilter.classify(File(currentPath).extension.lowercase()) == TypeFilter.Videos
        isPlaying = c.isPlaying
        shuffle = c.shuffleModeEnabled
        repeat = c.repeatMode
    }

    fun play(paths: List<String>, start: Int, video: Boolean) {
        val items = ArrayList<MediaItem>()
        for (p in paths) {
            val f = File(p)
            items.add(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(f))
                    .setMediaId(p)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(f.nameWithoutExtension)
                            .setArtist(f.parentFile?.name ?: "")
                            .build()
                    )
                    .build()
            )
        }
        videoOpen = video
        withController { c ->
            c.setMediaItems(items, start, 0L)
            c.prepare()
            c.play()
            sync(c)
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.seekToNext()
    }

    fun prev() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stop() {
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
        hasMedia = false
        isPlaying = false
        videoOpen = false
        title = ""
        subtitle = ""
        currentPath = ""
    }

    fun release() {
        controller?.release()
        controller = null
        ready = false
        connecting = false
    }
}
