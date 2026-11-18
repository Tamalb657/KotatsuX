package org.koitharu.kotatsu.settings.storage

import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.isWriteable
import org.koitharu.kotatsu.local.data.LocalStorageManager
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MoveDownloadsViewModel @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val settings: AppSettings,
) : BaseViewModel() {

	val items = MutableStateFlow(emptyList<DirectoryModel>())
	val selectedDirectory = MutableStateFlow<File?>(null)
	val onPickDirectory = MutableEventFlow<Unit>()

	init {
		refresh()
	}

	fun onItemClick(item: DirectoryModel) {
		if (item.file != null) {
			selectedDirectory.value = item.file
			items.value = items.value.map { model ->
				model.copy(isChecked = model.file != null && model.file == item.file)
			}
		} else {
			onPickDirectory.call(Unit)
		}
	}

	fun onCustomDirectoryPicked(uri: Uri) {
		launchJob(Dispatchers.Default) {
			storageManager.takePermissions(uri)
			val dir = storageManager.resolveUri(uri)
			if (!dir.isWriteable()) {
				throw AccessDeniedException(dir)
			}
			if (dir !in storageManager.getApplicationStorageDirs() && dir !in settings.userSpecifiedMangaDirectories) {
				settings.userSpecifiedMangaDirectories = settings.userSpecifiedMangaDirectories + dir
				storageManager.setDirIsNoMedia(dir)
			}
			selectedDirectory.value = dir
			refresh(preferSelected = dir)
		}
	}

	fun refresh(preferSelected: File? = selectedDirectory.value) {
		launchJob(Dispatchers.Default) {
			val currentDefault = storageManager.getDefaultWriteableDir()
			val available = storageManager.getWriteableDirs()
			val selected = preferSelected?.takeIf { it in available }
				?: available.firstOrNull { it != currentDefault }
				?: currentDefault
				?: available.firstOrNull()
			selectedDirectory.value = selected
			items.value = buildList(available.size + 1) {
				available.mapTo(this) { dir ->
					DirectoryModel(
						title = storageManager.getDirectoryDisplayName(dir, isFullPath = false),
						titleRes = 0,
						file = dir,
						isChecked = dir == selected,
						isAvailable = true,
						isRemovable = false,
					)
				}
				this += DirectoryModel(
					title = null,
					titleRes = R.string.pick_custom_directory,
					file = null,
					isChecked = false,
					isAvailable = true,
					isRemovable = false,
				)
			}
		}
	}
}
