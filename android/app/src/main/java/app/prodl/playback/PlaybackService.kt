package app.prodl.playback

import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.prodl.R
import app.prodl.core.AppJson
import app.prodl.core.SponsorBlockSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.builtins.ListSerializer

/**
 * Media3 session service: background audio with lockscreen controls, and the
 * player that [PlayerActivity] attaches to for video + PiP.
 */
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var skipper: SponsorBlockSkipper? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player = exo
        skipper = SponsorBlockSkipper(exo, scope)
        session = MediaSession.Builder(this, exo).build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_stat_prodl) }
        )
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applySegments(mediaItem)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) applySegments(exo.currentMediaItem) else skipper?.stop()
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val exo = player ?: return
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        skipper?.stop()
        session?.release()
        player?.release()
        session = null
        player = null
        scope.cancel()
        super.onDestroy()
    }

    private fun applySegments(mediaItem: MediaItem?) {
        val extras: Bundle? = mediaItem?.mediaMetadata?.extras
        val json = extras?.getString(EXTRA_SPONSORBLOCK)
        val segments = if (json.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                AppJson.decodeFromString(ListSerializer(SponsorBlockSegment.serializer()), json)
            }.getOrDefault(emptyList())
        }
        skipper?.update(segments)
    }

    companion object {
        const val EXTRA_SPONSORBLOCK = "sponsorblock_json"
        const val EXTRA_AUDIO_ONLY = "audio_only"
    }
}
