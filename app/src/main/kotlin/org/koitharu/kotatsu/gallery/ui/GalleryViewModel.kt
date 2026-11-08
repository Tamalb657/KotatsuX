package org.koitharu.kotatsu.gallery.ui

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.gallery.domain.GalleryImage
import javax.inject.Inject

/**
 * Collects the images saved by the editor (and saved pages) from the
 * configured folders plus the default Pictures/KotatsuX media collection.
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
) : ViewModel() {

	private val _images = MutableStateFlow<List<GalleryImage>>(emptyList())
	val images: StateFlow<List<GalleryImage>> = _images

	private val _isLoading = MutableStateFlow(false)
	val isLoading: StateFlow<Boolean> = _isLoading

	fun refresh() {
		viewModelScope.launch {
			_isLoading.value = true
			_images.value = withContext(Dispatchers.IO) {
				loadImages()
			}
			_isLoading.value = false
		}
	}

	private fun loadImages(): List<GalleryImage> {
		val result = ArrayList<GalleryImage>()
		result += loadFromMediaStore()
		result += loadFromDir(settings.getEditorSaveDir(context))
		result += loadFromDir(settings.getPagesSaveDir(safeContext()))
		result.distinctBy { it.uri }.sortedByDescending { it.dateModified }
		return result
	}

	private fun safeContext(): Context = context

	/** Deletes an image from MediaStore or its document tree, whichever holds it. */
	fun delete(image: GalleryImage) {
		viewModelScope.launch {
			withContext(Dispatchers.IO) {
				runCatching {
					if (image.uri.scheme == ContentResolver.SCHEME_CONTENT &&
						image.uri.authority == "media"
					) {
						context.contentResolver.delete(image.uri, null, null)
					} else {
						DocumentFile.fromSingleUri(context, image.uri)?.delete()
					}
				}
			}
			refresh()
		}
	}

	private fun loadFromMediaStore(): List<GalleryImage> {
		val out = ArrayList<GalleryImage>()
		runCatching {
			val resolver = context.contentResolver
			val projection = arrayOf(
				MediaStore.Images.Media._ID,
				MediaStore.Images.Media.DISPLAY_NAME,
				MediaStore.Images.Media.DATE_MODIFIED,
				MediaStore.Images.Media.SIZE,
			)
			val selection: String
			val args: Array<String>
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
				args = arrayOf("Pictures/KotatsuX%")
			} else {
				selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
				args = arrayOf("KotatsuX")
			}
			resolver.query(
				MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
				projection, selection, args,
				"${MediaStore.Images.Media.DATE_MODIFIED} DESC",
			)?.use { cursor ->
				while (cursor.moveToNext()) {
					val id = cursor.getLong(0)
					val name = cursor.getString(1) ?: continue
					val date = cursor.getLong(2) * 1000
					val size = cursor.getLong(3)
					out += GalleryImage(
						uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
						name = name,
						dateModified = date,
						size = size,
					)
				}
			}
		}
		return out
	}

	private fun loadFromDir(dir: DocumentFile?): List<GalleryImage> {
		if (dir == null) {
			return emptyList()
		}
		val out = ArrayList<GalleryImage>()
		runCatching {
			for (f in dir.listFiles()) {
				if (f.isFile && f.type?.startsWith("image/") == true) {
					out += GalleryImage(
						uri = f.uri,
						name = f.name ?: continue,
						dateModified = f.lastModified(),
						size = f.length(),
					)
				}
			}
		}
		return out
	}
}
