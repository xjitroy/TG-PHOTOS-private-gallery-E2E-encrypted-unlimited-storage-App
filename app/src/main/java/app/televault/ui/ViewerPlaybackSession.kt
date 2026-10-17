package app.televault.ui

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.televault.DriveViewModel
import app.televault.data.MediaMemory
import app.televault.domain.AdaptiveMediaPolicy

/** One lazy native video engine per viewer, not one engine per pager cell. Main-thread API only. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ViewerPlaybackSession(context: Context, private val model: DriveViewModel) {
    private val app = context.applicationContext
    private val activity = videoActivity(context)
    private val orientation = activity?.let { owner -> ViewerOrientationLease(owner.requestedOrientation, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, { owner.requestedOrientation }, { target -> runCatching { owner.requestedOrientation = target } }) }
    fun orientation(identity: String, landscape: Boolean, pip: Boolean) {
        if (owns(identity)) orientation?.request(identity, landscape, pip)
    }
    private val epoch = model.playbackEpoch
    private val sourceFactory = model.mediaSourceFactory()
    private var engine: ExoPlayer? = null
    private var currentIdentity: String? = null
    private var currentSource: Uri? = null
    private var released = false
    private var completedPlays = 0
    private var endedCounted = false
    fun completionCount(identity: String): Int = if (owns(identity)) completedPlays else 0
    fun restartCycle(identity: String) {
        if (owns(identity)) {
            completedPlays = 0
            endedCounted = false
        }
    }
    fun owns(identity: String): Boolean = !released && currentIdentity == identity
    private fun saveCurrent() {
        val player = engine ?: return
        currentIdentity?.let { model.savePlaybackPosition(it, player.currentPosition, player.duration, player.playbackState == Player.STATE_ENDED, epoch) }
    }
    fun prepare(uri: Uri, identity: String, resume: Long): ExoPlayer {
        check(!released)
        val player = engine ?: ExoPlayer.Builder(app)
            .setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(app).setEnableDecoderFallback(true))
            .setLoadControl(object : androidx.media3.exoplayer.DefaultLoadControl(androidx.media3.exoplayer.upstream.DefaultAllocator(true, androidx.media3.common.C.DEFAULT_BUFFER_SEGMENT_SIZE), 15000, 120000, 750, 1500, MediaMemory.budget(app, 48, 8), false, 0, false) {
                override fun shouldContinueLoading(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean {
                    val duration = if (parameters.timeline.isEmpty) 0L else parameters.timeline.getWindow(0, androidx.media3.common.Timeline.Window()).durationMs
                    val maximum = if (AdaptiveMediaPolicy.shortVideo(duration)) 120000L else 60000L
                    return parameters.bufferedDurationUs < maximum * 1000 && super.shouldContinueLoading(parameters)
                }
            })
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build().also {
                it.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                it.setHandleAudioBecomingNoisy(true)
                engine = it
                it.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED && !endedCounted && currentIdentity != null) {
                            completedPlays++
                            endedCounted = true
                        } else if (state != Player.STATE_ENDED) {
                            endedCounted = false
                        }
                    }
                })
            }
        if (currentIdentity != identity || currentSource != uri) {
            val switchingSource = currentIdentity == identity
            val start = if (switchingSource) player.currentPosition else resume
            saveCurrent()
            currentIdentity = identity
            currentSource = uri
            if (!switchingSource) {
                completedPlays = 0
                endedCounted = false
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setPlaybackSpeed(1f)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
            }
            if (uri.scheme == "tgphotos") player.setMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(MediaItem.fromUri(uri))) else player.setMediaItem(MediaItem.fromUri(uri))
            // Publish a restored/seek timestamp before the first encrypted range arrives.
            uri.lastPathSegment?.takeIf { uri.scheme == "tgphotos" }?.let { model.markVideoPosition(it, start, Long.MAX_VALUE, epoch) }
            player.seekTo(start)
            player.prepare()
        }
        player.playWhenReady = true
        return player
    }
    fun subtitle(identity: String, uri: Uri, mime: String, title: String) {
        check(owns(identity))
        require(uri.scheme == "content")
        val player = checkNotNull(engine)
        val video = checkNotNull(currentSource)
        val position = player.currentPosition
        val playing = player.playWhenReady
        val local = androidx.media3.datasource.DefaultDataSource.Factory(app)
        val routed = androidx.media3.datasource.DataSource.Factory {
            object : androidx.media3.datasource.DataSource {
                private var delegate: androidx.media3.datasource.DataSource? = null
                private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
                override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {
                    listeners.add(listener)
                    delegate?.addTransferListener(listener)
                }
                override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                    require(spec.uri.scheme in setOf("tgphotos", "content", "file"))
                    val next = if (spec.uri.scheme == "tgphotos") sourceFactory.createDataSource() else local.createDataSource()
                    delegate = next
                    listeners.forEach(next::addTransferListener)
                    return next.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(delegate).read(buffer, offset, length)
                override fun getUri(): Uri? = delegate?.uri
                override fun close() {
                    delegate?.close()
                    delegate = null
                }
            }
        }
        val track = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLabel(title).setLanguage("und").setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT).build()
        val item = MediaItem.Builder().setUri(video).setSubtitleConfigurations(listOf(track)).build()
        val source = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(routed).createMediaSource(item)
        player.setMediaSource(source, position)
        player.prepare()
        player.playWhenReady = playing
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build()
    }
    fun pause(identity: String) {
        if (owns(identity)) {
            saveCurrent()
            engine?.pause()
        }
    }
    fun visible(identity: String?) {
        orientation?.select(identity)
        if (currentIdentity != identity) {
            saveCurrent()
            engine?.pause()
        }
    }
    fun release() {
        if (released) return
        saveCurrent()
        released = true
        orientation?.close()
        currentIdentity = null
        currentSource = null
        engine?.let { activity?.detachVideoPiP(it) }
        engine?.release()
        engine = null
    }
}
