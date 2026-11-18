package org.koitharu.kotatsu.reader.ui.crop

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class NormPt(val x: Float, val y: Float)

data class NormRect(
	val x: Float,
	val y: Float,
	val w: Float,
	val h: Float,
	val r: Float = 14f,
)

enum class EditorTool(val title: String) {
	CROP("Perspective Crop"),
	LASSO("Lasso Select"),
	DRAW("Draw Brush"),
	ERASER("Eraser"),
	TEXT("Perspective Text"),
}

enum class EraserMode {
	BRUSH,
	OBJECT,
	RESTORE,
}

enum class CropExecMode {
	CUTOUT,
	UNWARP,
}

enum class LassoEffectMode {
	SPOTLIGHT,
	TINT,
	REDACT,
}

enum class StrokeOpKind {
	DRAW,
	ERASE_BRUSH,
	RESTORE,
	ERASE_UI_RECT,
}

data class StrokeOp(
	val id: String,
	val kind: StrokeOpKind,
	val color: Int = Color.WHITE,
	val size: Float = 16f,
	val pts: MutableList<NormPt> = ArrayList(),
	val rect: NormRect? = null,
	val fillColor: Int = Color.BLACK,
) {
	fun deepCopy(): StrokeOp = copy(
		pts = ArrayList(pts),
		rect = rect?.copy(),
	)
}

data class LassoRegion(
	val id: String,
	val mode: LassoEffectMode,
	val feather: Float,
	val pts: List<NormPt>,
) {
	fun deepCopy(): LassoRegion = copy(pts = ArrayList(pts))
}

data class PerspectiveTextLayer(
	val id: String,
	var text: String,
	var color: Int,
	var fontId: String,
	var pts: List<NormPt>,
) {
	fun deepCopy(): PerspectiveTextLayer = copy(pts = ArrayList(pts))
}

data class MangaFontPreset(
	val id: String,
	val name: String,
	val role: String,
	val typeface: Typeface,
	val isBoldWeight: Boolean,
	val uppercase: Boolean,
	val letterSpacingPx: Float,
	val isCustom: Boolean = false,
	val customFilePath: String? = null,
)

data class EditorDocumentState(
	var rotationSteps: Int = 0,
	var straighten: Int = 0,
	var cropMode: CropExecMode = CropExecMode.CUTOUT,
	var cropAspect: String = "free",
	var cropQuad: MutableList<NormPt> = createDefaultCropQuad(),
	val ops: MutableList<StrokeOp> = ArrayList(),
	val regions: MutableList<LassoRegion> = ArrayList(),
	val texts: MutableList<PerspectiveTextLayer> = ArrayList(),
) {
	fun deepCopy(): EditorDocumentState = EditorDocumentState(
		rotationSteps = rotationSteps,
		straighten = straighten,
		cropMode = cropMode,
		cropAspect = cropAspect,
		cropQuad = ArrayList(cropQuad),
		ops = ops.mapTo(ArrayList(ops.size)) { it.deepCopy() },
		regions = regions.mapTo(ArrayList(regions.size)) { it.deepCopy() },
		texts = texts.mapTo(ArrayList(texts.size)) { it.deepCopy() },
	)

	companion object {
		fun createDefaultCropQuad(): MutableList<NormPt> = arrayListOf(
			NormPt(0f, 0f),
			NormPt(1f, 0f),
			NormPt(1f, 1f),
			NormPt(0f, 1f),
		)
	}
}

object EditorColors {
	const val SURFACE_DIM = 0xFF0B0E14.toInt()
	const val SURFACE = 0xFF111318.toInt()
	const val SURFACE_CONTAINER_LOWEST = 0xFF0C0E13.toInt()
	const val SURFACE_CONTAINER_LOW = 0xFF191C20.toInt()
	const val SURFACE_CONTAINER = 0xFF1D2024.toInt()
	const val SURFACE_CONTAINER_HIGH = 0xFF282A2F.toInt()
	const val SURFACE_CONTAINER_HIGHEST = 0xFF33353A.toInt()
	const val ON_SURFACE = 0xFFE2E2E9.toInt()
	const val ON_SURFACE_VARIANT = 0xFFC4C6D0.toInt()
	const val OUTLINE = 0xFF8E9099.toInt()
	const val OUTLINE_VARIANT = 0xFF44474E.toInt()

	const val PRIMARY = 0xFFA8C7FA.toInt()
	const val ON_PRIMARY = 0xFF062E6F.toInt()
	const val PRIMARY_CONTAINER = 0xFF284777.toInt()
	const val ON_PRIMARY_CONTAINER = 0xFFD6E3FF.toInt()

	const val SECONDARY_CONTAINER = 0xFF3E4759.toInt()
	const val ON_SECONDARY_CONTAINER = 0xFFDAE2F9.toInt()

	const val ERROR = 0xFFFFB4AB.toInt()
	const val ERROR_CONTAINER_SOFT = 0x24FFB4AB

	const val INVERSE_SURFACE = 0xFFE2E2E9.toInt()
	const val INVERSE_ON_SURFACE = 0xFF2E3036.toInt()

	const val CANVAS_BACKDROP = 0xFF151922.toInt()
	const val SCRIM = 0xA3000000.toInt()
	const val HANDLE_BLUE = 0xFF56A8FF.toInt()

	val INK_COLORS = intArrayOf(
		0xFFA8C7FA.toInt(), // Material You Primary Sky Blue
		0xFFFF6B6B.toInt(), // Material You Coral Red
		0xFFFFD166.toInt(), // Material You Solar Amber
		0xFF6DD58C.toInt(), // Material You Mint Green
		0xFF4FD8EB.toInt(), // Material You Pixel Cyan
		0xFFD0BCFF.toInt(), // Material You Soft Violet
		0xFFFFFFFF.toInt(), // Crisp White
	)

	val TEXT_COLORS = intArrayOf(
		0xFFFFFFFF.toInt(), // Crisp White
		0xFF111318.toInt(), // Manga Ink Black
		0xFFA8C7FA.toInt(), // Material You Primary Sky Blue
		0xFFFF6B6B.toInt(), // Material You Coral Red
		0xFFFFD166.toInt(), // Material You Solar Amber
		0xFF6DD58C.toInt(), // Material You Mint Green
		0xFF4FD8EB.toInt(), // Material You Pixel Cyan
		0xFFD0BCFF.toInt(), // Material You Soft Violet
	)

	fun isLightSwatch(color: Int): Boolean {
		val r = Color.red(color)
		val g = Color.green(color)
		val b = Color.blue(color)
		val lum = 0.299 * r + 0.587 * g + 0.114 * b
		return lum > 150.0
	}

	fun withAlpha(color: Int, alphaFraction: Float): Int {
		val a = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
		return (color and 0x00FFFFFF) or (a shl 24)
	}
}

object MangaFontRegistry {

	private fun loadAssetFont(context: Context, fileName: String, fallback: Typeface): Typeface =
		runCatching { Typeface.createFromAsset(context.assets, "fonts/$fileName") }.getOrDefault(fallback)

	fun createBuiltInPresets(context: Context): List<MangaFontPreset> = listOf(
		MangaFontPreset(
			id = "wild",
			name = "Wild Words",
			role = "Manga Dialogue",
			typeface = loadAssetFont(context, "ComicNeue-Bold.ttf", Typeface.DEFAULT_BOLD),
			isBoldWeight = true,
			uppercase = true,
			letterSpacingPx = 1.5f,
		),
		MangaFontPreset(
			id = "anime",
			name = "Anime Ace",
			role = "Webtoon Speech",
			typeface = loadAssetFont(context, "ArchitectsDaughter-Regular.ttf", Typeface.DEFAULT),
			isBoldWeight = false,
			uppercase = true,
			letterSpacingPx = 2.0f,
		),
		MangaFontPreset(
			id = "shonen",
			name = "Shonen SFX",
			role = "Action Impact",
			typeface = loadAssetFont(context, "Bangers-Regular.ttf", Typeface.DEFAULT_BOLD),
			isBoldWeight = false,
			uppercase = true,
			letterSpacingPx = 3.0f,
		),
		MangaFontPreset(
			id = "brush",
			name = "Brush Shout",
			role = "Battle Scream",
			typeface = loadAssetFont(context, "PermanentMarker-Regular.ttf", Typeface.DEFAULT),
			isBoldWeight = false,
			uppercase = false,
			letterSpacingPx = 1.0f,
		),
		MangaFontPreset(
			id = "komika",
			name = "Komika Axis",
			role = "Bold Cover",
			typeface = loadAssetFont(context, "LuckiestGuy-Regular.ttf", Typeface.DEFAULT_BOLD),
			isBoldWeight = false,
			uppercase = true,
			letterSpacingPx = 2.0f,
		),
		MangaFontPreset(
			id = "chibi",
			name = "Chibi Round",
			role = "Comedy / Gag",
			typeface = Typeface.create(loadAssetFont(context, "Fredoka-Bold.ttf", Typeface.DEFAULT_BOLD), Typeface.BOLD),
			isBoldWeight = true,
			uppercase = false,
			letterSpacingPx = 0.5f,
		),
		MangaFontPreset(
			id = "horror",
			name = "Thriller SFX",
			role = "Dark / Dread",
			typeface = loadAssetFont(context, "Creepster-Regular.ttf", Typeface.DEFAULT),
			isBoldWeight = false,
			uppercase = true,
			letterSpacingPx = 2.5f,
		),
		MangaFontPreset(
			id = "system",
			name = "Manhwa Sans",
			role = "System UI Box",
			typeface = Typeface.create(loadAssetFont(context, "PlusJakartaSans-ExtraBold.ttf", Typeface.SANS_SERIF), Typeface.BOLD),
			isBoldWeight = true,
			uppercase = false,
			letterSpacingPx = 0.4f,
		),
	)
}

object HomographyMath {

	fun distPts(a: NormPt, b: NormPt): Float = hypot(b.x - a.x, b.y - a.y)

	fun quadCentroid(pts: List<NormPt>): NormPt = NormPt(
		x = (pts[0].x + pts[1].x + pts[2].x + pts[3].x) * 0.25f,
		y = (pts[0].y + pts[1].y + pts[2].y + pts[3].y) * 0.25f,
	)

	fun isQuadConvex(pts: List<NormPt>): Boolean {
		if (pts.size < 4) return false
		var pos = 0
		var neg = 0
		for (i in 0 until 4) {
			val a = pts[i]
			val b = pts[(i + 1) % 4]
			val c = pts[(i + 2) % 4]
			val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
			if (cross > 1e-6f) pos++ else if (cross < -1e-6f) neg++
		}
		return (pos == 4 && neg == 0) || (neg == 4 && pos == 0)
	}

	fun isAxisAlignedRectQuad(q: List<NormPt>): Boolean {
		if (q.size < 4) return false
		return abs(q[0].y - q[1].y) < 1e-4f &&
			abs(q[2].y - q[3].y) < 1e-4f &&
			abs(q[0].x - q[3].x) < 1e-4f &&
			abs(q[1].x - q[2].x) < 1e-4f &&
			q[1].x > q[0].x &&
			q[3].y > q[0].y
	}

	fun isDefaultFullCropQuad(q: List<NormPt>): Boolean {
		if (q.size < 4) return false
		return abs(q[0].x) < 1e-4f && abs(q[0].y) < 1e-4f &&
			abs(q[1].x - 1f) < 1e-4f && abs(q[1].y) < 1e-4f &&
			abs(q[2].x - 1f) < 1e-4f && abs(q[2].y - 1f) < 1e-4f &&
			abs(q[3].x) < 1e-4f && abs(q[3].y - 1f) < 1e-4f
	}

	fun pointInPoly(x: Float, y: Float, pts: List<NormPt>): Boolean {
		if (pts.size < 3) return false
		var inside = false
		var j = pts.size - 1
		for (i in pts.indices) {
			val xi = pts[i].x
			val yi = pts[i].y
			val xj = pts[j].x
			val yj = pts[j].y
			val intersect = ((yi > y) != (yj > y)) &&
				(x < ((xj - xi) * (y - yi)) / (yj - yi + 1e-12f) + xi)
			if (intersect) inside = !inside
			j = i
		}
		return inside
	}

	fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
		val dx = x2 - x1
		val dy = y2 - y1
		val lenSq = dx * dx + dy * dy
		if (lenSq < 1e-6f) return hypot(px - x1, py - y1)
		val t = ((px - x1) * dx + (py - y1) * dy) / lenSq
		val tc = min(1f, max(0f, t))
		return hypot(px - (x1 + tc * dx), py - (y1 + tc * dy))
	}

	fun computeHomography(src: List<NormPt>, dst: List<NormPt>): DoubleArray? {
		if (src.size < 4 || dst.size < 4) return null
		val a = Array(8) { DoubleArray(8) }
		val b = DoubleArray(8)
		for (i in 0 until 4) {
			val sx = src[i].x.toDouble()
			val sy = src[i].y.toDouble()
			val dx = dst[i].x.toDouble()
			val dy = dst[i].y.toDouble()
			a[i * 2] = doubleArrayOf(sx, sy, 1.0, 0.0, 0.0, 0.0, -dx * sx, -dx * sy)
			b[i * 2] = dx
			a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, sx, sy, 1.0, -dy * sx, -dy * sy)
			b[i * 2 + 1] = dy
		}
		for (col in 0 until 8) {
			var pivot = col
			var maxVal = abs(a[col][col])
			for (row in col + 1 until 8) {
				val v = abs(a[row][col])
				if (v > maxVal) {
					maxVal = v
					pivot = row
				}
			}
			if (maxVal < 1e-11) return null
			if (pivot != col) {
				val tmpRow = a[col]
				a[col] = a[pivot]
				a[pivot] = tmpRow
				val tmpB = b[col]
				b[col] = b[pivot]
				b[pivot] = tmpB
			}
			val diag = a[col][col]
			for (row in col + 1 until 8) {
				val factor = a[row][col] / diag
				if (factor == 0.0) continue
				for (k in col until 8) {
					a[row][k] -= factor * a[col][k]
				}
				b[row] -= factor * b[col]
			}
		}
		val h = DoubleArray(9)
		h[8] = 1.0
		for (row in 7 downTo 0) {
			var sum = b[row]
			for (k in row + 1 until 8) {
				sum -= a[row][k] * h[k]
			}
			h[row] = sum / a[row][row]
		}
		return h
	}

	fun applyHomographyPt(h: DoubleArray?, x: Float, y: Float): NormPt {
		if (h == null) return NormPt(x, y)
		val w = h[6] * x + h[7] * y + h[8]
		if (abs(w) < 1e-8) return NormPt(x, y)
		return NormPt(
			x = ((h[0] * x + h[1] * y + h[2]) / w).toFloat(),
			y = ((h[3] * x + h[4] * y + h[5]) / w).toFloat(),
		)
	}

	fun evalQuadUV(pts: List<NormPt>, hUnitToQuad: DoubleArray?, u: Float, v: Float): NormPt {
		if (hUnitToQuad != null) {
			return applyHomographyPt(hUnitToQuad, u, v)
		}
		val p0 = pts[0]
		val p1 = pts[1]
		val p2 = pts[2]
		val p3 = pts[3]
		return NormPt(
			x = (1f - u) * (1f - v) * p0.x + u * (1f - v) * p1.x + u * v * p2.x + (1f - u) * v * p3.x,
			y = (1f - u) * (1f - v) * p0.y + u * (1f - v) * p1.y + u * v * p2.y + (1f - u) * v * p3.y,
		)
	}
}
