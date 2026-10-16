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
    private var image: Bitmap? = null
    private var ownsImage = true
    private var overview: Bitmap? = null
    private var ownsOverview = false
    private var savedWidth = 0
    private var savedHeight = 0
    private var orientation = 1
    private var previewCache: DecodedPhotoCache? = null
    private var previewKey = ""
    private var previewEpoch = 0L
    private var region = Rect()
    private var renderedSample = Int.MAX_VALUE
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var loadingChanged: (Boolean, Boolean, Boolean) -> Unit = { _, _, _ -> }
    private companion object {
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    var tapped: () -> Unit = {}
    var swipeUp: (() -> Unit)? = null
    init {
        isClickable = true
        contentDescription = "Photo. Pinch to zoom; double tap to zoom or fit; swipe up for details."
    }
    private fun imageDelta(x: Float, y: Float): Pair<Float, Float> {
        val angle = Math.toRadians(-rotation.toDouble())
        var ix = (x * cos(angle) - y * sin(angle)).toFloat()
        if (mirrored) ix = -ix
        return ix to (x * sin(angle) + y * cos(angle)).toFloat()
    }
    private fun zoom(next: Float, x: Float, y: Float) {
        val old = displayScale()
        scale = next.coerceIn(1f, 20f)
        val factor = displayScale()
        val (ix, iy) = imageDelta(x - width / 2f, y - height / 2f)
        cx += ix * (1f / old - 1f / factor)
        cy += iy * (1f / old - 1f / factor)
        requestRegion()
    }
    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                animation?.cancel()
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )
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
                if (scale <= 1.05f && e1 != null && e1.y - e2.y > 72 * resources.displayMetrics.density && e1.y - e2.y > abs(e1.x - e2.x) * 1.5f) {
                    swipeUp?.invoke()
                    return swipeUp != null
                }
                return false
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale <= 1f) return false
                val (ix, iy) = imageDelta(dx, dy)
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
            ownsOverview = false
            ownsImage = false
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
        tasks.launch {
            var opened: Pair<android.os.ParcelFileDescriptor, BitmapRegionDecoder>? = null
            try {
                // Keep handles reachable even if the view closes during native I/O.
                withContext(Dispatchers.IO + NonCancellable) {
                    if (savedWidth == 0) orientation = context.contentResolver.openInputStream(uri)?.use { runCatching { androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1) } ?: 1
                    val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot read image")
                    try {
                        opened = fd to checkNotNull(BitmapRegionDecoder.newInstance(fd.fileDescriptor, false))
                    } catch (error: Throwable) {
                        fd.close()
                        throw error
                    }
                }
                ensureActive()
                setOrientation()
                descriptor = opened!!.first
                decoder = opened!!.second
                opened = null
                cx = decoder!!.width / 2f
                cy = decoder!!.height / 2f
                requestRegion()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                loadingChanged(false, true, image != null)
                contentDescription = "Cannot preview. Use Open with."
                invalidate()
            } finally {
                opened?.let {
                    it.second.recycle()
                    it.first.close()
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
    }
    private fun displayScale(): Float {
        val w = decoder?.width ?: savedWidth
        val h = decoder?.height ?: savedHeight
        if (w == 0 || h == 0) return 1f
        return min((if (rotation % 180 == 0f) width else height).toFloat() / w, (if (rotation % 180 == 0f) height else width).toFloat() / h).coerceAtLeast(.0001f) * scale
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        requestRegion()
    }
    private fun requestRegion() {
        val d = decoder ?: return
        if (width == 0 || height == 0 || closed) return
        val factor = displayScale()
        val halfW = min(d.width / 2f, (if (rotation % 180 == 0f) width else height) / factor / 2)
        val halfH = min(d.height / 2f, (if (rotation % 180 == 0f) height else width) / factor / 2)
        cx = cx.coerceIn(halfW, d.width - halfW)
        cy = cy.coerceIn(halfH, d.height - halfH)
        val wanted = Rect(floor(cx - halfW * 1.25f).toInt().coerceAtLeast(0), floor(cy - halfH * 1.25f).toInt().coerceAtLeast(0), ceil(cx + halfW * 1.25f).toInt().coerceAtMost(d.width), ceil(cy + halfH * 1.25f).toInt().coerceAtMost(d.height))
        var sample = 1
        val tileBudget = minOf(app.televault.data.MediaMemory.budget(context, 8, 3).toLong(), Runtime.getRuntime().maxMemory() / 16)
        while (wanted.width() / sample > width * 2 || wanted.height() / sample > height * 2 || wanted.width().toLong() / sample * (wanted.height() / sample) * 4 > tileBudget) sample *= 2
        invalidate() // Transform the existing tile immediately, without waiting for decoding.
        if (image != null && region.contains(wanted) && renderedSample <= sample && pending == null) return
        pending = Tile(wanted, sample, ++serial)
        if (job?.isActive == true) return
        job = tasks.launch {
            while (isActive && pending != null) {
                val tile = pending!!
                pending = null
                val originalWidth = d.width
                val originalHeight = d.height
                var bitmap: Bitmap? = null
                try {
                    withContext(Dispatchers.IO + NonCancellable) {
                        bitmap = synchronized(d) { if (d.isRecycled) null else runCatching { d.decodeRegion(tile.rect, BitmapFactory.Options().apply { inSampleSize = tile.sample }) }.getOrNull() }
                        if (tile.rect == Rect(0, 0, originalWidth, originalHeight) && previewCache != null) {
                            bitmap?.let { previewCache?.remember(previewKey, it, originalWidth, originalHeight, orientation, previewEpoch) }
                        }
                    }
                    ensureActive()
                    if (tile.version != serial && overview == null && tile.rect == Rect(0, 0, originalWidth, originalHeight)) {
                        overview = bitmap
                        ownsOverview = true
                        bitmap = null
                        invalidate()
                    }
                    if (tile.version == serial) {
                        if (bitmap != null) {
                            val old = image.takeIf { ownsImage && it !== overview }
                            if (tile.rect == Rect(0, 0, originalWidth, originalHeight)) {
                                val previousOverview = overview.takeIf { ownsOverview }
                                overview = bitmap
                                ownsOverview = true
                                previousOverview?.recycle()
                            }
                            ownsImage = true
                            image = bitmap
                            bitmap = null
                            region = tile.rect
                            renderedSample = tile.sample
                            loadingChanged(false, false, true)
                            invalidate()
                            old?.recycle()
                        } else {
                            // Keep the last authenticated tile when a new region/read fails.
                            loadingChanged(false, true, image != null)
                        }
                    }
                } finally {
                    bitmap?.recycle()
                }
            }
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = image ?: overview ?: return
        val factor = displayScale()
        drawBounds.set((region.left - cx) * factor, (region.top - cy) * factor, (region.right - cx) * factor, (region.bottom - cy) * factor)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(rotation)
        if (mirrored) canvas.scale(-1f, 1f)
        overview?.takeUnless { it.isRecycled }?.let { base ->
            val w = decoder?.width ?: savedWidth
            val h = decoder?.height ?: savedHeight
            canvas.drawBitmap(base, null, RectF(-cx * factor, -cy * factor, (w - cx) * factor, (h - cy) * factor), paint)
        }
        if (image != null) canvas.drawBitmap(bitmap, null, drawBounds, paint)
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
        }
        val upward = swipeUp != null && downY - event.y > ViewConfiguration.get(context).scaledTouchSlop && downY - event.y > abs(event.x - downX) * 1.5f
        parent?.requestDisallowInterceptTouchEvent(event.pointerCount > 1 || scale > 1f || upward)
        pinch.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }
    fun close() {
        closed = true
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
        if (ownsImage && image !== overview) image?.recycle()
        if (ownsOverview) overview?.recycle()
        overview = null
        image = null
    }
}

@Composable internal fun RegionImage(uri: Uri, modifier: Modifier, tap: () -> Unit = {}, swipeUp: (() -> Unit)? = null, placeholder: @Composable () -> Unit = {}, cache: DecodedPhotoCache? = null, cacheKey: String = uri.toString()) {
    var retry by remember(uri) { mutableIntStateOf(0) }
    var loading by remember(uri, retry) { mutableStateOf(true) }
    var error by remember(uri, retry) { mutableStateOf(false) }
    var hasPixels by remember(uri, retry) { mutableStateOf(false) }
    val loadingDescription = label("Loading original photo", "মূল ছবি লোড হচ্ছে")
    Box(modifier.semantics { if (loading && !hasPixels) stateDescription = loadingDescription }, contentAlignment = Alignment.Center) {
        key(uri, retry) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                RegionImageView(it).also { v ->
                    v.tapped = tap
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
