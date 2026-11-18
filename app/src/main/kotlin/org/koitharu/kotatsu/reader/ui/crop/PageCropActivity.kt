package org.koitharu.kotatsu.reader.ui.crop

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Material 3 Expressive Screenshot / Manga Page Editor:
 * - 5-Tool Dock: Perspective Crop, Lasso Select, Draw Brush, 3-Mode Eraser, 3-Mode Perspective Text
 * - Grouped Undo / Redo / Reset Capsule + Primary Save Pill + High-Res Export Modal
 * - Continuous M3 Split-Track Slider + Live Centered Brush Ring + 88dp 2.5x Corner Magnifier Loupe
 */
class PageCropActivity : AppCompatActivity() {

	private val dp: Float by lazy { resources.displayMetrics.density }

	private lateinit var cropView: CropDrawView
	private lateinit var settings: AppSettings

	// Top Bar Views
	private lateinit var topAppBar: LinearLayout
	private lateinit var topBarTitle: TextView
	private lateinit var buttonUndo: ImageButton
	private lateinit var buttonRedo: ImageButton
	private lateinit var buttonReset: ImageButton
	private lateinit var buttonSave: LinearLayout

	// Canvas Overlays
	private lateinit var brushPreviewContainer: LinearLayout
	private lateinit var brushPreviewRing: FrameLayout
	private lateinit var brushPreviewCenterDot: View
	private lateinit var brushPreviewLabel: TextView
	private lateinit var textContextMenu: LinearLayout
	private lateinit var ctxModeBadge: TextView
	private lateinit var snackbarBanner: LinearLayout
	private lateinit var snackbarText: TextView
	private lateinit var snackbarActionBtn: TextView
	private var snackbarJob: Job? = null

	// Bottom Control Sheet Views
	private lateinit var bottomSheet: LinearLayout
	private lateinit var subOptionsBar: LinearLayout
	private lateinit var sliderLabel: TextView
	private lateinit var m3Slider: M3ExpressiveSliderView
	private lateinit var sliderValueBadge: TextView

	private lateinit var toolDockButtons: Map<EditorTool, Triple<LinearLayout, ImageView, TextView>>

	// Perspective Text Dialog Views
	private lateinit var textInputOverlay: FrameLayout
	private lateinit var textSheetTitle: TextView
	private lateinit var canvasTextInput: EditText
	private lateinit var btnClearTextInput: ImageButton
	private lateinit var btnCustomFontToggle: LinearLayout
	private lateinit var iconCustomFontToggle: ImageView
	private lateinit var labelCustomFontToggle: TextView
	private lateinit var customFontDrawer: LinearLayout
	private lateinit var customFontNameInput: EditText
	private lateinit var btnCustomFontCaps: TextView
	private lateinit var btnCustomFontLoad: TextView
	private lateinit var fontSearchInput: EditText
	private lateinit var fontPresetsScroll: ScrollView
	private lateinit var textFontPresetsContainer: LinearLayout
	private lateinit var textColorSwatchesRow: LinearLayout
	private lateinit var btnDeleteTextSheet: LinearLayout
	private lateinit var deleteTextSheetLabel: TextView

	// Export Modal Views
	private lateinit var saveModalOverlay: FrameLayout
	private lateinit var exportMetaText: TextView
	private lateinit var exportPreviewImg: ImageView
	private lateinit var exportCropModeRow: LinearLayout
	private lateinit var btnExpModeCutout: TextView
	private lateinit var btnExpModeUnwarp: TextView
	private lateinit var btnFmtPng: TextView
	private lateinit var btnFmtJpg: TextView
	private var previewBitmap: Bitmap? = null

	// Active Text Editor State
	private var activeTextFontId: String = "wild"
	private var activeTextColor: Int = EditorColors.TEXT_COLORS[1] // #111318 Manga Ink Black default
	private var customFontCaps: Boolean = false
	private var editingTextId: String? = null
	private var pendingTextTapPos: NormPt? = null
	private var isUpdatingInputCase: Boolean = false
	private var fontSearchQuery: String = ""
	private val downloadingFontIds = HashSet<String>()

	// Export State
	private var isExportPng: Boolean = true

	private val fontImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) {
			importCustomFontUri(uri)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		configureSystemBars()
		setContentView(R.layout.activity_page_crop)

		val path = intent.getStringExtra(EXTRA_FILE)
		val sourceUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
		} else {
			@Suppress("DEPRECATION")
			intent.getParcelableExtra(EXTRA_URI)
		}
		if (path.isNullOrEmpty() && sourceUri == null) {
			finish()
			return
		}

		settings = AppSettings(this)
		isExportPng = settings.isEditorExportPng

		bindViews()
		applyStaticM3Backgrounds()
		applyWindowInsets()
		loadPersistedCustomFonts()
		setupInteractions()

		// Restore saved brush preferences if valid
		val savedColor = settings.editorStrokeColor
		if (EditorColors.INK_COLORS.contains(savedColor)) {
			cropView.drawColor = savedColor
		}
		cropView.drawSize = settings.editorStrokeWidth.roundToInt().coerceIn(2, 64)

		// Default active tool matches HTML prototype ('draw' with all 5 tools in dock)
		selectTool(EditorTool.DRAW)
		updateHistoryButtons(canUndo = false, canRedo = false, canReset = false)

		onBackPressedDispatcher.addCallback(
			this,
			object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() {
					when {
						saveModalOverlay.isVisible -> closeSaveModal()
						textInputOverlay.isVisible -> closeTextOverlay()
						textContextMenu.isVisible -> hideTextContextMenu()
						else -> finish()
					}
				}
			},
		)

		lifecycleScope.launch {
			val bitmap = withContext(Dispatchers.IO) {
				runCatching {
					if (path != null) decodeFile(File(path), sourceUri) else decodeUri(sourceUri!!)
				}
					.onFailure { it.printStackTraceDebug() }
					.getOrNull()
			}
			if (isDestroyed || bitmap == null) {
				Toast.makeText(this@PageCropActivity, R.string.error, Toast.LENGTH_SHORT).show()
				finish()
				return@launch
			}
			cropView.setBitmap(bitmap)
		}
	}

	@Suppress("DEPRECATION")
	private fun configureSystemBars() {
		WindowCompat.setDecorFitsSystemWindows(window, false)
		window.statusBarColor = EditorColors.SURFACE
		window.navigationBarColor = EditorColors.SURFACE_CONTAINER
		val controller = WindowCompat.getInsetsController(window, window.decorView)
		controller.isAppearanceLightStatusBars = false
		controller.isAppearanceLightNavigationBars = false
	}

	private fun applyWindowInsets() {
		val baseTopPadStart = (8f * dp).roundToInt()
		val baseTopPadEnd = (12f * dp).roundToInt()
		val baseBottomPadH = (12f * dp).roundToInt()
		val baseBottomPadTop = (12f * dp).roundToInt()
		val baseBottomPadBottom = (10f * dp).roundToInt()
		val pad16 = (16f * dp).roundToInt()
		val pad8 = (8f * dp).roundToInt()

		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.editorRoot)) { _, insets ->
			val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
			topAppBar.setPadding(baseTopPadStart, sys.top, baseTopPadEnd, 0)
			bottomSheet.setPadding(
				baseBottomPadH,
				baseBottomPadTop,
				baseBottomPadH,
				baseBottomPadBottom + sys.bottom,
			)
			textInputOverlay.setPadding(
				pad16,
				sys.top + pad8,
				pad16,
				max(sys.bottom + pad16, ime.bottom + pad8),
			)
			insets
		}
	}

	private fun bindViews() {
		cropView = findViewById(R.id.cropView)
		topAppBar = findViewById(R.id.topAppBar)
		topBarTitle = findViewById(R.id.topBarTitle)
		buttonUndo = findViewById(R.id.button_undo)
		buttonRedo = findViewById(R.id.button_redo)
		buttonReset = findViewById(R.id.button_reset)
		buttonSave = findViewById(R.id.button_save)

		brushPreviewContainer = findViewById(R.id.brushPreviewContainer)
		brushPreviewRing = findViewById(R.id.brushPreviewRing)
		brushPreviewCenterDot = findViewById(R.id.brushPreviewCenterDot)
		brushPreviewLabel = findViewById(R.id.brushPreviewLabel)

		textContextMenu = findViewById(R.id.textContextMenu)
		ctxModeBadge = findViewById(R.id.ctxModeBadge)

		snackbarBanner = findViewById(R.id.snackbarBanner)
		snackbarText = findViewById(R.id.snackbarText)
		snackbarActionBtn = findViewById(R.id.snackbarActionBtn)

		bottomSheet = findViewById(R.id.bottomSheet)
		subOptionsBar = findViewById(R.id.subOptionsBar)
		sliderLabel = findViewById(R.id.sliderLabel)
		m3Slider = findViewById(R.id.m3Slider)
		sliderValueBadge = findViewById(R.id.sliderValueBadge)

		toolDockButtons = mapOf(
			EditorTool.CROP to Triple(
				findViewById(R.id.button_mode_crop),
				findViewById(R.id.icon_mode_crop),
				findViewById(R.id.label_mode_crop),
			),
			EditorTool.LASSO to Triple(
				findViewById(R.id.button_mode_lasso),
				findViewById(R.id.icon_mode_lasso),
				findViewById(R.id.label_mode_lasso),
			),
			EditorTool.DRAW to Triple(
				findViewById(R.id.button_mode_draw),
				findViewById(R.id.icon_mode_draw),
				findViewById(R.id.label_mode_draw),
			),
			EditorTool.ERASER to Triple(
				findViewById(R.id.button_mode_erase),
				findViewById(R.id.icon_mode_erase),
				findViewById(R.id.label_mode_erase),
			),
			EditorTool.TEXT to Triple(
				findViewById(R.id.button_mode_text),
				findViewById(R.id.icon_mode_text),
				findViewById(R.id.label_mode_text),
			),
		)

		textInputOverlay = findViewById(R.id.textInputOverlay)
		textSheetTitle = findViewById(R.id.textSheetTitle)
		canvasTextInput = findViewById(R.id.canvasTextInput)
		btnClearTextInput = findViewById(R.id.btnClearTextInput)
		btnCustomFontToggle = findViewById(R.id.btnCustomFontToggle)
		iconCustomFontToggle = findViewById(R.id.iconCustomFontToggle)
		labelCustomFontToggle = findViewById(R.id.labelCustomFontToggle)
		customFontDrawer = findViewById(R.id.customFontDrawer)
		customFontNameInput = findViewById(R.id.customFontNameInput)
		btnCustomFontCaps = findViewById(R.id.btnCustomFontCaps)
		btnCustomFontLoad = findViewById(R.id.btnCustomFontLoad)
		fontSearchInput = findViewById(R.id.fontSearchInput)
		fontPresetsScroll = findViewById(R.id.fontPresetsScroll)
		textFontPresetsContainer = findViewById(R.id.textFontPresetsContainer)
		textColorSwatchesRow = findViewById(R.id.textColorSwatchesRow)
		btnDeleteTextSheet = findViewById(R.id.btnDeleteTextSheet)
		deleteTextSheetLabel = findViewById(R.id.deleteTextSheetLabel)

		saveModalOverlay = findViewById(R.id.saveModalOverlay)
		exportMetaText = findViewById(R.id.exportMetaText)
		exportPreviewImg = findViewById(R.id.exportPreviewImg)
		exportCropModeRow = findViewById(R.id.exportCropModeRow)
		btnExpModeCutout = findViewById(R.id.btnExpModeCutout)
		btnExpModeUnwarp = findViewById(R.id.btnExpModeUnwarp)
		btnFmtPng = findViewById(R.id.btnFmtPng)
		btnFmtJpg = findViewById(R.id.btnFmtJpg)
	}

	private fun roundedShape(
		fillColor: Int,
		radiusDp: Float,
		strokeWidthDp: Float = 0f,
		strokeColor: Int = Color.TRANSPARENT,
		dashed: Boolean = false,
	): GradientDrawable = GradientDrawable().apply {
		shape = GradientDrawable.RECTANGLE
		cornerRadius = radiusDp * dp
		setColor(fillColor)
		if (strokeWidthDp > 0f) {
			if (dashed) {
				setStroke(
					max(1, (strokeWidthDp * dp).roundToInt()),
					strokeColor,
					4f * dp,
					3f * dp,
				)
			} else {
				setStroke(max(1, (strokeWidthDp * dp).roundToInt()), strokeColor)
			}
		}
	}

	private fun rippleShape(
		fillColor: Int,
		radiusDp: Float,
		strokeWidthDp: Float = 0f,
		strokeColor: Int = Color.TRANSPARENT,
		dashed: Boolean = false,
	): Drawable {
		val content = roundedShape(fillColor, radiusDp, strokeWidthDp, strokeColor, dashed)
		val mask = roundedShape(Color.WHITE, radiusDp)
		return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
	}

	private fun applyStaticM3Backgrounds() {
		findViewById<View>(R.id.capsuleHistory).background =
			roundedShape(EditorColors.SURFACE_CONTAINER_HIGH, 999f, 1f, EditorColors.OUTLINE_VARIANT)
		buttonSave.background = rippleShape(EditorColors.PRIMARY, 999f)

		brushPreviewCenterDot.background = roundedShape(0xE6FFFFFF.toInt(), 999f)
		brushPreviewLabel.background =
			roundedShape(EditorColors.SURFACE_CONTAINER_HIGHEST, 999f, 1f, EditorColors.OUTLINE_VARIANT)

		textContextMenu.background =
			roundedShape(EditorColors.SURFACE_CONTAINER_HIGH, 20f, 1f, EditorColors.OUTLINE_VARIANT)
		ctxModeBadge.background = roundedShape(EditorColors.SECONDARY_CONTAINER, 6f)

		snackbarBanner.background = roundedShape(EditorColors.INVERSE_SURFACE, 16f)

		findViewById<View>(R.id.sliderCard).background = roundedShape(EditorColors.SURFACE_CONTAINER_HIGH, 20f)
		sliderValueBadge.background = roundedShape(EditorColors.SURFACE_CONTAINER, 999f)

		findViewById<View>(R.id.textDialogCard).background =
			roundedShape(EditorColors.SURFACE_CONTAINER_HIGH, 28f, 1f, EditorColors.OUTLINE_VARIANT)
		findViewById<View>(R.id.textInputFieldBox).background =
			roundedShape(EditorColors.SURFACE_CONTAINER_LOWEST, 16f, 1.5f, EditorColors.PRIMARY)
		customFontDrawer.background = roundedShape(EditorColors.SURFACE_CONTAINER, 16f)
		findViewById<View>(R.id.customFontInputBox).background =
			roundedShape(EditorColors.SURFACE_CONTAINER_LOWEST, 12f)
		findViewById<View>(R.id.fontSearchBox).background =
			roundedShape(EditorColors.SURFACE_CONTAINER_LOWEST, 12f, 1f, EditorColors.OUTLINE_VARIANT)
		btnCustomFontLoad.background = rippleShape(EditorColors.PRIMARY, 8f)
		findViewById<View>(R.id.btnUploadFontFile).background =
			rippleShape(EditorColors.SURFACE_CONTAINER_HIGH, 12f)
		btnDeleteTextSheet.background = rippleShape(EditorColors.ERROR_CONTAINER_SOFT, 999f)
		findViewById<View>(R.id.btnApplyTextSheet).background = rippleShape(EditorColors.PRIMARY, 999f)

		findViewById<View>(R.id.saveModalCard).background =
			roundedShape(EditorColors.SURFACE_CONTAINER_HIGH, 28f, 1f, EditorColors.OUTLINE_VARIANT)
		findViewById<View>(R.id.exportPreviewContainer).background = CheckerboardDrawable(
			tileSizePx = (8f * dp).roundToInt(),
			cornerRadiusPx = 16f * dp,
			borderColor = EditorColors.OUTLINE_VARIANT,
			borderWidthPx = 1f * dp,
			colorDark = EditorColors.SURFACE_DIM,
			colorLight = 0xFF171C26.toInt(),
		)
		exportCropModeRow.background = roundedShape(EditorColors.SURFACE_CONTAINER, 16f)
		findViewById<View>(R.id.exportFormatRow).background = roundedShape(EditorColors.SURFACE_CONTAINER, 16f)
		findViewById<View>(R.id.btnConfirmSave).background = rippleShape(EditorColors.PRIMARY, 999f)

		syncCustomFontToggleUI(false)
		syncCustomFontCapsUI()
	}

	private fun setupInteractions() {
		findViewById<View>(R.id.button_back).setOnClickListener { finish() }
		buttonUndo.setOnClickListener {
			cropView.undo()
			syncSliderConfig()
		}
		buttonRedo.setOnClickListener {
			cropView.redo()
			syncSliderConfig()
		}
		buttonReset.setOnClickListener {
			cropView.resetAllEdits()
			syncSliderConfig()
		}
		buttonSave.setOnClickListener { openSaveModal() }

		for ((tool, views) in toolDockButtons) {
			views.first.setOnClickListener { selectTool(tool) }
		}

		// CropDrawView callbacks
		cropView.onHistoryChanged = { canUndo, canRedo, canReset ->
			updateHistoryButtons(canUndo, canRedo, canReset)
		}
		cropView.onSubOptionsChanged = {
			renderSubOptions()
		}
		cropView.onSnackbarMessage = { msg ->
			showEditorSnackbar(msg)
		}
		cropView.onRequestAddOrEditText = { existing, tapPos ->
			pendingTextTapPos = tapPos
			openTextOverlay(existing)
		}
		cropView.onRequestTextContextMenu = { _, left, top ->
			showTextContextMenuAt(left, top)
		}
		cropView.onDismissOverlays = {
			hideTextContextMenu()
		}

		// M3 Expressive Slider wiring
		m3Slider.onDragStart = {
			hideTextContextMenu()
			if (cropView.activeTool == EditorTool.TEXT) {
				m3Slider.setValue(100, notify = false)
				cropView.beginTextScaleSlider()
			}
		}
		m3Slider.onValueChanged = { rawVal, fromUser ->
			val adjVal = if (cropView.activeTool == EditorTool.CROP && abs(rawVal) <= 1) 0 else rawVal
			sliderValueBadge.text = m3Slider.valueFormatter(adjVal)
			if (fromUser) {
				when (cropView.activeTool) {
					EditorTool.DRAW -> {
						cropView.drawSize = adjVal
						settings.editorStrokeWidth = adjVal.toFloat()
						showBrushRingPreview(adjVal, "draw", cropView.drawColor)
					}
					EditorTool.ERASER -> {
						cropView.eraserSize = adjVal
						val previewType = if (cropView.eraserMode == EraserMode.RESTORE) "restore" else "erase"
						showBrushRingPreview(adjVal, previewType, Color.WHITE)
					}
					EditorTool.CROP -> {
						cropView.setStraightenDegrees(adjVal, commitHistory = false)
					}
					EditorTool.LASSO -> {
						cropView.lassoFeather = adjVal
					}
					EditorTool.TEXT -> {
						cropView.updateTextScaleSlider(adjVal)
					}
				}
			}
		}
		m3Slider.onDragEnd = {
			hideBrushRingPreview()
			when (cropView.activeTool) {
				EditorTool.CROP -> {
					cropView.setStraightenDegrees(cropView.doc.straighten, commitHistory = true)
				}
				EditorTool.TEXT -> {
					cropView.endTextScaleSlider()
					m3Slider.setValue(100, notify = false)
					sliderValueBadge.text = "100%"
				}
				else -> Unit
			}
		}

		// Context Menu buttons
		findViewById<View>(R.id.ctxBtnEdit).setOnClickListener {
			hideTextContextMenu()
			val sel = cropView.doc.texts.firstOrNull { it.id == cropView.selectedTextId }
			if (sel != null) openTextOverlay(sel)
		}
		findViewById<View>(R.id.ctxBtnNextMode).setOnClickListener {
			hideTextContextMenu()
			cropView.cycleTextTransformMode()
		}
		findViewById<View>(R.id.ctxBtnResetQuad).setOnClickListener {
			hideTextContextMenu()
			cropView.resetSelectedTextQuad()
		}
		findViewById<View>(R.id.ctxBtnDuplicate).setOnClickListener {
			hideTextContextMenu()
			cropView.duplicateSelectedText()
		}
		findViewById<View>(R.id.ctxBtnDelete).setOnClickListener {
			hideTextContextMenu()
			cropView.removeSelectedText()
		}

		// Perspective Text Dialog interactions
		textInputOverlay.setOnClickListener { closeTextOverlay() }
		findViewById<View>(R.id.textDialogCard).setOnClickListener { /* consume */ }
		findViewById<View>(R.id.btnCloseTextOverlay).setOnClickListener { closeTextOverlay() }
		findViewById<View>(R.id.btnCancelTextSheet).setOnClickListener { closeTextOverlay() }
		findViewById<View>(R.id.btnApplyTextSheet).setOnClickListener { commitTextOverlay() }
		btnDeleteTextSheet.setOnClickListener { removeTextFromSheet() }
		btnClearTextInput.setOnClickListener {
			canvasTextInput.setText("")
			canvasTextInput.requestFocus()
		}

		canvasTextInput.addTextChangedListener(
			object : TextWatcher {
				override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
				override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
				override fun afterTextChanged(s: Editable?) {
					btnClearTextInput.isVisible = !s.isNullOrEmpty()
				}
			},
		)
		canvasTextInput.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				commitTextOverlay()
				true
			} else {
				false
			}
		}

		btnCustomFontToggle.setOnClickListener {
			toggleCustomFontDrawer(!customFontDrawer.isVisible)
		}
		btnCustomFontCaps.setOnClickListener {
			customFontCaps = !customFontCaps
			syncCustomFontCapsUI()
		}
		btnCustomFontLoad.setOnClickListener {
			addCustomSystemFontByName()
		}
		customFontNameInput.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				addCustomSystemFontByName()
				true
			} else {
				false
			}
		}
		findViewById<View>(R.id.btnUploadFontFile).setOnClickListener {
			fontImportLauncher.launch(
				arrayOf(
					"font/ttf",
					"font/otf",
					"application/x-font-ttf",
					"application/x-font-otf",
					"application/octet-stream",
				),
			)
		}

		@Suppress("ClickableViewAccessibility")
		fontPresetsScroll.setOnTouchListener { v, _ ->
			v.parent?.requestDisallowInterceptTouchEvent(true)
			false
		}

		fontSearchInput.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
			override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
			override fun afterTextChanged(s: Editable?) {
				val nextQuery = s?.toString()?.trim().orEmpty()
				if (fontSearchQuery != nextQuery) {
					fontSearchQuery = nextQuery
					renderTextFontPresetsGrid(scrollToActive = false)
				}
			}
		})

		// Save & Export Modal interactions
		saveModalOverlay.setOnClickListener { closeSaveModal() }
		findViewById<View>(R.id.saveModalCard).setOnClickListener { /* consume */ }
		findViewById<View>(R.id.btnCloseSaveModal).setOnClickListener { closeSaveModal() }
		findViewById<View>(R.id.btnCancelSaveModal).setOnClickListener { closeSaveModal() }
		btnExpModeCutout.setOnClickListener {
			cropView.setCropExecMode(CropExecMode.CUTOUT)
			if (cropView.isAngledQuadActive()) isExportPng = true
			refreshSaveModalPreview()
		}
		btnExpModeUnwarp.setOnClickListener {
			cropView.setCropExecMode(CropExecMode.UNWARP)
			refreshSaveModalPreview()
		}
		btnFmtPng.setOnClickListener {
			isExportPng = true
			settings.isEditorExportPng = true
			refreshSaveModalPreview()
		}
		btnFmtJpg.setOnClickListener {
			isExportPng = false
			settings.isEditorExportPng = false
			refreshSaveModalPreview()
		}
		findViewById<View>(R.id.btnConfirmSave).setOnClickListener {
			confirmAndSaveImage()
		}
	}

	private fun updateHistoryButtons(canUndo: Boolean, canRedo: Boolean, canReset: Boolean) {
		buttonUndo.isEnabled = canUndo
		buttonUndo.alpha = if (canUndo) 1f else 0.35f
		buttonRedo.isEnabled = canRedo
		buttonRedo.alpha = if (canRedo) 1f else 0.35f
		buttonReset.isEnabled = canReset
		buttonReset.alpha = if (canReset) 1f else 0.35f
	}

	// =========================================================================
	// TOOL SWITCHER & CONTEXTUAL SUB-OPTIONS BAR
	// =========================================================================

	private fun selectTool(tool: EditorTool) {
		cropView.activeTool = tool
		hideTextContextMenu()
		hideBrushRingPreview()
		topBarTitle.text = tool.title

		for ((t, views) in toolDockButtons) {
			val active = (t == tool)
			views.first.background = if (active) {
				rippleShape(EditorColors.SECONDARY_CONTAINER, 999f)
			} else {
				rippleShape(EditorColors.SURFACE_CONTAINER_HIGH, 16f)
			}
			val contentColor = if (active) EditorColors.ON_SECONDARY_CONTAINER else EditorColors.ON_SURFACE_VARIANT
			ImageViewCompat.setImageTintList(views.second, ColorStateList.valueOf(contentColor))
			views.third.setTextColor(contentColor)
		}

		renderSubOptions()
		syncSliderConfig()
	}

	private fun syncSliderConfig() {
		when (cropView.activeTool) {
			EditorTool.DRAW -> {
				sliderLabel.text = "Brush size"
				val fmt: (Int) -> String = { "$it px" }
				m3Slider.configure(2, 64, cropView.drawSize, fmt)
				sliderValueBadge.text = fmt(cropView.drawSize)
			}
			EditorTool.ERASER -> {
				sliderLabel.text = "Eraser"
				val fmt: (Int) -> String = { "$it px" }
				m3Slider.configure(4, 80, cropView.eraserSize, fmt)
				sliderValueBadge.text = fmt(cropView.eraserSize)
			}
			EditorTool.CROP -> {
				sliderLabel.text = "Straighten"
				val fmt: (Int) -> String = { v -> if (v > 0) "+$v°" else "$v°" }
				m3Slider.configure(-25, 25, cropView.doc.straighten, fmt)
				sliderValueBadge.text = fmt(cropView.doc.straighten)
			}
			EditorTool.LASSO -> {
				sliderLabel.text = "Feather"
				val fmt: (Int) -> String = { "$it px" }
				m3Slider.configure(0, 32, cropView.lassoFeather, fmt)
				sliderValueBadge.text = fmt(cropView.lassoFeather)
			}
			EditorTool.TEXT -> {
				sliderLabel.text = "Scale"
				val fmt: (Int) -> String = { "$it%" }
				m3Slider.configure(45, 185, 100, fmt)
				sliderValueBadge.text = "100%"
			}
		}
	}

	private fun renderSubOptions() {
		subOptionsBar.removeAllViews()
		when (cropView.activeTool) {
			EditorTool.ERASER -> renderEraserSubOptions()
			EditorTool.DRAW -> renderDrawSubOptions()
			EditorTool.CROP -> renderCropSubOptions()
			EditorTool.LASSO -> renderLassoSubOptions()
			EditorTool.TEXT -> renderTextSubOptions()
		}
	}

	private fun createChipButton(
		label: String,
		active: Boolean = false,
		iconRes: Int = 0,
		bgColor: Int = if (active) EditorColors.PRIMARY_CONTAINER else EditorColors.SURFACE_CONTAINER_HIGH,
		fgColor: Int = if (active) EditorColors.ON_PRIMARY_CONTAINER else EditorColors.ON_SURFACE,
		borderDp: Float = 0f,
		borderColor: Int = Color.TRANSPARENT,
		dashedBorder: Boolean = false,
		customTypeface: Typeface? = null,
		onClick: () -> Unit,
	): LinearLayout = LinearLayout(this).apply {
		orientation = LinearLayout.HORIZONTAL
		gravity = Gravity.CENTER
		val padH = (12f * dp).roundToInt()
		val padV = (6f * dp).roundToInt()
		setPadding(padH, padV, padH, padV)
		minimumHeight = (32f * dp).roundToInt()
		background = rippleShape(bgColor, 999f, borderDp, borderColor, dashedBorder)
		isClickable = true
		isFocusable = true
		setOnClickListener { onClick() }

		if (iconRes != 0) {
			val iv = ImageView(this@PageCropActivity).apply {
				setImageResource(iconRes)
				ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(fgColor))
			}
			addView(iv, LinearLayout.LayoutParams((14f * dp).roundToInt(), (14f * dp).roundToInt()).apply {
				marginEnd = (5f * dp).roundToInt()
			})
		}

		val tv = TextView(this@PageCropActivity).apply {
			text = label
			setTextColor(fgColor)
			textSize = 12f
			typeface = customTypeface ?: Typeface.DEFAULT_BOLD
			isSingleLine = true
		}
		addView(tv)
	}

	private fun addSubOptionView(v: View, marginEndDp: Float = 6f) {
		val lp = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		).apply {
			marginEnd = (marginEndDp * dp).roundToInt()
		}
		subOptionsBar.addView(v, lp)
	}

	private fun addVerticalDivider() {
		val div = View(this).apply {
			setBackgroundColor(EditorColors.OUTLINE_VARIANT)
		}
		val lp = LinearLayout.LayoutParams((1f * dp).roundToInt().coerceAtLeast(1), (16f * dp).roundToInt()).apply {
			marginStart = (2f * dp).roundToInt()
			marginEnd = (8f * dp).roundToInt()
		}
		subOptionsBar.addView(div, lp)
	}

	private fun renderEraserSubOptions() {
		val row = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
		}
		val modes = listOf(
			Triple(EraserMode.BRUSH, "Brush", R.drawable.ic_eraser),
			Triple(EraserMode.OBJECT, "Object", R.drawable.ic_editor_object_erase),
			Triple(EraserMode.RESTORE, "Restore", R.drawable.ic_editor_restore),
		)
		modes.forEachIndexed { idx, (mode, label, icon) ->
			val active = cropView.eraserMode == mode
			val btn = createChipButton(
				label = label,
				active = active,
				iconRes = icon,
			) {
				cropView.eraserMode = mode
				renderSubOptions()
				val msg = when (mode) {
					EraserMode.OBJECT -> "Object Eraser: tap a stroke, text, or UI element to remove it"
					EraserMode.RESTORE -> "Restore Brush: paint original pixels back"
					EraserMode.BRUSH -> "Brush Eraser active"
				}
				showEditorSnackbar(msg)
			}
			val lp = LinearLayout.LayoutParams(0, (34f * dp).roundToInt(), 1f).apply {
				if (idx < modes.lastIndex) marginEnd = (8f * dp).roundToInt()
			}
			row.addView(btn, lp)
		}
		subOptionsBar.addView(
			row,
			LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
		)
	}

	private fun renderDrawSubOptions() {
		val row = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding((6f * dp).roundToInt(), (2f * dp).roundToInt(), (6f * dp).roundToInt(), (2f * dp).roundToInt())
		}
		EditorColors.INK_COLORS.forEachIndexed { idx, color ->
			val active = cropView.drawColor == color
			val holder = FrameLayout(this)
			val swatch = buildColorSwatchView(
				color = color,
				active = active,
				sizeDp = 28f,
				ringGapColor = EditorColors.SURFACE_CONTAINER,
			) {
				cropView.drawColor = color
				settings.editorStrokeColor = color
				renderSubOptions()
			}
			holder.addView(
				swatch,
				FrameLayout.LayoutParams((28f * dp).roundToInt(), (28f * dp).roundToInt(), Gravity.CENTER),
			)
			row.addView(
				holder,
				LinearLayout.LayoutParams(0, (32f * dp).roundToInt(), 1f).apply {
					if (idx < EditorColors.INK_COLORS.lastIndex) marginEnd = (4f * dp).roundToInt()
				},
			)
		}
		subOptionsBar.addView(
			row,
			LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
		)
	}

	private fun buildColorSwatchView(
		color: Int,
		active: Boolean,
		sizeDp: Float,
		ringGapColor: Int,
		onClick: () -> Unit,
	): FrameLayout = FrameLayout(this).apply {
		isClickable = true
		isFocusable = true
		setOnClickListener { onClick() }

		if (active) {
			val outerRing = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(EditorColors.PRIMARY)
			}
			val gapRing = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(ringGapColor)
			}
			val innerFill = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(color)
			}
			val ld = LayerDrawable(arrayOf(outerRing, gapRing, innerFill))
			val r1 = (1.6f * dp).roundToInt().coerceAtLeast(1)
			val r2 = (3.4f * dp).roundToInt().coerceAtLeast(2)
			ld.setLayerInset(1, r1, r1, r1, r1)
			ld.setLayerInset(2, r2, r2, r2, r2)
			background = ld

			val dot = View(this@PageCropActivity).apply {
				val dotColor = if (EditorColors.isLightSwatch(color)) 0xFF111318.toInt() else Color.WHITE
				background = GradientDrawable().apply {
					shape = GradientDrawable.OVAL
					setColor(dotColor)
				}
			}
			val dotSz = (7f * dp).roundToInt()
			addView(dot, FrameLayout.LayoutParams(dotSz, dotSz, Gravity.CENTER))
			scaleX = 1.06f
			scaleY = 1.06f
		} else {
			background = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(color)
				setStroke(max(1, (1f * dp).roundToInt()), 0x3DFFFFFF)
			}
			alpha = 0.88f
		}
	}

	private fun renderCropSubOptions() {
		addSubOptionView(
			createChipButton(
				label = "90°",
				iconRes = R.drawable.ic_editor_rotate_90,
			) {
				cropView.rotate90Clockwise()
			},
		)
		addVerticalDivider()

		val aspects = listOf("free" to "Free", "1:1" to "1:1", "9:16" to "9:16", "16:9" to "16:9", "4:3" to "4:3")
		val curAspect = cropView.doc.cropAspect
		for ((id, label) in aspects) {
			val active = curAspect == id
			addSubOptionView(
				createChipButton(
					label = label,
					active = active,
					fgColor = if (active) EditorColors.ON_PRIMARY_CONTAINER else EditorColors.ON_SURFACE_VARIANT,
				) {
					cropView.applyCropQuadPreset(id)
				},
			)
		}
		addVerticalDivider()

		val isCutout = cropView.doc.cropMode == CropExecMode.CUTOUT
		addSubOptionView(
			createChipButton(
				label = "Exact Shape",
				active = isCutout,
				fgColor = if (isCutout) EditorColors.ON_PRIMARY_CONTAINER else EditorColors.ON_SURFACE_VARIANT,
			) {
				cropView.toggleCropExecMode()
			},
		)
		addSubOptionView(
			createChipButton(
				label = "Reset",
				active = false,
			) {
				cropView.applyCropQuadPreset("reset")
			},
			marginEndDp = 0f,
		)
	}

	private fun renderLassoSubOptions() {
		val hasLoop = cropView.activeLassoPts.size >= 5
		if (hasLoop) {
			addSubOptionView(
				createChipButton("Spotlight", active = true) {
					cropView.applyLassoEffect(LassoEffectMode.SPOTLIGHT)
				},
			)
			addSubOptionView(
				createChipButton("Highlight", active = false) {
					cropView.applyLassoEffect(LassoEffectMode.TINT)
				},
			)
			addSubOptionView(
				createChipButton("Redact", active = false) {
					cropView.applyLassoEffect(LassoEffectMode.REDACT)
				},
			)
			addSubOptionView(
				createChipButton(
					label = "Cancel",
					bgColor = Color.TRANSPARENT,
					fgColor = EditorColors.ON_SURFACE_VARIANT,
				) {
					cropView.clearLassoSelection()
				},
				marginEndDp = 0f,
			)
		} else {
			val hint = TextView(this).apply {
				text = "Draw a loop on image to Spotlight, Highlight, or Redact"
				setTextColor(EditorColors.ON_SURFACE_VARIANT)
				textSize = 11.5f
				val ph = (12f * dp).roundToInt()
				val pv = (6f * dp).roundToInt()
				setPadding(ph, pv, ph, pv)
				background = roundedShape(EditorColors.SURFACE_CONTAINER_HIGH, 999f)
			}
			addSubOptionView(hint)
			if (cropView.doc.regions.isNotEmpty()) {
				addSubOptionView(
					createChipButton("Clear regions", active = false) {
						cropView.clearAllRegions()
					},
					marginEndDp = 0f,
				)
			}
		}
	}

	private fun renderTextSubOptions() {
		val sel = cropView.doc.texts.firstOrNull { it.id == cropView.selectedTextId }
		val curPreset = cropView.getFontPresetById(sel?.fontId ?: activeTextFontId)

		addSubOptionView(
			createChipButton("+ Add Text", active = true) {
				pendingTextTapPos = null
				openTextOverlay(null)
			},
		)
		addSubOptionView(
			createChipButton(
				label = if (curPreset.uppercase) curPreset.name.uppercase() else curPreset.name,
				active = false,
				borderDp = 1f,
				borderColor = EditorColors.OUTLINE_VARIANT,
				customTypeface = curPreset.typeface,
			) {
				openTextOverlay(sel)
			},
		)
		addSubOptionView(
			createChipButton(
				label = "Custom Font",
				active = false,
				iconRes = R.drawable.ic_editor_upload,
				fgColor = EditorColors.PRIMARY,
				borderDp = 1f,
				borderColor = EditorColors.OUTLINE,
				dashedBorder = true,
			) {
				openTextOverlay(sel)
				toggleCustomFontDrawer(true)
			},
		)

		val modeLabel = when (cropView.textTransformMode) {
			1 -> "Mode 1: Perspective"
			2 -> "Mode 2: Stretch & Skew"
			else -> "Mode 3: Rotate & Scale"
		}
		addSubOptionView(
			createChipButton(
				label = modeLabel,
				bgColor = EditorColors.SECONDARY_CONTAINER,
				fgColor = EditorColors.ON_SECONDARY_CONTAINER,
			) {
				cropView.cycleTextTransformMode()
			},
		)

		if (sel != null) {
			addSubOptionView(
				createChipButton("Edit & Font", active = false) {
					openTextOverlay(sel)
				},
			)
			addSubOptionView(
				createChipButton(
					label = "Remove Text",
					iconRes = R.drawable.ic_delete,
					bgColor = EditorColors.ERROR_CONTAINER_SOFT,
					fgColor = EditorColors.ERROR,
				) {
					cropView.removeSelectedText()
				},
			)
			addSubOptionView(
				createChipButton(
					label = "Reset Quad",
					fgColor = EditorColors.ON_SURFACE_VARIANT,
				) {
					cropView.resetSelectedTextQuad()
				},
				marginEndDp = 0f,
			)
		} else if (cropView.doc.texts.isNotEmpty()) {
			addSubOptionView(
				createChipButton(
					label = "Remove All (${cropView.doc.texts.size})",
					iconRes = R.drawable.ic_delete,
					bgColor = EditorColors.ERROR_CONTAINER_SOFT,
					fgColor = EditorColors.ERROR,
				) {
					cropView.clearAllTextLayers()
				},
				marginEndDp = 0f,
			)
		} else {
			val hint = TextView(this).apply {
				text = "Tap canvas or + Add Text for comic/manga lettering"
				setTextColor(EditorColors.ON_SURFACE_VARIANT)
				textSize = 11f
				setPadding((8f * dp).roundToInt(), 0, (8f * dp).roundToInt(), 0)
			}
			addSubOptionView(hint, marginEndDp = 0f)
		}
	}

	// =========================================================================
	// LIVE BRUSH RING PREVIEW, CONTEXT MENU & SNACKBAR
	// =========================================================================

	private fun showBrushRingPreview(sizeImgPx: Int, previewType: String, color: Int) {
		val (iw, ih) = cropView.getOrientedDimensions()
		val sf = max(iw, ih) / 1100f
		val exactDiameterPx = max((4f * dp).roundToInt(), (sizeImgPx * sf * cropView.displayScale).roundToInt())

		val lp = brushPreviewRing.layoutParams
		lp.width = exactDiameterPx
		lp.height = exactDiameterPx
		brushPreviewRing.layoutParams = lp

		when (previewType) {
			"draw" -> {
				brushPreviewRing.background = GradientDrawable().apply {
					shape = GradientDrawable.OVAL
					setColor(EditorColors.withAlpha(color, 0.28f))
					setStroke(max(2, (2f * dp).roundToInt()), color)
				}
				brushPreviewLabel.text = "$sizeImgPx px"
			}
			"restore" -> {
				val mint = 0xFF6DD58C.toInt()
				brushPreviewRing.background = GradientDrawable().apply {
					shape = GradientDrawable.OVAL
					setColor(EditorColors.withAlpha(mint, 0.22f))
					setStroke(max(2, (2f * dp).roundToInt()), mint)
				}
				brushPreviewLabel.text = "Restore • $sizeImgPx px"
			}
			else -> {
				brushPreviewRing.background = GradientDrawable().apply {
					shape = GradientDrawable.OVAL
					setColor(EditorColors.withAlpha(0xFFFF6B6B.toInt(), 0.20f))
					setStroke(
						max(2, (2f * dp).roundToInt()),
						Color.WHITE,
						5f * dp,
						4f * dp,
					)
				}
				brushPreviewLabel.text = "$sizeImgPx px"
			}
		}
		brushPreviewContainer.isVisible = true
	}

	private fun hideBrushRingPreview() {
		brushPreviewContainer.isVisible = false
	}

	private fun showTextContextMenuAt(leftPx: Float, topPx: Float) {
		val nextMode = (cropView.textTransformMode % 3) + 1
		ctxModeBadge.text = "→ M$nextMode"
		val lp = textContextMenu.layoutParams as FrameLayout.LayoutParams
		lp.gravity = Gravity.TOP or Gravity.START
		lp.leftMargin = leftPx.roundToInt()
		lp.topMargin = topPx.roundToInt()
		textContextMenu.layoutParams = lp
		textContextMenu.isVisible = true
	}

	private fun hideTextContextMenu() {
		textContextMenu.isVisible = false
	}

	private fun showEditorSnackbar(msg: String, shareUri: Uri? = null, sharePng: Boolean = true) {
		snackbarJob?.cancel()
		snackbarText.text = msg
		if (shareUri != null) {
			snackbarActionBtn.isVisible = true
			snackbarActionBtn.setOnClickListener {
				shareSavedImage(shareUri, sharePng)
			}
		} else {
			snackbarActionBtn.isVisible = false
		}
		snackbarBanner.isVisible = true
		snackbarBanner.animate().alpha(1f).setDuration(160).start()
		snackbarJob = lifecycleScope.launch {
			delay(if (shareUri != null) 4200L else 2200L)
			snackbarBanner.animate().alpha(0f).setDuration(180).withEndAction {
				snackbarBanner.isVisible = false
			}.start()
		}
	}

	// =========================================================================
	// PERSPECTIVE TEXT DIALOG & CUSTOM FONT DRAWER
	// =========================================================================

	private fun syncCustomFontToggleUI(opened: Boolean) {
		val bg = if (opened) EditorColors.PRIMARY_CONTAINER else EditorColors.SURFACE_CONTAINER
		val fg = if (opened) EditorColors.ON_PRIMARY_CONTAINER else EditorColors.PRIMARY
		btnCustomFontToggle.background = rippleShape(bg, 999f)
		ImageViewCompat.setImageTintList(iconCustomFontToggle, ColorStateList.valueOf(fg))
		labelCustomFontToggle.setTextColor(fg)
	}

	private fun syncCustomFontCapsUI() {
		val bg = if (customFontCaps) EditorColors.PRIMARY_CONTAINER else EditorColors.SURFACE_CONTAINER_HIGH
		val fg = if (customFontCaps) EditorColors.ON_PRIMARY_CONTAINER else EditorColors.ON_SURFACE_VARIANT
		btnCustomFontCaps.background = rippleShape(bg, 8f)
		btnCustomFontCaps.setTextColor(fg)
	}

	private fun toggleCustomFontDrawer(open: Boolean) {
		customFontDrawer.isVisible = open
		syncCustomFontToggleUI(open)
		if (open) {
			customFontNameInput.requestFocus()
		}
	}

	private fun openTextOverlay(existing: PerspectiveTextLayer?) {
		hideTextContextMenu()
		toggleCustomFontDrawer(false)

		if (existing != null) {
			editingTextId = existing.id
			activeTextColor = existing.color
			activeTextFontId = existing.fontId
			textSheetTitle.text = "Edit text"
			canvasTextInput.setText(existing.text)
			btnDeleteTextSheet.isVisible = true
			deleteTextSheetLabel.text = "Remove"
		} else {
			editingTextId = null
			textSheetTitle.text = "Add text"
			canvasTextInput.setText("")
			val selOnCanvas = cropView.doc.texts.firstOrNull { it.id == cropView.selectedTextId }
			when {
				selOnCanvas != null -> {
					btnDeleteTextSheet.isVisible = true
					deleteTextSheetLabel.text = "Remove"
				}
				cropView.doc.texts.isNotEmpty() -> {
					btnDeleteTextSheet.isVisible = true
					deleteTextSheetLabel.text = "Clear (${cropView.doc.texts.size})"
				}
				else -> {
					btnDeleteTextSheet.isVisible = false
				}
			}
		}

		if (fontSearchInput.text?.isNotEmpty() == true) {
			fontSearchQuery = ""
			fontSearchInput.setText("")
		}
		btnClearTextInput.isVisible = !canvasTextInput.text.isNullOrEmpty()
		renderTextFontPresetsGrid(scrollToActive = true)
		renderTextColorSwatches()
		syncTextInputFontPreview()

		textInputOverlay.isVisible = true
		canvasTextInput.post {
			canvasTextInput.requestFocus()
			canvasTextInput.setSelection(canvasTextInput.text?.length ?: 0)
			val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
			imm?.showSoftInput(canvasTextInput, InputMethodManager.SHOW_IMPLICIT)
		}
	}

	private fun closeTextOverlay() {
		val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
		imm?.hideSoftInputFromWindow(canvasTextInput.windowToken, 0)
		textInputOverlay.isVisible = false
		editingTextId = null
		pendingTextTapPos = null
	}

	private fun commitTextOverlay() {
		val raw = canvasTextInput.text?.toString()?.trim().orEmpty()
		if (raw.isEmpty()) {
			if (editingTextId != null) {
				removeTextFromSheet()
			} else {
				closeTextOverlay()
			}
			return
		}
		val editingId = editingTextId
		val tapPos = pendingTextTapPos
		cropView.addOrUpdateTextLayer(
			editingId = editingId,
			text = raw,
			color = activeTextColor,
			fontId = activeTextFontId,
			tapPos = tapPos,
		)
		if (cropView.activeTool != EditorTool.TEXT) {
			selectTool(EditorTool.TEXT)
		} else {
			renderSubOptions()
			syncSliderConfig()
		}
		closeTextOverlay()
	}

	private fun removeTextFromSheet() {
		val targetId = editingTextId ?: cropView.selectedTextId
		if (targetId != null) {
			cropView.removeTextLayerById(targetId)
			closeTextOverlay()
		} else if (cropView.doc.texts.isNotEmpty()) {
			cropView.clearAllTextLayers()
			closeTextOverlay()
		} else {
			canvasTextInput.setText("")
			closeTextOverlay()
		}
	}

	private fun syncTextInputFontPreview() {
		val preset = cropView.getFontPresetById(activeTextFontId)
		canvasTextInput.typeface = preset.typeface
		canvasTextInput.letterSpacing = (preset.letterSpacingPx / 28f).coerceIn(0f, 0.14f)
		canvasTextInput.filters = if (preset.uppercase) {
			arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(80))
		} else {
			arrayOf(InputFilter.LengthFilter(80))
		}
		if (preset.uppercase && !isUpdatingInputCase && !canvasTextInput.text.isNullOrEmpty()) {
			val cur = canvasTextInput.text.toString()
			val up = cur.uppercase(Locale.getDefault())
			if (cur != up) {
				isUpdatingInputCase = true
				val sel = canvasTextInput.selectionStart.coerceAtLeast(0)
				canvasTextInput.setText(up)
				canvasTextInput.setSelection(sel.coerceAtMost(up.length))
				isUpdatingInputCase = false
			}
		}
	}

	private fun normalizeFontQuery(raw: String): String =
		raw.lowercase().replace(Regex("[\\s_-]+"), "")

	private fun renderTextFontPresetsGrid(scrollToActive: Boolean = false) {
		textFontPresetsContainer.removeAllViews()
		val normQuery = normalizeFontQuery(fontSearchQuery)
		val presets = if (normQuery.isEmpty()) {
			cropView.fontPresets
		} else {
			cropView.fontPresets.filter {
				normalizeFontQuery(it.name).contains(normQuery) ||
					normalizeFontQuery(it.role).contains(normQuery)
			}
		}

		val maxScrollH = (216f * dp).roundToInt()
		val rowH = (40f * dp).roundToInt()
		val estH = max(1, presets.size) * (rowH + (4f * dp).roundToInt())
		val slp = fontPresetsScroll.layoutParams
		slp.height = if (estH > maxScrollH) maxScrollH else ViewGroup.LayoutParams.WRAP_CONTENT
		fontPresetsScroll.layoutParams = slp

		if (presets.isEmpty()) {
			val emptyTv = TextView(this).apply {
				text = "No matching fonts"
				setTextColor(EditorColors.ON_SURFACE_VARIANT)
				textSize = 12f
				gravity = Gravity.CENTER
				val pad = (14f * dp).roundToInt()
				setPadding(pad, pad, pad, pad)
			}
			textFontPresetsContainer.addView(emptyTv)
			return
		}

		var activeRowIndex = -1
		presets.forEachIndexed { idx, preset ->
			if (preset.id == activeTextFontId) activeRowIndex = idx
			val pill = buildFontPresetPill(preset)
			val rowLp = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				rowH,
			).apply {
				if (idx < presets.lastIndex) bottomMargin = (4f * dp).roundToInt()
			}
			textFontPresetsContainer.addView(pill, rowLp)
		}

		if (scrollToActive && activeRowIndex > 2) {
			val targetY = max(0, (activeRowIndex - 1) * (rowH + (4f * dp).roundToInt()))
			fontPresetsScroll.post { fontPresetsScroll.scrollTo(0, targetY) }
		}
	}

	private fun buildFontPresetPill(preset: MangaFontPreset): FrameLayout {
		val active = activeTextFontId == preset.id
		val isOfflineReady = preset.isAvailableOffline
		val isDownloading = downloadingFontIds.contains(preset.id)
		val bgColor = if (active) EditorColors.SECONDARY_CONTAINER else EditorColors.SURFACE_CONTAINER
		val nameColor = if (active) EditorColors.ON_SECONDARY_CONTAINER else EditorColors.ON_SURFACE
		val previewColor = if (active || isDownloading) EditorColors.PRIMARY else EditorColors.ON_SURFACE_VARIANT
		val borderDp = if (active) 1.5f else 0f
		val borderColor = if (active) EditorColors.PRIMARY else Color.TRANSPARENT

		return FrameLayout(this).apply {
			background = rippleShape(bgColor, 10f, borderDp, borderColor)
			isClickable = true
			isFocusable = true
			setOnClickListener {
				activeTextFontId = preset.id
				if (preset.isAvailableOffline) {
					cropView.clearTextCache()
					cropView.invalidate()
					renderTextFontPresetsGrid(scrollToActive = false)
					syncTextInputFontPreview()
					renderSubOptions()
				} else if (!downloadingFontIds.contains(preset.id)) {
					downloadingFontIds.add(preset.id)
					renderTextFontPresetsGrid(scrollToActive = false)
					lifecycleScope.launch {
						val ok = withContext(Dispatchers.IO) {
							MangaFontRegistry.downloadFontIfNeeded(applicationContext, preset)
						}
						downloadingFontIds.remove(preset.id)
						if (ok) {
							cropView.clearTextCache()
							cropView.invalidate()
							if (textInputOverlay.isVisible) {
								renderTextFontPresetsGrid(scrollToActive = false)
								syncTextInputFontPreview()
							}
							renderSubOptions()
							showEditorSnackbar("Downloaded font \"${preset.name}\"")
						} else {
							if (textInputOverlay.isVisible) {
								renderTextFontPresetsGrid(scrollToActive = false)
							}
							showEditorSnackbar("Connect to internet once to download \"${preset.name}\"")
						}
					}
				}
			}

			val rowContent = LinearLayout(this@PageCropActivity).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				val endPad = if (preset.isCustom) (32f * dp).roundToInt() else (14f * dp).roundToInt()
				setPadding((14f * dp).roundToInt(), 0, endPad, 0)
			}

			val nameTv = TextView(this@PageCropActivity).apply {
				text = preset.name
				setTextColor(nameColor)
				textSize = 13f
				typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
				isSingleLine = true
				ellipsize = TextUtils.TruncateAt.END
			}
			rowContent.addView(
				nameTv,
				LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
					marginEnd = (10f * dp).roundToInt()
				},
			)

			val previewTv = TextView(this@PageCropActivity).apply {
				when {
					isOfflineReady -> {
						text = if (preset.uppercase) "PREVIEW" else "Preview"
						setTextColor(previewColor)
						textSize = 15f
						typeface = preset.typeface
						letterSpacing = (preset.letterSpacingPx / 32f).coerceIn(0f, 0.1f)
					}
					isDownloading -> {
						text = "Downloading…"
						setTextColor(EditorColors.PRIMARY)
						textSize = 11.5f
						typeface = Typeface.DEFAULT_BOLD
					}
					else -> {
						text = "↓ Tap to get"
						setTextColor(EditorColors.ON_SURFACE_VARIANT)
						textSize = 11.5f
						typeface = Typeface.DEFAULT
					}
				}
				isSingleLine = true
				gravity = Gravity.END or Gravity.CENTER_VERTICAL
			}
			rowContent.addView(
				previewTv,
				LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
			)

			addView(
				rowContent,
				FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
			)

			if (preset.isCustom) {
				val delBtn = ImageButton(this@PageCropActivity).apply {
					setImageResource(R.drawable.ic_editor_close)
					scaleType = ImageView.ScaleType.FIT_CENTER
					val p = (4f * dp).roundToInt()
					setPadding(p, p, p, p)
					background = rippleShape(EditorColors.SURFACE_CONTAINER_HIGHEST, 999f)
					ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(EditorColors.ON_SURFACE))
					setOnClickListener {
						removeCustomFontPreset(preset.id)
					}
				}
				val sz = (20f * dp).roundToInt()
				addView(
					delBtn,
					FrameLayout.LayoutParams(sz, sz, Gravity.END or Gravity.CENTER_VERTICAL).apply {
						marginEnd = (6f * dp).roundToInt()
					},
				)
			}
		}
	}

	private fun renderTextColorSwatches() {
		textColorSwatchesRow.removeAllViews()
		EditorColors.TEXT_COLORS.forEachIndexed { idx, color ->
			val active = activeTextColor == color
			val holder = FrameLayout(this)
			val sw = buildColorSwatchView(
				color = color,
				active = active,
				sizeDp = 24f,
				ringGapColor = EditorColors.SURFACE_CONTAINER_HIGH,
			) {
				activeTextColor = color
				renderTextColorSwatches()
			}
			val sz = (24f * dp).roundToInt()
			holder.addView(sw, FrameLayout.LayoutParams(sz, sz, Gravity.CENTER))
			textColorSwatchesRow.addView(
				holder,
				LinearLayout.LayoutParams(0, (28f * dp).roundToInt(), 1f).apply {
					if (idx < EditorColors.TEXT_COLORS.lastIndex) marginEnd = (3f * dp).roundToInt()
				},
			)
		}
	}

	private fun loadPersistedCustomFonts() {
		val dir = File(filesDir, "custom_editor_fonts")
		if (!dir.isDirectory) return
		dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
			val tf = runCatching { Typeface.createFromFile(f) }.getOrNull() ?: return@forEach
			val cleanName = f.nameWithoutExtension.replace(Regex("[_-]+"), " ").trim().take(24).ifEmpty { "Custom Font" }
			cropView.fontPresets.add(
				0,
				MangaFontPreset(
					id = "cfont_${f.name}",
					name = cleanName,
					role = "Uploaded Font",
					typeface = tf,
					isBoldWeight = false,
					uppercase = false,
					letterSpacingPx = 0.8f,
					isCustom = true,
					customFilePath = f.absolutePath,
				),
			)
		}
	}

	private fun importCustomFontUri(uri: Uri) {
		lifecycleScope.launch {
			val addedPreset = withContext(Dispatchers.IO) {
				runCatching {
					val dir = File(filesDir, "custom_editor_fonts").apply { mkdirs() }
					var rawName = "CustomFont.ttf"
					contentResolver.query(uri, null, null, null, null)?.use { cursor ->
						val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
						if (idx >= 0 && cursor.moveToFirst()) {
							rawName = cursor.getString(idx) ?: rawName
						}
					}
					if (!rawName.endsWith(".ttf", true) && !rawName.endsWith(".otf", true)) {
						rawName += ".ttf"
					}
					val target = File(dir, "${System.currentTimeMillis()}_$rawName")
					contentResolver.openInputStream(uri)?.use { input ->
						target.outputStream().use { out -> input.copyTo(out) }
					} ?: error("Cannot open font file")

					val tf = Typeface.createFromFile(target)
					val cleanName = rawName.replace(Regex("\\.(ttf|otf)$", RegexOption.IGNORE_CASE), "")
						.replace(Regex("[_-]+"), " ")
						.trim()
						.take(24)
						.ifEmpty { "Custom Font" }

					MangaFontPreset(
						id = "cfont_${target.name}",
						name = cleanName,
						role = "Uploaded Font",
						typeface = tf,
						isBoldWeight = false,
						uppercase = customFontCaps,
						letterSpacingPx = 0.8f,
						isCustom = true,
						customFilePath = target.absolutePath,
					)
				}.onFailure { it.printStackTraceDebug() }.getOrNull()
			}

			if (addedPreset != null) {
				cropView.fontPresets.add(0, addedPreset)
				applyImportedFontSelection(addedPreset.id)
				showEditorSnackbar("Loaded custom font \"${addedPreset.name}\"")
			} else {
				showEditorSnackbar("Unsupported font file")
			}
		}
	}

	private fun addCustomSystemFontByName() {
		val rawName = customFontNameInput.text?.toString()?.trim()?.replace(Regex("[\"'<>]"), "").orEmpty()
		if (rawName.isEmpty()) {
			showEditorSnackbar("Enter a system font family name")
			return
		}
		val tf = Typeface.create(rawName, Typeface.BOLD)
		val existing = cropView.fontPresets.firstOrNull {
			it.isCustom && it.name.equals(rawName, ignoreCase = true) && it.uppercase == customFontCaps
		}
		val targetId = if (existing != null) {
			existing.id
		} else {
			val id = "cfont_sys_${System.currentTimeMillis()}"
			cropView.fontPresets.add(
				0,
				MangaFontPreset(
					id = id,
					name = rawName.take(24),
					role = "Custom Font",
					typeface = tf,
					isBoldWeight = true,
					uppercase = customFontCaps,
					letterSpacingPx = if (customFontCaps) 1.4f else 0.6f,
					isCustom = true,
				),
			)
			id
		}
		customFontNameInput.setText("")
		toggleCustomFontDrawer(false)
		applyImportedFontSelection(targetId)
		showEditorSnackbar("Added font \"$rawName\"")
	}

	private fun applyImportedFontSelection(fontId: String) {
		activeTextFontId = fontId
		cropView.clearTextCache()
		if (textInputOverlay.isVisible) {
			renderTextFontPresetsGrid()
			syncTextInputFontPreview()
		} else if (cropView.selectedTextId != null) {
			val sel = cropView.doc.texts.firstOrNull { it.id == cropView.selectedTextId }
			if (sel != null) {
				cropView.addOrUpdateTextLayer(sel.id, sel.text, sel.color, fontId, null)
			}
		}
		renderSubOptions()
		cropView.invalidate()
	}

	private fun removeCustomFontPreset(fontId: String) {
		val idx = cropView.fontPresets.indexOfFirst { it.id == fontId && it.isCustom }
		if (idx == -1) return
		val removed = cropView.fontPresets.removeAt(idx)
		removed.customFilePath?.let { runCatching { File(it).delete() } }

		val fallbackId = cropView.fontPresets.firstOrNull()?.id ?: "wild"
		if (activeTextFontId == fontId) {
			activeTextFontId = fallbackId
		}
		for (t in cropView.doc.texts) {
			if (t.fontId == fontId) t.fontId = fallbackId
		}
		cropView.clearTextCache()
		if (textInputOverlay.isVisible) {
			renderTextFontPresetsGrid()
			syncTextInputFontPreview()
		}
		renderSubOptions()
		cropView.invalidate()
		showEditorSnackbar("Removed \"${removed.name}\"")
	}

	// =========================================================================
	// SAVE & EXPORT MODAL + MEDIASTORE / SAF PERSISTENCE
	// =========================================================================

	private fun openSaveModal() {
		hideTextContextMenu()
		val isAngled = cropView.isAngledQuadActive()
		if (isAngled && cropView.doc.cropMode == CropExecMode.CUTOUT && !isExportPng) {
			isExportPng = true
		}
		saveModalOverlay.isVisible = true
		refreshSaveModalPreview()
	}

	private fun refreshSaveModalPreview() {
		val isFull = HomographyMath.isDefaultFullCropQuad(cropView.doc.cropQuad)
		val isRect = HomographyMath.isAxisAlignedRectQuad(cropView.doc.cropQuad)
		val isAngled = !isFull && !isRect

		exportCropModeRow.isVisible = isAngled
		if (isAngled) {
			val isCut = cropView.doc.cropMode == CropExecMode.CUTOUT
			styleModalSegmentedPill(btnExpModeCutout, isCut)
			styleModalSegmentedPill(btnExpModeUnwarp, !isCut)
		}

		styleModalSegmentedPill(btnFmtPng, isExportPng)
		styleModalSegmentedPill(btnFmtJpg, !isExportPng)

		val bmp = cropView.renderExportBitmap(isExportPng)
		previewBitmap?.let { if (it != bmp && !it.isRecycled) it.recycle() }
		previewBitmap = bmp
		exportPreviewImg.setImageBitmap(bmp)

		val modeLabel = when {
			isFull -> "Full Resolution"
			isRect -> "Rectangular Crop"
			cropView.doc.cropMode == CropExecMode.CUTOUT -> "Exact Shape"
			else -> "Perspective Unwarp"
		}
		val fmtLabel = if (isExportPng) "PNG" else "JPG"
		exportMetaText.text = "$modeLabel • $fmtLabel • ${bmp.width} × ${bmp.height} px"
	}

	private fun styleModalSegmentedPill(tv: TextView, active: Boolean) {
		tv.background = if (active) {
			rippleShape(EditorColors.SECONDARY_CONTAINER, 999f)
		} else {
			rippleShape(Color.TRANSPARENT, 999f)
		}
		tv.setTextColor(
			if (active) EditorColors.ON_SECONDARY_CONTAINER else EditorColors.ON_SURFACE_VARIANT,
		)
	}

	private fun closeSaveModal() {
		saveModalOverlay.isVisible = false
		exportPreviewImg.setImageDrawable(null)
		previewBitmap?.let { if (!it.isRecycled) it.recycle() }
		previewBitmap = null
	}

	private fun confirmAndSaveImage() {
		val png = isExportPng
		val output = cropView.renderExportBitmap(png)
		closeSaveModal()

		lifecycleScope.launch {
			val savedUri = withContext(Dispatchers.IO) {
				runCatching { saveBitmapToDevice(output, png) }
					.onFailure { it.printStackTraceDebug() }
					.getOrNull()
			}
			if (!output.isRecycled) output.recycle()

			if (savedUri == null) {
				Toast.makeText(this@PageCropActivity, R.string.error, Toast.LENGTH_SHORT).show()
				return@launch
			}
			showEditorSnackbar(
				msg = getString(R.string.file_saved_to, savedUri.displayPath()),
				shareUri = savedUri,
				sharePng = png,
			)
		}
	}

	private fun shareSavedImage(uri: Uri, png: Boolean) {
		val intent = Intent(Intent.ACTION_SEND).apply {
			type = if (png) "image/png" else "image/jpeg"
			putExtra(Intent.EXTRA_STREAM, uri)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		startActivity(Intent.createChooser(intent, getString(R.string.share)))
	}

	private fun Uri.displayPath(): String = when {
		scheme == ContentResolver.SCHEME_FILE -> path.orEmpty()
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
			val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
			contentResolver.query(this, projection, null, null, null)?.use { cursor ->
				if (cursor.moveToFirst()) {
					val rel = cursor.getString(0)
					val name = cursor.getString(1)
					if (rel != null && name != null) rel + name else toString()
				} else {
					toString()
				}
			} ?: toString()
		}
		else -> toString()
	}

	private fun saveBitmapToDevice(bitmap: Bitmap, png: Boolean): Uri {
		val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
		val name = "KotatsuX_crop_$date"
		val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
		val mime = if (png) "image/png" else "image/jpeg"
		val ext = if (png) "png" else "jpg"

		val dir = settings.getEditorSaveDir(this)
		if (dir != null) {
			val doc = dir.createFile(mime, name)
				?: throw IllegalStateException("Cannot create the destination file")
			contentResolver.openOutputStream(doc.uri)?.use { out ->
				if (!bitmap.compress(format, 95, out)) {
					throw IllegalStateException("Cannot compress the edited image")
				}
			}
			return doc.uri
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			val values = ContentValues().apply {
				put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.$ext")
				put(MediaStore.MediaColumns.MIME_TYPE, mime)
				put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KotatsuX")
				put(MediaStore.MediaColumns.IS_PENDING, 1)
			}
			val resolver = contentResolver
			val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
				?: throw IllegalStateException("MediaStore rejected the insert")
			resolver.openOutputStream(uri)?.use { out ->
				if (!bitmap.compress(format, 95, out)) {
					throw IllegalStateException("Cannot compress the edited image")
				}
			}
			values.clear()
			values.put(MediaStore.MediaColumns.IS_PENDING, 0)
			resolver.update(uri, values, null, null)
			return uri
		}

		val legacyDir = File(
			Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
			"KotatsuX",
		)
		if (!legacyDir.isDirectory && !legacyDir.mkdirs()) {
			throw IllegalStateException("Cannot create the pictures directory")
		}
		val file = File(legacyDir, "$name.$ext")
		file.outputStream().use { out ->
			if (!bitmap.compress(format, 95, out)) {
				throw IllegalStateException("Cannot compress the edited image")
			}
		}
		return Uri.fromFile(file)
	}

	private fun decodeFile(file: File, fallbackUri: Uri?): Bitmap {
		if (fallbackUri != null) {
			return decodeUri(fallbackUri)
		}
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(file.path, bounds)
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
			return BitmapDecoderCompat.decode(file)
		}
		val opts = BitmapFactory.Options().apply {
			inPreferredConfig = Bitmap.Config.ARGB_8888
			inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 2600)
		}
		return BitmapFactory.decodeFile(file.path, opts)
			?: BitmapFactory.decodeFile(file.path, null)
			?: BitmapDecoderCompat.decode(file)
	}

	private fun decodeUri(uri: Uri): Bitmap {
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		contentResolver.openInputStream(uri)?.use { input ->
			BitmapFactory.decodeStream(input, null, bounds)
			check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot decode page" }
		} ?: throw IllegalStateException("Cannot open the image")

		contentResolver.openInputStream(uri)?.use { input ->
			val opts = BitmapFactory.Options().apply {
				inPreferredConfig = Bitmap.Config.ARGB_8888
				inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 2600)
			}
			return BitmapFactory.decodeStream(input, null, opts)
				?: throw IllegalStateException("Cannot decode page")
		}
		throw IllegalStateException("Cannot open the image")
	}

	private fun calculateInSampleSize(w: Int, h: Int, maxDim: Int): Int {
		var sample = 1
		while (max(w / sample, h / sample) > maxDim * 2) {
			sample *= 2
		}
		return sample
	}

	override fun onDestroy() {
		previewBitmap?.let { if (!it.isRecycled) it.recycle() }
		previewBitmap = null
		super.onDestroy()
	}

	companion object {
		private const val EXTRA_FILE = "file"
		private const val EXTRA_URI = "uri"

		fun newIntent(context: Context, file: File): Intent = Intent(context, PageCropActivity::class.java)
			.putExtra(EXTRA_FILE, file.absolutePath)

		fun newIntent(context: Context, uri: Uri): Intent = Intent(context, PageCropActivity::class.java)
			.putExtra(EXTRA_URI, uri)
	}
}
