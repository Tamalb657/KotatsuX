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
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

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
		CROP, DRAW, PICK_COLOR,
	}

	class Stroke(
		val color: Int,
		val width: Float,
		val points: MutableList<Point>,
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
		notifyHistoryChanged()
		invalidate()
	}

	fun undo() {
		val last = strokes.removeLastOrNull() ?: return
		redoStack.add(last)
		notifyHistoryChanged()
		invalidate()
	}

	fun redo() {
		val next = redoStack.removeLastOrNull() ?: return
		strokes.add(next)
		notifyHistoryChanged()
		invalidate()
	}

	fun canUndo() = strokes.isNotEmpty() || activeStroke != null

	fun canRedo() = redoStack.isNotEmpty()

	private fun notifyHistoryChanged() {
		onHistoryChanged?.invoke(canUndo(), canRedo())
	}

	fun resetCrop() {
		val bmp = bitmap ?: return
		cropRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
		invalidate()
	}

	/** Renders the marked and cropped region into a new bitmap at full source resolution. */
	fun renderOutput(): Bitmap {
		val bmp = checkNotNull(bitmap) { "No bitmap to render" }
		val rect = RectF(cropRect)
		val output = Bitmap.createBitmap(rect.width().toInt().coerceAtLeast(1), rect.height().toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
		val canvas = Canvas(output)
		canvas.translate(-rect.left, -rect.top)
		canvas.drawBitmap(bmp, 0f, 0f, null)
		for (stroke in strokes) {
			strokePaint.color = stroke.color
			strokePaint.strokeWidth = stroke.width
			val path = Path()
			val points = stroke.points
			if (points.isNotEmpty()) {
				path.moveTo(points[0].x, points[0].y)
				for (i in 1 until points.size) {
					path.lineTo(points[i].x, points[i].y)
				}
				if (points.size == 1) {
					// a single tap makes a dot
					path.lineTo(points[0].x + 0.1f, points[0].y + 0.1f)
				}
			}
			canvas.drawPath(path, strokePaint)
		}
		return output
	}

	private fun drawStrokePath(canvas: Canvas, stroke: Stroke) {
		strokePaint.color = stroke.color
		strokePaint.strokeWidth = stroke.width
		val path = Path()
		val points = stroke.points
		if (points.isEmpty()) {
			return
		}
		path.moveTo(points[0].x, points[0].y)
		for (i in 1 until points.size) {
			path.lineTo(points[i].x, points[i].y)
		}
		if (points.size == 1) {
			path.lineTo(points[0].x + 0.1f, points[0].y + 0.1f)
		}
		canvas.drawPath(path, strokePaint)
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
		// strokes
		for (stroke in strokes) {
			drawStroke(canvas, stroke)
		}
		activeStroke?.let { drawStroke(canvas, it) }
	}

	private fun drawStroke(canvas: Canvas, stroke: Stroke) {
		if (stroke.points.isEmpty()) {
			return
		}
		strokePaint.color = stroke.color
		strokePaint.strokeWidth = stroke.width * viewMatrix.mapRadius(1f)
		val path = Path()
		val first = toView(stroke.points[0])
		path.moveTo(first.x, first.y)
		for (i in 1 until stroke.points.size) {
			val p = toView(stroke.points[i])
			path.lineTo(p.x, p.y)
		}
		if (stroke.points.size == 1) {
			path.lineTo(first.x + 0.1f, first.y + 0.1f)
		}
		canvas.drawPath(path, strokePaint)
	}

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
		if (dx != 0f || dy != 0f) {
			userMatrix.postTranslate(dx, dy)
			recomputeMatrices()
			clampPan()
			recomputeMatrices()
			invalidate()
		}
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
		activeStroke = null
		dragHandle = null
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

			Mode.DRAW -> {
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN -> {
						val image = toImage(event.x, event.y)
						if (!cropRect.contains(image.x, image.y)) {
							return false
						}
						redoStack.clear()
						activeStroke = Stroke(strokeColor, strokeWidth, mutableListOf(image))
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

			Mode.PICK_COLOR -> {
				if (event.actionMasked == MotionEvent.ACTION_DOWN) {
					val image = toImage(event.x, event.y)
					if (image.x >= 0 && image.y >= 0 && image.x < bmp.width && image.y < bmp.height) {
						val pixel = bmp.getPixel(image.x.toInt(), image.y.toInt())
						strokeColor = if (android.graphics.Color.alpha(pixel) == 0) Color.WHITE else pixel
						onColorPicked?.invoke(strokeColor)
					}
					mode = Mode.DRAW
					return true
				}
			}
		}
		return super.onTouchEvent(event)
	}

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
	}
}
