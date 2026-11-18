package org.koitharu.kotatsu.reader.ui.crop

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

class CheckerboardDrawable(
	tileSizePx: Int,
	private val cornerRadiusPx: Float = 0f,
	private val borderColor: Int = 0,
	private val borderWidthPx: Float = 0f,
	colorDark: Int = 0xFF141821.toInt(),
	colorLight: Int = 0xFF1F2430.toInt(),
) : Drawable() {

	private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		color = borderColor
		strokeWidth = borderWidthPx
	}
	private val rectF = RectF()
	private val clipPath = Path()

	init {
		val s = tileSizePx.coerceAtLeast(4)
		val bmp = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
		val c = Canvas(bmp)
		val p = Paint()
		p.color = colorDark
		c.drawRect(0f, 0f, (s * 2).toFloat(), (s * 2).toFloat(), p)
		p.color = colorLight
		c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
		c.drawRect(s.toFloat(), s.toFloat(), (s * 2).toFloat(), (s * 2).toFloat(), p)
		patternPaint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
	}

	override fun draw(canvas: Canvas) {
		val b = bounds
		if (b.isEmpty) return
		rectF.set(b)
		if (cornerRadiusPx > 0f) {
			clipPath.reset()
			clipPath.addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
			val save = canvas.save()
			canvas.clipPath(clipPath)
			canvas.drawRect(rectF, patternPaint)
			canvas.restoreToCount(save)
			if (borderWidthPx > 0f && borderColor != 0) {
				val inset = borderWidthPx * 0.5f
				rectF.inset(inset, inset)
				canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, borderPaint)
			}
		} else {
			canvas.drawRect(rectF, patternPaint)
		}
	}

	override fun setAlpha(alpha: Int) {
		patternPaint.alpha = alpha
		borderPaint.alpha = alpha
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		patternPaint.colorFilter = colorFilter
	}

	@Deprecated("Deprecated in Java")
	override fun getOpacity(): Int = PixelFormat.OPAQUE
}
