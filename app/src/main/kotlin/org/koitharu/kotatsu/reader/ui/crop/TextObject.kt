package org.koitharu.kotatsu.reader.ui.crop

import android.graphics.Typeface
import android.graphics.RectF

/**
 * A caption placed on the page by the text tool: everything is stored in
 * image pixels, so the export does not depend on the view size or zoom.
 */
class TextObject(
	var text: String,
	var fontName: String,
	var typeface: Typeface?,
	var color: Int,
	var size: Float,
	var x: Float,
	var y: Float,
	var rotationDeg: Float = 0f,
) {
	/** Bounds of the un-rotated text around (x, y) as centre, in image pixels. */
	val bounds: RectF = RectF()

	/** Recomputes [bounds] from the current text, font and size. */
	fun measure(paint: android.text.TextPaint) {
		paint.typeface = typeface
		paint.textSize = size
		val lines = text.split('\n')
		val maxLineWidth = lines.maxOfOrNull { paint.measureText(it) }?.coerceAtLeast(20f) ?: paint.measureText(text).coerceAtLeast(20f)
		val lineHeight = paint.fontSpacing
		val totalHeight = (lines.size * lineHeight).coerceAtLeast(paint.textSize)
		bounds.set(
			x - maxLineWidth / 2f,
			y - totalHeight / 2f,
			x + maxLineWidth / 2f,
			y + totalHeight / 2f,
		)
	}

	/** Maps a point from image space into the object's local (un-rotated) space around (x, y). */
	fun toLocal(px: Float, py: Float): FloatArray {
		val dx = px - x
		val dy = py - y
		val rad = Math.toRadians(rotationDeg.toDouble())
		val cos = Math.cos(rad).toFloat()
		val sin = Math.sin(rad).toFloat()
		// rotate by -angle
		return floatArrayOf(
			dx * cos + dy * sin,
			-dx * sin + dy * cos,
		)
	}
}
