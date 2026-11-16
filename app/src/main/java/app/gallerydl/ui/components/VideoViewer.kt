package app.gallerydl.ui.components

import android.app.Activity
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.SeekParameters
import app.gallerydl.R
import app.gallerydl.ui.theme.Spacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Owns the player of the full-screen viewer: play and pause, seeking, and the playback speed. It behaves like the iOS
 * app's player. The speed is kept between videos and app launches.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Stable
class VideoController(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    var isPlaying by mutableStateOf(false); private set
    var current by mutableFloatStateOf(0f)
    var duration by mutableFloatStateOf(0f); private set
    var unplayable by mutableStateOf(false); private set
    var videoSize by mutableStateOf(VideoSize.UNKNOWN); private set
    var speed by mutableFloatStateOf(prefs.getFloat("speed", 1f).takeIf { it in SPEEDS } ?: 1f); private set
    /** Plays the same video again when it ends. Remembered between videos and app launches. */
    var looping by mutableStateOf(prefs.getBoolean("loop", false)); private set
    var scrubbing = false
    private var lastScrub = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && player.duration > 0) duration = player.duration / 1000f
            if (state == Player.STATE_ENDED) isPlaying = false
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) { this@VideoController.videoSize = videoSize }
        override fun onPlayerError(error: PlaybackException) { unplayable = true }
    }

    init {
        player.addListener(listener)
        player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun toggleLoop() {
        looping = !looping
        prefs.edit().putBoolean("loop", looping).apply()
        player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun load(uri: Uri) {
        if (player.currentMediaItem != null) return
        val item = MediaItem.fromUri(uri)
        val end = pictureEndUs(uri)
        if (end != null) {
            // The sound runs a little longer than the picture, which would leave the last frame frozen on screen before a
            // loop starts again. The video is cut where the picture ends, so the loop turns over at once.
            player.setMediaSource(ClippingMediaSource(ProgressiveMediaSource.Factory(DefaultDataSource.Factory(appContext)).createMediaSource(item), 0, end))
        } else {
            player.setMediaItem(item)
        }
        player.setPlaybackSpeed(speed)
        player.prepare()
    }

    /**
     * Where the picture ends, in microseconds, when the sound track is a little longer than it (by up to a second); null
     * when the two end together or the file cannot be read.
     */
    private fun pictureEndUs(uri: Uri): Long? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(appContext, uri, null)
            var video = 0L
            var audio = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (!format.containsKey(MediaFormat.KEY_DURATION)) continue
                val length = format.getLong(MediaFormat.KEY_DURATION)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) video = maxOf(video, length) else if (mime.startsWith("audio/")) audio = maxOf(audio, length)
            }
            video.takeIf { it > 0 && audio - it in 5_000..1_000_000 }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    /** Called often, to move the seek bar while playing. */
    fun tick() {
        if (!scrubbing && player.isPlaying) current = player.currentPosition / 1000f
    }

    fun play() {
        if (duration > 0 && current >= duration - 0.25f) seek(0f)
        player.play()
    }

    fun pause() = player.pause()

    /** Jumps forward or back by [delta] seconds and keeps playing or staying paused as it was. */
    fun skip(delta: Float) {
        val end = if (duration > 0) duration else Float.MAX_VALUE
        seek((current + delta).coerceIn(0f, end))
    }

    fun toggle() = if (player.isPlaying) pause() else play()

    fun seek(seconds: Float) {
        current = seconds
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo((seconds * 1000).toLong())
    }

    /** Moves the picture while a finger drags. The tolerance keeps it quick; the final seek is exact. */
    fun scrub(seconds: Float) {
        current = seconds
        // One seek every 40 ms is plenty for a smooth picture; more would queue up behind the decoder.
        val now = SystemClock.elapsedRealtime()
        if (now - lastScrub < 40) return
        lastScrub = now
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.seekTo((seconds * 1000).toLong())
    }

    fun chooseSpeed(value: Float) {
        speed = value
        prefs.edit().putFloat("speed", value).apply()
        player.setPlaybackSpeed(value)
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }

    companion object {
        val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}

private const val MAX_SCALE = 5f
private const val SKIP_SECONDS = 10f

/** The share of the screen's width at each side where a sideways swipe changes video instead of scrubbing. */
private const val EDGE_FRACTION = 0.2f
private val EDGE_SWIPE_DISTANCE = 56.dp

/**
 * The viewer's video (or audio) player: pinch to zoom, drag to pan, double-tap to zoom in or out, a sideways swipe or
 * the seek bar to scrub with a live picture, a speed menu, and play and pause. [onZoomedChange] tells the pager to stay
 * put while the picture is zoomed in. Formats the system cannot play fall back to the open button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoViewer(
    uri: Uri,
    isAudio: Boolean,
    active: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** While locked, every touch is ignored and the controls are hidden, except the button that unlocks. */
    locked: Boolean = false,
    onLockedChange: (Boolean) -> Unit = {},
    /** Whether there is a file before or after this one to swipe to from the edges. */
    canGoPrevious: Boolean = false,
    canGoNext: Boolean = false,
    /** Which way this page came in (+1 from the right, -1 from the left, 0 when it did not slide in). */
    enterFrom: Int = 0,
    /**
     * A swipe that starts near the left or right edge slides the picture away with the finger, and then asks for the
     * previous (-1) or next (+1) file. The bar and the buttons stay put and start afresh with the next file.
     */
    onEdgeSwipe: (Int) -> Unit = {},
    /**
     * Whether another slide to a neighbouring file may start now. The screen that hosts the pages says no for a moment after
     * each slide, so a key that repeats or a remote that misfires cannot flip through the files again and again.
     */
    slideReady: () -> Boolean = { true },
) {
    if (!active) {
        // Pages next to the one on screen only show black; nothing is decoded until the page is the current one.
        Box(modifier.fillMaxSize().background(Color.Black))
        return
    }
    val context = LocalContext.current
    val controller = remember { VideoController(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val onZoom by rememberUpdatedState(onZoomedChange)
    val onEdge by rememberUpdatedState(onEdgeSwipe)
    // The jitter monitor (see PlayerMonitor): only does anything while it is switched on in the viewer's menu.
    PlayerMonitor.load(context)
    val monitoring = PlayerMonitor.enabled
    val probe = remember { MonitorProbe() }
    val pagerInfo = LocalPagerInfo.current
    val hasPrevious by rememberUpdatedState(canGoPrevious)
    val hasNext by rememberUpdatedState(canGoNext)

    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // A TV, or anything without a touchscreen: nothing there can be swiped.
    val remoteOnly = configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH ||
        (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    // The rotate button is for phones; a TV or a big tablet is already wide.
    val canRotate = activity != null && configuration.smallestScreenWidthDp < 600

    // Keeps the controls clear of the gesture bar or navigation buttons. The viewer is a dialog window, which can report
    // no bottom inset, so the activity's own navigation bar height is the fallback.
    val density = LocalDensity.current
    val dialogBottom = WindowInsets.safeDrawing.getBottom(density)
    val activityBottom = activity?.window?.decorView?.let {
        ViewCompat.getRootWindowInsets(it)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
    } ?: 0
    val bottomInset = with(density) { (if (dialogBottom > 0) dialogBottom else activityBottom).toDp() }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoomScope = rememberCoroutineScope()
    var zoomAnimation by remember { mutableStateOf<Job?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // How far an edge swipe has moved the picture sideways. A page that slides in starts out of sight.
    var edgeShift by remember { mutableFloatStateOf(enterFrom * 4000f) }
    LaunchedEffect(size.width > 0) {
        if (enterFrom != 0 && size.width > 0) {
            edgeShift = enterFrom * size.width.toFloat()
            animate(edgeShift, 0f, animationSpec = tween(220, easing = FastOutSlowInEasing)) { value, _ -> edgeShift = value }
        }
    }
    var controlsVisible by remember { mutableStateOf(true) }
    // While locked, a touch shows the unlock button for a moment; the counter restarts that moment.
    var unlockTouches by remember { mutableIntStateOf(0) }
    var unlockVisible by remember { mutableStateOf(false) }
    LaunchedEffect(unlockTouches) {
        if (unlockTouches > 0) {
            unlockVisible = true
            delay(2_500)
            unlockVisible = false
        }
    }
    var scrubStart by remember { mutableStateOf<Float?>(null) }
    var scrubDelta by remember { mutableFloatStateOf(0f) }
    var resumeAfterScrub by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        controller.load(uri)
        controller.play()
        // Playing on while another app has the screen would be a surprise, so it pauses.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            onZoom(false)
            // The screen is given back to the system's rotation when the whole viewer closes, not here: a page that is
            // only briefly inactive (a neighbour taking over) must not throw a landscape video back to portrait.
            controller.release()
        }
    }
    DisposableEffect(controller.isPlaying) {
        view.keepScreenOn = controller.isPlaying
        onDispose { }
    }
    LaunchedEffect(controller) {
        while (true) {
            controller.tick()
            delay(200)
        }
    }

    fun clamp(value: Offset, s: Float): Offset {
        val limitX = maxOf(0f, size.width * (s - 1) / 2)
        val limitY = maxOf(0f, size.height * (s - 1) / 2)
        return Offset(value.x.coerceIn(-limitX, limitX), value.y.coerceIn(-limitY, limitY))
    }

    fun setScale(value: Float) {
        scale = value.coerceIn(0.8f, MAX_SCALE)
        offset = clamp(offset, scale)
        onZoom(scale > 1.02f)
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
        onZoom(false)
    }

    // Double-tap zooms glide to their end instead of jumping there.
    // A remote's D-pad moves between the buttons, so the play button takes the focus when the player opens. The picture's
    // own layer holds it only while the bar is hidden; any D-pad key then brings the bar up and moves the focus to the button.
    val surfaceFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var wakeBar by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // Only where there is a remote: on a touch phone nothing should be drawn as focused when the player opens.
        if (!remoteOnly) return@LaunchedEffect
        delay(200)
        runCatching { if (controlsVisible) playFocus.requestFocus() else surfaceFocus.requestFocus() }
    }
    // Locked, the bar is gone and a remote can only reach the unlock button through the picture's own layer.
    LaunchedEffect(locked) {
        if (locked) {
            delay(100)
            runCatching { surfaceFocus.requestFocus() }
        }
    }
    LaunchedEffect(wakeBar) {
        if (wakeBar > 0) {
            delay(100)
            runCatching { playFocus.requestFocus() }
        }
    }

    /** Slides the picture out and asks for the previous (-1) or next (+1) file; does nothing where there is none. */
    var sliding by remember { mutableStateOf(false) }
    @OptIn(ExperimentalFoundationApi::class)
    val keepPageInView = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect(Offset.Zero, Size(size.width.toFloat(), size.height.toFloat()))
            override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit
        }
    }
    fun slideTo(step: Int, source: String) {
        if (!(if (step > 0) hasNext else hasPrevious)) return
        // One slide at a time, and not right after another one.
        if (sliding || !slideReady()) {
            if (monitoring) probe.key("slide$step:$source blocked")
            return
        }
        sliding = true
        if (monitoring) probe.key("slide$step:$source")
        val from = edgeShift
        zoomScope.launch {
            animate(from, -step * size.width.toFloat(), animationSpec = tween(180, easing = FastOutLinearInEasing)) { value, _ -> edgeShift = value }
            onEdge(step)
            sliding = false
        }
    }

    fun animateZoomTo(target: Float) {
        zoomAnimation?.cancel()
        val fromScale = scale
        val fromOffset = offset
        zoomAnimation = zoomScope.launch {
            animate(0f, 1f, animationSpec = tween(280, easing = FastOutSlowInEasing)) { t, _ ->
                scale = fromScale + (target - fromScale) * t
                offset = if (target > 1f) clamp(fromOffset * (1 - t), scale) else fromOffset * (1 - t)
                onZoom(scale > 1.02f)
            }
        }
    }

    fun beginScrub() {
        if (scrubStart != null) return
        scrubStart = controller.current
        scrubDelta = 0f
        resumeAfterScrub = controller.isPlaying
        controller.scrubbing = true
        controller.pause()
    }

    fun endScrub() {
        if (scrubStart == null) return
        scrubStart = null
        controller.scrubbing = false
        controller.seek(controller.current)
        if (resumeAfterScrub) controller.play()
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { size = it }
            // A D-pad moves the keyboard focus, and whatever takes focus asks the containers around it to scroll it into view,
            // pager included (switching touch scrolling off does not stop that). A mouse or a finger never moves the focus,
            // which is why only a D-pad shook the page. This answers every such request with "the whole page is what must be
            // in view", which is where the page already is, so nothing scrolls.
            .bringIntoViewResponder(keepPageInView)
            // A remote that is held down sends its key again and again (about eight times a second on this projector). A
            // repeat must not move the focus along the bar or press a button again; each press counts once.
            .onPreviewKeyEvent { event ->
                val navigation = event.key == Key.DirectionLeft || event.key == Key.DirectionRight || event.key == Key.DirectionUp ||
                    event.key == Key.DirectionDown || event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (navigation && event.nativeKeyEvent.repeatCount > 0) {
                    if (monitoring) probe.key("repeat dropped")
                    true
                } else false
            }
            .then(
                if (!monitoring) Modifier else Modifier
                    .onGloballyPositioned { probe.place("page", it.positionInWindow().x, it.positionInWindow().y); probe.place("page-size", it.size.width.toFloat(), it.size.height.toFloat()) }
                    .onPreviewKeyEvent { event ->
                        probe.key((if (event.type == KeyEventType.KeyDown) "v" else "^") + event.nativeKeyEvent.keyCode + (if (event.nativeKeyEvent.repeatCount > 0) "r" else ""))
                        false
                    }
                    .pointerInput(Unit) {
                        // Only watches: nothing is consumed, so the gestures below behave as without the monitor.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                probe.pointer(change.type.toString() + if (change.pressed) " down" else " up", change.position.x, change.position.y)
                            }
                        }
                    },
            ),
    ) {
        if (controller.unplayable) {
            IconButton(onClick = onOpen, modifier = Modifier.align(Alignment.Center).size(72.dp)) {
                Icon(
                    if (isAudio) Icons.Filled.MusicNote else Icons.Filled.PlayCircle,
                    contentDescription = stringResource(R.string.action_open),
                    modifier = Modifier.size(64.dp),
                    tint = Color.White,
                )
            }
            return@Box
        }

        // The picture, as large as fits, which is then zoomed and moved.
        val videoSize = controller.videoSize
        val ratio = if (videoSize.width > 0 && videoSize.height > 0) videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height else 16f / 9f
        Box(
            Modifier
                .fillMaxSize()
                .then(if (monitoring) Modifier.onGloballyPositioned { probe.place("picture", it.positionInWindow().x, it.positionInWindow().y) } else Modifier)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x + edgeShift
                    translationY = offset.y
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isAudio) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.White.copy(alpha = 0.6f))
            } else {
                AndroidView(
                    factory = { TextureView(it).also { texture -> controller.player.setVideoTextureView(texture) } },
                    modifier = Modifier.aspectRatio(ratio),
                )
            }
        }

        // Taps and gestures sit in a layer of their own above the picture.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    if (locked) {
                        // Locked: a touch only shows the unlock button.
                        detectTapGestures(onTap = { unlockTouches++ })
                    } else {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { animateZoomTo(if (scale > 1.02f) 1f else 2.5f) },
                        )
                    }
                }
                .pointerInput(isAudio, locked, remoteOnly) {
                    // Drags, edge swipes and pinches are for a touchscreen. On a TV or projector a pointer-style remote would
                    // turn every small move near an edge into a sideways drag, and the picture and the bar would jitter.
                    if (locked || remoteOnly) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        zoomAnimation?.cancel()
                        // A drag that starts in the outer fifth of the screen changes video; in the middle it scrubs.
                        val atEdge = down.position.x < size.width * EDGE_FRACTION || down.position.x > size.width * (1 - EDGE_FRACTION)
                        var edgeSwiping = false
                        var pinched = false
                        var moved = Offset.Zero
                        var dragging = false
                        var scrubbingNow = false
                        var startTime = 0f
                        val slop = viewConfiguration.touchSlop
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2 && !isAudio) pinched = true
                            if (pressed >= 2 && !isAudio) {
                                // Two fingers: pinch to zoom, and move the picture along with them.
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    setScale(scale * zoom)
                                    offset = clamp(offset + pan, scale)
                                    event.changes.forEach { it.consume() }
                                }
                                dragging = true
                            } else if (pressed == 1) {
                                val change = event.changes.first { it.pressed }
                                val delta = change.positionChange()
                                moved += delta
                                if (!dragging && (abs(moved.x) > slop || abs(moved.y) > slop)) {
                                    dragging = true
                                    if (atEdge && scale <= 1.02f && abs(moved.x) > abs(moved.y)) {
                                        edgeSwiping = true
                                    } else if (!isAudio && scale <= 1.02f && abs(moved.x) > abs(moved.y) && controller.duration > 0) {
                                        // A drag across a picture that is not zoomed is a scrub, when it is mostly sideways.
                                        scrubbingNow = true
                                        beginScrub()
                                        startTime = scrubStart ?: controller.current
                                        moved = Offset(moved.x, 0f)
                                    }
                                }
                                if (dragging && delta != Offset.Zero) {
                                    if (edgeSwiping) {
                                        // The picture follows the finger, held back a little where there is no file to go to.
                                        edgeShift = if ((moved.x < 0 && !hasNext) || (moved.x > 0 && !hasPrevious)) moved.x * 0.3f else moved.x
                                        change.consume()
                                    } else if (scrubbingNow) {
                                        // Across the whole screen covers up to 90 seconds, or the whole video if shorter.
                                        val span = controller.duration.coerceIn(1f, 90f)
                                        val target = (startTime + moved.x / size.width.coerceAtLeast(1) * span).coerceIn(0f, controller.duration)
                                        scrubDelta = target - startTime
                                        controller.scrub(target)
                                        change.consume()
                                    } else if (scale > 1.02f) {
                                        offset = clamp(offset + delta, scale)
                                        change.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (scrubbingNow) endScrub()
                        if (edgeSwiping) {
                            val step = if (moved.x < 0) 1 else -1
                            val goes = (if (step > 0) hasNext else hasPrevious) && abs(moved.x) > EDGE_SWIPE_DISTANCE.toPx()
                            if (goes) {
                                // The picture leaves the rest of the way, then the next file takes its place.
                                slideTo(step, "swipe")
                            } else {
                                val from = edgeShift
                                zoomScope.launch {
                                    animate(from, 0f, animationSpec = tween(200, easing = FastOutSlowInEasing)) { value, _ -> edgeShift = value }
                                }
                            }
                        }
                        // Letting go of a pinch near normal size springs back to normal.
                        if (pinched && scale < 1.05f) animateZoomTo(1f)
                    }
                }
                .focusRequester(surfaceFocus)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (locked) {
                        // Locked, a remote can only bring the unlock button up and press it.
                        val select = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (select && unlockVisible) onLockedChange(false) else unlockTouches++
                        return@onKeyEvent true
                    }
                    val first = event.nativeKeyEvent.repeatCount == 0
                    val handled = when (event.key) {
                        // The D-pad does nothing of its own here: it brings the bar up and moves to its buttons.
                        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (first) wakeBar++; true }
                        // The dedicated media keys of a remote still do what they say.
                        Key.MediaRewind -> { controller.skip(-3 * SKIP_SECONDS); true }
                        Key.MediaFastForward -> { controller.skip(3 * SKIP_SECONDS); true }
                        Key.MediaPlayPause -> { if (first) controller.toggle(); true }
                        Key.MediaPlay -> { controller.play(); true }
                        Key.MediaPause -> { controller.pause(); true }
                        // While one video is on repeat, the keys that change file are ignored: it is staying on purpose.
                        Key.MediaNext, Key.PageDown, Key.ChannelDown -> { if (first && !controller.looping) slideTo(1, "key"); true }
                        Key.MediaPrevious, Key.PageUp, Key.ChannelUp -> { if (first && !controller.looping) slideTo(-1, "key"); true }
                        else -> false
                    }
                    // Any key that did something brings the bar up, as a tap does.
                    if (handled) controlsVisible = true
                    handled
                }
                // Focusable only while the bar is hidden or locked. Otherwise this layer, which covers the whole screen, would
                // catch a D-pad press meant for the buttons and the focus would seem to vanish.
                .focusable(enabled = !controlsVisible || locked),
        )

        if (scrubStart != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    (if (scrubDelta >= 0) "+" else "-") + formatTime(abs(scrubDelta)),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    formatTime(controller.current) + " / " + formatTime(controller.duration),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (locked && unlockVisible) {
            IconButton(
                onClick = {
                    controlsVisible = true
                    onLockedChange(false)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset + 56.dp)
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape),
            ) {
                Icon(Icons.Filled.LockOpen, contentDescription = stringResource(R.string.action_unlock), tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }

        if (controlsVisible && !locked) {
            Controls(
                controller = controller,
                onLock = { onLockedChange(true) },
                onScrubStart = { beginScrub() },
                onScrub = { value ->
                    scrubDelta = value - (scrubStart ?: value)
                    controller.scrub(value)
                },
                onScrubEnd = { endScrub() },
                landscape = landscape,
                bottomInset = bottomInset,
                playFocus = playFocus,
                // Without a touchscreen there is no swipe from the edge, so the bar has buttons for the neighbouring files.
                onPrevious = if (remoteOnly && canGoPrevious) ({ slideTo(-1, "button") }) else null,
                onNext = if (remoteOnly && canGoNext) ({ slideTo(1, "button") }) else null,
                onRotate = if (canRotate) {
                    {
                        activity?.requestedOrientation =
                            if (landscape) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                } else null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (monitoring) Modifier.onGloballyPositioned { probe.place("bar", it.positionInWindow().x, it.positionInWindow().y) } else Modifier),
            )
        }

        if (monitoring) {
            val header = listOf(
                "MONITOR  remoteOnly=$remoteOnly  touchscreen=${configuration.touchscreen}  uiMode=${configuration.uiMode and Configuration.UI_MODE_TYPE_MASK}",
                "size=${size.width}x${size.height}  density=${configuration.densityDpi}  refresh=${"%.0f".format(view.display?.refreshRate ?: 0f)}Hz  ${android.os.Build.MODEL}",
            )
            PlayerMonitorOverlay(
                probe, header,
                extra = {
                    val format = controller.player.videoFormat
                    listOf(
                        pagerInfo(),
                        app.gallerydl.CrashLog.memory(context).trim(),
                        "window=${view.width}x${view.height}  root=${view.rootView.width}x${view.rootView.height}",
                        "player: ${controller.player.playbackState} playing=${controller.isPlaying} pos=${controller.player.currentPosition}ms",
                        "video: ${format?.width}x${format?.height} @${format?.frameRate}  dropped=${controller.player.videoDecoderCounters?.droppedBufferCount ?: 0}",
                        "state: edgeShift=${edgeShift.toInt()} offset=${offset.x.toInt()},${offset.y.toInt()} scale=${"%.2f".format(scale)} bar=$controlsVisible locked=$locked",
                    )
                },
                modifier = Modifier.align(Alignment.TopStart).padding(top = 96.dp, start = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(
    controller: VideoController,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    landscape: Boolean,
    onRotate: (() -> Unit)?,
    bottomInset: Dp,
    onLock: () -> Unit,
    playFocus: FocusRequester,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(bottom = bottomInset)
            .padding(horizontal = Spacing.Sm)
            .padding(top = Spacing.Md, bottom = Spacing.Sm),
    ) {
        // All the buttons in one row, evenly spaced: loop, lock, back 10 s, play or pause, forward 10 s, speed, and on phones
        // rotate. The row is capped in width, so it looks the same in portrait and landscape.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.widthIn(max = if (onPrevious != null || onNext != null) 560.dp else 440.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (onPrevious != null) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(44.dp).focusRing(CircleShape)) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.action_previous_video), tint = Color.White)
                    }
                }
                IconButton(onClick = controller::toggleLoop, modifier = Modifier.size(44.dp).focusRing(CircleShape)) {
                    Icon(
                        if (controller.looping) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = stringResource(if (controller.looping) R.string.action_loop_off else R.string.action_loop_on),
                        tint = if (controller.looping) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = onLock, modifier = Modifier.size(44.dp).focusRing(CircleShape)) {
                    Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.action_lock), tint = Color.White)
                }
                IconButton(onClick = { controller.skip(-SKIP_SECONDS) }, modifier = Modifier.size(44.dp).focusRing(CircleShape)) {
                    Icon(Icons.Filled.Replay10, contentDescription = stringResource(R.string.action_skip_back), tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { controller.toggle() }, modifier = Modifier.size(56.dp).focusRing(CircleShape).focusRequester(playFocus)) {
                    Icon(
                        if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (controller.isPlaying) R.string.action_pause else R.string.action_play),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { controller.skip(SKIP_SECONDS) }, modifier = Modifier.size(44.dp).focusRing(CircleShape)) {
                    Icon(Icons.Filled.Forward10, contentDescription = stringResource(R.string.action_skip_forward), tint = Color.White, modifier = Modifier.size(30.dp))
                }
                SpeedMenu(controller)
                if (onNext != null) {
                    IconButton(onClick = onNext, modifier = Modifier.size(44.dp).focusRing(CircleShape)) {
                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.action_next_video), tint = Color.White)
                    }
                }
                if (onRotate != null) {
                    IconButton(onClick = onRotate, modifier = Modifier.size(44.dp).focusRing(CircleShape)) {
                        Icon(
                            if (landscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = stringResource(if (landscape) R.string.action_exit_landscape else R.string.action_landscape),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
        // Position, seek bar and length.
        Row(
            // The system's back swipe from the screen edge stays off over the seek bar, so a finger that drifts to the edge
            // while seeking does not close the viewer.
            Modifier.fillMaxWidth().systemGestureExclusion(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            SeekRow(controller, onScrubStart, onScrub, onScrubEnd)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.SeekRow(
    controller: VideoController,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
) {
    Text(formatTime(controller.current), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 36.dp))
    var dragging by remember { mutableStateOf(false) }
    val end = maxOf(controller.duration, 0.1f)
    Slider(
        value = controller.current.coerceIn(0f, end),
        onValueChange = { value ->
            if (!dragging) {
                dragging = true
                onScrubStart()
            }
            onScrub(value)
        },
        onValueChangeFinished = {
            dragging = false
            onScrubEnd()
        },
        valueRange = 0f..end,
        modifier = Modifier.weight(1f),
        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
    )
    Text(formatTime(controller.duration), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 36.dp))
}

@Composable
private fun SpeedMenu(controller: VideoController) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .height(44.dp)
                .widthIn(min = 44.dp)
                .focusRing(CircleShape)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                speedLabel(controller.speed),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VideoController.SPEEDS.forEach { value ->
                DropdownMenuItem(
                    text = { Text(speedLabel(value)) },
                    leadingIcon = { if (value == controller.speed) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        controller.chooseSpeed(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun speedLabel(value: Float) = (if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString().trimEnd('0')) + "×"

private fun formatTime(seconds: Float): String {
    if (!seconds.isFinite() || seconds < 0) return "0:00"
    val total = Math.round(seconds)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}



