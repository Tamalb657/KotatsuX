package org.koitharu.kotatsu.reader.ui.crop

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
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
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An image editor view for the crop & save feature: shows a page, lets the user
 * drag a crop rectangle and paint opaque strokes over it (all coordinates are kept
 * in image space, so the export does not depend on the view size or zoom).
 */
class CropDrawView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {

	enum class Mode {
		CROP, DRAW, ERASE, LASSO, TEXT, PICK_COLOR,
	}

	class Stroke(
		val color: Int,
		val width: Float,
		val points: MutableList<Point>,
		val isEraser: Boolean = false,
	)

	class Point(val x: Float, val y: Float)

	var bitmap: Bitmap? = null
		private set

	var mode = Mode.CROP
		set(value) {
			field = value
			invalidate()
		}

	/** Colour used for new strokes. */
	var strokeColor = Color.BLACK

	/** Stroke width in image pixels. */
	var strokeWidth = 12f

	var onColorPicked: ((Int) -> Unit)? = null

	/** Notifies that the lasso region appeared, changed or was cleared. */
	var onCropShapeChanged: (() -> Unit)? = null

	/** The closed free-form region; when set it replaces the rectangular crop for the export. */
	var lassoRegion: Path? = null
		private set

	val lassoPoints = ArrayList<Point>()

	val hasLasso: Boolean
		get() = lassoRegion != null

	// text tool: captions in image space, one may be selected and transformed
	val textObjects = ArrayList<TextObject>()
	var selectedText: TextObject? = null

	/** Called when an empty spot is tapped or a caption is double-tapped; arg is the object to edit or null. */
	var onTextRequested: ((TextObject?) -> Unit)? = null

	private var textDragMode = TextDragMode.NONE
	private var textDragLast = PointF()
	private var textRotateLast = 0f

	private enum class TextDragMode { NONE, MOVE, ROTATE }

	/** Notifies that undo/redo availability changed, so buttons can enable or disable. */
	var onHistoryChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null

	val cropRect = RectF()
	val strokes = ArrayList<Stroke>()
	private val redoStack = ArrayList<Stroke>()

	// the fit transform and the user's pinch-zoom/pan, composed into viewMatrix
	private val baseMatrix = Matrix()
	private val userMatrix = Matrix()
	private val viewMatrix = Matrix()
	private val inverseMatrix = Matrix()
	private var bitmapRect = RectF()
	private var fitScale = 1f
	private var multiGesture = false
	private var prevCx = 0f
	private var prevCy = 0f

	private val dimPaint = Paint().apply {
		color = 0x99000000.toInt()
	}
	private val rectOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		color = Color.BLACK
		strokeWidth = 5f
	}
	private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		color = Color.WHITE
		strokeWidth = 2.5f
		pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
	}
	private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = Color.WHITE
		setShadowLayer(10f, 0f, 0f, Color.BLACK)
	}
	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
	}
	private val samplePaint = Paint()

	private var activeStroke: Stroke? = null
	private var dragHandle: Handle? = null
	private var dragStart = Point(0f, 0f)
	private var dragRectStart = RectF()

	// drawing layer: strokes are rendered here once and blitted in onDraw;
	// eraser strokes punch holes in it with Porter-Duff CLEAR
	private var overlay: Bitmap? = null
	private var overlayCanvas: Canvas? = null
	private var overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
	}

	// colour-picker loupe state
	private var loupePoint: PointF? = null
	private var sampledColor = Color.WHITE
	private val loupeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		color = Color.WHITE
		strokeWidth = 4f
	}
	private val loupeRingOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = 0x66000000
	}
	private val loupeDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
	}
	private val loupeCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		color = Color.WHITE
		strokeWidth = 2f
	}

	private val dashAnimator = ValueAnimator.ofFloat(0f, 60f).apply {
		duration = 900
		repeatCount = ValueAnimator.INFINITE
		addUpdateListener { animator ->
			rectPaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), animator.animatedValue as Float)
			if (mode == Mode.CROP) {
				invalidate()
			}
		}
	}

	private val doubleTapDetector = GestureDetector(
		context,
		object : GestureDetector.SimpleOnGestureListener() {
			override fun onDoubleTap(e: MotionEvent): Boolean {
				if (mode == Mode.CROP) {
					resetCrop()
					return true
				}
				if (mode == Mode.TEXT) {
					selectedText?.let {
						onTextRequested?.invoke(it)
						return true
					}
				}
				return false
			}
		},
	)

	private val scaleDetector = ScaleGestureDetector(
		context,
		object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
			override fun onScale(detector: ScaleGestureDetector): Boolean {
				applyScale(detector.scaleFactor, detector.focusX, detector.focusY)
				return true
			}
		},
	)

	private enum class Handle {
		TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE,
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		dashAnimator.start()
	}

	override fun onDetachedFromWindow() {
		dashAnimator.cancel()
		super.onDetachedFromWindow()
	}

	fun setBitmap(value: Bitmap) {
		bitmap = value
		cropRect.set(0f, 0f, value.width.toFloat(), value.height.toFloat())
		strokes.clear()
		redoStack.clear()
		activeStroke = null
		userMatrix.reset()
		overlay = null
		overlayCanvas = null
		loupePoint = null
		if (width > 0 && height > 0) {
			// the view is already laid out: compute the fit matrix now
			// (onSizeChanged fired before the bitmap existed)
			updateMatrix()
		}
		notifyHistoryChanged()
		invalidate()
	}

	fun undo() {
		val last = strokes.removeLastOrNull() ?: return
		redoStack.add(last)
		rebuildOverlay()
		notifyHistoryChanged()
		invalidate()
	}

	fun redo() {
		val next = redoStack.removeLastOrNull() ?: return
		strokes.add(next)
		rebuildOverlay()
		notifyHistoryChanged()
		invalidate()
	}

	fun canUndo() = strokes.isNotEmpty() || activeStroke != null

	fun canRedo() = redoStack.isNotEmpty()

	private fun notifyHistoryChanged() {
		onHistoryChanged?.invoke(canUndo(), canRedo())
	}

	/** Renders every committed stroke onto the drawing layer from scratch. */
	private fun rebuildOverlay() {
		val bmp = bitmap ?: run {
			overlay = null
			overlayCanvas = null
			return
		}
		if (strokes.isEmpty() && activeStroke == null) {
			overlay = null
			overlayCanvas = null
			return
		}
		ensureOverlay()
		val ov = overlay ?: return
		ov.eraseColor(Color.TRANSPARENT)
		val canvas = Canvas(ov)
		overlayCanvas = canvas
		for (stroke in strokes) {
			drawStrokeOn(canvas, stroke)
		}
	}

	/** Draws one stroke onto the drawing layer; eraser strokes punch holes with CLEAR. */
	private fun drawStrokeOn(canvas: Canvas, stroke: Stroke) {
		overlayPaint.color = stroke.color
		overlayPaint.strokeWidth = stroke.width
		overlayPaint.xfermode = if (stroke.isEraser) {
			PorterDuffXfermode(PorterDuff.Mode.CLEAR)
		} else {
			null
		}
		val points = stroke.points
		if (points.isEmpty()) {
			return
		}
		val path = Path()
		path.moveTo(points[0].x, points[0].y)
		for (i in 1 until points.size) {
			path.lineTo(points[i].x, points[i].y)
		}
		if (points.size == 1) {
			// a single tap makes a dot
			path.lineTo(points[0].x + 0.1f, points[0].y + 0.1f)
		}
		canvas.drawPath(path, overlayPaint)
	}

	/** Draws just the newest segment (or the initial dot) of the active stroke. */
	private fun drawActiveSegment(stroke: Stroke, fromIndex: Int) {
		val canvas = overlayCanvas ?: return
		val points = stroke.points
		if (points.isEmpty()) {
			return
		}
		overlayPaint.color = stroke.color
		overlayPaint.strokeWidth = stroke.width
		overlayPaint.xfermode = if (stroke.isEraser) xferClear else null
		val path = Path()
		if (fromIndex <= 0) {
			// nothing before this point: draw a dot
			val p = points[0]
			path.moveTo(p.x, p.y)
			path.lineTo(p.x + 0.1f, p.y + 0.1f)
		} else {
			val a = points[fromIndex - 1]
			val b = points[fromIndex]
			path.moveTo(a.x, a.y)
			path.lineTo(b.x, b.y)
		}
		canvas.drawPath(path, overlayPaint)
	}

	private val xferClear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

	fun resetCrop() {
		val bmp = bitmap ?: return
		cropRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
		if (lassoRegion != null) {
			lassoRegion = null
			onCropShapeChanged?.invoke()
		}
		invalidate()
	}

	/** Renders the marked and cropped region into a new bitmap at full source resolution. */
	fun renderOutput(): Bitmap {
		val bmp = checkNotNull(bitmap) { "No bitmap to render" }
		rebuildOverlay()
		val full = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(full)
		canvas.drawBitmap(bmp, 0f, 0f, null)
		overlay?.let { canvas.drawBitmap(it, 0f, 0f, null) }
		drawTextObjects(canvas)
		val lasso = lassoRegion
		if (lasso == null) {
			val rect = RectF(cropRect)
			val left = rect.left.toInt().coerceIn(0, full.width - 1)
			val top = rect.top.toInt().coerceIn(0, full.height - 1)
			val width = (rect.right.toInt() - left).coerceAtLeast(1).coerceAtMost(full.width - left)
			val height = (rect.bottom.toInt() - top).coerceAtLeast(1).coerceAtMost(full.height - top)
			return Bitmap.createBitmap(full, left, top, width, height)
		}
		// free-form crop: clear everything outside the region by drawing the
		// inverse region with CLEAR (drawPaint+clipOutPath is unreliable here)
		val inverse = Path(lasso)
		inverse.fillType = Path.FillType.INVERSE_WINDING
		canvas.drawPath(inverse, Paint().apply { xfermode = xferClear })
		val bounds = RectF()
		lasso.computeBounds(bounds, true)
		val left = bounds.left.toInt().coerceIn(0, full.width - 1)
		val top = bounds.top.toInt().coerceIn(0, full.height - 1)
		val right = bounds.right.toInt().coerceIn(left + 1, full.width)
		val bottom = bounds.bottom.toInt().coerceIn(top + 1, full.height)
		return Bitmap.createBitmap(full, left, top, right - left, bottom - top)
	}

	/** Makes sure the drawing-layer bitmap exists and matches the page size. */
	private fun ensureOverlay() {
		val bmp = bitmap ?: return
		val ov = overlay
		if (ov == null || ov.width != bmp.width || ov.height != bmp.height) {
			val created = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
			overlay = created
			overlayCanvas = Canvas(created)
		}
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		updateMatrix()
	}

	private fun updateMatrix() {
		val bmp = bitmap ?: return
		if (width == 0 || height == 0) {
			return
		}
		val scale = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
		baseMatrix.reset()
		baseMatrix.postScale(scale, scale)
		val dx = (width - bmp.width * scale) / 2f
		val dy = (height - bmp.height * scale) / 2f
		baseMatrix.postTranslate(dx, dy)
		fitScale = scale
		recomputeMatrices()
		clampPan()
		recomputeMatrices()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val bmp = bitmap ?: return
		canvas.drawColor(Color.BLACK)
		canvas.drawBitmap(bmp, viewMatrix, null)
		// dim everything outside the crop rectangle
		val save = canvas.save()
		canvas.clipRect(bitmapRect)
		canvas.drawRect(bitmapRect, dimPaint)
		canvas.restoreToCount(save)
		val cropInView = RectF(cropRect)
		viewMatrix.mapRect(cropInView)
		val lasso = lassoRegion
		if (lasso == null) {
			// rectangular crop: dim outside, redraw inside, outline + handles
			canvas.drawRect(bitmapRect, dimPaint)
			canvas.save()
			canvas.clipRect(cropInView)
			canvas.drawBitmap(bmp, viewMatrix, null)
			canvas.restore()
			canvas.drawRect(cropInView, rectOuterPaint)
			canvas.drawRect(cropInView, rectPaint)
			drawHandle(canvas, cropInView.left, cropInView.top)
			drawHandle(canvas, cropInView.right, cropInView.top)
			drawHandle(canvas, cropInView.left, cropInView.bottom)
			drawHandle(canvas, cropInView.right, cropInView.bottom)
		} else {
			// lasso crop: dim outside the free-form region, outline it
			val viewPath = Path(lasso)
			viewPath.transform(viewMatrix)
			canvas.save()
			canvas.clipOutPath(viewPath)
			canvas.drawRect(bitmapRect, dimPaint)
			canvas.restore()
			canvas.drawPath(viewPath, rectOuterPaint)
			canvas.drawPath(viewPath, rectPaint)
		}
		// the free-form region being drawn right now
		if (mode == Mode.LASSO && lassoPoints.isNotEmpty()) {
			val path = Path()
			val first = toView(lassoPoints[0])
			path.moveTo(first.x, first.y)
			for (i in 1 until lassoPoints.size) {
				val p = toView(lassoPoints[i])
				path.lineTo(p.x, p.y)
			}
			canvas.drawPath(path, rectOuterPaint)
			canvas.drawPath(path, rectPaint)
		}
		// the drawing layer with all marks (strokes + eraser holes)
		overlay?.let { canvas.drawBitmap(it, viewMatrix, null) }
		canvas.save()
		canvas.concat(viewMatrix)
		drawTextObjects(canvas)
		canvas.restore()
		drawTextSelection(canvas)
		if (mode == Mode.PICK_COLOR) {
			loupePoint?.let { drawLoupe(canvas, it) }
		}
	}

	private fun drawLoupe(canvas: Canvas, point: PointF) {
		val bmp = bitmap ?: return
		val r = 90f
		val cx = point.x.coerceIn(r, width - r)
		val cy = (point.y - 170f).coerceIn(r, height - r)
		val image = toImage(point.x, point.y)
		val zoom = 4f
		val half = r / zoom
		val src = Rect(
			(image.x - half).roundToInt(),
			(image.y - half).roundToInt(),
			(image.x + half).roundToInt(),
			(image.y + half).roundToInt(),
		)
		val dest = Rect(
			(cx - r).roundToInt(),
			(cy - r).roundToInt(),
			(cx + r).roundToInt(),
			(cy + r).roundToInt(),
		)
		val shadow = RectF(dest)
		shadow.offset(0f, 5f)
		canvas.drawOval(shadow, loupeRingOuterPaint)
		val save2 = canvas.save()
		canvas.clipPath(Path().apply { addOval(RectF(dest), Path.Direction.CW) })
		canvas.drawBitmap(bmp, src, dest, null)
		canvas.restoreToCount(save2)
		canvas.drawOval(RectF(dest), loupeRingPaint)
		val c = sampledColor
		loupeDiscPaint.color = c
		canvas.drawCircle(cx, cy, 16f, loupeDiscPaint)
		canvas.drawCircle(cx, cy, 16f, loupeRingPaint)
		val cl = if (c.isWhiteish()) Color.BLACK else Color.WHITE
		loupeCrossPaint.color = cl
		canvas.drawLine(cx - 26f, cy, cx - 18f, cy, loupeCrossPaint)
		canvas.drawLine(cx + 18f, cy, cx + 26f, cy, loupeCrossPaint)
		canvas.drawLine(cx, cy - 26f, cx, cy - 18f, loupeCrossPaint)
		canvas.drawLine(cx, cy + 18f, cx, cy + 26f, loupeCrossPaint)
	}

	private fun Int.isWhiteish(): Boolean =
		Color.red(this) > 235 && Color.green(this) > 235 && Color.blue(this) > 235

	private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
		val r = 18f
		canvas.drawCircle(x, y, r, handlePaint)
		canvas.drawCircle(x, y, r, rectOuterPaint)
	}

	private fun toView(p: Point): android.graphics.PointF {
		val out = floatArrayOf(p.x, p.y)
		viewMatrix.mapPoints(out)
		return android.graphics.PointF(out[0], out[1])
	}

	private fun toImage(x: Float, y: Float): Point {
		val out = floatArrayOf(x, y)
		inverseMatrix.mapPoints(out)
		return Point(out[0], out[1])
	}

	/** Rebuilds the composed view matrix (fit transform followed by the user's zoom and pan). */
	private fun recomputeMatrices() {
		viewMatrix.set(baseMatrix)
		viewMatrix.preConcat(userMatrix)
		viewMatrix.invert(inverseMatrix)
		val bmp = bitmap
		if (bmp != null) {
			bitmapRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
			viewMatrix.mapRect(bitmapRect)
		}
	}

	/** Keeps the page on screen: centers it when smaller than the view, pins the edges otherwise. */
	private fun clampPan() {
		val bmp = bitmap ?: return
		if (width == 0 || height == 0) {
			return
		}
		val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
		viewMatrix.mapRect(rect)
		var dx = 0f
		var dy = 0f
		if (rect.width() <= width) {
			dx = (width - rect.width()) / 2f - rect.left
		} else {
			if (rect.left > 0f) dx = -rect.left
			else if (rect.right < width) dx = width - rect.right
		}
		if (rect.height() <= height) {
			dy = (height - rect.height()) / 2f - rect.top
		} else {
			if (rect.top > 0f) dy = -rect.top
			else if (rect.bottom < height) dy = height - rect.bottom
		}
		if (dx != 0f || dy != 0f) {
			userMatrix.postTranslate(dx, dy)
		}
	}

	/** Applies one pinch step, clamped between the fit size and 8x, and snaps back at the minimum. */
	private fun applyScale(factor: Float, fx: Float, fy: Float) {
		val sel = selectedText
		if (mode == Mode.TEXT && sel != null) {
			// pinch resizes the selected caption instead of zooming the view
			sel.size = (sel.size * factor).coerceIn(16f, 800f)
			invalidate()
			return
		}
		cancelTransient()
		val current = viewMatrix.mapRadius(1f)
		if (current <= 0f) {
			return
		}
		val target = (current * factor).coerceIn(fitScale, fitScale * MAX_ZOOM)
		val eff = target / current
		if (eff in 0.999f..1.001f) {
			return
		}
		if (target <= fitScale + 0.01f) {
			userMatrix.reset()
		} else {
			userMatrix.postScale(eff, eff, fx, fy)
		}
		recomputeMatrices()
		clampPan()
		recomputeMatrices()
		invalidate()
	}

	/** Two-finger pan by the centroid movement of all active pointers. */
	private fun panBy(event: MotionEvent) {
		val cx = centroidX(event)
		val cy = centroidY(event)
		val dx = cx - prevCx
		val dy = cy - prevCy
		prevCx = cx
		prevCy = cy
		if (dx == 0f && dy == 0f) {
			return
		}
		val sel = selectedText
		if (mode == Mode.TEXT && sel != null) {
			// two-finger drag moves the selected caption
			val scale = viewMatrix.mapRadius(1f)
			if (scale > 0f) {
				sel.x += dx / scale
				sel.y += dy / scale
			}
			invalidate()
			return
		}
		userMatrix.postTranslate(dx, dy)
		recomputeMatrices()
		clampPan()
		recomputeMatrices()
		invalidate()
	}

	private fun centroidX(event: MotionEvent): Float {
		var sum = 0f
		for (i in 0 until event.pointerCount) {
			sum += event.getX(i)
		}
		return sum / event.pointerCount
	}

	private fun centroidY(event: MotionEvent): Float {
		var sum = 0f
		for (i in 0 until event.pointerCount) {
			sum += event.getX(i)
		}
		return sum / event.pointerCount
	}

	/** Drops the in-progress drag or stroke, so a pinch never leaves half-finished work. */
	private fun cancelTransient() {
		if (activeStroke != null) {
			activeStroke = null
			rebuildOverlay()
		}
		dragHandle = null
		lassoPoints.clear()
		textDragMode = TextDragMode.NONE
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		val bmp = bitmap ?: return false
		scaleDetector.onTouchEvent(event)
		val count = event.pointerCount
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				multiGesture = false
				prevCx = event.x
				prevCy = event.y
			}

			MotionEvent.ACTION_POINTER_DOWN -> {
				if (count >= 2) {
					if (!multiGesture) {
						multiGesture = true
						cancelTransient()
					}
					prevCx = centroidX(event)
					prevCy = centroidY(event)
					return true
				}
			}

			MotionEvent.ACTION_MOVE -> {
				if (multiGesture || count >= 2) {
					if (count >= 2) {
						panBy(event)
					} else {
						prevCx = event.x
						prevCy = event.y
					}
					return true
				}
			}

			MotionEvent.ACTION_POINTER_UP -> {
				prevCx = centroidX(event)
				prevCy = centroidY(event)
				return true
			}

			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				multiGesture = false
			}
		}
		if (multiGesture || scaleDetector.isInProgress) {
			return true
		}
		if (count == 1) {
			doubleTapDetector.onTouchEvent(event)
		}
		when (mode) {
			Mode.CROP -> {
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN -> {
						val image = toImage(event.x, event.y)
						dragHandle = hitHandle(event.x, event.y) ?: if (cropRect.contains(image.x, image.y)) Handle.MOVE else null
						if (dragHandle != null) {
							dragStart = image
							dragRectStart = RectF(cropRect)
							parent?.requestDisallowInterceptTouchEvent(true)
							return true
						}
						return false
					}

					MotionEvent.ACTION_MOVE -> {
						val handle = dragHandle ?: return false
						val image = toImage(event.x, event.y)
						val dx = image.x - dragStart.x
						val dy = image.y - dragStart.y
						val r = dragRectStart
						val left = r.left + dx
						val top = r.top + dy
						val right = r.right + dx
						val bottom = r.bottom + dy
						when (handle) {
							Handle.MOVE -> cropRect.set(
								left.coerceIn(0f, bmp.width - r.width()),
								top.coerceIn(0f, bmp.height - r.height()),
								right.coerceIn(r.width(), bmp.width.toFloat()),
								bottom.coerceIn(r.height(), bmp.height.toFloat()),
							)

							Handle.TOP_LEFT -> cropRect.set(
								min(image.x, cropRect.right - MIN_CROP),
								min(image.y, cropRect.bottom - MIN_CROP),
								cropRect.right,
								cropRect.bottom,
							)

							Handle.TOP_RIGHT -> cropRect.set(
								cropRect.left,
								min(image.y, cropRect.bottom - MIN_CROP),
								max(image.x, cropRect.left + MIN_CROP),
								cropRect.bottom,
							)

							Handle.BOTTOM_LEFT -> cropRect.set(
								min(image.x, cropRect.right - MIN_CROP),
								cropRect.top,
								cropRect.right,
								max(image.y, cropRect.top + MIN_CROP),
							)

							Handle.BOTTOM_RIGHT -> cropRect.set(
								cropRect.left,
								cropRect.top,
								max(image.x, cropRect.left + MIN_CROP),
								max(image.y, cropRect.top + MIN_CROP),
							)
						}
						cropRect.intersect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
						invalidate()
						return true
					}

					MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						dragHandle = null
						return true
					}
				}
			}

			Mode.DRAW, Mode.ERASE -> {
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN -> {
						val image = toImage(event.x, event.y)
						if (!cropRect.contains(image.x, image.y)) {
							return false
						}
						redoStack.clear()
						activeStroke = Stroke(strokeColor, strokeWidth, mutableListOf(image), isEraser = mode == Mode.ERASE)
						ensureOverlay()
						drawActiveSegment(activeStroke!!, 0)
						parent?.requestDisallowInterceptTouchEvent(true)
						notifyHistoryChanged()
						invalidate()
						return true
					}

					MotionEvent.ACTION_MOVE -> {
						val stroke = activeStroke ?: return false
						val image = toImage(event.x, event.y)
						val last = stroke.points.lastOrNull()
						if (last == null || (image.x - last.x) * (image.x - last.x) + (image.y - last.y) * (image.y - last.y) > 4f) {
							stroke.points.add(image)
							drawActiveSegment(stroke, stroke.points.size - 1)
							invalidate()
						}
						return true
					}

					MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						activeStroke?.let { stroke ->
							if (stroke.points.isNotEmpty()) {
								strokes.add(stroke)
							}
						}
						activeStroke = null
						notifyHistoryChanged()
						invalidate()
						return true
					}
				}
			}

			Mode.LASSO -> {
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN -> {
						lassoPoints.clear()
						lassoPoints.add(toImage(event.x, event.y))
						invalidate()
						return true
					}

					MotionEvent.ACTION_MOVE -> {
						if (lassoPoints.isNotEmpty()) {
							val image = toImage(event.x, event.y)
							val last = lassoPoints.last()
							if ((image.x - last.x) * (image.x - last.x) + (image.y - last.y) * (image.y - last.y) > 16f) {
								lassoPoints.add(image)
								invalidate()
							}
						}
						return true
					}

					MotionEvent.ACTION_UP -> {
						if (lassoPoints.size >= 3) {
							val path = Path()
							path.moveTo(lassoPoints[0].x, lassoPoints[0].y)
							for (i in 1 until lassoPoints.size) {
								path.lineTo(lassoPoints[i].x, lassoPoints[i].y)
							}
							path.close()
							lassoRegion = path
							onCropShapeChanged?.invoke()
						}
						lassoPoints.clear()
						invalidate()
						return true
					}

					MotionEvent.ACTION_CANCEL -> {
						lassoPoints.clear()
						invalidate()
						return true
					}
				}
			}

			Mode.TEXT -> {
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN -> {
						val image = toImage(event.x, event.y)
						val hit = hitTextObject(image.x, image.y)
						if (hit != null) {
							selectedText = hit
							textDragMode = TextDragMode.MOVE
							textDragLast = PointF(event.x, event.y)
							invalidate()
							return true
						}
						val sel = selectedText
						if (sel != null) {
							if (hitRotateHandle(event.x, event.y, sel)) {
								textDragMode = TextDragMode.ROTATE
								textRotateLast = angleTo(sel, event.x, event.y)
								invalidate()
								return true
							}
							if (hitDeleteButton(event.x, event.y, sel)) {
								textObjects.remove(sel)
								selectedText = null
								invalidate()
								return true
							}
						}
						// empty spot: deselect and ask the activity for a new caption
						selectedText = null
						invalidate()
						onTextRequested?.invoke(null)
						return true
					}

					MotionEvent.ACTION_MOVE -> {
						when (textDragMode) {
							TextDragMode.MOVE -> {
								val sel = selectedText ?: return false
								val image = toImage(event.x, event.y)
								val start = toImage(textDragLast.x, textDragLast.y)
								sel.x += image.x - start.x
								sel.y += image.y - start.y
								textDragLast = PointF(event.x, event.y)
								invalidate()
								return true
							}

							TextDragMode.ROTATE -> {
								val sel = selectedText ?: return false
								val angle = angleTo(sel, event.x, event.y)
								var delta = angle - textRotateLast
								if (delta > 180f) delta -= 360f
								if (delta < -180f) delta += 360f
								sel.rotationDeg += delta
								textRotateLast = angle
								invalidate()
								return true
							}

							TextDragMode.NONE -> return false
						}
					}

					MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						textDragMode = TextDragMode.NONE
						return true
					}
				}
			}

			Mode.PICK_COLOR -> {
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
						if (event.x >= 0f && event.y >= 0f && event.x <= width && event.y <= height) {
							loupePoint = PointF(event.x, event.y)
							val image = toImage(event.x, event.y)
							val px = image.x.toInt().coerceIn(0, bmp.width - 1)
							val py = image.y.toInt().coerceIn(0, bmp.height - 1)
							sampledColor = bmp.getPixel(px, py)
							invalidate()
						}
						return true
					}

					MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						val wasUp = event.actionMasked == MotionEvent.ACTION_UP
						loupePoint = null
						if (wasUp) {
							strokeColor = sampledColor
							onColorPicked?.invoke(strokeColor)
						}
						mode = Mode.DRAW
						invalidate()
						return true
					}
				}
			}
		}
		return super.onTouchEvent(event)
	}

	/** Finds the topmost text object under an image-space point, or null. */
	private fun hitTextObject(ix: Float, iy: Float): TextObject? {
		val paint = textPaint
		for (i in textObjects.indices.reversed()) {
			val t = textObjects[i]
			t.measure(paint)
			val local = t.toLocal(ix, iy)
			// bounds are absolute; convert them to centre-relative for the rotated local point
			val halfWidth = t.bounds.width() / 2f + HIT_PAD
			val top = t.bounds.top - t.y - HIT_PAD
			val bottom = t.bounds.bottom - t.y + HIT_PAD
			if (local[0] >= -halfWidth && local[0] <= halfWidth && local[1] >= top && local[1] <= bottom) {
				return t
			}
		}
		return null
	}

	/** Angle from the object centre to a view point, in degrees. */
	private fun angleTo(t: TextObject, vx: Float, vy: Float): Float {
		val image = toImage(vx, vy)
		return Math.toDegrees(Math.atan2((image.y - t.y).toDouble(), (image.x - t.x).toDouble())).toFloat()
	}

	/** Centre of the rotate handle for a selected object, in view coordinates. */
	private fun rotateHandleViewPos(t: TextObject): PointF {
		t.measure(textPaint)
		val corner = floatArrayOf(t.bounds.right, t.bounds.top)
		viewMatrix.mapPoints(corner)
		return PointF(corner[0], corner[1])
	}

	/** Centre of the delete handle for a selected object, in view coordinates. */
	private fun deleteHandleViewPos(t: TextObject): PointF {
		t.measure(textPaint)
		val corner = floatArrayOf(t.bounds.left, t.bounds.top)
		viewMatrix.mapPoints(corner)
		return PointF(corner[0], corner[1])
	}

	private fun hitRotateHandle(vx: Float, vy: Float, t: TextObject): Boolean {
		val p = rotateHandleViewPos(t)
		return (vx - p.x) * (vx - p.x) + (vy - p.y) * (vy - p.y) <= ROTATE_HANDLE_R * ROTATE_HANDLE_R * 2.5f
	}

	private fun hitDeleteButton(vx: Float, vy: Float, t: TextObject): Boolean {
		val p = deleteHandleViewPos(t)
		return (vx - p.x) * (vx - p.x) + (vy - p.y) * (vy - p.y) <= ROTATE_HANDLE_R * ROTATE_HANDLE_R * 2.5f
	}

	/** Renders every caption onto the canvas in image space. */
	private fun drawTextObjects(canvas: Canvas) {
		val paint = textPaint
		for (t in textObjects) {
			t.measure(paint)
			paint.color = t.color
			paint.typeface = t.typeface
			paint.textSize = t.size
			canvas.save()
			canvas.translate(t.x, t.y)
			canvas.rotate(t.rotationDeg)
			paint.getTextBounds(t.text, 0, t.text.length, tmpTextBounds)
			val width = paint.measureText(t.text)
			val metrics = paint.fontMetrics
			canvas.drawText(t.text, -width / 2f, -(metrics.ascent + metrics.descent) / 2f, paint)
			canvas.restore()
		}
	}

	/** Draws the selection box, rotate and delete handles for the selected caption. */
	private fun drawTextSelection(canvas: Canvas) {
		val sel = selectedText ?: return
		sel.measure(textPaint)
		val viewBounds = RectF(sel.bounds)
		viewMatrix.mapRect(viewBounds)
		canvas.drawRect(viewBounds, rectPaint)
		val rot = rotateHandleViewPos(sel)
		canvas.drawCircle(rot.x, rot.y, ROTATE_HANDLE_R, handlePaint)
		canvas.drawCircle(rot.x, rot.y, ROTATE_HANDLE_R, rectOuterPaint)
		val del = deleteHandleViewPos(sel)
		canvas.drawCircle(del.x, del.y, ROTATE_HANDLE_R, handlePaint)
		canvas.drawCircle(del.x, del.y, ROTATE_HANDLE_R, rectOuterPaint)
		// X mark on the delete handle
		val d = ROTATE_HANDLE_R * 0.45f
		canvas.drawLine(del.x - d, del.y - d, del.x + d, del.y + d, rectOuterPaint)
		canvas.drawLine(del.x - d, del.y + d, del.x + d, del.y - d, rectOuterPaint)
		// arrows hint on the rotate handle
		val rr = ROTATE_HANDLE_R * 0.4f
		canvas.drawArc(
			RectF(rot.x - rr, rot.y - rr, rot.x + rr, rot.y + rr),
			-60f, 280f, false, rectOuterPaint,
		)
	}

	private val textPaint = android.text.TextPaint(android.text.TextPaint.ANTI_ALIAS_FLAG)

	private val tmpTextBounds = Rect()

	private fun hitHandle(x: Float, y: Float): Handle? {
		val cropInView = RectF(cropRect)
		viewMatrix.mapRect(cropInView)
		val touch = 48f
		return when {
			max(x - cropInView.left, cropInView.left - x) <= touch && max(y - cropInView.top, cropInView.top - y) <= touch -> Handle.TOP_LEFT
			max(x - cropInView.right, cropInView.right - x) <= touch && max(y - cropInView.top, cropInView.top - y) <= touch -> Handle.TOP_RIGHT
			max(x - cropInView.left, cropInView.left - x) <= touch && max(y - cropInView.bottom, cropInView.bottom - y) <= touch -> Handle.BOTTOM_LEFT
			max(x - cropInView.right, cropInView.right - x) <= touch && max(y - cropInView.bottom, cropInView.bottom - y) <= touch -> Handle.BOTTOM_RIGHT
			else -> null
		}
	}

	private companion object {
		const val MIN_CROP = 64f
		const val MAX_ZOOM = 8f
		const val ROTATE_HANDLE_R = 22f
		const val HIT_PAD = 20f
	}
}
