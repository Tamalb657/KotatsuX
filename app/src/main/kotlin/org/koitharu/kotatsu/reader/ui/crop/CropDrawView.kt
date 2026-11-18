package org.koitharu.kotatsu.reader.ui.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class CropDrawView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

	private val dp = resources.displayMetrics.density

	// Source & Offscreen Compositing Bitmaps
	private var baseBitmap: Bitmap? = null
	private var orientedOrigBitmap: Bitmap? = null
	private var committedWorkingBitmap: Bitmap? = null
	private var workingBitmap: Bitmap? = null
	private var opScratchBitmap: Bitmap? = null

	var imgW: Int = 1000
		private set
	var imgH: Int = 1416
		private set

	// Tool & Document State
	var activeTool: EditorTool = EditorTool.DRAW
		set(value) {
			if (field == EditorTool.LASSO && value != EditorTool.LASSO) {
				activeLassoPts.clear()
			}
			field = value
			loupeVisible = false
			invalidate()
		}

	var drawColor: Int = 0xFFFF6B6B.toInt()
	var drawSize: Int = 16
	var eraserMode: EraserMode = EraserMode.BRUSH
	var eraserSize: Int = 28
	var lassoFeather: Int = 16
	var textTransformMode: Int = 1 // 1: Perspective, 2: Stretch & Skew, 3: Rotate & Scale

	var doc: EditorDocumentState = EditorDocumentState()
		private set

	// Font Registry & Text Bitmap Cache
	val fontPresets = ArrayList<MangaFontPreset>()
	private data class TextCacheEntry(
		val bitmap: Bitmap,
		val w: Int,
		val h: Int,
		val aspect: Float,
	)
	private val textBitmapCache = LinkedHashMap<String, TextCacheEntry>(32, 0.75f, true)

	// History Stack (up to 50 states)
	private val history = ArrayList<EditorDocumentState>()
	private var historyIndex = -1

	// Transient Interaction State
	private var activeOp: StrokeOp? = null
	val activeLassoPts = ArrayList<NormPt>()
	var selectedTextId: String? = null
	private var isDraggingCanvas = false
	private var dragMoved = false
	private var dragMode: String? = null
	private var dragStartPx = 0f
	private var dragStartPy = 0f
	private var dragStartNx = 0f
	private var dragStartNy = 0f
	private var dragStartQuad: List<NormPt>? = null
	private var dragTransformBasis: TextTransformBasis? = null
	private var wasAlreadySelectedOnDown = false
	private var modeBadgeScreenRect: RectF? = null

	// Slider scaling state for Text tool
	private var textSliderBaseQuad: List<NormPt>? = null

	// Viewport & Pinch-Zoom/Pan State
	private val fitImgRect = RectF()
	val imgRect = RectF()
	private var userZoom = 1f
	private var userPanX = 0f
	private var userPanY = 0f
	private var isMultiTouchZooming = false
	private var lastFocusX = 0f
	private var lastFocusY = 0f

	// 88dp Magnifier Loupe State
	private var loupeVisible = false
	private var loupeFlippedRight = false
	private var loupePointerPx = 0f
	private var loupePointerPy = 0f
	private var loupePointerNx = 0f
	private var loupePointerNy = 0f

	// Callbacks to Activity
	var onHistoryChanged: ((canUndo: Boolean, canRedo: Boolean, canReset: Boolean) -> Unit)? = null
	var onSubOptionsChanged: (() -> Unit)? = null
	var onSnackbarMessage: ((String) -> Unit)? = null
	var onRequestAddOrEditText: ((existing: PerspectiveTextLayer?, tapPos: NormPt?) -> Unit)? = null
	var onRequestTextContextMenu: ((layer: PerspectiveTextLayer, anchorLeft: Float, anchorTop: Float) -> Unit)? = null
	var onDismissOverlays: (() -> Unit)? = null

	// Reusable Paints & Paths
	private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val shadowRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = 0xFF141821.toInt()
		setShadowLayer(18f * dp, 0f, 5f * dp, 0x73000000)
	}
	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
	}
	private val dstOutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
		color = Color.BLACK
		xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
	}
	private val srcInPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
		xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
	}
	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
	}
	private val overlayStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
	}
	private val lassoDashEffect = DashPathEffect(floatArrayOf(6f * dp, 5f * dp), 0f)
	private val loupeEraseDashEffect = DashPathEffect(floatArrayOf(5f * dp, 4f * dp), 0f)

	private val tmpPath = Path()
	private val tmpPath2 = Path()
	private val tmpRect = RectF()
	private val tmpMatrix = Matrix()
	private val srcPolyPts = FloatArray(8)
	private val dstPolyPts = FloatArray(8)

	private data class TextTransformBasis(
		val screenPts: List<NormPt>,
		val center: NormPt,
		val u0: NormPt,
		val v0: NormPt,
		val axisX: NormPt,
		val axisY: NormPt,
		val startRadius: Float,
		val startAngle: Float,
	)

	private val scaleDetector = ScaleGestureDetector(
		context,
		object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
			override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
				isMultiTouchZooming = true
				lastFocusX = detector.focusX
				lastFocusY = detector.focusY
				cancelActivePointerGesture()
				return true
			}

			override fun onScale(detector: ScaleGestureDetector): Boolean {
				val prevZoom = userZoom
				userZoom = (userZoom * detector.scaleFactor).coerceIn(1f, 5f)
				val zoomChange = userZoom / prevZoom
				val fx = detector.focusX
				val fy = detector.focusY
				val dx = fx - lastFocusX
				val dy = fy - lastFocusY
				val cx = fitImgRect.centerX() + userPanX
				val cy = fitImgRect.centerY() + userPanY
				val newCx = fx + (cx - fx) * zoomChange + dx
				val newCy = fy + (cy - fy) * zoomChange + dy
				userPanX = newCx - fitImgRect.centerX()
				userPanY = newCy - fitImgRect.centerY()
				clampPan()
				lastFocusX = fx
				lastFocusY = fy
				updateImgRectFromFit()
				invalidate()
				return true
			}

			override fun onScaleEnd(detector: ScaleGestureDetector) {
				if (userZoom < 1.02f) {
					userZoom = 1f
					userPanX = 0f
					userPanY = 0f
					updateImgRectFromFit()
					invalidate()
				}
			}
		},
	)

	init {
		setBackgroundColor(EditorColors.CANVAS_BACKDROP)
		initCheckerShader()
		fontPresets.addAll(MangaFontRegistry.createBuiltInPresets(context))
	}

	private fun initCheckerShader() {
		val tile = (8f * dp).roundToInt().coerceAtLeast(4)
		val bmp = Bitmap.createBitmap(tile * 2, tile * 2, Bitmap.Config.ARGB_8888)
		val c = Canvas(bmp)
		val p = Paint()
		p.color = 0xFF141821.toInt()
		c.drawRect(0f, 0f, (tile * 2).toFloat(), (tile * 2).toFloat(), p)
		p.color = 0xFF1F2430.toInt()
		c.drawRect(0f, 0f, tile.toFloat(), tile.toFloat(), p)
		c.drawRect(tile.toFloat(), tile.toFloat(), (tile * 2).toFloat(), (tile * 2).toFloat(), p)
		checkerPaint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
	}

	fun setBitmap(source: Bitmap) {
		val maxDim = 2200
		val bw = source.width
		val bh = source.height
		val prepared = if (max(bw, bh) > maxDim) {
			val r = maxDim.toFloat() / max(bw, bh).toFloat()
			val sw = max(1, (bw * r).roundToInt())
			val sh = max(1, (bh * r).roundToInt())
			Bitmap.createScaledBitmap(source, sw, sh, true)
		} else if (source.config != Bitmap.Config.ARGB_8888) {
			source.copy(Bitmap.Config.ARGB_8888, false)
		} else {
			source
		}
		baseBitmap = prepared
		imgW = prepared.width
		imgH = prepared.height

		doc = EditorDocumentState()
		userZoom = 1f
		userPanX = 0f
		userPanY = 0f
		activeOp = null
		activeLassoPts.clear()
		selectedTextId = null
		history.clear()
		historyIndex = -1
		clearTextCache()

		computeViewportMetrics()
		pushHistory()
	}

	fun getOrientedDimensions(): Pair<Int, Int> {
		val swapped = doc.rotationSteps % 2 == 1
		return if (swapped) Pair(imgH, imgW) else Pair(imgW, imgH)
	}

	val displayScale: Float
		get() {
			val (iw, _) = getOrientedDimensions()
			return if (iw > 0 && imgRect.width() > 0f) imgRect.width() / iw.toFloat() else 1f
		}

	private fun strokeScaleFactor(w: Int, h: Int): Float = max(w, h) / 1100f

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		computeViewportMetrics()
	}

	fun computeViewportMetrics() {
		val vw = width.toFloat()
		val vh = height.toFloat()
		if (vw <= 0f || vh <= 0f) return

		val padX = 24f * dp
		val padY = 28f * dp
		val availW = max(80f, vw - padX * 2f)
		val availH = max(80f, vh - padY * 2f)

		val (iw, ih) = getOrientedDimensions()
		if (iw <= 0 || ih <= 0) return

		val scale = min(availW / iw.toFloat(), availH / ih.toFloat())
		val drawW = iw * scale
		val drawH = ih * scale
		val drawX = (vw - drawW) * 0.5f
		val drawY = padY + (availH - drawH) * 0.5f
		fitImgRect.set(drawX, drawY, drawX + drawW, drawY + drawH)
		clampPan()
		updateImgRectFromFit()
		onDismissOverlays?.invoke()
		invalidate()
	}

	private fun clampPan() {
		if (userZoom <= 1.001f) {
			userPanX = 0f
			userPanY = 0f
			return
		}
		val maxPanX = max(0f, (fitImgRect.width() * userZoom - width * 0.85f) * 0.5f + 48f * dp)
		val maxPanY = max(0f, (fitImgRect.height() * userZoom - height * 0.85f) * 0.5f + 48f * dp)
		userPanX = userPanX.coerceIn(-maxPanX, maxPanX)
		userPanY = userPanY.coerceIn(-maxPanY, maxPanY)
	}

	private fun updateImgRectFromFit() {
		val cx = fitImgRect.centerX() + userPanX
		val cy = fitImgRect.centerY() + userPanY
		val hw = fitImgRect.width() * userZoom * 0.5f
		val hh = fitImgRect.height() * userZoom * 0.5f
		imgRect.set(cx - hw, cy - hh, cx + hw, cy + hh)
	}

	// =========================================================================
	// HISTORY STACK (UNDO / REDO / RESET)
	// =========================================================================

	fun canUndo(): Boolean = historyIndex > 0
	fun canRedo(): Boolean = historyIndex < history.size - 1
	fun canReset(): Boolean = historyIndex > 0

	fun pushHistory() {
		while (history.size > historyIndex + 1) {
			history.removeAt(history.lastIndex)
		}
		history.add(doc.deepCopy())
		if (history.size > 50) {
			history.removeAt(0)
		}
		historyIndex = history.lastIndex
		notifyHistoryChanged()
		rebuildWorkingImage(rebuildCommitted = true)
		invalidate()
	}

	fun undo() {
		if (!canUndo()) return
		historyIndex--
		doc = history[historyIndex].deepCopy()
		activeLassoPts.clear()
		activeOp = null
		onDismissOverlays?.invoke()
		notifyHistoryChanged()
		computeViewportMetrics()
		rebuildWorkingImage(rebuildCommitted = true)
		onSubOptionsChanged?.invoke()
		onSnackbarMessage?.invoke("Undid last action")
		invalidate()
	}

	fun redo() {
		if (!canRedo()) return
		historyIndex++
		doc = history[historyIndex].deepCopy()
		activeLassoPts.clear()
		activeOp = null
		onDismissOverlays?.invoke()
		notifyHistoryChanged()
		computeViewportMetrics()
		rebuildWorkingImage(rebuildCommitted = true)
		onSubOptionsChanged?.invoke()
		onSnackbarMessage?.invoke("Redid action")
		invalidate()
	}

	fun resetAllEdits() {
		if (!canReset()) return
		doc = EditorDocumentState()
		userZoom = 1f
		userPanX = 0f
		userPanY = 0f
		activeLassoPts.clear()
		activeOp = null
		selectedTextId = null
		onDismissOverlays?.invoke()
		computeViewportMetrics()
		pushHistory()
		onSubOptionsChanged?.invoke()
		onSnackbarMessage?.invoke("Reset to original image")
	}

	private fun notifyHistoryChanged() {
		onHistoryChanged?.invoke(canUndo(), canRedo(), canReset())
	}

	// =========================================================================
	// OFFSCREEN PIXEL & STROKE COMPOSITING ENGINE
	// =========================================================================

	private fun ensureBitmapSize(existing: Bitmap?, w: Int, h: Int): Bitmap {
		if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
			return existing
		}
		existing?.recycle()
		return Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
	}

	private fun ensureOrientedOriginalBitmap() {
		val base = baseBitmap ?: return
		val (w, h) = getOrientedDimensions()
		val bmp = ensureBitmapSize(orientedOrigBitmap, w, h)
		orientedOrigBitmap = bmp
		bmp.eraseColor(Color.TRANSPARENT)
		val c = Canvas(bmp)
		c.save()
		c.translate(w * 0.5f, h * 0.5f)
		c.rotate((doc.rotationSteps * 90).toFloat())
		val swapped = doc.rotationSteps % 2 == 1
		val rw = if (swapped) h else w
		val rh = if (swapped) w else h
		tmpRect.set(-rw * 0.5f, -rh * 0.5f, rw * 0.5f, rh * 0.5f)
		c.drawBitmap(base, null, tmpRect, bitmapPaint)
		c.restore()
	}

	private fun traceSmoothStrokePath(path: Path, pts: List<NormPt>, w: Float, h: Float) {
		path.reset()
		if (pts.isEmpty()) return
		val first = pts[0]
		if (pts.size == 1) {
			path.moveTo(first.x * w, first.y * h)
			path.lineTo(first.x * w + 0.1f, first.y * h + 0.1f)
			return
		}
		path.moveTo(first.x * w, first.y * h)
		for (i in 1 until pts.size - 1) {
			val xc = (pts[i].x + pts[i + 1].x) * 0.5f * w
			val yc = (pts[i].y + pts[i + 1].y) * 0.5f * h
			path.quadTo(pts[i].x * w, pts[i].y * h, xc, yc)
		}
		val last = pts.last()
		path.lineTo(last.x * w, last.y * h)
	}

	private fun applySingleOpToCanvas(targetCanvas: Canvas, op: StrokeOp, w: Int, h: Int) {
		val wf = w.toFloat()
		val hf = h.toFloat()
		val sf = strokeScaleFactor(w, h)
		when (op.kind) {
			StrokeOpKind.DRAW -> {
				if (op.pts.isEmpty()) return
				traceSmoothStrokePath(tmpPath, op.pts, wf, hf)
				strokePaint.color = op.color
				strokePaint.strokeWidth = max(1.5f, op.size * sf)
				targetCanvas.drawPath(tmpPath, strokePaint)
			}
			StrokeOpKind.ERASE_BRUSH -> {
				if (op.pts.isEmpty()) return
				traceSmoothStrokePath(tmpPath, op.pts, wf, hf)
				dstOutPaint.strokeWidth = max(2f, op.size * sf)
				targetCanvas.drawPath(tmpPath, dstOutPaint)
			}
			StrokeOpKind.RESTORE -> {
				if (op.pts.isEmpty()) return
				val orig = orientedOrigBitmap ?: return
				val scratch = ensureBitmapSize(opScratchBitmap, w, h)
				opScratchBitmap = scratch
				scratch.eraseColor(Color.TRANSPARENT)
				val sc = Canvas(scratch)

				traceSmoothStrokePath(tmpPath, op.pts, wf, hf)
				strokePaint.color = Color.WHITE
				strokePaint.strokeWidth = max(2f, op.size * sf)
				sc.drawPath(tmpPath, strokePaint)

				// Punch out any drawn pixels under the restore brush first
				dstOutPaint.strokeWidth = max(2f, op.size * sf)
				targetCanvas.drawPath(tmpPath, dstOutPaint)

				// Mask original image through stroke mask and composite back
				sc.drawBitmap(orig, 0f, 0f, srcInPaint)
				targetCanvas.drawBitmap(scratch, 0f, 0f, bitmapPaint)
			}
			StrokeOpKind.ERASE_UI_RECT -> {
				val r = op.rect ?: return
				val rx = r.x * wf
				val ry = r.y * hf
				val rw = r.w * wf
				val rh = r.h * hf
				val rad = min(min(rw * 0.5f, rh * 0.5f), r.r * sf)
				fillPaint.color = op.fillColor
				fillPaint.maskFilter = null
				tmpRect.set(rx, ry, rx + rw, ry + rh)
				targetCanvas.drawRoundRect(tmpRect, rad, rad, fillPaint)
			}
		}
	}

	fun rebuildWorkingImage(rebuildCommitted: Boolean = true) {
		if (baseBitmap == null) return
		val (w, h) = getOrientedDimensions()
		ensureOrientedOriginalBitmap()
		val orig = orientedOrigBitmap ?: return

		if (rebuildCommitted || committedWorkingBitmap == null) {
			val committed = ensureBitmapSize(committedWorkingBitmap, w, h)
			committedWorkingBitmap = committed
			committed.eraseColor(Color.TRANSPARENT)
			val cc = Canvas(committed)
			cc.drawBitmap(orig, 0f, 0f, null)
			for (op in doc.ops) {
				applySingleOpToCanvas(cc, op, w, h)
			}
		}

		val committed = committedWorkingBitmap ?: return
		val working = ensureBitmapSize(workingBitmap, w, h)
		workingBitmap = working
		working.eraseColor(Color.TRANSPARENT)
		val wc = Canvas(working)
		wc.drawBitmap(committed, 0f, 0f, null)
		activeOp?.let { op ->
			applySingleOpToCanvas(wc, op, w, h)
		}
	}

	// =========================================================================
	// SMART OBJECT ERASER
	// =========================================================================

	private fun performObjectEraseAt(nx: Float, ny: Float): Boolean {
		val (w, h) = getOrientedDimensions()
		val px = nx * w
		val py = ny * h
		val sf = strokeScaleFactor(w, h)
		val hitTol = max(16f * sf, eraserSize * sf * 0.65f)

		// 1. Perspective Text Layers
		for (i in doc.texts.indices.reversed()) {
			val t = doc.texts[i]
			val q = t.pts.map { NormPt(it.x * w, it.y * h) }
			if (HomographyMath.pointInPoly(px, py, q)) {
				doc.texts.removeAt(i)
				if (selectedTextId == t.id) selectedTextId = null
				pushHistory()
				onSubOptionsChanged?.invoke()
				onSnackbarMessage?.invoke("Removed text layer")
				return true
			}
		}

		// 2. Drawn Brush Strokes
		for (i in doc.ops.indices.reversed()) {
			val op = doc.ops[i]
			if (op.kind != StrokeOpKind.DRAW || op.pts.isEmpty()) continue
			val tol = max(hitTol, op.size * sf * 0.65f)
			var hit = false
			if (op.pts.size == 1) {
				hit = hypot(px - op.pts[0].x * w, py - op.pts[0].y * h) <= tol
			} else {
				for (j in 0 until op.pts.size - 1) {
					val d = HomographyMath.distToSegment(
						px, py,
						op.pts[j].x * w, op.pts[j].y * h,
						op.pts[j + 1].x * w, op.pts[j + 1].y * h,
					)
					if (d <= tol) {
						hit = true
						break
					}
				}
			}
			if (hit) {
				doc.ops.removeAt(i)
				pushHistory()
				onSnackbarMessage?.invoke("Removed brush stroke")
				return true
			}
		}

		// 3. Lasso Regions
		for (i in doc.regions.indices.reversed()) {
			val reg = doc.regions[i]
			val poly = reg.pts.map { NormPt(it.x * w, it.y * h) }
			if (HomographyMath.pointInPoly(px, py, poly)) {
				doc.regions.removeAt(i)
				pushHistory()
				onSubOptionsChanged?.invoke()
				onSnackbarMessage?.invoke("Removed lasso region")
				return true
			}
		}

		// 4. Connected UI / Speech-Bubble Element Detection
		val detected = detectConnectedUiBounds(px.roundToInt(), py.roundToInt(), w, h)
		if (detected != null) {
			doc.ops.add(
				StrokeOp(
					id = "op_${System.currentTimeMillis()}",
					kind = StrokeOpKind.ERASE_UI_RECT,
					rect = detected.first,
					fillColor = detected.second,
				),
			)
			pushHistory()
			onSnackbarMessage?.invoke("Removed UI element")
			return true
		}

		onSnackbarMessage?.invoke("No stroke or UI item found at tap point")
		return false
	}

	private fun detectConnectedUiBounds(ix: Int, iy: Int, w: Int, h: Int): Pair<NormRect, Int>? {
		val bmp = workingBitmap ?: return null
		if (ix !in 0 until w || iy !in 0 until h) return null
		val c0 = bmp.getPixel(ix, iy)
		if (Color.alpha(c0) < 20) return null

		val probeR = (min(w, h) * 0.14f).roundToInt().coerceAtLeast(8)
		val sampleCoords = arrayOf(
			intArrayOf((ix - probeR).coerceAtLeast(0), iy),
			intArrayOf((ix + probeR).coerceAtMost(w - 1), iy),
			intArrayOf(ix, (iy - probeR).coerceAtLeast(0)),
			intArrayOf(ix, (iy + probeR).coerceAtMost(h - 1)),
			intArrayOf((ix - probeR).coerceAtLeast(0), (iy - probeR).coerceAtLeast(0)),
			intArrayOf((ix + probeR).coerceAtMost(w - 1), (iy + probeR).coerceAtMost(h - 1)),
		)
		var bgR = 0
		var bgG = 0
		var bgB = 0
		for (sc in sampleCoords) {
			val p = bmp.getPixel(sc[0], sc[1])
			bgR += Color.red(p)
			bgG += Color.green(p)
			bgB += Color.blue(p)
		}
		bgR /= sampleCoords.size
		bgG /= sampleCoords.size
		bgB /= sampleCoords.size

		fun diffFromBg(p: Int): Int =
			abs(Color.red(p) - bgR) + abs(Color.green(p) - bgG) + abs(Color.blue(p) - bgB)

		if (diffFromBg(c0) < 24) return null

		val step = max(4, min(w, h) / 220)
		val gw = ceil(w / step.toDouble()).toInt()
		val gh = ceil(h / step.toDouble()).toInt()
		val visited = BooleanArray(gw * gh)
		val startGx = (ix / step).coerceIn(0, gw - 1)
		val startGy = (iy / step).coerceIn(0, gh - 1)
		val queue = IntArray(gw * gh)
		var head = 0
		var tail = 0
		val startIdx = startGy * gw + startGx
		visited[startIdx] = true
		queue[tail++] = startIdx

		var minGx = startGx
		var maxGx = startGx
		var minGy = startGy
		var maxGy = startGy
		var count = 0
		val maxCells = (gw * gh * 0.18f).toInt().coerceAtLeast(16)

		while (head < tail && count < maxCells) {
			val cur = queue[head++]
			val cy = cur / gw
			val cx = cur % gw
			count++
			if (cx < minGx) minGx = cx
			if (cx > maxGx) maxGx = cx
			if (cy < minGy) minGy = cy
			if (cy > maxGy) maxGy = cy

			for (dy in -2..2) {
				for (dx in -2..2) {
					val nx = cx + dx
					val ny = cy + dy
					if (nx !in 0 until gw || ny !in 0 until gh) continue
					val nIdx = ny * gw + nx
					if (visited[nIdx]) continue
					val sx = min(w - 1, nx * step + step / 2)
					val sy = min(h - 1, ny * step + step / 2)
					val pxColor = bmp.getPixel(sx, sy)
					if (Color.alpha(pxColor) > 25 && diffFromBg(pxColor) >= 22) {
						visited[nIdx] = true
						queue[tail++] = nIdx
					}
				}
			}
		}

		if (count >= maxCells || count < 2) return null
		val pad = 8f * strokeScaleFactor(w, h)
		val rx = max(0f, minGx * step - pad)
		val ry = max(0f, minGy * step - pad)
		val rw = min(w - rx, (maxGx - minGx + 1) * step + pad * 2f)
		val rh = min(h - ry, (maxGy - minGy + 1) * step + pad * 2f)
		val fillColor = Color.rgb(bgR, bgG, bgB)
		return Pair(NormRect(rx / w, ry / h, rw / w, rh / h, 14f), fillColor)
	}

	// =========================================================================
	// PERSPECTIVE TEXT CACHE & POLY-TO-POLY RENDERING
	// =========================================================================

	fun clearTextCache() {
		textBitmapCache.values.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
		textBitmapCache.clear()
	}

	fun getFontPresetById(fontId: String): MangaFontPreset =
		fontPresets.firstOrNull { it.id == fontId } ?: fontPresets.first()

	private fun getOrCreateTextCache(text: String, color: Int, fontId: String): TextCacheEntry {
		val preset = getFontPresetById(fontId)
		val rawText = text.trim().ifEmpty { "TEXT" }
		val cleanText = if (preset.uppercase) rawText.uppercase() else rawText
		val key = "${preset.id}::$color::$cleanText"
		textBitmapCache[key]?.let { if (!it.bitmap.isRecycled) return it }

		var fontSize = 96f
		val tp = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
			typeface = preset.typeface
			textSize = fontSize
			isFakeBoldText = preset.isBoldWeight && !preset.typeface.isBold
			letterSpacing = (preset.letterSpacingPx / fontSize).coerceIn(0f, 0.25f)
			textAlign = Paint.Align.CENTER
		}

		val lines = cleanText.split('\n')
		var maxLineW = lines.maxOfOrNull { tp.measureText(it) }?.coerceAtLeast(64f) ?: 64f
		if (maxLineW > 3200f) {
			fontSize = (fontSize * (3200f / maxLineW)).coerceAtLeast(28f)
			tp.textSize = fontSize
			tp.letterSpacing = (preset.letterSpacingPx / fontSize).coerceIn(0f, 0.25f)
			maxLineW = lines.maxOfOrNull { tp.measureText(it) }?.coerceAtLeast(64f) ?: 64f
		}
		val padX = 40
		val padY = 32
		val lineH = (fontSize * 1.24f)
		val cw = ceil(maxLineW).toInt() + padX * 2
		val ch = ceil(lineH * lines.size).toInt() + padY * 2

		val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
		val c = Canvas(bmp)

		val isDarkInk = !EditorColors.isLightSwatch(color) &&
			(Color.red(color) + Color.green(color) + Color.blue(color) < 95)
		val shadowColor: Int = if (isDarkInk) 0x70FFFFFF else 0x96000000.toInt()
		val shadowDy = if (isDarkInk) 2f else 4f
		tp.color = color
		tp.setShadowLayer(10f, 0f, shadowDy, shadowColor)

		val fm = tp.fontMetrics
		val totalTextH = lineH * lines.size
		var startY = (ch - totalTextH) * 0.5f - fm.ascent * 0.85f
		val cx = cw * 0.5f
		for (line in lines) {
			c.drawText(line, cx, startY, tp)
			startY += lineH
		}

		if (textBitmapCache.size > 40) {
			val eldest = textBitmapCache.entries.firstOrNull()
			if (eldest != null) {
				if (!eldest.value.bitmap.isRecycled) eldest.value.bitmap.recycle()
				textBitmapCache.remove(eldest.key)
			}
		}

		val entry = TextCacheEntry(bmp, cw, ch, cw.toFloat() / ch.toFloat())
		textBitmapCache[key] = entry
		return entry
	}

	private fun drawBitmapPolyToPoly(canvas: Canvas, entry: TextCacheEntry, dstPts: List<NormPt>) {
		if (dstPts.size < 4 || entry.bitmap.isRecycled) return
		val sw = entry.w.toFloat()
		val sh = entry.h.toFloat()

		if (HomographyMath.isQuadConvex(dstPts)) {
			srcPolyPts[0] = 0f; srcPolyPts[1] = 0f
			srcPolyPts[2] = sw; srcPolyPts[3] = 0f
			srcPolyPts[4] = sw; srcPolyPts[5] = sh
			srcPolyPts[6] = 0f; srcPolyPts[7] = sh

			for (i in 0 until 4) {
				dstPolyPts[i * 2] = dstPts[i].x
				dstPolyPts[i * 2 + 1] = dstPts[i].y
			}

			if (tmpMatrix.setPolyToPoly(srcPolyPts, 0, dstPolyPts, 0, 4)) {
				tmpPath.reset()
				tmpPath.moveTo(dstPts[0].x, dstPts[0].y)
				tmpPath.lineTo(dstPts[1].x, dstPts[1].y)
				tmpPath.lineTo(dstPts[2].x, dstPts[2].y)
				tmpPath.lineTo(dstPts[3].x, dstPts[3].y)
				tmpPath.close()

				val save = canvas.save()
				canvas.clipPath(tmpPath)
				canvas.concat(tmpMatrix)
				canvas.drawBitmap(entry.bitmap, 0f, 0f, bitmapPaint)
				canvas.restoreToCount(save)
				return
			}
		}

		// Concave quad fallback: 2 affine triangles (0,1,2) and (0,2,3)
		drawTriangleAffine(canvas, entry.bitmap, 0f, 0f, sw, 0f, sw, sh, dstPts[0], dstPts[1], dstPts[2])
		drawTriangleAffine(canvas, entry.bitmap, 0f, 0f, sw, sh, 0f, sh, dstPts[0], dstPts[2], dstPts[3])
	}

	private fun drawTriangleAffine(
		canvas: Canvas,
		bmp: Bitmap,
		sx0: Float, sy0: Float,
		sx1: Float, sy1: Float,
		sx2: Float, sy2: Float,
		d0: NormPt, d1: NormPt, d2: NormPt,
	) {
		srcPolyPts[0] = sx0; srcPolyPts[1] = sy0
		srcPolyPts[2] = sx1; srcPolyPts[3] = sy1
		srcPolyPts[4] = sx2; srcPolyPts[5] = sy2
		dstPolyPts[0] = d0.x; dstPolyPts[1] = d0.y
		dstPolyPts[2] = d1.x; dstPolyPts[3] = d1.y
		dstPolyPts[4] = d2.x; dstPolyPts[5] = d2.y
		if (tmpMatrix.setPolyToPoly(srcPolyPts, 0, dstPolyPts, 0, 3)) {
			tmpPath.reset()
			tmpPath.moveTo(d0.x, d0.y)
			tmpPath.lineTo(d1.x, d1.y)
			tmpPath.lineTo(d2.x, d2.y)
			tmpPath.close()
			val save = canvas.save()
			canvas.clipPath(tmpPath)
			canvas.concat(tmpMatrix)
			canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
			canvas.restoreToCount(save)
		}
	}

	private fun drawBaseAndInkContent(canvas: Canvas, destW: Float, destH: Float) {
		val work = workingBitmap ?: return
		val save = canvas.save()

		if (doc.straighten != 0) {
			val zoom = 1f + abs(doc.straighten) * 0.012f
			canvas.translate(destW * 0.5f, destH * 0.5f)
			canvas.rotate(doc.straighten.toFloat())
			canvas.scale(zoom, zoom)
			canvas.translate(-destW * 0.5f, -destH * 0.5f)
		}

		tmpRect.set(0f, 0f, destW, destH)
		canvas.drawBitmap(work, null, tmpRect, bitmapPaint)

		val featherScale = destW / 1000f
		for (reg in doc.regions) {
			if (reg.pts.size < 3) continue
			val rSave = canvas.save()
			val blurPx = reg.feather * featherScale
			fillPaint.maskFilter = if (blurPx > 0.8f) BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) else null
			when (reg.mode) {
				LassoEffectMode.SPOTLIGHT -> {
					tmpPath.reset()
					tmpPath.fillType = Path.FillType.EVEN_ODD
					tmpPath.addRect(-blurPx * 2f, -blurPx * 2f, destW + blurPx * 2f, destH + blurPx * 2f, Path.Direction.CW)
					tmpPath2.reset()
					tmpPath2.moveTo(reg.pts[0].x * destW, reg.pts[0].y * destH)
					for (i in 1 until reg.pts.size) {
						tmpPath2.lineTo(reg.pts[i].x * destW, reg.pts[i].y * destH)
					}
					tmpPath2.close()
					tmpPath.addPath(tmpPath2)
					fillPaint.color = 0xAD000000.toInt()
					canvas.drawPath(tmpPath, fillPaint)
				}
				LassoEffectMode.REDACT -> {
					tmpPath.reset()
					tmpPath.fillType = Path.FillType.WINDING
					tmpPath.moveTo(reg.pts[0].x * destW, reg.pts[0].y * destH)
					for (i in 1 until reg.pts.size) {
						tmpPath.lineTo(reg.pts[i].x * destW, reg.pts[i].y * destH)
					}
					tmpPath.close()
					fillPaint.color = 0xFF181A20.toInt()
					canvas.drawPath(tmpPath, fillPaint)
					fillPaint.maskFilter = null
					overlayStrokePaint.color = 0x29FFFFFF
					overlayStrokePaint.strokeWidth = 1.5f * featherScale.coerceAtLeast(1f)
					overlayStrokePaint.pathEffect = null
					canvas.drawPath(tmpPath, overlayStrokePaint)
				}
				LassoEffectMode.TINT -> {
					tmpPath.reset()
					tmpPath.fillType = Path.FillType.WINDING
					tmpPath.moveTo(reg.pts[0].x * destW, reg.pts[0].y * destH)
					for (i in 1 until reg.pts.size) {
						tmpPath.lineTo(reg.pts[i].x * destW, reg.pts[i].y * destH)
					}
					tmpPath.close()
					fillPaint.color = 0x47A8C7FA
					canvas.drawPath(tmpPath, fillPaint)
					fillPaint.maskFilter = null
					overlayStrokePaint.color = EditorColors.PRIMARY
					overlayStrokePaint.strokeWidth = 2f * featherScale.coerceAtLeast(1f)
					overlayStrokePaint.pathEffect = null
					canvas.drawPath(tmpPath, overlayStrokePaint)
				}
			}
			fillPaint.maskFilter = null
			canvas.restoreToCount(rSave)
		}

		canvas.restoreToCount(save)
	}

	// =========================================================================
	// DISPLAY CANVAS RENDERING PIPELINE
	// =========================================================================

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (baseBitmap == null || imgRect.isEmpty) return

		val cornerR = 10f * dp

		// 1. Elevation shadow + transparency checkerboard inside image bounds
		canvas.drawRoundRect(imgRect, cornerR, cornerR, shadowRectPaint)
		tmpPath.reset()
		tmpPath.fillType = Path.FillType.WINDING
		tmpPath.addRoundRect(imgRect, cornerR, cornerR, Path.Direction.CW)
		val s1 = canvas.save()
		canvas.clipPath(tmpPath)
		canvas.drawRect(imgRect, checkerPaint)

		// 2. Clipped Active Image + Strokes + Lasso Regions
		canvas.translate(imgRect.left, imgRect.top)
		drawBaseAndInkContent(canvas, imgRect.width(), imgRect.height())
		canvas.restoreToCount(s1)

		// 3. Multi-Layer 4-Point Perspective Text Layers
		for (layer in doc.texts) {
			val entry = getOrCreateTextCache(layer.text, layer.color, layer.fontId)
			val dstScreen = layer.pts.map { p ->
				NormPt(imgRect.left + p.x * imgRect.width(), imgRect.top + p.y * imgRect.height())
			}
			drawBitmapPolyToPoly(canvas, entry, dstScreen)
		}

		// Subtle 1dp border when not in Crop mode
		if (activeTool != EditorTool.CROP) {
			overlayStrokePaint.color = 0x29FFFFFF
			overlayStrokePaint.strokeWidth = 1f * dp
			overlayStrokePaint.pathEffect = null
			tmpRect.set(imgRect)
			tmpRect.inset(-0.5f * dp, -0.5f * dp)
			canvas.drawRoundRect(tmpRect, cornerR + 0.5f * dp, cornerR + 0.5f * dp, overlayStrokePaint)
		}

		// 4. Active Lasso Loop Overlay
		if (activeLassoPts.size > 1) {
			tmpPath.reset()
			tmpPath.fillType = Path.FillType.WINDING
			tmpPath.moveTo(
				imgRect.left + activeLassoPts[0].x * imgRect.width(),
				imgRect.top + activeLassoPts[0].y * imgRect.height(),
			)
			for (i in 1 until activeLassoPts.size) {
				tmpPath.lineTo(
					imgRect.left + activeLassoPts[i].x * imgRect.width(),
					imgRect.top + activeLassoPts[i].y * imgRect.height(),
				)
			}
			if (!isDraggingCanvas) tmpPath.close()
			fillPaint.color = 0x29A8C7FA
			fillPaint.maskFilter = null
			canvas.drawPath(tmpPath, fillPaint)

			overlayStrokePaint.color = EditorColors.PRIMARY
			overlayStrokePaint.strokeWidth = 2.2f * dp
			overlayStrokePaint.pathEffect = lassoDashEffect
			canvas.drawPath(tmpPath, overlayStrokePaint)
			overlayStrokePaint.pathEffect = null
		}

		// 5. Decoupled 4-Point Perspective / Quad Crop Overlay
		if (activeTool == EditorTool.CROP) {
			drawPerspectiveCropOverlay(canvas)
		} else {
			ViewCompat.setSystemGestureExclusionRects(this, emptyList())
		}

		// 6. Decoupled 3-Mode Perspective Text Wireframe & Top-Right Mode Badge
		modeBadgeScreenRect = null
		if (activeTool == EditorTool.TEXT && selectedTextId != null) {
			val sel = doc.texts.firstOrNull { it.id == selectedTextId }
			if (sel != null) {
				drawPerspectiveTextOverlay(canvas, sel)
			}
		}

		// 7. 88dp 2.5x / 2.8x Auto-Flipping Corner Magnifier Loupe
		if (loupeVisible) {
			drawMagnifierLoupe(canvas)
		}
	}

	private fun drawPerspectiveCropOverlay(canvas: Canvas) {
		val q = doc.cropQuad.map { p ->
			NormPt(imgRect.left + p.x * imgRect.width(), imgRect.top + p.y * imgRect.height())
		}
		updateCropGestureExclusions(q)

		// Scrim outside quad
		tmpPath.reset()
		tmpPath.fillType = Path.FillType.EVEN_ODD
		tmpRect.set(imgRect)
		tmpRect.inset(-1f * dp, -1f * dp)
		tmpPath.addRoundRect(tmpRect, 10f * dp, 10f * dp, Path.Direction.CW)
		tmpPath2.reset()
		tmpPath2.moveTo(q[0].x, q[0].y)
		tmpPath2.lineTo(q[1].x, q[1].y)
		tmpPath2.lineTo(q[2].x, q[2].y)
		tmpPath2.lineTo(q[3].x, q[3].y)
		tmpPath2.close()
		tmpPath.addPath(tmpPath2)
		fillPaint.color = if (doc.cropMode == CropExecMode.CUTOUT) 0xC206080D.toInt() else 0xA3000000.toInt()
		fillPaint.maskFilter = null
		canvas.drawPath(tmpPath, fillPaint)

		// Perspective Rule-of-Thirds Grid
		val unitSq = listOf(NormPt(0f, 0f), NormPt(1f, 0f), NormPt(1f, 1f), NormPt(0f, 1f))
		val hGrid = if (HomographyMath.isQuadConvex(q)) HomographyMath.computeHomography(unitSq, q) else null
		val steps = if (hGrid != null) 1 else 12
		overlayStrokePaint.color = if (isDraggingCanvas) 0x85FFFFFF.toInt() else 0x3DFFFFFF
		overlayStrokePaint.strokeWidth = 1f * dp
		overlayStrokePaint.pathEffect = null

		for (frac in floatArrayOf(1f / 3f, 2f / 3f)) {
			tmpPath.reset()
			for (s in 0..steps) {
				val pt = HomographyMath.evalQuadUV(q, hGrid, frac, s.toFloat() / steps)
				if (s == 0) tmpPath.moveTo(pt.x, pt.y) else tmpPath.lineTo(pt.x, pt.y)
			}
			canvas.drawPath(tmpPath, overlayStrokePaint)

			tmpPath.reset()
			for (s in 0..steps) {
				val pt = HomographyMath.evalQuadUV(q, hGrid, s.toFloat() / steps, frac)
				if (s == 0) tmpPath.moveTo(pt.x, pt.y) else tmpPath.lineTo(pt.x, pt.y)
			}
			canvas.drawPath(tmpPath, overlayStrokePaint)
		}

		// Crisp Quad Border
		overlayStrokePaint.color = 0xEBFFFFFF.toInt()
		overlayStrokePaint.strokeWidth = 1.8f * dp
		canvas.drawPath(tmpPath2, overlayStrokePaint)

		// Edge Midpoint Pill Handles
		for (i in 0 until 4) {
			val a = q[i]
			val b = q[(i + 1) % 4]
			if (HomographyMath.distPts(a, b) < 48f * dp) continue
			val mx = (a.x + b.x) * 0.5f
			val my = (a.y + b.y) * 0.5f
			val angleDeg = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()

			val s = canvas.save()
			canvas.translate(mx, my)
			canvas.rotate(angleDeg)
			tmpRect.set(-9f * dp, -3.5f * dp, 9f * dp, 3.5f * dp)
			fillPaint.color = EditorColors.PRIMARY
			canvas.drawRoundRect(tmpRect, 3.5f * dp, 3.5f * dp, fillPaint)
			overlayStrokePaint.color = EditorColors.ON_PRIMARY
			overlayStrokePaint.strokeWidth = 1.5f * dp
			canvas.drawRoundRect(tmpRect, 3.5f * dp, 3.5f * dp, overlayStrokePaint)
			canvas.restoreToCount(s)
		}

		// 4 Corner Handles
		for (i in 0 until 4) {
			val p = q[i]
			val activeCorner = dragMode == "crop-corner-$i"
			fillPaint.color = if (activeCorner) 0x57A8C7FA else 0x59000000
			canvas.drawCircle(p.x, p.y, (if (activeCorner) 14f else 10.5f) * dp, fillPaint)

			val r = (if (activeCorner) 8.5f else 7f) * dp
			fillPaint.color = EditorColors.PRIMARY
			canvas.drawCircle(p.x, p.y, r, fillPaint)
			overlayStrokePaint.color = Color.WHITE
			overlayStrokePaint.strokeWidth = 2.2f * dp
			canvas.drawCircle(p.x, p.y, r, overlayStrokePaint)
		}
	}

	private fun updateCropGestureExclusions(q: List<NormPt>) {
		val halfH = (28f * dp).roundToInt() // 56dp height per handle; 3 * 56dp = 168dp <= 200dp quota
		val edgeW = (64f * dp).roundToInt()
		val leftMidY = ((q[0].y + q[3].y) * 0.5f).roundToInt()
		val rightMidY = ((q[1].y + q[2].y) * 0.5f).roundToInt()
		val rects = listOf(
			Rect(0, q[0].y.roundToInt() - halfH, edgeW, q[0].y.roundToInt() + halfH),
			Rect(0, leftMidY - halfH, edgeW, leftMidY + halfH),
			Rect(0, q[3].y.roundToInt() - halfH, edgeW, q[3].y.roundToInt() + halfH),
			Rect(width - edgeW, q[1].y.roundToInt() - halfH, width, q[1].y.roundToInt() + halfH),
			Rect(width - edgeW, rightMidY - halfH, width, rightMidY + halfH),
			Rect(width - edgeW, q[2].y.roundToInt() - halfH, width, q[2].y.roundToInt() + halfH),
		)
		ViewCompat.setSystemGestureExclusionRects(this, rects)
	}

	private fun getTextRotationHandleScreenPt(screenPts: List<NormPt>): Pair<NormPt, NormPt> {
		val mr = NormPt(
			(screenPts[1].x + screenPts[2].x) * 0.5f,
			(screenPts[1].y + screenPts[2].y) * 0.5f,
		)
		val c = HomographyMath.quadCentroid(screenPts)
		var dx = mr.x - c.x
		var dy = mr.y - c.y
		val len = hypot(dx, dy).let { if (it < 1e-4f) 1f else it }
		dx /= len
		dy /= len
		val handle = NormPt(mr.x + dx * 28f * dp, mr.y + dy * 28f * dp)
		return Pair(mr, handle)
	}

	private fun drawDiamondHandle(canvas: Canvas, x: Float, y: Float, radiusDp: Float, fillColor: Int) {
		val r = radiusDp * dp
		val s = canvas.save()
		canvas.translate(x, y)
		canvas.rotate(45f)
		tmpRect.set(-r, -r, r, r)
		fillPaint.color = fillColor
		canvas.drawRoundRect(tmpRect, 2.5f * dp, 2.5f * dp, fillPaint)
		overlayStrokePaint.color = Color.WHITE
		overlayStrokePaint.strokeWidth = 2f * dp
		canvas.drawRoundRect(tmpRect, 2.5f * dp, 2.5f * dp, overlayStrokePaint)
		canvas.restoreToCount(s)
	}

	private fun drawCircleHandle(canvas: Canvas, x: Float, y: Float, radiusDp: Float, fillColor: Int) {
		val r = radiusDp * dp
		fillPaint.color = fillColor
		canvas.drawCircle(x, y, r, fillPaint)
		overlayStrokePaint.color = Color.WHITE
		overlayStrokePaint.strokeWidth = 2f * dp
		canvas.drawCircle(x, y, r, overlayStrokePaint)
	}

	private fun drawPerspectiveTextOverlay(canvas: Canvas, layer: PerspectiveTextLayer) {
		val q = layer.pts.map { p ->
			NormPt(imgRect.left + p.x * imgRect.width(), imgRect.top + p.y * imgRect.height())
		}

		tmpPath.reset()
		tmpPath.moveTo(q[0].x, q[0].y)
		tmpPath.lineTo(q[1].x, q[1].y)
		tmpPath.lineTo(q[2].x, q[2].y)
		tmpPath.lineTo(q[3].x, q[3].y)
		tmpPath.close()

		overlayStrokePaint.pathEffect = null
		overlayStrokePaint.color = 0x8C000000.toInt()
		overlayStrokePaint.strokeWidth = 3f * dp
		canvas.drawPath(tmpPath, overlayStrokePaint)
		overlayStrokePaint.color = EditorColors.PRIMARY
		overlayStrokePaint.strokeWidth = 1.6f * dp
		canvas.drawPath(tmpPath, overlayStrokePaint)

		when (textTransformMode) {
			1 -> {
				for (i in 0 until 4) {
					drawDiamondHandle(canvas, q[i].x, q[i].y, 6.5f, EditorColors.HANDLE_BLUE)
				}
			}
			2 -> {
				val mr = NormPt((q[1].x + q[2].x) * 0.5f, (q[1].y + q[2].y) * 0.5f)
				drawCircleHandle(canvas, mr.x, mr.y, 7.5f, EditorColors.PRIMARY)
				drawDiamondHandle(canvas, q[2].x, q[2].y, 6.8f, EditorColors.HANDLE_BLUE)
			}
			3 -> {
				val (anchor, handle) = getTextRotationHandleScreenPt(q)
				overlayStrokePaint.color = EditorColors.PRIMARY
				overlayStrokePaint.strokeWidth = 2f * dp
				canvas.drawLine(anchor.x, anchor.y, handle.x, handle.y, overlayStrokePaint)
				drawCircleHandle(canvas, handle.x, handle.y, 8.5f, EditorColors.PRIMARY)
				fillPaint.color = EditorColors.ON_PRIMARY
				canvas.drawCircle(handle.x, handle.y, 2.5f * dp, fillPaint)
			}
		}

		// Top-Right Mode Switcher Badge (24dp x 24dp)
		val c = HomographyMath.quadCentroid(q)
		var vx = q[1].x - c.x
		var vy = q[1].y - c.y
		val vLen = hypot(vx, vy).let { if (it < 1e-4f) 1f else it }
		vx /= vLen
		vy /= vLen

		val badgeSize = 24f * dp
		var bx = q[1].x + vx * 22f * dp
		var by = q[1].y + vy * 22f * dp
		bx = bx.coerceIn(badgeSize, max(badgeSize, width - badgeSize))
		by = by.coerceIn(badgeSize + 8f * dp, max(badgeSize + 8f * dp, height - badgeSize))

		modeBadgeScreenRect = RectF(
			bx - badgeSize * 0.5f - 6f * dp,
			by - badgeSize * 0.5f - 6f * dp,
			bx + badgeSize * 0.5f + 6f * dp,
			by + badgeSize * 0.5f + 6f * dp,
		)

		tmpRect.set(bx - badgeSize * 0.5f, by - badgeSize * 0.5f, bx + badgeSize * 0.5f, by + badgeSize * 0.5f)
		fillPaint.color = EditorColors.PRIMARY
		canvas.drawRoundRect(tmpRect, 7f * dp, 7f * dp, fillPaint)
		overlayStrokePaint.color = Color.WHITE
		overlayStrokePaint.strokeWidth = 1.5f * dp
		canvas.drawRoundRect(tmpRect, 7f * dp, 7f * dp, overlayStrokePaint)

		// Draw M1 / M2 / M3 mini icon inside badge
		overlayStrokePaint.color = EditorColors.ON_PRIMARY
		overlayStrokePaint.strokeWidth = 1.7f * dp
		overlayStrokePaint.strokeCap = Paint.Cap.ROUND
		overlayStrokePaint.strokeJoin = Paint.Join.ROUND
		when (textTransformMode) {
			1 -> {
				tmpPath.reset()
				tmpPath.moveTo(bx - 5f * dp, by - 5.5f * dp)
				tmpPath.lineTo(bx + 5f * dp, by - 3.5f * dp)
				tmpPath.lineTo(bx + 5f * dp, by + 5.5f * dp)
				tmpPath.lineTo(bx - 5f * dp, by + 3.5f * dp)
				tmpPath.close()
				canvas.drawPath(tmpPath, overlayStrokePaint)
			}
			2 -> {
				canvas.drawLine(bx - 6f * dp, by, bx + 6f * dp, by, overlayStrokePaint)
				canvas.drawLine(bx + 3f * dp, by - 3f * dp, bx + 6f * dp, by, overlayStrokePaint)
				canvas.drawLine(bx + 6f * dp, by, bx + 3f * dp, by + 3f * dp, overlayStrokePaint)
				canvas.drawLine(bx - 4f * dp, by - 5f * dp, bx - 6f * dp, by + 5f * dp, overlayStrokePaint)
			}
			else -> {
				tmpRect.set(bx - 5.5f * dp, by - 5.5f * dp, bx + 5.5f * dp, by + 5.5f * dp)
				canvas.drawArc(tmpRect, -135f, 250f, false, overlayStrokePaint)
				fillPaint.color = EditorColors.ON_PRIMARY
				tmpPath.reset()
				tmpPath.moveTo(bx + 2f * dp, by - 6.5f * dp)
				tmpPath.lineTo(bx + 6f * dp, by - 4f * dp)
				tmpPath.lineTo(bx + 2.5f * dp, by - 1.5f * dp)
				tmpPath.close()
				canvas.drawPath(tmpPath, fillPaint)
			}
		}
	}

	// =========================================================================
	// 88dp 2.5x / 2.8x CORNER MAGNIFIER LOUPE WITH AUTO-FLIP
	// =========================================================================

	private fun syncLoupeCornerPosition(checkX: Float, checkY: Float, secX: Float? = null, secY: Float? = null) {
		val vw = width.toFloat()
		val zone = min(134f * dp, vw * 0.38f)
		val hitTopLeft = (checkX < zone && checkY < zone) ||
			(secX != null && secY != null && secX < zone && secY < zone)
		val hitTopRight = (checkX > vw - zone && checkY < zone) ||
			(secX != null && secY != null && secX > vw - zone && secY < zone)

		if (hitTopLeft && !loupeFlippedRight) {
			loupeFlippedRight = true
		} else if (hitTopRight && loupeFlippedRight) {
			loupeFlippedRight = false
		} else if (!hitTopLeft && !hitTopRight && loupeFlippedRight && checkX > 156f * dp && checkY > 156f * dp) {
			loupeFlippedRight = false
		}
	}

	private fun drawMagnifierLoupe(canvas: Canvas) {
		val diameter = 88f * dp
		val radius = diameter * 0.5f
		val margin = 14f * dp
		val left = if (loupeFlippedRight) width - margin - diameter else margin
		val top = margin
		val cx = left + radius
		val cy = top + radius

		val (fullW, fullH) = getOrientedDimensions()
		if (fullW <= 0 || fullH <= 0) return
		val dispScale = displayScale

		// Outer shadow
		canvas.drawCircle(cx, cy, radius, shadowRectPaint)

		val saveOuter = canvas.save()
		tmpPath.reset()
		tmpPath.addCircle(cx, cy, radius, Path.Direction.CW)
		canvas.clipPath(tmpPath)

		fillPaint.color = EditorColors.SURFACE_DIM
		canvas.drawRect(left, top, left + diameter, top + diameter, fillPaint)

		val mode = dragMode.orEmpty()
		if (activeTool == EditorTool.CROP && (mode.startsWith("crop-corner-") || mode.startsWith("crop-edge-"))) {
			val focal = if (mode.startsWith("crop-corner-")) {
				val idx = mode.substringAfterLast('-').toIntOrNull() ?: 0
				doc.cropQuad[idx]
			} else {
				val i1 = mode.substringAfterLast('-').toIntOrNull() ?: 0
				val i2 = (i1 + 1) % 4
				NormPt(
					(doc.cropQuad[i1].x + doc.cropQuad[i2].x) * 0.5f,
					(doc.cropQuad[i1].y + doc.cropQuad[i2].y) * 0.5f,
				)
			}

			val zoomFactor = 2.8f
			val sampleSpanImgPx = diameter / max(0.01f, dispScale * zoomFactor)
			val imgToLoupeScale = diameter / sampleSpanImgPx
			val focalImgX = focal.x * fullW
			val focalImgY = focal.y * fullH

			val sImg = canvas.save()
			canvas.translate(cx, cy)
			canvas.scale(imgToLoupeScale, imgToLoupeScale)
			canvas.translate(-focalImgX, -focalImgY)

			canvas.drawRect(0f, 0f, fullW.toFloat(), fullH.toFloat(), checkerPaint)
			canvas.clipRect(0f, 0f, fullW.toFloat(), fullH.toFloat())
			drawBaseAndInkContent(canvas, fullW.toFloat(), fullH.toFloat())
			for (layer in doc.texts) {
				val entry = getOrCreateTextCache(layer.text, layer.color, layer.fontId)
				val dstImg = layer.pts.map { NormPt(it.x * fullW, it.y * fullH) }
				drawBitmapPolyToPoly(canvas, entry, dstImg)
			}
			canvas.restoreToCount(sImg)

			// Quad scrim & border inside loupe
			val qLoupe = doc.cropQuad.map { p ->
				NormPt(
					cx + (p.x * fullW - focalImgX) * imgToLoupeScale,
					cy + (p.y * fullH - focalImgY) * imgToLoupeScale,
				)
			}
			tmpPath2.reset()
			tmpPath2.fillType = Path.FillType.EVEN_ODD
			tmpPath2.addRect(left, top, left + diameter, top + diameter, Path.Direction.CW)
			tmpPath.reset()
			tmpPath.moveTo(qLoupe[0].x, qLoupe[0].y)
			tmpPath.lineTo(qLoupe[1].x, qLoupe[1].y)
			tmpPath.lineTo(qLoupe[2].x, qLoupe[2].y)
			tmpPath.lineTo(qLoupe[3].x, qLoupe[3].y)
			tmpPath.close()
			tmpPath2.addPath(tmpPath)
			fillPaint.color = 0x9E06080D.toInt()
			canvas.drawPath(tmpPath2, fillPaint)

			overlayStrokePaint.pathEffect = null
			overlayStrokePaint.color = 0xAD000000.toInt()
			overlayStrokePaint.strokeWidth = 2.4f * dp
			canvas.drawPath(tmpPath, overlayStrokePaint)
			overlayStrokePaint.color = EditorColors.PRIMARY
			overlayStrokePaint.strokeWidth = 1.4f * dp
			canvas.drawPath(tmpPath, overlayStrokePaint)

			// Open-center precision reticle
			fun drawGapReticle(col: Int, sw: Float) {
				overlayStrokePaint.color = col
				overlayStrokePaint.strokeWidth = sw
				canvas.drawLine(cx - 10f * dp, cy, cx - 3f * dp, cy, overlayStrokePaint)
				canvas.drawLine(cx + 3f * dp, cy, cx + 10f * dp, cy, overlayStrokePaint)
				canvas.drawLine(cx, cy - 10f * dp, cx, cy - 3f * dp, overlayStrokePaint)
				canvas.drawLine(cx, cy + 3f * dp, cx, cy + 10f * dp, overlayStrokePaint)
			}
			drawGapReticle(0xC7000000.toInt(), 2.2f * dp)
			drawGapReticle(Color.WHITE, 1.0f * dp)
			overlayStrokePaint.color = EditorColors.PRIMARY
			overlayStrokePaint.strokeWidth = 1.1f * dp
			canvas.drawCircle(cx, cy, 2.6f * dp, overlayStrokePaint)
		} else if (activeTool == EditorTool.DRAW || activeTool == EditorTool.ERASER) {
			val zoomFactor = 2.5f
			val sampleSpanImgPx = diameter / max(0.01f, dispScale * zoomFactor)
			val imgToLoupeScale = diameter / sampleSpanImgPx
			val centerImgX = loupePointerNx * fullW
			val centerImgY = loupePointerNy * fullH

			val sImg = canvas.save()
			canvas.translate(cx, cy)
			canvas.scale(imgToLoupeScale, imgToLoupeScale)
			canvas.translate(-centerImgX, -centerImgY)
			canvas.drawRect(0f, 0f, fullW.toFloat(), fullH.toFloat(), checkerPaint)
			workingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
			canvas.restoreToCount(sImg)

			val sf = strokeScaleFactor(fullW, fullH)
			val activeBrushImgPx = (if (activeTool == EditorTool.DRAW) drawSize else eraserSize) * sf
			val loupeBrushRadius = max(3f * dp, activeBrushImgPx * 0.5f * imgToLoupeScale)
			if (activeTool == EditorTool.DRAW || eraserMode != EraserMode.OBJECT) {
				overlayStrokePaint.color = if (activeTool == EditorTool.DRAW) drawColor else 0xD9FFFFFF.toInt()
				overlayStrokePaint.strokeWidth = 1.4f * dp
				overlayStrokePaint.pathEffect = if (activeTool == EditorTool.ERASER) loupeEraseDashEffect else null
				canvas.drawCircle(cx, cy, loupeBrushRadius, overlayStrokePaint)
				overlayStrokePaint.pathEffect = null
			}

			// Center crosshair
			overlayStrokePaint.color = 0xB8000000.toInt()
			overlayStrokePaint.strokeWidth = 2.2f * dp
			canvas.drawLine(cx - 7f * dp, cy, cx + 7f * dp, cy, overlayStrokePaint)
			canvas.drawLine(cx, cy - 7f * dp, cx, cy + 7f * dp, overlayStrokePaint)
			overlayStrokePaint.color = Color.WHITE
			overlayStrokePaint.strokeWidth = 1.0f * dp
			canvas.drawLine(cx - 6.5f * dp, cy, cx + 6.5f * dp, cy, overlayStrokePaint)
			canvas.drawLine(cx, cy - 6.5f * dp, cx, cy + 6.5f * dp, overlayStrokePaint)
		}

		// Inner highlight rim
		overlayStrokePaint.color = 0x47FFFFFF
		overlayStrokePaint.strokeWidth = 1.5f * dp
		canvas.drawCircle(cx, cy, radius - 1f * dp, overlayStrokePaint)

		canvas.restoreToCount(saveOuter)

		// Primary 2dp outer border
		overlayStrokePaint.color = EditorColors.PRIMARY
		overlayStrokePaint.strokeWidth = 2f * dp
		canvas.drawCircle(cx, cy, radius, overlayStrokePaint)
	}

	// =========================================================================
	// POINTER & TOUCH GESTURE INTERACTIONS
	// =========================================================================

	private fun cancelActivePointerGesture() {
		if (!isDraggingCanvas) return
		isDraggingCanvas = false
		dragMode = null
		activeOp = null
		activeLassoPts.clear()
		loupeVisible = false
		rebuildWorkingImage(rebuildCommitted = false)
		invalidate()
	}

	private fun detectCropQuadTarget(px: Float, py: Float): String? {
		val q = doc.cropQuad.map { p ->
			NormPt(imgRect.left + p.x * imgRect.width(), imgRect.top + p.y * imgRect.height())
		}
		for (i in 0 until 4) {
			if (hypot(px - q[i].x, py - q[i].y) <= 24f * dp) return "crop-corner-$i"
		}
		for (i in 0 until 4) {
			val a = q[i]
			val b = q[(i + 1) % 4]
			val mx = (a.x + b.x) * 0.5f
			val my = (a.y + b.y) * 0.5f
			if (hypot(px - mx, py - my) <= 22f * dp) return "crop-edge-$i"
		}
		if (HomographyMath.pointInPoly(px, py, q)) return "crop-move"
		return null
	}

	private fun detectSelectedTextHandle(px: Float, py: Float, layer: PerspectiveTextLayer): String? {
		val badge = modeBadgeScreenRect
		if (badge != null && badge.contains(px, py)) {
			return "text-badge"
		}

		val q = layer.pts.map { p ->
			NormPt(imgRect.left + p.x * imgRect.width(), imgRect.top + p.y * imgRect.height())
		}
		val tol = 22f * dp

		when (textTransformMode) {
			1 -> {
				var bestIdx = -1
				var bestDist = tol
				for (i in 0 until 4) {
					val d = hypot(px - q[i].x, py - q[i].y)
					if (d <= bestDist) {
						bestDist = d
						bestIdx = i
					}
				}
				if (bestIdx != -1) return "text-corner-$bestIdx"
			}
			2 -> {
				val mr = NormPt((q[1].x + q[2].x) * 0.5f, (q[1].y + q[2].y) * 0.5f)
				val dMr = hypot(px - mr.x, py - mr.y)
				val dBr = hypot(px - q[2].x, py - q[2].y)
				if (dMr <= tol && dMr <= dBr) return "text-stretch-skew"
				if (dBr <= tol) return "text-scale-xy"
			}
			3 -> {
				val (_, handle) = getTextRotationHandleScreenPt(q)
				if (hypot(px - handle.x, py - handle.y) <= 24f * dp) return "text-rotate-scale"
			}
		}
		return null
	}

	private fun findTextLayerAtScreenPt(px: Float, py: Float): PerspectiveTextLayer? {
		for (i in doc.texts.indices.reversed()) {
			val t = doc.texts[i]
			val q = t.pts.map { p ->
				NormPt(imgRect.left + p.x * imgRect.width(), imgRect.top + p.y * imgRect.height())
			}
			if (HomographyMath.pointInPoly(px, py, q)) return t
		}
		return null
	}

	private fun buildTextTransformBasis(layer: PerspectiveTextLayer, startPx: Float, startPy: Float): TextTransformBasis {
		val screenPts = layer.pts.map { p ->
			NormPt(imgRect.left + p.x * imgRect.width(), imgRect.top + p.y * imgRect.height())
		}
		val center = HomographyMath.quadCentroid(screenPts)
		val ml = NormPt((screenPts[0].x + screenPts[3].x) * 0.5f, (screenPts[0].y + screenPts[3].y) * 0.5f)
		val mr = NormPt((screenPts[1].x + screenPts[2].x) * 0.5f, (screenPts[1].y + screenPts[2].y) * 0.5f)
		val mt = NormPt((screenPts[0].x + screenPts[1].x) * 0.5f, (screenPts[0].y + screenPts[1].y) * 0.5f)
		val mb = NormPt((screenPts[3].x + screenPts[2].x) * 0.5f, (screenPts[3].y + screenPts[2].y) * 0.5f)

		val u0 = NormPt((mr.x - ml.x) * 0.5f, (mr.y - ml.y) * 0.5f)
		val v0 = NormPt((mb.x - mt.x) * 0.5f, (mb.y - mt.y) * 0.5f)
		val uLen = hypot(u0.x, u0.y).let { if (it < 1e-4f) 1f else it }
		val vLen = hypot(v0.x, v0.y).let { if (it < 1e-4f) 1f else it }
		val axisX = NormPt(u0.x / uLen, u0.y / uLen)
		val axisY = NormPt(v0.x / vLen, v0.y / vLen)

		val vx = startPx - center.x
		val vy = startPy - center.y
		return TextTransformBasis(
			screenPts = screenPts,
			center = center,
			u0 = u0,
			v0 = v0,
			axisX = axisX,
			axisY = axisY,
			startRadius = max(12f * dp, hypot(vx, vy)),
			startAngle = atan2(vy.toDouble(), vx.toDouble()).toFloat(),
		)
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		if (baseBitmap == null || imgRect.isEmpty) return false

		scaleDetector.onTouchEvent(event)
		if (event.pointerCount > 1) {
			isMultiTouchZooming = true
			cancelActivePointerGesture()
			return true
		}
		if (isMultiTouchZooming) {
			if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
				isMultiTouchZooming = false
			}
			return true
		}

		val px = event.x
		val py = event.y
		val nx = (px - imgRect.left) / max(1f, imgRect.width())
		val ny = (py - imgRect.top) / max(1f, imgRect.height())

		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				parent?.requestDisallowInterceptTouchEvent(true)
				onDismissOverlays?.invoke()
				dragMoved = false
				dragStartPx = px
				dragStartPy = py
				dragStartNx = nx
				dragStartNy = ny

				if (activeTool == EditorTool.CROP) {
					val target = detectCropQuadTarget(px, py) ?: return true
					isDraggingCanvas = true
					dragMode = target
					dragStartQuad = ArrayList(doc.cropQuad)
					if (target.startsWith("crop-corner-") || target.startsWith("crop-edge-")) {
						updateLoupeForCrop(target, px, py)
					}
					invalidate()
					return true
				}

				if (activeTool == EditorTool.TEXT) {
					val selLayer = doc.texts.firstOrNull { it.id == selectedTextId }
					if (selLayer != null) {
						val handleHit = detectSelectedTextHandle(px, py, selLayer)
						if (handleHit == "text-badge") {
							cycleTextTransformMode()
							return true
						}
						if (handleHit != null) {
							isDraggingCanvas = true
							dragMode = handleHit
							dragStartQuad = ArrayList(selLayer.pts)
							dragTransformBasis = buildTextTransformBasis(selLayer, px, py)
							invalidate()
							return true
						}
					}

					val hitLayer = findTextLayerAtScreenPt(px, py)
					if (hitLayer != null) {
						wasAlreadySelectedOnDown = (selectedTextId == hitLayer.id)
						selectedTextId = hitLayer.id
						isDraggingCanvas = true
						dragMode = "text-move"
						dragStartQuad = ArrayList(hitLayer.pts)
						onSubOptionsChanged?.invoke()
						invalidate()
						return true
					}

					if (selectedTextId != null) {
						selectedTextId = null
						onSubOptionsChanged?.invoke()
						invalidate()
						return true
					}

					if (nx in 0f..1f && ny in 0f..1f) {
						onRequestAddOrEditText?.invoke(null, NormPt(nx, ny))
					}
					return true
				}

				if (nx < -0.02f || nx > 1.02f || ny < -0.02f || ny > 1.02f) return true
				val cnx = nx.coerceIn(0f, 1f)
				val cny = ny.coerceIn(0f, 1f)

				when (activeTool) {
					EditorTool.DRAW -> {
						isDraggingCanvas = true
						dragMode = "draw"
						activeOp = StrokeOp(
							id = "op_${System.currentTimeMillis()}",
							kind = StrokeOpKind.DRAW,
							color = drawColor,
							size = drawSize.toFloat(),
							pts = arrayListOf(NormPt(cnx, cny)),
						)
						rebuildWorkingImage(rebuildCommitted = false)
						updateLoupeForBrush(px, py, cnx, cny)
						invalidate()
					}
					EditorTool.ERASER -> {
						isDraggingCanvas = true
						if (eraserMode == EraserMode.OBJECT) {
							dragMode = "eraser-object"
							updateLoupeForBrush(px, py, cnx, cny)
							invalidate()
						} else {
							dragMode = "eraser"
							activeOp = StrokeOp(
								id = "op_${System.currentTimeMillis()}",
								kind = if (eraserMode == EraserMode.RESTORE) StrokeOpKind.RESTORE else StrokeOpKind.ERASE_BRUSH,
								size = eraserSize.toFloat(),
								pts = arrayListOf(NormPt(cnx, cny)),
							)
							rebuildWorkingImage(rebuildCommitted = false)
							updateLoupeForBrush(px, py, cnx, cny)
							invalidate()
						}
					}
					EditorTool.LASSO -> {
						isDraggingCanvas = true
						dragMode = "lasso"
						activeLassoPts.clear()
						activeLassoPts.add(NormPt(cnx, cny))
						onSubOptionsChanged?.invoke()
						invalidate()
					}
					else -> Unit
				}
				return true
			}

			MotionEvent.ACTION_MOVE -> {
				if (!isDraggingCanvas) return true
				if (hypot(px - dragStartPx, py - dragStartPy) > 4.5f * dp) {
					dragMoved = true
				}

				val dxNorm = nx - dragStartNx
				val dyNorm = ny - dragStartNy
				val mode = dragMode.orEmpty()

				if (mode.startsWith("crop-")) {
					handleCropPointerMove(mode, dxNorm, dyNorm, px, py)
					return true
				}

				if (mode.startsWith("text-")) {
					handleTextPointerMove(mode, dxNorm, dyNorm, px, py)
					return true
				}

				val cnx = nx.coerceIn(0f, 1f)
				val cny = ny.coerceIn(0f, 1f)

				when (mode) {
					"eraser-object" -> {
						updateLoupeForBrush(px, py, cnx, cny)
						invalidate()
					}
					"draw", "eraser" -> {
						activeOp?.pts?.add(NormPt(cnx, cny))
						rebuildWorkingImage(rebuildCommitted = false)
						updateLoupeForBrush(px, py, cnx, cny)
						invalidate()
					}
					"lasso" -> {
						activeLassoPts.add(NormPt(cnx, cny))
						invalidate()
					}
				}
				return true
			}

			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				parent?.requestDisallowInterceptTouchEvent(false)
				finishCanvasDrag(px, py, nx, ny, event.actionMasked == MotionEvent.ACTION_CANCEL)
				return true
			}
		}
		return super.onTouchEvent(event)
	}

	private fun updateLoupeForCrop(target: String, px: Float, py: Float) {
		val focal = if (target.startsWith("crop-corner-")) {
			val idx = target.substringAfterLast('-').toIntOrNull() ?: 0
			doc.cropQuad[idx]
		} else {
			val i1 = target.substringAfterLast('-').toIntOrNull() ?: 0
			val i2 = (i1 + 1) % 4
			NormPt(
				(doc.cropQuad[i1].x + doc.cropQuad[i2].x) * 0.5f,
				(doc.cropQuad[i1].y + doc.cropQuad[i2].y) * 0.5f,
			)
		}
		val fsx = imgRect.left + focal.x * imgRect.width()
		val fsy = imgRect.top + focal.y * imgRect.height()
		syncLoupeCornerPosition(fsx, fsy, px, py)
		loupeVisible = true
	}

	private fun updateLoupeForBrush(px: Float, py: Float, cnx: Float, cny: Float) {
		loupePointerPx = px
		loupePointerPy = py
		loupePointerNx = cnx
		loupePointerNy = cny
		syncLoupeCornerPosition(px, py)
		loupeVisible = true
	}

	private fun getLockedCropNormRatio(): Float? {
		val targetAspect = when (doc.cropAspect) {
			"1:1" -> 1f
			"9:16" -> 9f / 16f
			"16:9" -> 16f / 9f
			"4:3" -> 4f / 3f
			else -> return null
		}
		val (iw, ih) = getOrientedDimensions()
		if (iw <= 0 || ih <= 0) return null
		return targetAspect * (ih.toFloat() / iw.toFloat())
	}

	private fun handleCropPointerMove(mode: String, dxNorm: Float, dyNorm: Float, px: Float, py: Float) {
		val sq = dragStartQuad ?: return
		val lockedNormRatio = getLockedCropNormRatio()

		if (mode.startsWith("crop-corner-")) {
			val idx = mode.substringAfterLast('-').toIntOrNull() ?: return
			if (lockedNormRatio != null) {
				val oppIdx = (idx + 2) % 4
				val ax = sq[oppIdx].x
				val ay = sq[oppIdx].y
				val sx = if (idx == 1 || idx == 2) 1f else -1f
				val sy = if (idx == 2 || idx == 3) 1f else -1f
				val rawW = max(0.04f, sx * ((sq[idx].x + dxNorm) - ax))
				val rawH = max(0.04f, sy * ((sq[idx].y + dyNorm) - ay))
				val candH = (rawH + rawW / lockedNormRatio) * 0.5f
				val availW = if (sx > 0f) (1f - ax) else ax
				val availH = if (sy > 0f) (1f - ay) else ay
				val maxH = min(availH, availW / lockedNormRatio)
				val minH = min(maxH, 0.08f)
				val finalH = candH.coerceIn(minH, maxH)
				val finalW = finalH * lockedNormRatio
				val x0 = min(ax, ax + sx * finalW)
				val x1 = max(ax, ax + sx * finalW)
				val y0 = min(ay, ay + sy * finalH)
				val y1 = max(ay, ay + sy * finalH)
				doc.cropQuad = arrayListOf(NormPt(x0, y0), NormPt(x1, y0), NormPt(x1, y1), NormPt(x0, y1))
			} else {
				doc.cropQuad[idx] = NormPt(
					(sq[idx].x + dxNorm).coerceIn(0f, 1f),
					(sq[idx].y + dyNorm).coerceIn(0f, 1f),
				)
			}
		} else if (mode.startsWith("crop-edge-")) {
			val i1 = mode.substringAfterLast('-').toIntOrNull() ?: return
			val i2 = (i1 + 1) % 4
			if (lockedNormRatio != null) {
				if (i1 == 0 || i1 == 2) {
					val cx = (sq[0].x + sq[1].x) * 0.5f
					val ay = if (i1 == 0) sq[3].y else sq[0].y
					val sy = if (i1 == 0) -1f else 1f
					val movingY = (if (i1 == 0) sq[0].y else sq[2].y) + dyNorm
					val rawH = max(0.04f, sy * (movingY - ay))
					val availW = 2f * min(cx, 1f - cx)
					val availH = if (sy > 0f) (1f - ay) else ay
					val maxH = min(availH, availW / lockedNormRatio)
					val minH = min(maxH, 0.08f)
					val finalH = rawH.coerceIn(minH, maxH)
					val finalW = finalH * lockedNormRatio
					val y0 = min(ay, ay + sy * finalH)
					val y1 = max(ay, ay + sy * finalH)
					doc.cropQuad = arrayListOf(
						NormPt(cx - finalW * 0.5f, y0),
						NormPt(cx + finalW * 0.5f, y0),
						NormPt(cx + finalW * 0.5f, y1),
						NormPt(cx - finalW * 0.5f, y1),
					)
				} else {
					val cy = (sq[0].y + sq[3].y) * 0.5f
					val ax = if (i1 == 1) sq[0].x else sq[1].x
					val sx = if (i1 == 1) 1f else -1f
					val movingX = (if (i1 == 1) sq[1].x else sq[0].x) + dxNorm
					val rawW = max(0.04f, sx * (movingX - ax))
					val availH = 2f * min(cy, 1f - cy)
					val availW = if (sx > 0f) (1f - ax) else ax
					val maxW = min(availW, availH * lockedNormRatio)
					val minW = min(maxW, 0.08f)
					val finalW = rawW.coerceIn(minW, maxW)
					val finalH = finalW / lockedNormRatio
					val x0 = min(ax, ax + sx * finalW)
					val x1 = max(ax, ax + sx * finalW)
					doc.cropQuad = arrayListOf(
						NormPt(x0, cy - finalH * 0.5f),
						NormPt(x1, cy - finalH * 0.5f),
						NormPt(x1, cy + finalH * 0.5f),
						NormPt(x0, cy + finalH * 0.5f),
					)
				}
			} else {
				val minDx = max(-sq[i1].x, -sq[i2].x)
				val maxDx = min(1f - sq[i1].x, 1f - sq[i2].x)
				val minDy = max(-sq[i1].y, -sq[i2].y)
				val maxDy = min(1f - sq[i1].y, 1f - sq[i2].y)
				val cdx = dxNorm.coerceIn(minDx, maxDx)
				val cdy = dyNorm.coerceIn(minDy, maxDy)
				doc.cropQuad[i1] = NormPt(sq[i1].x + cdx, sq[i1].y + cdy)
				doc.cropQuad[i2] = NormPt(sq[i2].x + cdx, sq[i2].y + cdy)
			}
		} else if (mode == "crop-move") {
			val minDx = sq.maxOf { -it.x }
			val maxDx = sq.minOf { 1f - it.x }
			val minDy = sq.maxOf { -it.y }
			val maxDy = sq.minOf { 1f - it.y }
			val cdx = dxNorm.coerceIn(minDx, maxDx)
			val cdy = dyNorm.coerceIn(minDy, maxDy)
			doc.cropQuad = sq.mapTo(ArrayList(4)) { NormPt(it.x + cdx, it.y + cdy) }
		}

		if (mode.startsWith("crop-corner-") || mode.startsWith("crop-edge-")) {
			updateLoupeForCrop(mode, px, py)
		}
		invalidate()
	}

	private fun handleTextPointerMove(mode: String, dxNorm: Float, dyNorm: Float, px: Float, py: Float) {
		val layer = doc.texts.firstOrNull { it.id == selectedTextId } ?: return
		val sq = dragStartQuad ?: return

		when {
			mode == "text-move" -> {
				val minDx = sq.maxOf { -0.15f - it.x }
				val maxDx = sq.minOf { 1.15f - it.x }
				val minDy = sq.maxOf { -0.15f - it.y }
				val maxDy = sq.minOf { 1.15f - it.y }
				val cdx = dxNorm.coerceIn(minDx, maxDx)
				val cdy = dyNorm.coerceIn(minDy, maxDy)
				layer.pts = sq.map { NormPt(it.x + cdx, it.y + cdy) }
			}
			mode.startsWith("text-corner-") -> {
				val idx = mode.substringAfterLast('-').toIntOrNull() ?: return
				val updated = ArrayList(layer.pts)
				updated[idx] = NormPt(
					(sq[idx].x + dxNorm).coerceIn(-0.08f, 1.08f),
					(sq[idx].y + dyNorm).coerceIn(-0.08f, 1.08f),
				)
				layer.pts = updated
			}
			mode == "text-stretch-skew" -> {
				val b = dragTransformBasis ?: return
				val dPx = px - dragStartPx
				val dPy = py - dragStartPy
				val uNewX = b.u0.x + dPx
				val uNewY = b.u0.y + dPy
				if (hypot(uNewX, uNewY) >= 16f * dp) {
					val signs = arrayOf(
						floatArrayOf(-1f, -1f),
						floatArrayOf(1f, -1f),
						floatArrayOf(1f, 1f),
						floatArrayOf(-1f, 1f),
					)
					layer.pts = signs.map { s ->
						val sx = b.center.x + s[0] * uNewX + s[1] * b.v0.x
						val sy = b.center.y + s[0] * uNewY + s[1] * b.v0.y
						NormPt(
							(sx - imgRect.left) / max(1f, imgRect.width()),
							(sy - imgRect.top) / max(1f, imgRect.height()),
						)
					}
				}
			}
			mode == "text-scale-xy" -> {
				val b = dragTransformBasis ?: return
				val br0x = b.screenPts[2].x - b.center.x
				val br0y = b.screenPts[2].y - b.center.y
				val curX = px - b.center.x
				val curY = py - b.center.y
				val det = (b.axisX.x * b.axisY.y - b.axisX.y * b.axisY.x).let { if (abs(it) < 1e-4f) 1f else it }
				fun projU(vx: Float, vy: Float) = (vx * b.axisY.y - vy * b.axisY.x) / det
				fun projV(vx: Float, vy: Float) = (b.axisX.x * vy - b.axisX.y * vx) / det
				val p0u = projU(br0x, br0y).let { if (abs(it) < 1e-4f) 1f else it }
				val p0v = projV(br0x, br0y).let { if (abs(it) < 1e-4f) 1f else it }
				val sx = (projU(curX, curY) / p0u).coerceIn(0.18f, 6f)
				val sy = (projV(curX, curY) / p0v).coerceIn(0.18f, 6f)
				layer.pts = b.screenPts.map { sp ->
					val ru = projU(sp.x - b.center.x, sp.y - b.center.y)
					val rv = projV(sp.x - b.center.x, sp.y - b.center.y)
					val nxPx = b.center.x + ru * sx * b.axisX.x + rv * sy * b.axisY.x
					val nyPx = b.center.y + ru * sx * b.axisX.y + rv * sy * b.axisY.y
					NormPt(
						(nxPx - imgRect.left) / max(1f, imgRect.width()),
						(nyPx - imgRect.top) / max(1f, imgRect.height()),
					)
				}
			}
			mode == "text-rotate-scale" -> {
				val b = dragTransformBasis ?: return
				val vx = px - b.center.x
				val vy = py - b.center.y
				val curRadius = hypot(vx, vy)
				val scale = (curRadius / b.startRadius).coerceIn(0.16f, 6f)
				val dAngle = atan2(vy.toDouble(), vx.toDouble()).toFloat() - b.startAngle
				val cosA = cos(dAngle.toDouble()).toFloat()
				val sinA = sin(dAngle.toDouble()).toFloat()
				layer.pts = b.screenPts.map { sp ->
					val rx = (sp.x - b.center.x) * scale
					val ry = (sp.y - b.center.y) * scale
					val nxPx = b.center.x + rx * cosA - ry * sinA
					val nyPx = b.center.y + rx * sinA + ry * cosA
					NormPt(
						(nxPx - imgRect.left) / max(1f, imgRect.width()),
						(nyPx - imgRect.top) / max(1f, imgRect.height()),
					)
				}
			}
		}
		invalidate()
	}

	private fun finishCanvasDrag(px: Float, py: Float, nx: Float, ny: Float, cancelled: Boolean) {
		if (!isDraggingCanvas) return
		val mode = dragMode.orEmpty()
		val moved = dragMoved
		isDraggingCanvas = false
		dragMode = null
		loupeVisible = false

		if (cancelled) {
			activeOp = null
			activeLassoPts.clear()
			rebuildWorkingImage(rebuildCommitted = false)
			invalidate()
			return
		}

		if (mode == "text-move" && !moved && wasAlreadySelectedOnDown && selectedTextId != null) {
			val sel = doc.texts.firstOrNull { it.id == selectedTextId }
			if (sel != null) {
				openContextMenuForLayer(sel)
			}
			invalidate()
			return
		}

		if (mode == "eraser-object") {
			performObjectEraseAt(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
			invalidate()
			return
		}

		if (mode == "draw" || mode == "eraser") {
			activeOp?.let { op ->
				doc.ops.add(op)
				activeOp = null
				pushHistory()
			}
			return
		}

		if (mode.startsWith("crop-") || (mode.startsWith("text-") && moved)) {
			pushHistory()
		} else if (mode == "lasso") {
			if (activeLassoPts.size < 5) {
				activeLassoPts.clear()
			} else {
				onSnackbarMessage?.invoke("Region selected — choose Spotlight, Highlight, or Redact below")
			}
			onSubOptionsChanged?.invoke()
			invalidate()
		}
	}

	private fun openContextMenuForLayer(layer: PerspectiveTextLayer) {
		val q = layer.pts.map { p ->
			NormPt(imgRect.left + p.x * imgRect.width(), imgRect.top + p.y * imgRect.height())
		}
		val minY = q.minOf { it.y }
		val maxY = q.maxOf { it.y }
		val center = HomographyMath.quadCentroid(q)
		val menuW = 208f * dp
		val menuH = 212f * dp
		val left = (center.x - menuW * 0.5f).coerceIn(10f * dp, max(10f * dp, width - menuW - 10f * dp))
		val top = if (maxY + menuH + 14f * dp < height) {
			maxY + 12f * dp
		} else {
			max(12f * dp, minY - menuH - 12f * dp)
		}
		onRequestTextContextMenu?.invoke(layer, left, top)
	}

	// =========================================================================
	// PUBLIC TOOL & CONTEXTUAL ACTIONS
	// =========================================================================

	fun rotate90Clockwise() {
		fun rotPt(p: NormPt) = NormPt(1f - p.y, p.x)
		doc.rotationSteps = (doc.rotationSteps + 1) % 4
		doc.cropAspect = when (doc.cropAspect) {
			"16:9" -> "9:16"
			"9:16" -> "16:9"
			"4:3" -> "free"
			else -> doc.cropAspect
		}
		val rq = doc.cropQuad.map(::rotPt)
		doc.cropQuad = arrayListOf(rq[3], rq[0], rq[1], rq[2])

		for (i in doc.ops.indices) {
			val op = doc.ops[i]
			val newPts = op.pts.mapTo(ArrayList(op.pts.size), ::rotPt)
			val newRect = op.rect?.let { r ->
				NormRect(
					x = 1f - (r.y + r.h),
					y = r.x,
					w = r.h,
					h = r.w,
					r = r.r,
				)
			}
			doc.ops[i] = op.copy(pts = newPts, rect = newRect)
		}
		for (i in doc.regions.indices) {
			val reg = doc.regions[i]
			doc.regions[i] = reg.copy(pts = reg.pts.map(::rotPt))
		}
		for (t in doc.texts) {
			t.pts = t.pts.map(::rotPt)
		}

		userZoom = 1f
		userPanX = 0f
		userPanY = 0f
		computeViewportMetrics()
		pushHistory()
		onSubOptionsChanged?.invoke()
		onSnackbarMessage?.invoke("Rotated 90° clockwise")
	}

	fun applyCropQuadPreset(preset: String) {
		when (preset) {
			"reset" -> {
				doc.cropAspect = "free"
				doc.cropQuad = EditorDocumentState.createDefaultCropQuad()
			}
			"free" -> {
				if (doc.cropAspect == "free") {
					doc.cropQuad = EditorDocumentState.createDefaultCropQuad()
				} else {
					doc.cropAspect = "free"
					onSnackbarMessage?.invoke("Free 4-point perspective crop unlocked")
				}
			}
			"1:1", "9:16", "16:9", "4:3" -> {
				doc.cropAspect = preset
				val ratio = getLockedCropNormRatio() ?: 1f
				val maxH = min(1f, 1f / ratio)
				val maxW = maxH * ratio
				val hw = (maxW * 0.90f) * 0.5f
				val hh = (maxH * 0.90f) * 0.5f
				doc.cropQuad = arrayListOf(
					NormPt(0.5f - hw, 0.5f - hh),
					NormPt(0.5f + hw, 0.5f - hh),
					NormPt(0.5f + hw, 0.5f + hh),
					NormPt(0.5f - hw, 0.5f + hh),
				)
				onSnackbarMessage?.invoke("Aspect ratio locked to $preset")
			}
		}
		pushHistory()
		onSubOptionsChanged?.invoke()
	}

	fun toggleCropExecMode() {
		doc.cropMode = if (doc.cropMode == CropExecMode.CUTOUT) CropExecMode.UNWARP else CropExecMode.CUTOUT
		pushHistory()
		onSubOptionsChanged?.invoke()
		onSnackbarMessage?.invoke(
			if (doc.cropMode == CropExecMode.CUTOUT) "Crop mode: Exact Shape Cutout"
			else "Crop mode: Perspective Unwarp",
		)
	}

	fun setCropExecMode(mode: CropExecMode) {
		if (doc.cropMode == mode) return
		doc.cropMode = mode
		onSubOptionsChanged?.invoke()
		invalidate()
	}

	fun setStraightenDegrees(deg: Int, commitHistory: Boolean) {
		doc.straighten = deg
		if (commitHistory) {
			pushHistory()
		} else {
			invalidate()
		}
	}

	fun applyLassoEffect(mode: LassoEffectMode) {
		if (activeLassoPts.size < 5) return
		doc.regions.add(
			LassoRegion(
				id = "reg_${System.currentTimeMillis()}",
				mode = mode,
				feather = lassoFeather.toFloat(),
				pts = ArrayList(activeLassoPts),
			),
		)
		activeLassoPts.clear()
		pushHistory()
		onSubOptionsChanged?.invoke()
		val msg = when (mode) {
			LassoEffectMode.SPOTLIGHT -> "Applied Spotlight"
			LassoEffectMode.TINT -> "Applied Highlight"
			LassoEffectMode.REDACT -> "Redacted region"
		}
		onSnackbarMessage?.invoke(msg)
	}

	fun clearLassoSelection() {
		activeLassoPts.clear()
		onSubOptionsChanged?.invoke()
		invalidate()
	}

	fun clearAllRegions() {
		if (doc.regions.isEmpty()) return
		doc.regions.clear()
		pushHistory()
		onSubOptionsChanged?.invoke()
	}

	fun cycleTextTransformMode() {
		textTransformMode = (textTransformMode % 3) + 1
		onSubOptionsChanged?.invoke()
		val label = when (textTransformMode) {
			1 -> "Mode 1: Perspective"
			2 -> "Mode 2: Stretch & Skew"
			else -> "Mode 3: Rotate & Scale"
		}
		onSnackbarMessage?.invoke(label)
		invalidate()
	}

	fun buildUprightTextQuad(cx: Float, cy: Float, text: String, color: Int, fontId: String): List<NormPt> {
		val entry = getOrCreateTextCache(text, color, fontId)
		val (iw, ih) = getOrientedDimensions()
		var boxW = iw * 0.58f
		var boxH = boxW / entry.aspect
		if (boxH > ih * 0.15f) {
			boxH = ih * 0.15f
			boxW = boxH * entry.aspect
		}
		val hw = (boxW / max(1, iw)) * 0.5f
		val hh = (boxH / max(1, ih)) * 0.5f
		val clampedCx = cx.coerceIn(min(0.45f, hw + 0.03f), max(0.55f, 1f - hw - 0.03f))
		val clampedCy = cy.coerceIn(min(0.45f, hh + 0.03f), max(0.55f, 1f - hh - 0.03f))
		return arrayListOf(
			NormPt(clampedCx - hw, clampedCy - hh),
			NormPt(clampedCx + hw, clampedCy - hh),
			NormPt(clampedCx + hw, clampedCy + hh),
			NormPt(clampedCx - hw, clampedCy + hh),
		)
	}

	fun addOrUpdateTextLayer(
		editingId: String?,
		text: String,
		color: Int,
		fontId: String,
		tapPos: NormPt?,
	) {
		val cleanVal = text.trim()
		if (cleanVal.isEmpty()) {
			if (editingId != null) {
				removeTextLayerById(editingId)
			}
			return
		}

		val newEntry = getOrCreateTextCache(cleanVal, color, fontId)
		if (editingId != null) {
			val item = doc.texts.firstOrNull { it.id == editingId }
			if (item != null) {
				val oldEntry = getOrCreateTextCache(item.text, item.color, item.fontId)
				item.text = cleanVal
				item.color = color
				item.fontId = fontId
				if (abs(newEntry.aspect - oldEntry.aspect) > 0.12f && HomographyMath.isQuadConvex(item.pts)) {
					val ratio = (newEntry.aspect / oldEntry.aspect).coerceIn(0.45f, 2.2f)
					val pts = ArrayList(item.pts)
					val uTopX = (pts[1].x - pts[0].x) * 0.5f * (ratio - 1f)
					val uTopY = (pts[1].y - pts[0].y) * 0.5f * (ratio - 1f)
					val uBotX = (pts[2].x - pts[3].x) * 0.5f * (ratio - 1f)
					val uBotY = (pts[2].y - pts[3].y) * 0.5f * (ratio - 1f)
					pts[0] = NormPt(pts[0].x - uTopX, pts[0].y - uTopY)
					pts[1] = NormPt(pts[1].x + uTopX, pts[1].y + uTopY)
					pts[2] = NormPt(pts[2].x + uBotX, pts[2].y + uBotY)
					pts[3] = NormPt(pts[3].x - uBotX, pts[3].y - uBotY)
					item.pts = pts
				}
			}
		} else {
			val pos = tapPos ?: HomographyMath.quadCentroid(doc.cropQuad)
			val id = "txt_${System.currentTimeMillis()}"
			doc.texts.add(
				PerspectiveTextLayer(
					id = id,
					text = cleanVal,
					color = color,
					fontId = fontId,
					pts = buildUprightTextQuad(pos.x, pos.y, cleanVal, color, fontId),
				),
			)
			selectedTextId = id
		}
		pushHistory()
		onSubOptionsChanged?.invoke()
	}

	fun beginTextScaleSlider() {
		val sel = doc.texts.firstOrNull { it.id == selectedTextId }
		textSliderBaseQuad = sel?.pts?.let { ArrayList(it) }
	}

	fun updateTextScaleSlider(pct: Int) {
		val sel = doc.texts.firstOrNull { it.id == selectedTextId } ?: return
		val base = textSliderBaseQuad ?: return
		val factor = pct / 100f
		val c = HomographyMath.quadCentroid(base)
		sel.pts = base.map { p ->
			NormPt(c.x + (p.x - c.x) * factor, c.y + (p.y - c.y) * factor)
		}
		invalidate()
	}

	fun endTextScaleSlider() {
		if (textSliderBaseQuad != null && selectedTextId != null) {
			textSliderBaseQuad = null
			pushHistory()
		}
	}

	fun resetSelectedTextQuad() {
		val sel = doc.texts.firstOrNull { it.id == selectedTextId } ?: return
		val c = HomographyMath.quadCentroid(sel.pts)
		sel.pts = buildUprightTextQuad(c.x, c.y, sel.text, sel.color, sel.fontId)
		pushHistory()
		onSnackbarMessage?.invoke("Reset text quadrilateral")
	}

	fun duplicateSelectedText() {
		val sel = doc.texts.firstOrNull { it.id == selectedTextId } ?: return
		val newId = "txt_${System.currentTimeMillis()}"
		val dup = PerspectiveTextLayer(
			id = newId,
			text = sel.text,
			color = sel.color,
			fontId = sel.fontId,
			pts = sel.pts.map { p -> NormPt(min(0.98f, p.x + 0.045f), min(0.98f, p.y + 0.045f)) },
		)
		doc.texts.add(dup)
		selectedTextId = newId
		pushHistory()
		onSubOptionsChanged?.invoke()
		onSnackbarMessage?.invoke("Duplicated perspective text layer")
	}

	fun removeTextLayerById(id: String) {
		val removed = doc.texts.removeAll { it.id == id }
		if (selectedTextId == id) selectedTextId = null
		if (removed) {
			pushHistory()
			onSubOptionsChanged?.invoke()
			onSnackbarMessage?.invoke("Removed text layer")
		}
	}

	fun removeSelectedText() {
		val id = selectedTextId ?: return
		removeTextLayerById(id)
	}

	fun clearAllTextLayers() {
		if (doc.texts.isEmpty()) return
		doc.texts.clear()
		selectedTextId = null
		pushHistory()
		onSubOptionsChanged?.invoke()
		onSnackbarMessage?.invoke("Removed all text layers")
	}

	// =========================================================================
	// NON-DESTRUCTIVE HIGH-RES BITMAP EXPORT PIPELINE
	// =========================================================================

	fun isAngledQuadActive(): Boolean =
		!HomographyMath.isDefaultFullCropQuad(doc.cropQuad) &&
			!HomographyMath.isAxisAlignedRectQuad(doc.cropQuad)

	private data class CropOutDims(
		val minX: Int,
		val minY: Int,
		val outW: Int,
		val outH: Int,
		val srcQuad: List<NormPt>,
	)

	private fun getCropOutputDimensionsPx(fullW: Int, fullH: Int): CropOutDims {
		val q = doc.cropQuad.map { NormPt(it.x * fullW, it.y * fullH) }
		if (doc.cropMode == CropExecMode.CUTOUT || HomographyMath.isAxisAlignedRectQuad(doc.cropQuad)) {
			val rawMinX = max(0, floor(q.minOf { it.x }.toDouble()).toInt())
			val rawMaxX = min(fullW, ceil(q.maxOf { it.x }.toDouble()).toInt())
			val rawMinY = max(0, floor(q.minOf { it.y }.toDouble()).toInt())
			val rawMaxY = min(fullH, ceil(q.maxOf { it.y }.toDouble()).toInt())
			return CropOutDims(
				minX = rawMinX,
				minY = rawMinY,
				outW = max(16, rawMaxX - rawMinX),
				outH = max(16, rawMaxY - rawMinY),
				srcQuad = q,
			)
		}
		val topLen = HomographyMath.distPts(q[0], q[1])
		val botLen = HomographyMath.distPts(q[3], q[2])
		val leftLen = HomographyMath.distPts(q[0], q[3])
		val rightLen = HomographyMath.distPts(q[1], q[2])
		return CropOutDims(
			minX = 0,
			minY = 0,
			outW = max(16, ((topLen + botLen) * 0.5f).roundToInt()),
			outH = max(16, ((leftLen + rightLen) * 0.5f).roundToInt()),
			srcQuad = q,
		)
	}

	fun renderExportBitmap(isPng: Boolean): Bitmap {
		rebuildWorkingImage(rebuildCommitted = true)
		val (fullW, fullH) = getOrientedDimensions()
		val fullOffscreen = Bitmap.createBitmap(max(1, fullW), max(1, fullH), Bitmap.Config.ARGB_8888)
		val fc = Canvas(fullOffscreen)
		if (!isPng) {
			fc.drawColor(0xFF07080A.toInt())
		}
		drawBaseAndInkContent(fc, fullW.toFloat(), fullH.toFloat())

		if (HomographyMath.isDefaultFullCropQuad(doc.cropQuad)) {
			for (layer in doc.texts) {
				val entry = getOrCreateTextCache(layer.text, layer.color, layer.fontId)
				val dstPts = layer.pts.map { NormPt(it.x * fullW, it.y * fullH) }
				drawBitmapPolyToPoly(fc, entry, dstPts)
			}
			return fullOffscreen
		}

		val dims = getCropOutputDimensionsPx(fullW, fullH)
		val useExactCutout = doc.cropMode == CropExecMode.CUTOUT || HomographyMath.isAxisAlignedRectQuad(doc.cropQuad)

		if (useExactCutout) {
			val outBmp = Bitmap.createBitmap(dims.outW, dims.outH, Bitmap.Config.ARGB_8888)
			val oc = Canvas(outBmp)
			if (!isPng) {
				oc.drawColor(0xFF07090D.toInt())
			}

			val clip = Path().apply {
				moveTo(dims.srcQuad[0].x - dims.minX, dims.srcQuad[0].y - dims.minY)
				lineTo(dims.srcQuad[1].x - dims.minX, dims.srcQuad[1].y - dims.minY)
				lineTo(dims.srcQuad[2].x - dims.minX, dims.srcQuad[2].y - dims.minY)
				lineTo(dims.srcQuad[3].x - dims.minX, dims.srcQuad[3].y - dims.minY)
				close()
			}
			oc.save()
			oc.clipPath(clip)
			oc.drawBitmap(fullOffscreen, -dims.minX.toFloat(), -dims.minY.toFloat(), bitmapPaint)
			for (layer in doc.texts) {
				val entry = getOrCreateTextCache(layer.text, layer.color, layer.fontId)
				val dstPts = layer.pts.map {
					NormPt(it.x * fullW - dims.minX, it.y * fullH - dims.minY)
				}
				drawBitmapPolyToPoly(oc, entry, dstPts)
			}
			oc.restore()
			fullOffscreen.recycle()
			return outBmp
		}

		// Perspective Unwarp (Homography from srcQuad -> [0,0 .. outW,outH])
		val outBmp = unwarpBitmapPolyToPoly(fullOffscreen, dims.srcQuad, dims.outW, dims.outH, isPng)
		val oc = Canvas(outBmp)
		val dstRect = listOf(
			NormPt(0f, 0f),
			NormPt(dims.outW.toFloat(), 0f),
			NormPt(dims.outW.toFloat(), dims.outH.toFloat()),
			NormPt(0f, dims.outH.toFloat()),
		)
		val hSrcToOut = if (HomographyMath.isQuadConvex(dims.srcQuad)) {
			HomographyMath.computeHomography(dims.srcQuad, dstRect)
		} else {
			null
		}
		for (layer in doc.texts) {
			val entry = getOrCreateTextCache(layer.text, layer.color, layer.fontId)
			val dstPts = layer.pts.map { p ->
				val sx = p.x * fullW
				val sy = p.y * fullH
				if (hSrcToOut != null) HomographyMath.applyHomographyPt(hSrcToOut, sx, sy)
				else NormPt(p.x * dims.outW, p.y * dims.outH)
			}
			drawBitmapPolyToPoly(oc, entry, dstPts)
		}
		fullOffscreen.recycle()
		return outBmp
	}

	private fun unwarpBitmapPolyToPoly(
		srcBmp: Bitmap,
		srcQuad: List<NormPt>,
		outW: Int,
		outH: Int,
		isPng: Boolean,
	): Bitmap {
		val outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
		val oc = Canvas(outBmp)
		if (!isPng) {
			oc.drawColor(0xFF07090D.toInt())
		}

		if (HomographyMath.isQuadConvex(srcQuad)) {
			for (i in 0 until 4) {
				srcPolyPts[i * 2] = srcQuad[i].x
				srcPolyPts[i * 2 + 1] = srcQuad[i].y
			}
			dstPolyPts[0] = 0f; dstPolyPts[1] = 0f
			dstPolyPts[2] = outW.toFloat(); dstPolyPts[3] = 0f
			dstPolyPts[4] = outW.toFloat(); dstPolyPts[5] = outH.toFloat()
			dstPolyPts[6] = 0f; dstPolyPts[7] = outH.toFloat()

			val m = Matrix()
			if (m.setPolyToPoly(srcPolyPts, 0, dstPolyPts, 0, 4)) {
				oc.save()
				oc.clipRect(0f, 0f, outW.toFloat(), outH.toFloat())
				oc.concat(m)
				oc.drawBitmap(srcBmp, 0f, 0f, bitmapPaint)
				oc.restore()
				return outBmp
			}
		}

		// Bilinear fallback for concave quads
		val sw = srcBmp.width
		val sh = srcBmp.height
		val srcPixels = IntArray(sw * sh)
		srcBmp.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)
		val outPixels = IntArray(outW * outH)
		for (y in 0 until outH) {
			val vNorm = if (outH > 1) y.toFloat() / (outH - 1).toFloat() else 0f
			val rowOff = y * outW
			for (x in 0 until outW) {
				val uNorm = if (outW > 1) x.toFloat() / (outW - 1).toFloat() else 0f
				val pt = HomographyMath.evalQuadUV(srcQuad, null, uNorm, vNorm)
				val sx = pt.x.coerceIn(0f, sw - 1.001f)
				val sy = pt.y.coerceIn(0f, sh - 1.001f)
				outPixels[rowOff + x] = srcPixels[sy.toInt() * sw + sx.toInt()]
			}
		}
		outBmp.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
		return outBmp
	}
}
