package app.prodl.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import app.prodl.core.AppJson
import app.prodl.core.SponsorBlockSegment
import app.prodl.playback.PlaybackService
import kotlinx.serialization.builtins.ListSerializer

/**
 * One MediaController for the whole app, bound to [PlaybackService] so audio
 * keeps playing when the UI goes away and the lock-screen controls stay live.
 */
@Composable
fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { controller = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            controller?.release()
            controller = null
        }
    }
    return controller
}

/** Inline 16:9 surface; the same player instance the service owns. */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    controller: MediaController?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setKeepScreenOn(true)
            }
        },
        update = { view -> if (view.player !== controller) view.player = controller },
        onRelease = { view -> view.player = null },
    )
}

/** Encode the segments so [PlaybackService] can skip them without a round trip. */
fun sponsorJson(segments: List<SponsorBlockSegment>): String? =
    if (segments.isEmpty()) {
        null
    } else {
        runCatching { AppJson.encodeToString(ListSerializer(SponsorBlockSegment.serializer()), segments) }.getOrNull()
    }

/**
 * Replace what the session plays. Passing the same URL again restarts nothing,
 * so tapping the current video is a no-op.
 */
fun playInSession(
    controller: MediaController,
    url: String,
    title: String?,
    artist: String?,
    thumbnail: String?,
    audioOnly: Boolean,
    segments: List<SponsorBlockSegment>,
    mimeType: String? = null,
) {
    if (url.isBlank()) return
    if (controller.currentMediaItem?.mediaId == url) {
        if (!controller.isPlaying) controller.play()
        return
    }
    val extras = Bundle().apply {
        putString(PlaybackService.EXTRA_SPONSORBLOCK, sponsorJson(segments))
        putBoolean(PlaybackService.EXTRA_AUDIO_ONLY, audioOnly)
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setExtras(extras)
        .build()
    val item = MediaItem.Builder()
        .setUri(url)
        .setMediaId(url)
        .setMediaMetadata(metadata)
        .apply { if (mimeType != null) setMimeType(mimeType) }
        .build()
    controller.setMediaItem(item)
    controller.prepare()
    controller.play()
}

/** Fullscreen / PiP: hands the same session to the native player activity. */
fun openFullscreen(
    context: Context,
    url: String,
    title: String?,
    thumbnail: String?,
    audioOnly: Boolean,
    segments: List<SponsorBlockSegment>,
    mimeType: String? = null,
) {
    app.prodl.playback.PlayerActivity.start(
        context = context,
        url = url,
        title = title,
        thumbnail = thumbnail,
        audioOnly = audioOnly,
        sponsorblockJson = sponsorJson(segments),
        mimeType = mimeType,
    )
}

/** Share a link without touching any analytics or shortener. */
fun shareLink(context: Context, url: String, title: String?) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
