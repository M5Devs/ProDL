package app.prodl.playback

import androidx.media3.common.Player
import app.prodl.core.SponsorBlockSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SponsorBlock seeking. [target] is pure so the decision logic can be unit
 * tested without a player.
 */
object SponsorSkip {
    /**
     * Returns the seek target in milliseconds when [positionMs] falls inside a
     * skippable segment that has not been handled yet, otherwise null.
     */
    fun target(
        positionMs: Long,
        segments: List<SponsorBlockSegment>,
        skipped: MutableSet<String>,
    ): Long? {
        if (segments.isEmpty()) return null
        val position = positionMs / 1000.0
        for (segment in segments) {
            if (segment.action != "skip") continue
            val key = segment.uuid ?: "${segment.category}:${segment.start}:${segment.end}"
            if (key in skipped) continue
            if (position >= segment.start && position < segment.end) {
                skipped += key
                return (segment.end * 1000).toLong()
            }
        }
        return null
    }
}

/** Polls playback position and jumps over the segments handed to [update]. */
class SponsorBlockSkipper(
    private val player: Player,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 400L,
) {
    private var segments: List<SponsorBlockSegment> = emptyList()
    private val skipped = mutableSetOf<String>()
    private var job: Job? = null

    fun update(newSegments: List<SponsorBlockSegment>) {
        segments = newSegments
        skipped.clear()
        if (newSegments.isEmpty()) stop() else start()
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val target = SponsorSkip.target(player.currentPosition, segments, skipped)
                if (target != null) {
                    runCatching { player.seekTo(target) }
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
