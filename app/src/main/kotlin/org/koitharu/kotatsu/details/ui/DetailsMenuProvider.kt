package org.koitharu.kotatsu.details.ui

import android.app.Activity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.domain.MoveLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.ui.MoveDownloadsService
import org.koitharu.kotatsu.parsers.model.Manga

class DetailsMenuProvider(
	private val activity: FragmentActivity,
	private val viewModel: DetailsViewModel,
	private val snackbarHost: View,
	private val appShortcutManager: AppShortcutManager,
	private val moveLocalMangaUseCase: MoveLocalMangaUseCase,
	private val localStorageManager: LocalStorageManager,
) : MenuProvider, ActivityResultCallback<ActivityResult> {

	private val activityForResultLauncher = activity.registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
		this,
	)

	private val router: AppRouter
		get() = activity.router

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_details, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		val manga = viewModel.manga.value
		menu.findItem(R.id.action_share).isVisible = manga != null && AppRouter.isShareSupported(manga)
		menu.findItem(R.id.action_save).isVisible = manga?.source != null && manga.source != LocalMangaSource
		menu.findItem(R.id.action_delete).isVisible = manga?.source == LocalMangaSource
		menu.findItem(R.id.action_move).isVisible = manga?.source == LocalMangaSource
		menu.findItem(R.id.action_copy).isVisible = manga?.source == LocalMangaSource
		menu.findItem(R.id.action_browser).isVisible = manga?.publicUrl?.isHttpUrl() == true
		menu.findItem(R.id.action_alternatives).isVisible = manga?.source != LocalMangaSource
		menu.findItem(R.id.action_replace_source).isVisible = manga?.source != LocalMangaSource
		menu.findItem(R.id.action_shortcut).isVisible = appShortcutManager.isPinShortcutSupported
		menu.findItem(R.id.action_scrobbling).isVisible = viewModel.isScrobblingAvailable
		menu.findItem(R.id.action_online).isVisible = viewModel.remoteManga.value != null
		menu.findItem(R.id.action_stats).isVisible = viewModel.isStatsAvailable.value
		// Only for what the reader has no stake in yet: saving or reading it already says they are interested
		menu.findItem(R.id.action_not_interested).isVisible = manga != null &&
			viewModel.favouriteCategories.value.isEmpty() && viewModel.history.value == null &&
			!viewModel.dismissedIndex.value.matches(manga)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		val manga = viewModel.getMangaOrNull() ?: return false
		when (menuItem.itemId) {
			R.id.action_share -> {
				router.showShareDialog(manga)
			}

			R.id.action_delete -> {
				buildAlertDialog(activity) {
					setTitle(R.string.delete_manga)
					setMessage(activity.getString(R.string.text_delete_local_manga, manga.title))
					setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteLocal() }
					setNegativeButton(android.R.string.cancel, null)
				}.show()
			}

			R.id.action_move, R.id.action_copy -> {
				showStoragePicker(manga, menuItem.itemId == R.id.action_move)
			}

			R.id.action_save -> {
				router.showDownloadDialog(manga, snackbarHost)
			}

			R.id.action_browser -> {
				router.openBrowser(url = manga.publicUrl, source = manga.source, title = manga.title)
			}

			R.id.action_online -> {
				router.openDetails(viewModel.remoteManga.value ?: return false)
			}

			R.id.action_related -> {
				router.openSearch(manga.title)
			}

			R.id.action_alternatives -> {
				router.openAlternatives(manga)
			}

			R.id.action_replace_source -> {
				router.openSourceReplacement(listOf(manga))
			}

			R.id.action_stats -> {
				router.showStatisticSheet(manga)
			}

			R.id.action_scrobbling -> {
				router.showScrobblingSelectorSheet(manga, null)
			}

			R.id.action_not_interested -> {
				viewModel.dismissFromSuggestions()
			}

			R.id.action_shortcut -> {
				activity.lifecycleScope.launch {
					if (!appShortcutManager.requestPinShortcut(manga)) {
						Snackbar.make(snackbarHost, R.string.operation_not_supported, Snackbar.LENGTH_SHORT)
							.show()
					}
				}
			}

			R.id.action_edit_override -> {
				val intent = AppRouter.overrideEditIntent(activity, manga)
				activityForResultLauncher.launch(intent)
			}

			else -> return false
		}
		return true
	}

	override fun onActivityResult(result: ActivityResult) {
		if (result.resultCode == Activity.RESULT_OK) {
			viewModel.reload()
		}
	}

	/** Lets the user pick one of the configured download storages as the destination. */
	private fun showStoragePicker(manga: Manga, isMove: Boolean) {
		activity.lifecycleScope.launch {
			val targets = runCatching { moveLocalMangaUseCase.getTargets(manga) }
				.onFailure { it.printStackTraceDebug() }
				.getOrDefault(emptyList())
			if (targets.isEmpty()) {
				Snackbar.make(
					snackbarHost,
					R.string.operation_not_supported,
					Snackbar.LENGTH_SHORT,
				).show()
				return@launch
			}
			val names = targets.map { localStorageManager.getDirectoryDisplayName(it, isFullPath = true) }
			buildAlertDialog(activity) {
				setTitle(if (isMove) R.string.move_to else R.string.copy_to)
				setMessage(activity.getString(R.string.move_or_copy_pick))
				setItems(names.toTypedArray()) { _, which ->
					val localFile = runCatching { LocalManga(manga).file }.getOrNull()
					if (localFile != null && MoveDownloadsService.startSingle(activity, localFile, targets[which], isMove)) {
						if (isMove) {
							activity.finishAfterTransition()
						}
					} else {
						viewModel.moveLocal(targets[which], isMove)
					}
				}
				setNegativeButton(android.R.string.cancel, null)
			}.show()
		}
	}
}
