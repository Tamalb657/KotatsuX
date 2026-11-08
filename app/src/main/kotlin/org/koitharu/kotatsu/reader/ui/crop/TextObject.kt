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
		val width = paint.measureText(text)
		val metrics = paint.fontMetrics
		val ascent = metrics.ascent
		val descent = metrics.descent
		bounds.set(x - width / 2f, y + ascent / 2f, x + width / 2f, y + descent / 2f)
	}

	/** Maps a point from image space into the object's local (un-rotated) space. */
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
