package app.televault.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.view.*
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.televault.data.DecodedPhotoCache
import kotlin.math.*
import kotlinx.coroutines.*

/** Bounded original-image regions; reuse the last tile during smooth gestures. */
internal class RegionImageView(context: Context) : View(context) {
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var decoder: BitmapRegionDecoder? = null
    private var descriptor: android.os.ParcelFileDescriptor? = null
    private var reads: app.televault.data.MediaReadSession? = null
    private var image: Bitmap? = null
    private var overview: Bitmap? = null
    private var savedWidth = 0
    private var savedHeight = 0
    private var orientation = 1
    private var previewCache: DecodedPhotoCache? = null
    private var previewKey = ""
    private var previewEpoch = 0L
    private var region = Rect()
    private var renderedSample = Int.MAX_VALUE
    private val tileBudget = minOf(app.televault.data.MediaMemory.budget(context, 8, 3).toLong(), Runtime.getRuntime().maxMemory() / 16)
    private var multiTouchGesture = false
    private var inverseCos = 1f
    private var inverseSin = 0f
    private val decodeRequest = Runnable { enqueueRegion() }
    private var downX = 0f
    private var downY = 0f
    private var rotation = 0f
    private var mirrored = false
    private var scale = 1f
    private var cx = 0f
    private var cy = 0f
    private var serial = 0
    private var job: Job? = null
    private var animation: ValueAnimator? = null
    private var closed = false
    private data class Tile(val rect: Rect, val sample: Int, val version: Int)
    private var pending: Tile? = null
    private val drawBounds = RectF()
    private val overviewBounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var loadingChanged: (Boolean, Boolean, Boolean) -> Unit = { _, _, _ -> }
    private companion object {
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    var longHeld: (() -> Unit)? = null
    private val holdPolicy = app.televault.domain.PhotoTextHold()
    private val hold = Runnable {
        if (!closed && longHeld != null && holdPolicy.fire(android.os.SystemClock.elapsedRealtime())) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            longHeld?.invoke()
        }
    }
    var tapped: () -> Unit = {}
    var swipeUp: (() -> Unit)? = null
    init {
        isClickable = true
        contentDescription = "Photo. Pinch to zoom; double tap to zoom or fit; swipe up for details."
    }
    private fun imageDeltaX(x: Float, y: Float): Float = (x * inverseCos - y * inverseSin) * if (mirrored) -1f else 1f
    private fun imageDeltaY(x: Float, y: Float): Float = x * inverseSin + y * inverseCos
    private fun zoom(next: Float, x: Float, y: Float) {
        val old = displayScale()
        scale = next.coerceIn(1f, 20f)
        val factor = displayScale()
        val ix = imageDeltaX(x - width / 2f, y - height / 2f)
        val iy = imageDeltaY(x - width / 2f, y - height / 2f)
        cx += ix * (1f / old - 1f / factor)
        cy += iy * (1f / old - 1f / factor)
        requestRegion()
    }
    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var focusX = 0f
            private var focusY = 0f
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                animation?.cancel()
                focusX = detector.focusX
                focusY = detector.focusY
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = displayScale()
                val dx = detector.focusX - focusX
                val dy = detector.focusY - focusY
                cx -= imageDeltaX(dx, dy) / factor
                cy -= imageDeltaY(dx, dy) / factor
                focusX = detector.focusX
                focusY = detector.focusY
                zoom(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    ).apply { isQuickScaleEnabled = false }
    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean = performClick()
            override fun onDoubleTap(e: MotionEvent): Boolean {
                animation?.cancel()
                val focalX = e.x
                val focalY = e.y
                animation = ValueAnimator.ofFloat(scale, if (scale > 1.05f) 1f else 3f).apply {
                    duration = (240 * (context.applicationContext as app.televault.TeleVaultApplication).motion.userMultiplier).toLong()
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { zoom(it.animatedValue as Float, focalX, focalY) }
                    start()
                }
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (multiTouchGesture) return false
                if (scale <= 1.05f && e1 != null && e1.y - e2.y > 72 * resources.displayMetrics.density && e1.y - e2.y > abs(e1.x - e2.x) * 1.5f) {
                    swipeUp?.invoke()
                    return swipeUp != null
                }
                return false
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale <= 1f || e2.pointerCount > 1 || pinch.isInProgress) return false
                val ix = imageDeltaX(dx, dy)
                val iy = imageDeltaY(dx, dy)
                val factor = displayScale()
                cx += ix / factor
                cy += iy / factor
                requestRegion()
                return true
            }
        }
    )
    override fun performClick(): Boolean {
        super.performClick()
        tapped()
        return true
    }
    fun open(uri: Uri, cache: DecodedPhotoCache?, cacheKey: String) {
        previewCache = cache
        previewKey = cacheKey
        previewEpoch = cache?.epoch() ?: 0L
        cache?.get(cacheKey)?.let { frame ->
            image = frame.bitmap
            overview = frame.bitmap
            savedWidth = frame.width
            savedHeight = frame.height
            orientation = frame.orientation
            setOrientation()
            region = Rect(0, 0, frame.width, frame.height)
            renderedSample = ((frame.width.toLong() + frame.bitmap.width - 1) / frame.bitmap.width).toInt().coerceAtLeast(1)
            cx = frame.width / 2f
            cy = frame.height / 2f
            loadingChanged(false, false, true)
            invalidate()
        }
        val readSession = app.televault.data.MediaReadSession.open(context, uri)
        reads = readSession
        tasks.launch {
            var opened: Pair<android.os.ParcelFileDescriptor, BitmapRegionDecoder>? = null
            try {
                // Keep handles reachable even if the view closes during native I/O.
                app.televault.data.PreviewWorkGate.originals.run {
                    withContext(Dispatchers.IO + NonCancellable) {
                        if (savedWidth == 0) orientation = android.os.ParcelFileDescriptor.AutoCloseInputStream(readSession.descriptor(context)).use { runCatching { androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1) }
                        val fd = readSession.descriptor(context)
                        try {
                            val next = checkNotNull(BitmapRegionDecoder.newInstance(fd.fileDescriptor, false))
                            opened = fd to next
                            savedWidth = next.width
                            savedHeight = next.height
                        } catch (error: Throwable) {
                            fd.close()
                            throw error
                        }
                    }
                }
                ensureActive()
                setOrientation()
                descriptor = opened!!.first
                decoder = opened!!.second
                opened = null
                if (image == null && overview == null) {
                    cx = savedWidth / 2f
                    cy = savedHeight / 2f
                }
                requestRegion(immediate = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                readSession.close()
                if (!closed) {
                    loadingChanged(false, true, image != null)
                    contentDescription = "Not enough memory to preview. Use Open with."
                    invalidate()
                }
            } catch (_: Exception) {
                readSession.close()
                if (!closed) {
                    loadingChanged(false, true, image != null)
                    contentDescription = "Cannot preview. Use Open with."
                    invalidate()
                }
            } finally {
                opened?.let { abandoned ->
                    app.televault.data.MediaReadSession.dispose {
                        try {
                            abandoned.second.recycle()
                        } finally {
                            abandoned.first.close()
                        }
                    }
                }
            }
        }
    }
    private fun setOrientation() {
        rotation = when (orientation) {
            3, 4 -> 180f
            6, 7 -> 90f
            5, 8 -> 270f
            else -> 0f
        }
        mirrored = orientation in setOf(2, 4, 5, 7)
        val angle = Math.toRadians(-rotation.toDouble())
        inverseCos = cos(angle).toFloat()
        inverseSin = sin(angle).toFloat()
    }
    private fun displayScale(): Float {
        val w = savedWidth
        val h = savedHeight
        if (w == 0 || h == 0) return 1f
        return min((if (rotation % 180 == 0f) width else height).toFloat() / w, (if (rotation % 180 == 0f) height else width).toFloat() / h).coerceAtLeast(.0001f) * scale
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        requestRegion()
    }
    private fun requestRegion(immediate: Boolean = false) {
        if (width == 0 || height == 0 || savedWidth == 0 || savedHeight == 0 || closed) return
        val factor = displayScale()
        val halfW = min(savedWidth / 2f, (if (rotation % 180 == 0f) width else height) / factor / 2)
        val halfH = min(savedHeight / 2f, (if (rotation % 180 == 0f) height else width) / factor / 2)
        cx = cx.coerceIn(halfW, savedWidth - halfW)
        cy = cy.coerceIn(halfH, savedHeight - halfH)
        // Never touch a decoder, Binder memory budget or Compose state in a gesture frame.
        postInvalidateOnAnimation()
        if (decoder == null) return
        removeCallbacks(decodeRequest)
        if (immediate || image == null) post(decodeRequest) else postDelayed(decodeRequest, 90)
    }
    private fun enqueueRegion() {
        val d = decoder ?: return
        if (closed || width == 0 || height == 0 || savedWidth == 0 || savedHeight == 0) return
        val plan = app.televault.domain.RegionTilePlan.create(savedWidth, savedHeight, width, height, rotation % 180 != 0f, scale, cx, cy, tileBudget)
        val visible = Rect(plan.visibleLeft(), plan.visibleTop(), plan.visibleRight(), plan.visibleBottom())
        val wanted = Rect(plan.left(), plan.top(), plan.right(), plan.bottom())
        val sample = plan.sample()
        if (image != null && region.contains(visible) && renderedSample <= sample && pending == null) return
        pending = Tile(wanted, sample, ++serial)
        if (job?.isActive == true) return
        job = tasks.launch {
            while (isActive && pending != null) {
                val tile = pending!!
                pending = null
                val originalWidth = savedWidth
                val originalHeight = savedHeight
                var bitmap: Bitmap? = null
                try {
                    app.televault.data.PreviewWorkGate.originals.run {
                        withContext(Dispatchers.IO + NonCancellable) {
                            bitmap = synchronized(d) { if (d.isRecycled) null else runCatching { d.decodeRegion(tile.rect, BitmapFactory.Options().apply { inSampleSize = tile.sample }) }.getOrNull() }
                            if (tile.rect == Rect(0, 0, originalWidth, originalHeight) && previewCache != null) {
                                bitmap?.let { previewCache?.remember(previewKey, it, originalWidth, originalHeight, orientation, previewEpoch) }
                            }
                        }
                    }
                    ensureActive()
                    if (tile.version != serial && overview == null && tile.rect == Rect(0, 0, originalWidth, originalHeight)) {
                        overview = bitmap
                        bitmap = null
                        invalidate()
                    }
                    if (tile.version == serial) {
                        if (bitmap != null) {
                            if (tile.rect == Rect(0, 0, originalWidth, originalHeight)) {
                                overview = bitmap
                            }
                            image = bitmap
                            bitmap = null
                            region = tile.rect
                            renderedSample = tile.sample
                            loadingChanged(false, false, true)
                            invalidate()
                            // Published bitmaps may still belong to RenderThread display lists.
                            // Drop references; Android reclaims them after the renderer releases them.
                        } else {
                            // Keep the last authenticated tile when a new region/read fails.
                            loadingChanged(false, true, image != null)
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: OutOfMemoryError) {
                    // Retain the painted frame; never repeatedly allocate tiles under pressure.
                    pending = null
                    previewCache?.clear()
                    loadingChanged(false, true, image != null || overview != null)
                } catch (_: Exception) {
                    pending = null
                    loadingChanged(false, true, image != null || overview != null)
                } finally {
                    bitmap?.let { abandoned -> app.televault.data.MediaReadSession.dispose { abandoned.recycle() } }
                }
            }
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // A region replaces the previous frame; pixels outside it must not retain an old tile.
        canvas.drawColor(android.graphics.Color.BLACK)
        val bitmap = image ?: overview ?: return
        val factor = displayScale()
        drawBounds.set((region.left - cx) * factor, (region.top - cy) * factor, (region.right - cx) * factor, (region.bottom - cy) * factor)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(rotation)
        if (mirrored) canvas.scale(-1f, 1f)
        overview?.takeUnless { it.isRecycled }?.let { base ->
            overviewBounds.set(-cx * factor, -cy * factor, (savedWidth - cx) * factor, (savedHeight - cy) * factor)
            canvas.drawBitmap(base, null, overviewBounds, paint)
        }
        if (image != null && image !== overview) canvas.drawBitmap(bitmap, null, drawBounds, paint)
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            animation?.cancel()
            multiTouchGesture = false
            downX = event.x
            downY = event.y
            removeCallbacks(hold)
            holdPolicy.begin(android.os.SystemClock.elapsedRealtime(), event.x, event.y, ViewConfiguration.get(context).scaledTouchSlop.toFloat())
            if (longHeld != null) postDelayed(hold, app.televault.domain.PhotoTextHold.DELAY)
        }
        if (event.pointerCount > 1 || (event.x - downX) * (event.x - downX) + (event.y - downY) * (event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop.toFloat().let { it * it } || event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) removeCallbacks(hold)
        holdPolicy.update(event.x, event.y, event.pointerCount, event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
        if (event.pointerCount > 1) multiTouchGesture = true
        val upward = !multiTouchGesture && swipeUp != null && downY - event.y > ViewConfiguration.get(context).scaledTouchSlop && downY - event.y > abs(event.x - downX) * 1.5f
        parent?.requestDisallowInterceptTouchEvent(event.pointerCount > 1 || scale > 1f || upward)
        pinch.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            requestRegion(immediate = true)
            multiTouchGesture = false
        }
        return true
    }
    fun close() {
        if (closed) return
        closed = true
        reads?.close()
        reads = null
        removeCallbacks(hold)
        removeCallbacks(decodeRequest)
        holdPolicy.cancel()
        longHeld = null
        tapped = {}
        swipeUp = null
        loadingChanged = { _, _, _ -> }
        ++serial
        animation?.cancel()
        pending = null
        tasks.cancel()
        val toClose = decoder
        val fd = descriptor
        decoder = null
        descriptor = null
        cleanup.launch {
            try {
                toClose?.let { synchronized(it) { if (!it.isRecycled) it.recycle() } }
            } finally {
                runCatching { fd?.close() }
            }
        }
        // Published buffers are released by GC after the renderer drops its references.
        overview = null
        image = null
    }
}

@Composable internal fun RegionImage(uri: Uri, modifier: Modifier, tap: () -> Unit = {}, swipeUp: (() -> Unit)? = null, placeholder: @Composable () -> Unit = {}, pixelsReady: () -> Unit = {}, cache: DecodedPhotoCache? = null, cacheKey: String = uri.toString(), longHold: (() -> Unit)? = null) {
    var retry by remember(uri) { mutableIntStateOf(0) }
    var loading by remember(uri, retry) { mutableStateOf(true) }
    var error by remember(uri, retry) { mutableStateOf(false) }
    var hasPixels by remember(uri, retry) { mutableStateOf(false) }
    val onPixelsReady by rememberUpdatedState(pixelsReady)
    // A newly selected/settled caller may own a new priority token while these pixels stay ready.
    LaunchedEffect(hasPixels, error, pixelsReady) { if (hasPixels || error) onPixelsReady() }
    val loadingDescription = label("Loading original photo", "মূল ছবি লোড হচ্ছে")
    Box(modifier.semantics { if (loading && !hasPixels) stateDescription = loadingDescription }, contentAlignment = Alignment.Center) {
        key(uri, retry) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                RegionImageView(it).also { v ->
                    v.tapped = tap
                    v.longHeld = longHold
                    v.swipeUp = swipeUp
                    v.loadingChanged = { busy, failed, visible ->
                        loading = busy
                        error = failed
                        hasPixels = visible
                    }
                    v.open(uri, cache, cacheKey)
                }
            }, onReset = null, onRelease = { it.close() }, update = {
                it.tapped = tap
                it.longHeld = longHold
                it.swipeUp = swipeUp
            })
        }
        // Loading is conveyed by the existing thumbnail, not an animated overlay.
        if (!hasPixels) placeholder()
        if (error) {
            Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label("Original unavailable", "মূল ছবি খোলা যায়নি"), style = MaterialTheme.typography.bodySmall)
                    TextButton({ retry++ }) { Text(label("Retry", "আবার চেষ্টা করুন")) }
                }
            }
        }
    }
}
