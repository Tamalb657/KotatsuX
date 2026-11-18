package org.koitharu.kotatsu.reader.ui.crop

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
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

class MangaFontPreset(
	val id: String,
	val name: String,
	val role: String,
	private val typefaceLoader: () -> Typeface,
	private val offlineCheck: () -> Boolean = { true },
	val remoteUrl: String? = null,
	val cacheFileName: String? = null,
	val forceBoldStyle: Boolean = false,
	val isBoldWeight: Boolean,
	val uppercase: Boolean,
	val letterSpacingPx: Float,
	val isCustom: Boolean = false,
	val customFilePath: String? = null,
) {
	@Volatile
	private var cachedOfflineTypeface: Typeface? = null

	val isAvailableOffline: Boolean
		get() = cachedOfflineTypeface != null || offlineCheck()

	val typeface: Typeface
		get() {
			cachedOfflineTypeface?.let { return it }
			val loaded = typefaceLoader()
			if (offlineCheck()) {
				cachedOfflineTypeface = loaded
			}
			return loaded
		}

	internal fun updateDownloadedTypeface(tf: Typeface) {
		cachedOfflineTypeface = tf
	}

	constructor(
		id: String,
		name: String,
		role: String,
		typeface: Typeface,
		isBoldWeight: Boolean,
		uppercase: Boolean,
		letterSpacingPx: Float,
		isCustom: Boolean = false,
		customFilePath: String? = null,
	) : this(
		id = id,
		name = name,
		role = role,
		typefaceLoader = { typeface },
		offlineCheck = { true },
		isBoldWeight = isBoldWeight,
		uppercase = uppercase,
		letterSpacingPx = letterSpacingPx,
		isCustom = isCustom,
		customFilePath = customFilePath,
	)
}

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

	private const val GF_BASE = "https://raw.githubusercontent.com/google/fonts/main/"
	private const val DOWNLOADED_DIR = "downloaded_fonts"

	private val typefaceCache = ConcurrentHashMap<String, Typeface>()

	private fun getDownloadedFile(appContext: Context, fileName: String): File =
		File(File(appContext.filesDir, DOWNLOADED_DIR), fileName)

	private fun isBundledOrCached(appContext: Context, spec: BuiltInSpec): Boolean {
		if (spec.remoteUrl == null) return true
		val f = getDownloadedFile(appContext, spec.fileName)
		return f.isFile && f.length() > 256L
	}

	private fun loadResolvedFont(
		appContext: Context,
		spec: BuiltInSpec,
		fallback: Typeface,
	): Typeface {
		val key = if (spec.forceBoldStyle) "${spec.fileName}#bold" else spec.fileName
		typefaceCache[key]?.let { return it }

		if (spec.remoteUrl == null) {
			val base = runCatching { Typeface.createFromAsset(appContext.assets, "fonts/${spec.fileName}") }
				.getOrDefault(fallback)
			val resolved = if (spec.forceBoldStyle) Typeface.create(base, Typeface.BOLD) else base
			typefaceCache[key] = resolved
			return resolved
		}

		val cachedFile = getDownloadedFile(appContext, spec.fileName)
		if (cachedFile.isFile && cachedFile.length() > 256L) {
			val base = runCatching { Typeface.createFromFile(cachedFile) }.getOrNull()
			if (base != null) {
				val resolved = if (spec.forceBoldStyle) Typeface.create(base, Typeface.BOLD) else base
				typefaceCache[key] = resolved
				return resolved
			}
		}

		return fallback
	}

	fun downloadFontIfNeeded(context: Context, preset: MangaFontPreset): Boolean {
		if (preset.isAvailableOffline) return true
		val urlStr = preset.remoteUrl ?: return false
		val fileName = preset.cacheFileName ?: return false
		val appCtx = context.applicationContext
		val dir = File(appCtx.filesDir, DOWNLOADED_DIR).apply { mkdirs() }
		val finalFile = File(dir, fileName)
		val tmpFile = File(dir, "$fileName.tmp_${System.currentTimeMillis()}")

		return runCatching {
			val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
				connectTimeout = 15_000
				readTimeout = 30_000
				instanceFollowRedirects = true
				setRequestProperty("User-Agent", "KotatsuX-Android")
			}
			try {
				if (conn.responseCode !in 200..299) {
					error("HTTP ${conn.responseCode}")
				}
				conn.inputStream.use { input ->
					tmpFile.outputStream().use { out ->
						input.copyTo(out)
					}
				}
			} finally {
				conn.disconnect()
			}

			val baseTf = Typeface.createFromFile(tmpFile)
			if (finalFile.exists()) finalFile.delete()
			if (!tmpFile.renameTo(finalFile)) {
				tmpFile.copyTo(finalFile, overwrite = true)
				tmpFile.delete()
			}
			val loadedFromFinal = Typeface.createFromFile(finalFile) ?: baseTf
			val resolved = if (preset.forceBoldStyle) Typeface.create(loadedFromFinal, Typeface.BOLD) else loadedFromFinal
			val key = if (preset.forceBoldStyle) "$fileName#bold" else fileName
			typefaceCache[key] = resolved
			preset.updateDownloadedTypeface(resolved)
			true
		}.onFailure {
			runCatching { tmpFile.delete() }
		}.getOrDefault(false)
	}

	private data class BuiltInSpec(
		val id: String,
		val name: String,
		val fileName: String,
		val role: String = "Comic & Multilingual",
		val isBoldWeight: Boolean = false,
		val uppercase: Boolean = false,
		val letterSpacingPx: Float = 0.6f,
		val forceBoldStyle: Boolean = false,
		val remoteUrl: String? = null,
	)

	private val BUILT_IN_SPECS = listOf(
		// Exact 59 fonts requested by user (in order): 14 compact specialty comic fonts bundled offline + 51 on-demand CDN fonts
		BuiltInSpec("notosans", "NotoSans", "NotoSans.ttf", "Clean Sans", remoteUrl = GF_BASE + "ofl/notosans/NotoSans%5Bwdth%2Cwght%5D.ttf"),
		BuiltInSpec("wild", "WildWords", "WildWords.otf", "Manga Dialogue", isBoldWeight = false, uppercase = true, letterSpacingPx = 1.0f),
		BuiltInSpec("heroika", "Heroika", "Heroika.otf", "Comic Dialogue", uppercase = true, letterSpacingPx = 0.8f),
		BuiltInSpec("shonen", "Shonen", "Shonen.otf", "Shonen Action", uppercase = true, letterSpacingPx = 1.0f),
		BuiltInSpec("badcomic", "BadComic", "BadComic.ttf", "Indie Comic"),
		BuiltInSpec("mashanzheng", "MaShanZheng", "MaShanZheng.ttf", "Brush CJK", remoteUrl = GF_BASE + "ofl/mashanzheng/MaShanZheng-Regular.ttf"),
		BuiltInSpec("komikajam", "KomikaJam", "KomikaJam.ttf", "Comic SFX", uppercase = true, letterSpacingPx = 0.8f),
		BuiltInSpec("bangers", "Bangers", "Bangers.ttf", "Action Impact", uppercase = true, letterSpacingPx = 1.6f),
		BuiltInSpec("edo", "Edo", "Edo.ttf", "Brush Shout", uppercase = true, letterSpacingPx = 1.0f),
		BuiltInSpec("ridibatang", "RIDIBatang", "RIDIBatang.otf", "Serif Novel"),
		BuiltInSpec("bushidoo", "Bushidoo", "Bushidoo.ttf", "Samurai SFX", uppercase = true, letterSpacingPx = 1.2f),
		BuiltInSpec("itim", "Itim", "Itim.ttf", "Casual Handwritten", remoteUrl = GF_BASE + "ofl/itim/Itim-Regular.ttf"),
		BuiltInSpec("mogulirina", "Mogul Irina", "MogulIrina.ttf", "Comic Script", uppercase = true, letterSpacingPx = 0.8f),
		BuiltInSpec("kalam", "Kalam", "Kalam.ttf", "Handwritten", remoteUrl = GF_BASE + "ofl/kalam/Kalam-Bold.ttf"),
		BuiltInSpec("hindsiliguri", "HindSiliguri", "HindSiliguri.ttf", "Bangla & Latin", remoteUrl = GF_BASE + "ofl/hindsiliguri/HindSiliguri-Bold.ttf"),
		BuiltInSpec("shantellsans", "ShantellSans", "ShantellSans.ttf", "Expressive Sans", remoteUrl = GF_BASE + "ofl/shantellsans/ShantellSans%5BBNCE%2CINFM%2CSPAC%2Cwght%5D.ttf"),
		BuiltInSpec("shantellsansinformal", "ShantellSansInformal", "ShantellSansInformal.ttf", "Informal Comic", remoteUrl = GF_BASE + "ofl/shantellsans/ShantellSans%5BBNCE%2CINFM%2CSPAC%2Cwght%5D.ttf"),
		BuiltInSpec("vazirmatn", "Vazirmatn", "Vazirmatn.ttf", "Arabic & Latin", remoteUrl = GF_BASE + "ofl/vazirmatn/Vazirmatn%5Bwght%5D.ttf"),
		BuiltInSpec("pretendard", "Pretendard", "Pretendard.otf", "Manhwa UI Sans", remoteUrl = "https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/packages/pretendard/dist/public/static/Pretendard-SemiBold.otf"),
		BuiltInSpec("zenmarugothic", "ZenMaruGothic", "ZenMaruGothic.ttf", "Rounded Gothic", remoteUrl = GF_BASE + "ofl/zenmarugothic/ZenMaruGothic-Bold.ttf"),
		BuiltInSpec("chillround", "ChillRound", "ChillRound.otf", "Rounded CJK"),
		BuiltInSpec("cairo", "Cairo", "Cairo.ttf", "Geometric Sans", remoteUrl = GF_BASE + "ofl/cairo/Cairo%5Bslnt%2Cwght%5D.ttf"),
		BuiltInSpec("pangolin", "Pangolin", "Pangolin.ttf", "Cartoon Speech", remoteUrl = GF_BASE + "ofl/pangolin/Pangolin-Regular.ttf"),
		BuiltInSpec("mansalva", "Mansalva", "Mansalva.ttf", "Marker Script", remoteUrl = GF_BASE + "ofl/mansalva/Mansalva-Regular.ttf"),
		BuiltInSpec("patrickhand", "PatrickHand", "PatrickHand.ttf", "Hand Lettering", remoteUrl = GF_BASE + "ofl/patrickhand/PatrickHand-Regular.ttf"),
		BuiltInSpec("poetsenone", "PoetsenOne", "PoetsenOne.ttf", "Bold Display", remoteUrl = GF_BASE + "ofl/poetsenone/PoetsenOne-Regular.ttf"),
		BuiltInSpec("balsamiqsans", "BalsamiqSans", "BalsamiqSans.ttf", "Playful Dialogue", remoteUrl = GF_BASE + "ofl/balsamiqsans/BalsamiqSans-Bold.ttf"),
		BuiltInSpec("lilitaone", "LilitaOne", "LilitaOne.ttf", "Heavy Title", remoteUrl = GF_BASE + "ofl/lilitaone/LilitaOne-Regular.ttf"),
		BuiltInSpec("estedad", "Estedad", "Estedad.ttf", "Modern Sans", remoteUrl = GF_BASE + "ofl/estedad/Estedad%5Bwght%5D.ttf"),
		BuiltInSpec("marhey", "Marhey", "Marhey.ttf", "Playful Display", remoteUrl = GF_BASE + "ofl/marhey/Marhey%5Bwght%5D.ttf"),
		BuiltInSpec("gveretlevin", "GveretLevin", "GveretLevin.ttf", "Handwritten Script", remoteUrl = GF_BASE + "ofl/gveretlevin/GveretLevin-Regular.ttf"),
		BuiltInSpec("kodchasan", "Kodchasan", "Kodchasan.ttf", "Rounded Thai & Latin", remoteUrl = GF_BASE + "ofl/kodchasan/Kodchasan-Bold.ttf"),
		BuiltInSpec("poorstory", "PoorStory", "PoorStory.ttf", "Storybook Hangul", remoteUrl = GF_BASE + "ofl/poorstory/PoorStory-Regular.ttf"),
		BuiltInSpec("nanumpenscript", "NanumPenScript", "NanumPenScript.ttf", "Pen Script", remoteUrl = GF_BASE + "ofl/nanumpenscript/NanumPenScript-Regular.ttf"),
		BuiltInSpec("zcoolkuaile", "ZCOOLKuaiLe", "ZCOOLKuaiLe.ttf", "Happy CJK", remoteUrl = GF_BASE + "ofl/zcoolkuaile/ZCOOLKuaiLe-Regular.ttf"),
		BuiltInSpec("chilanka", "Chilanka", "Chilanka.ttf", "Light Handwritten", remoteUrl = GF_BASE + "ofl/chilanka/Chilanka-Regular.ttf"),
		BuiltInSpec("agbalumo", "Agbalumo", "Agbalumo.ttf", "Poster Display", remoteUrl = GF_BASE + "ofl/agbalumo/Agbalumo-Regular.ttf"),
		BuiltInSpec("atma", "Atma", "Atma.ttf", "Bangla Comic", remoteUrl = GF_BASE + "ofl/atma/Atma-Bold.ttf"),
		BuiltInSpec("koulen", "Koulen", "Koulen.ttf", "Condensed Impact", uppercase = true, letterSpacingPx = 1.0f, remoteUrl = GF_BASE + "ofl/koulen/Koulen-Regular.ttf"),
		BuiltInSpec("yatraone", "YatraOne", "YatraOne.ttf", "Devanagari Display", remoteUrl = GF_BASE + "ofl/yatraone/YatraOne-Regular.ttf"),
		BuiltInSpec("akayakanadaka", "AkayaKanadaka", "AkayaKanadaka.ttf", "Comic Kannada", remoteUrl = GF_BASE + "ofl/akayakanadaka/AkayaKanadaka-Regular.ttf"),
		BuiltInSpec("mogra", "Mogra", "Mogra.ttf", "Bold Groovy", remoteUrl = GF_BASE + "ofl/mogra/Mogra-Regular.ttf"),
		BuiltInSpec("anekodia", "AnekOdia", "AnekOdia.ttf", "Odia & Latin", remoteUrl = GF_BASE + "ofl/anekodia/AnekOdia%5Bwdth%2Cwght%5D.ttf"),
		BuiltInSpec("langar", "Langar", "Langar.ttf", "Gurmukhi Display", remoteUrl = GF_BASE + "ofl/langar/Langar-Regular.ttf"),
		BuiltInSpec("kavivanar", "Kavivanar", "Kavivanar.ttf", "Slanted Script", remoteUrl = GF_BASE + "ofl/kavivanar/Kavivanar-Regular.ttf"),
		BuiltInSpec("akayatelivigala", "AkayaTelivigala", "AkayaTelivigala.ttf", "Telugu Comic", remoteUrl = GF_BASE + "ofl/akayatelivigala/AkayaTelivigala-Regular.ttf"),
		BuiltInSpec("yaldevi", "Yaldevi", "Yaldevi.ttf", "Sinhala & Latin", remoteUrl = GF_BASE + "ofl/yaldevicolombo/YaldeviColombo-Bold.ttf"),
		BuiltInSpec("anekmalayalam", "AnekMalayalam", "AnekMalayalam.ttf", "Malayalam & Latin", remoteUrl = GF_BASE + "ofl/anekmalayalam/AnekMalayalam%5Bwdth%2Cwght%5D.ttf"),
		BuiltInSpec("notoserifmyanmar", "NotoSerifMyanmar", "NotoSerifMyanmar.ttf", "Myanmar Serif", remoteUrl = GF_BASE + "ofl/notoserifmyanmar/NotoSerifMyanmar-Bold.ttf"),
		BuiltInSpec("phetsarath", "Phetsarath", "Phetsarath.ttf", "Lao & Latin", remoteUrl = GF_BASE + "ofl/phetsarath/Phetsarath-Bold.ttf"),
		BuiltInSpec("kantumruypro", "KantumruyPro", "KantumruyPro.ttf", "Khmer & Latin", remoteUrl = GF_BASE + "ofl/kantumruypro/KantumruyPro%5Bwght%5D.ttf"),
		BuiltInSpec("eeyek", "Eeyek", "Eeyek.ttf", "Meetei Mayek"),
		BuiltInSpec("gurugomke", "GuruGomke", "GuruGomke.ttf", "Ol Chiki"),
		BuiltInSpec("montserratarmenian", "MontserratArmenian", "MontserratArmenian.ttf", "Armenian Sans"),
		BuiltInSpec("firago", "FiraGO", "FiraGO.ttf", "Global Sans", remoteUrl = "https://raw.githubusercontent.com/bBoxType/FiraGO/master/Fonts/FiraGO_TTF_1001/Roman/FiraGO-SemiBold.ttf"),
		BuiltInSpec("chibi", "Fredoka", "Fredoka.ttf", "Comedy / Gag", isBoldWeight = true, forceBoldStyle = true, remoteUrl = GF_BASE + "ofl/fredoka/Fredoka%5Bwdth%2Cwght%5D.ttf"),
		BuiltInSpec("abyssinicasil", "AbyssinicaSIL", "AbyssinicaSIL.ttf", "Ethiopic Serif", remoteUrl = GF_BASE + "ofl/abyssinicasil/AbyssinicaSIL-Regular.ttf"),
		BuiltInSpec("scheherazadenew", "ScheherazadeNew", "ScheherazadeNew.ttf", "Classic Naskh", remoteUrl = GF_BASE + "ofl/scheherazadenew/ScheherazadeNew-Bold.ttf"),
		BuiltInSpec("iansui", "Iansui", "Iansui.ttf", "Calligraphy CJK", remoteUrl = GF_BASE + "ofl/iansui/Iansui-Regular.ttf"),
		// Additional comic presets (on-demand)
		BuiltInSpec("comicneue", "ComicNeue", "ComicNeue-Bold.ttf", "Classic Comic", isBoldWeight = true, remoteUrl = GF_BASE + "ofl/comicneue/ComicNeue-Bold.ttf"),
		BuiltInSpec("anime", "ArchitectsDaughter", "ArchitectsDaughter-Regular.ttf", "Webtoon Speech", uppercase = true, letterSpacingPx = 1.6f, remoteUrl = GF_BASE + "ofl/architectsdaughter/ArchitectsDaughter-Regular.ttf"),
		BuiltInSpec("brush", "PermanentMarker", "PermanentMarker-Regular.ttf", "Battle Scream", letterSpacingPx = 1.0f, remoteUrl = GF_BASE + "apache/permanentmarker/PermanentMarker-Regular.ttf"),
		BuiltInSpec("komika", "LuckiestGuy", "LuckiestGuy-Regular.ttf", "Bold Cover", uppercase = true, letterSpacingPx = 1.6f, remoteUrl = GF_BASE + "apache/luckiestguy/LuckiestGuy-Regular.ttf"),
		BuiltInSpec("horror", "Creepster", "Creepster-Regular.ttf", "Dark / Dread", uppercase = true, letterSpacingPx = 2.0f, remoteUrl = GF_BASE + "ofl/creepster/Creepster-Regular.ttf"),
		BuiltInSpec("system", "PlusJakartaSans", "PlusJakartaSans-ExtraBold.ttf", "System UI Box", isBoldWeight = true, forceBoldStyle = true, remoteUrl = GF_BASE + "ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf"),
	)

	fun createBuiltInPresets(context: Context): List<MangaFontPreset> {
		val appCtx = context.applicationContext
		return BUILT_IN_SPECS.map { spec ->
			val fallback = if (spec.isBoldWeight) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
			MangaFontPreset(
				id = spec.id,
				name = spec.name,
				role = spec.role,
				typefaceLoader = { loadResolvedFont(appCtx, spec, fallback) },
				offlineCheck = { isBundledOrCached(appCtx, spec) },
				remoteUrl = spec.remoteUrl,
				cacheFileName = spec.fileName,
				forceBoldStyle = spec.forceBoldStyle,
				isBoldWeight = spec.isBoldWeight,
				uppercase = spec.uppercase,
				letterSpacingPx = spec.letterSpacingPx,
			)
		}
	}
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
