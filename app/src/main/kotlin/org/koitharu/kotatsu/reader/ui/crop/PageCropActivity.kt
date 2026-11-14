package org.koitharu.kotatsu.reader.ui.crop

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen editor for the crop & save reader feature: shows the current page at its
 * original resolution, lets the user drag a crop rectangle, paint opaque strokes over it
 * (with a colour taken from a palette or sampled from the page) and saves the result.
 */
class PageCropActivity : AppCompatActivity(), View.OnClickListener {

	private lateinit var cropView: CropDrawView
	private lateinit var settings: AppSettings
	private var isPng = true

	private var paletteColors = IntArray(0)
	private var selectedColorView: View? = null

	private val fontNames = ArrayList<String>()
	private val fontFiles = ArrayList<Typeface?>()
	private var lastFontIndex = 0
	private var pendingTextObject: TextObject? = null

	private val fontImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) {
			importFont(uri)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
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
		setSupportActionBar(findViewById(R.id.toolbar) as Toolbar)
		supportActionBar?.apply {
			setDisplayHomeAsUpEnabled(true)
			setDisplayShowTitleEnabled(true)
			title = getString(R.string.crop_and_save)
		}
		toolbar()?.setNavigationOnClickListener { finish() }

		cropView = findViewById(R.id.cropView)
		cropView.onColorPicked = { color ->
			selectColorSwatch(-1, color)
		}
		cropView.onHistoryChanged = { canUndo, canRedo ->
			findViewById<View>(R.id.button_undo).isEnabled = canUndo
			findViewById<View>(R.id.button_redo).isEnabled = canRedo
		}

		paletteColors = IntArray(PALETTE.size)
		for (i in PALETTE.indices) {
			paletteColors[i] = PALETTE[i]
			val swatch = findViewById<View>(SWATCH_IDS[i])
			swatch.setOnClickListener(this)
			swatch.tag = i
			swatch.background?.setTint(PALETTE[i])
		}
		findViewById<View>(R.id.button_eyedropper).setOnClickListener(this)
		findViewById<View>(R.id.button_mode_draw).setOnClickListener(this)
		findViewById<View>(R.id.button_mode_erase).setOnClickListener(this)
		findViewById<View>(R.id.button_mode_lasso).setOnClickListener(this)
		findViewById<View>(R.id.button_mode_text).setOnClickListener(this)
		findViewById<View>(R.id.button_mode_crop).setOnClickListener(this)
		cropView.onCropShapeChanged = {
			updateFormatButton()
		}
		cropView.onTextRequested = { obj ->
			showTextDialog(obj)
		}

		loadFonts()
		findViewById<View>(R.id.button_undo).setOnClickListener(this)
		findViewById<View>(R.id.button_redo).setOnClickListener(this)
		findViewById<View>(R.id.button_reset).setOnClickListener(this)
		findViewById<View>(R.id.button_format).setOnClickListener(this)
		findViewById<View>(R.id.button_save).setOnClickListener(this)

		// restore the tools from the previous session
		settings = AppSettings(this)
		val savedColor = settings.editorStrokeColor
		val savedColorIndex = PALETTE.indexOf(savedColor)
		selectColorSwatch(savedColorIndex, savedColor)
		setStrokeWidth(settings.editorStrokeWidth)
		isPng = settings.isEditorExportPng
		updateFormatButton()

		setMode(CropDrawView.Mode.CROP)

		val slider = findViewById<Slider>(R.id.slider_stroke)
		slider.value = settings.editorStrokeWidth.coerceIn(1f, 100f)
		slider.addOnChangeListener { _, value, fromUser ->
			if (fromUser) {
				setStrokeWidth(value)
			}
		}

		findViewById<Slider>(R.id.slider_erase)?.apply {
			value = settings.editorStrokeWidth.coerceIn(5f, 120f)
			addOnChangeListener { _, value, fromUser ->
				if (fromUser) {
					setStrokeWidth(value)
				}
			}
		}

		lifecycleScope.launch {
			val bitmap = withContext(Dispatchers.IO) {
				runCatching {
					if (path != null) decodeFile(File(path)) else decodeUri(sourceUri!!)
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

	private fun toolbar(): Toolbar = findViewById(R.id.toolbar)

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_mode_crop -> setMode(CropDrawView.Mode.CROP)
			R.id.button_mode_draw -> setMode(CropDrawView.Mode.DRAW)
			R.id.button_mode_erase -> setMode(CropDrawView.Mode.ERASE)
			R.id.button_mode_lasso -> setMode(CropDrawView.Mode.LASSO)
			R.id.button_mode_text -> setMode(CropDrawView.Mode.TEXT)
			R.id.button_eyedropper -> setMode(CropDrawView.Mode.PICK_COLOR)
			R.id.button_undo -> cropView.undo()
			R.id.button_redo -> cropView.redo()
			R.id.button_reset -> cropView.resetCrop()
			R.id.button_format -> {
				isPng = !isPng
				updateFormatButton()
			}
			R.id.button_save -> save()
			else -> {
				val index = v.tag as? Int
				if (index != null && index in PALETTE.indices) {
					selectColorSwatch(index, PALETTE[index])
					setMode(CropDrawView.Mode.DRAW)
				}
			}
		}
	}

	private fun setMode(mode: CropDrawView.Mode) {
		cropView.mode = mode
		findViewById<View>(R.id.button_mode_crop).isSelected = mode == CropDrawView.Mode.CROP
		findViewById<View>(R.id.button_mode_draw).isSelected = mode == CropDrawView.Mode.DRAW
		findViewById<View>(R.id.button_mode_erase).isSelected = mode == CropDrawView.Mode.ERASE
		findViewById<View>(R.id.button_mode_lasso).isSelected = mode == CropDrawView.Mode.LASSO
		findViewById<View>(R.id.button_mode_text).isSelected = mode == CropDrawView.Mode.TEXT
		findViewById<View>(R.id.button_eyedropper).isSelected = mode == CropDrawView.Mode.PICK_COLOR

		val isCropOrLasso = mode == CropDrawView.Mode.CROP || mode == CropDrawView.Mode.LASSO
		val isDrawOrText = mode == CropDrawView.Mode.DRAW || mode == CropDrawView.Mode.PICK_COLOR || mode == CropDrawView.Mode.TEXT
		val isErase = mode == CropDrawView.Mode.ERASE

		findViewById<View>(R.id.layout_options_crop)?.isVisible = isCropOrLasso
		findViewById<View>(R.id.layout_options_draw)?.isVisible = isDrawOrText
		findViewById<View>(R.id.layout_options_erase)?.isVisible = isErase

		if (isCropOrLasso) {
			findViewById<TextView>(R.id.textView_crop_hint)?.setText(
				if (mode == CropDrawView.Mode.LASSO) R.string.lasso else R.string.crop
			)
		}
	}

	private fun setStrokeWidth(width: Float) {
		cropView.strokeWidth = width
		findViewById<TextView>(R.id.textView_stroke_width).text = width.toInt().toString()
		settings.editorStrokeWidth = width
	}

	private fun selectColorSwatch(index: Int, color: Int) {
		cropView.strokeColor = color
		settings.editorStrokeColor = color
		selectedColorView?.isSelected = false
		selectedColorView = if (index >= 0) findViewById(SWATCH_IDS[index]) else null
		selectedColorView?.isSelected = true
	}

	private fun updateFormatButton() {
		val button = findViewById<MaterialButton>(R.id.button_format)
		if (cropView.hasLasso) {
			// a free-form crop exports with transparency, which requires PNG
			button.text = "PNG"
			button.isEnabled = false
			return
		}
		button.isEnabled = true
		button.text = if (isPng) "PNG" else "JPG"
		settings.isEditorExportPng = isPng
	}

	private fun decodeFile(file: File): Bitmap {
		val sourceUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
		} else {
			@Suppress("DEPRECATION")
			intent.getParcelableExtra(EXTRA_URI)
		}
		if (sourceUri != null) {
			return decodeUri(sourceUri)
		}
		val options = BitmapFactory.Options().apply {
			inJustDecodeBounds = true
		}
		BitmapFactory.decodeFile(file.path, options)
		check(options.outWidth > 0 && options.outHeight > 0) { "Cannot decode page" }
		val options2 = BitmapFactory.Options().apply {
			inPreferredConfig = Bitmap.Config.ARGB_8888
		}
		return BitmapFactory.decodeFile(file.path, options2)
			?: BitmapFactory.decodeFile(file.path, null)
		?: throw IllegalStateException("Cannot decode page")
	}

	private fun decodeUri(uri: Uri): Bitmap {
		contentResolver.openInputStream(uri)?.use { input ->
			val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeStream(input, null, bounds)
			check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot decode page" }
		} ?: throw IllegalStateException("Cannot open the image")
		contentResolver.openInputStream(uri)?.use { input ->
			val options = BitmapFactory.Options().apply {
				inPreferredConfig = Bitmap.Config.ARGB_8888
			}
			return BitmapFactory.decodeStream(input, null, options)
				?: throw IllegalStateException("Cannot decode page")
		}
		throw IllegalStateException("Cannot open the image")
	}

	private fun save() {
		val output = cropView.renderOutput()
		val png = isPng || cropView.hasLasso
		lifecycleScope.launch {
			val saved = withContext(Dispatchers.IO) {
				runCatching { saveBitmap(output, png) }
					.onFailure { it.printStackTraceDebug() }
					.getOrNull()
			}
			if (saved == null) {
				Toast.makeText(this@PageCropActivity, R.string.error, Toast.LENGTH_SHORT).show()
				return@launch
			}
			output.recycle()
			val snackbar = Snackbar.make(
				findViewById(R.id.cropView),
				getString(R.string.file_saved_to, saved.displayPath()),
				Snackbar.LENGTH_LONG,
			).setAction(R.string.share) { share(saved, png) }
			snackbar.show()
			finish()
		}
	}

	private fun share(uri: Uri, png: Boolean) {
		val intent = Intent(Intent.ACTION_SEND).apply {
			type = if (png) "image/png" else "image/jpeg"
			putExtra(Intent.EXTRA_STREAM, uri)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		startActivity(Intent.createChooser(intent, getString(R.string.share), null))
	}

	private fun Uri.displayPath(): String = when {
		scheme == ContentResolver.SCHEME_FILE -> path.orEmpty()
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
			val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
			contentResolver.query(this, projection, null, null, null)?.use { cursor ->
				if (cursor.moveToFirst()) {
					val rel = cursor.getString(0)
					val name = cursor.getString(1)
					if (rel != null && name != null) rel + File.separator + name else toString()
				} else {
					toString()
				}
			} ?: toString()
		}

		else -> toString()
	}

	private fun saveBitmap(bitmap: Bitmap, png: Boolean): Uri {
		val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
		val name = "KotatsuX_crop_$date"
		val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
		val mime = if (png) "image/png" else "image/jpeg"
		val ext = if (png) "png" else "jpg"
		// the folder the user picked in Settings -> Downloads & storage (if any)
		val dir = settings.getEditorSaveDir(this)
		if (dir != null) {
			val doc = dir.createFile(mime, name)
				?: throw IllegalStateException("Cannot create the destination file")
			contentResolver.openOutputStream(doc.uri)?.use { out ->
				if (!bitmap.compress(format, 95, out)) {
					throw IllegalStateException("Cannot compress the cropped image")
				}
			}
			return doc.uri
		}
		// default: the system Pictures collection, shown in gallery apps
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
					throw IllegalStateException("Cannot compress the cropped image")
				}
			}
			values.clear()
			values.put(MediaStore.MediaColumns.IS_PENDING, 0)
			resolver.update(uri, values, null, null)
			return uri
		}
		val legacyDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KotatsuX")
		if (!legacyDir.isDirectory && !legacyDir.mkdirs()) {
			throw IllegalStateException("Cannot create the pictures directory")
		}
		val file = File(legacyDir, "$name.$ext")
		file.outputStream().use { out ->
			if (!bitmap.compress(format, 95, out)) {
				throw IllegalStateException("Cannot compress the cropped image")
			}
		}
		return Uri.fromFile(file)
	}

	override fun onSupportNavigateUp(): Boolean {
		finish()
		return true
	}

	private fun loadFonts() {
		fontNames.clear()
		fontFiles.clear()
		runCatching {
			for (name in assets.list("fonts").orEmpty().sorted()) {
				val tf = runCatching { Typeface.createFromAsset(assets, "fonts/$name") }.getOrNull()
				if (tf != null) {
					fontNames.add(name.removeSuffix(".ttf"))
					fontFiles.add(tf)
				}
			}
		}.onFailure { it.printStackTraceDebug() }
		val dir = File(filesDir, "fonts")
		dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
			val tf = runCatching { Typeface.createFromFile(f) }.getOrNull()
			if (tf != null) {
				fontNames.add(f.name.removeSuffix(".ttf"))
				fontFiles.add(tf)
			}
		}
	}

	private fun importFont(uri: Uri) {
		lifecycleScope.launch {
			val done = withContext(Dispatchers.IO) {
				runCatching {
					val dir = File(filesDir, "fonts").apply { mkdirs() }
					var name = "font.ttf"
					contentResolver.query(uri, null, null, null, null)?.use { cursor ->
						val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
						if (idx >= 0 && cursor.moveToFirst()) {
							name = cursor.getString(idx) ?: name
						}
					}
					if (!name.endsWith(".ttf", true) && !name.endsWith(".otf", true)) {
						name += ".ttf"
					}
					val target = File(dir, name)
					contentResolver.openInputStream(uri)?.use { input ->
						target.outputStream().use { output ->
							input.copyTo(output)
						}
					} ?: throw IllegalStateException("Cannot open the font file")
					return@runCatching name
				}.onFailure { it.printStackTraceDebug() }.getOrNull()
			}
			if (done != null) {
				loadFonts()
				Toast.makeText(this@PageCropActivity, done, Toast.LENGTH_SHORT).show()
				showTextDialog(pendingTextObject)
			}
		}
	}

	private fun showTextDialog(edit: TextObject?) {
		pendingTextObject = edit
		val container = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			val pad = (resources.displayMetrics.density * 20).toInt()
			setPadding(pad, pad / 2, pad, 0)
		}
		val input = EditText(this).apply {
			hint = getString(R.string.text_hint)
			setText(edit?.text.orEmpty())
			setSingleLine(false)
			maxLines = 3
		}
		val spinner = Spinner(this)
		val items = ArrayList(fontNames).apply { add(getString(R.string.import_font)) }
		spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
		spinner.setSelection(
			when {
				edit != null -> fontNames.indexOfFirst { it == edit.fontName }.coerceAtLeast(0)
				else -> lastFontIndex.coerceIn(0, (fontNames.size - 1).coerceAtLeast(0))
			},
		)
		spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
				if (position == fontNames.size) {
					fontImportLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
				}
			}

			override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
		}
		container.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
		container.addView(spinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
		MaterialAlertDialogBuilder(this)
			.setTitle(if (edit == null) R.string.add_text else R.string.edit)
			.setView(container)
			.setPositiveButton(if (edit == null) R.string.add else R.string.save) { _, _ ->
				val text = input.text.toString().trim()
				val pos = spinner.selectedItemPosition
				if (text.isNotEmpty() && pos in fontNames.indices) {
					val tf = fontFiles[pos]
					if (edit == null) {
						val bmp = cropView.bitmap
						val size = ((bmp?.height ?: 1000) / 14f).coerceAtLeast(24f)
						val obj = TextObject(
							text = text,
							fontName = fontNames[pos],
							typeface = tf,
							color = cropView.strokeColor,
							size = size,
							x = (bmp?.width ?: 600) / 2f,
							y = (bmp?.height ?: 800) / 2f,
						)
						cropView.textObjects.add(obj)
						cropView.selectedText = obj
					} else {
						edit.text = text
						edit.fontName = fontNames[pos]
						edit.typeface = tf
					}
					lastFontIndex = pos
					cropView.invalidate()
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	companion object {

		private val PALETTE = intArrayOf(
			Color.BLACK,
			Color.WHITE,
			Color.parseColor("#E53935"),
			Color.parseColor("#1E88E5"),
			Color.parseColor("#43A047"),
			Color.parseColor("#FDD835"),
		)

		private val SWATCH_IDS = intArrayOf(
			R.id.swatch_0,
			R.id.swatch_1,
			R.id.swatch_2,
			R.id.swatch_3,
			R.id.swatch_4,
			R.id.swatch_5,
		)

		private const val EXTRA_FILE = "file"
		private const val EXTRA_URI = "uri"

		fun newIntent(context: Context, file: File): Intent = Intent(context, PageCropActivity::class.java)
			.putExtra(EXTRA_FILE, file.absolutePath)

		fun newIntent(context: Context, uri: Uri): Intent = Intent(context, PageCropActivity::class.java)
			.putExtra(EXTRA_URI, uri)
	}
}
