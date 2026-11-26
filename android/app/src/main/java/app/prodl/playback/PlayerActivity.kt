package app.prodl.playback

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView

/**
 * Native video surface for Media3 playback. Audio keeps playing through
 * [PlaybackService] when this activity goes away; video enters Picture-in-Picture.
 */
class PlayerActivity : ComponentActivity() {

    private var controller: MediaController? = null
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            setKeepScreenOn(true)
            useController = true
        }
        setContentView(playerView)

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener(
            {
                val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = connected
                playerView.player = connected
                if (savedInstanceState == null) {
                    startPlayback(connected, intent)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
        enableAutoPip()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller?.let { startPlayback(it, intent) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isVideoSession()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
    }

    override fun onStop() {
        if (!isInPictureInPictureMode) playerView.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        playerView.player = null
        controller?.release()
        controller = null
        super.onDestroy()
    }

    private fun startPlayback(controller: MediaController, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val extras = Bundle().apply {
            putString(PlaybackService.EXTRA_SPONSORBLOCK, intent.getStringExtra(EXTRA_SPONSORBLOCK))
            putBoolean(PlaybackService.EXTRA_AUDIO_ONLY, intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false))
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(intent.getStringExtra(EXTRA_TITLE) ?: getString(app.prodl.R.string.app_name))
            .setArtist(intent.getStringExtra(EXTRA_ARTIST))
            .setArtworkUri(intent.getStringExtra(EXTRA_THUMBNAIL)?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(extras)
            .build()
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaId(url)
            .setMediaMetadata(metadata)
            .build()
        controller.setMediaItem(item)
        controller.prepare()
        controller.play()
    }

    private fun isVideoSession(): Boolean {
        val extras = controller?.currentMediaItem?.mediaMetadata?.extras
        return extras?.getBoolean(PlaybackService.EXTRA_AUDIO_ONLY, false) != true
    }

    private fun enterPip() {
        if (!isVideoSession()) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params); setPictureInPictureParams(params) }
    }

    @OptIn(UnstableApi::class)
    private fun enableAutoPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setSourceRectHint(playerView.videoSurfaceView?.let { view ->
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                android.graphics.Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
            })
            .setAutoEnterEnabled(true)
            .build()
        runCatching { setPictureInPictureParams(params) }
    }

    companion object {
        private const val EXTRA_URL = "prodl.url"
        private const val EXTRA_TITLE = "prodl.title"
        private const val EXTRA_ARTIST = "prodl.artist"
        private const val EXTRA_THUMBNAIL = "prodl.thumbnail"
        private const val EXTRA_AUDIO_ONLY = "prodl.audio_only"
        private const val EXTRA_SPONSORBLOCK = "prodl.sponsorblock"

        fun start(
            context: Context,
            url: String?,
            title: String?,
            thumbnail: String?,
            audioOnly: Boolean,
            sponsorblockJson: String?,
        ) {
            if (url.isNullOrBlank()) return
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_THUMBNAIL, thumbnail)
                .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
                .putExtra(EXTRA_SPONSORBLOCK, sponsorblockJson)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
