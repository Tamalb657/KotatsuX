package org.koitharu.kotatsu.reader.ui.crop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Authentic Material 3 Expressive Continuous Split-Track Slider (Android 16 style):
 * - Left active pill track (outer pill, inner 4dp radius) with evenly spaced stop dots
 * - Thumb breathing gap + vertical pill handle (4dp x 28dp -> 6dp x 32dp when dragging)
 * - Right inactive pill track (inner 4dp radius, outer pill) with evenly spaced stop dots
 * - Floating value tooltip pill above the thumb while dragging
 */
class M3ExpressiveSliderView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

	private val dp = resources.displayMetrics.density
	private val sp = resources.displayMetrics.scaledDensity

	var minValue: Int = 2
		private set
	var maxValue: Int = 64
		private set
	var currentValue: Int = 16
		private set
	var valueFormatter: (Int) -> String = { "$it px" }

	var onDragStart: (() -> Unit)? = null
	var onValueChanged: ((Int, Boolean) -> Unit)? = null
	var onDragEnd: (() -> Unit)? = null

	private var isDragging = false

	private val activeTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = EditorColors.PRIMARY
		style = Paint.Style.FILL
	}
	private val inactiveTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = EditorColors.SURFACE_CONTAINER_HIGHEST
		style = Paint.Style.FILL
	}
	private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = EditorColors.PRIMARY
		style = Paint.Style.FILL
	}
	private val activeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = EditorColors.withAlpha(EditorColors.ON_PRIMARY, 0.45f)
		style = Paint.Style.FILL
	}
	private val inactiveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = EditorColors.withAlpha(EditorColors.ON_SURFACE_VARIANT, 0.50f)
		style = Paint.Style.FILL
	}
	private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = EditorColors.PRIMARY
		style = Paint.Style.FILL
		setShadowLayer(4f * dp, 0f, 2f * dp, 0x55000000)
	}
	private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = EditorColors.ON_PRIMARY
		textSize = 11f * sp
		typeface = Typeface.DEFAULT_BOLD
		textAlign = Paint.Align.CENTER
	}

	private val trackPath = Path()
	private val tmpRect = RectF()
	private val leftRadii = FloatArray(8)
	private val rightRadii = FloatArray(8)

	fun configure(min: Int, max: Int, value: Int, formatter: (Int) -> String) {
		minValue = min
		maxValue = max(max, min + 1)
		currentValue = value.coerceIn(minValue, maxValue)
		valueFormatter = formatter
		invalidate()
	}

	fun setValue(value: Int, notify: Boolean = false) {
		val clamped = value.coerceIn(minValue, maxValue)
		if (currentValue != clamped) {
			currentValue = clamped
			invalidate()
			if (notify) onValueChanged?.invoke(currentValue, false)
		} else {
			invalidate()
		}
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val desiredH = (36f * dp).roundToInt()
		val w = resolveSize((180f * dp).roundToInt(), widthMeasureSpec)
		val h = resolveSize(desiredH, heightMeasureSpec)
		setMeasuredDimension(w, h)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val w = width.toFloat()
		val h = height.toFloat()
		if (w <= 0f || h <= 0f) return

		val cy = h * 0.5f
		val trackH = 14f * dp
		val outerR = trackH * 0.5f
		val innerR = 4f * dp
		val gapHalf = 7f * dp // 5dp gap + half thumb breathing space
		val usableW = max(1f, w - gapHalf * 2f)

		val ratio = ((currentValue - minValue).toFloat() / (maxValue - minValue).toFloat()).coerceIn(0f, 1f)
		val thumbX = gapHalf + usableW * ratio

		// 1. Left Active Split Track
		val leftEnd = thumbX - (if (isDragging) 6f * dp else 5f * dp)
		if (leftEnd > 2f * dp) {
			tmpRect.set(0f, cy - outerR, leftEnd, cy + outerR)
			leftRadii[0] = outerR; leftRadii[1] = outerR
			leftRadii[2] = innerR; leftRadii[3] = innerR
			leftRadii[4] = innerR; leftRadii[5] = innerR
			leftRadii[6] = outerR; leftRadii[7] = outerR
			trackPath.reset()
			trackPath.addRoundRect(tmpRect, leftRadii, Path.Direction.CW)
			canvas.drawPath(trackPath, activeTrackPaint)
		}

		// 2. Right Inactive Split Track
		val rightStart = thumbX + (if (isDragging) 6f * dp else 5f * dp)
		if (w - rightStart > 2f * dp) {
			tmpRect.set(rightStart, cy - outerR, w, cy + outerR)
			rightRadii[0] = innerR; rightRadii[1] = innerR
			rightRadii[2] = outerR; rightRadii[3] = outerR
			rightRadii[4] = outerR; rightRadii[5] = outerR
			rightRadii[6] = innerR; rightRadii[7] = innerR
			trackPath.reset()
			trackPath.addRoundRect(tmpRect, rightRadii, Path.Direction.CW)
			canvas.drawPath(trackPath, inactiveTrackPaint)
		}

		// 3. Stop Dots (9 stop positions matching HTML renderSliderDots)
		val totalDots = 9
		val dotR = 2f * dp
		val padX = 8f * dp
		val dotSpan = max(1f, w - padX * 2f)
		for (i in 0 until totalDots) {
			val dx = padX + (dotSpan * i) / (totalDots - 1)
			if (dx < leftEnd - 6f * dp) {
				canvas.drawCircle(dx, cy, dotR, activeDotPaint)
			} else if (dx > rightStart + 6f * dp) {
				canvas.drawCircle(dx, cy, dotR, inactiveDotPaint)
			}
		}

		// 4. Vertical Pill Handle
		val thumbW = (if (isDragging) 6f else 4f) * dp
		val thumbH = (if (isDragging) 32f else 28f) * dp
		tmpRect.set(
			thumbX - thumbW * 0.5f,
			cy - thumbH * 0.5f,
			thumbX + thumbW * 0.5f,
			cy + thumbH * 0.5f,
		)
		canvas.drawRoundRect(tmpRect, thumbW * 0.5f, thumbW * 0.5f, thumbPaint)

		// 5. Floating Value Pill Tooltip While Dragging (parent has clipChildren=false)
		if (isDragging) {
			val label = valueFormatter(currentValue)
			val textW = tooltipTextPaint.measureText(label)
			val pillW = textW + 16f * dp
			val pillH = 22f * dp
			val clampedX = thumbX.coerceIn(pillW * 0.5f, max(pillW * 0.5f, w - pillW * 0.5f))
			val pillBottom = cy - thumbH * 0.5f - 6f * dp
			val pillTop = pillBottom - pillH
			tmpRect.set(clampedX - pillW * 0.5f, pillTop, clampedX + pillW * 0.5f, pillBottom)
			canvas.drawRoundRect(tmpRect, pillH * 0.5f, pillH * 0.5f, tooltipBgPaint)
			val fm = tooltipTextPaint.fontMetrics
			val baseline = tmpRect.centerY() - (fm.ascent + fm.descent) * 0.5f
			canvas.drawText(label, tmpRect.centerX(), baseline, tooltipTextPaint)
		}
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				parent?.requestDisallowInterceptTouchEvent(true)
				isDragging = true
				onDragStart?.invoke()
				updateFromTouch(event.x)
				invalidate()
				return true
			}
			MotionEvent.ACTION_MOVE -> {
				if (isDragging) {
					updateFromTouch(event.x)
				}
				return true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				if (isDragging) {
					isDragging = false
					invalidate()
					onDragEnd?.invoke()
				}
				parent?.requestDisallowInterceptTouchEvent(false)
				return true
			}
		}
		return super.onTouchEvent(event)
	}

	private fun updateFromTouch(x: Float) {
		val w = width.toFloat()
		if (w <= 0f) return
		val ratio = (x / w).coerceIn(0f, 1f)
		val raw = (minValue + ratio * (maxValue - minValue)).roundToInt().coerceIn(minValue, maxValue)
		currentValue = raw
		invalidate()
		onValueChanged?.invoke(currentValue, true)
	}
}
