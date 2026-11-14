package org.koitharu.kotatsu.local.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Copies a downloaded manga byte-for-byte to another configured storage and, for a
 * move, removes the original. Manga that carry an index.json keep their database
 * identity; path-derived ids are remapped so history, favourites and per-manga
 * reader settings survive the move.
 */
@Reusable
class MoveLocalMangaUseCase @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val localMangaRepository: LocalMangaRepository,
	private val localMangaIndex: LocalMangaIndex,
	private val db: MangaDatabase,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) {

	/** Configured storages that can receive a copy, excluding the manga's own folder. */
	suspend fun getTargets(manga: Manga): List<File> = withContext(Dispatchers.IO) {
		val local = findLocal(manga)
		storageManager.getWriteableDirs().filter {
			it.absolutePath != local.file.parentFile?.absolutePath
		}
	}

	suspend operator fun invoke(manga: Manga, targetDir: File, deleteSource: Boolean) {
		val source = findLocal(manga).file
		if (source.parentFile?.absolutePath == targetDir.absolutePath) {
			return
		}
		val dest = File(targetDir, source.name)
		check(!dest.exists()) { "A file or folder with the same name already exists in the target" }
		val oldLocal = localMangaIndex.get(manga.id, withDetails = true)
			?: throw IOException("Cannot read the current manga data")
		val oldChapters = oldLocal.manga.chapters.orEmpty()

		// 1. the real byte-for-byte copy
		withContext(Dispatchers.IO) {
			copyRecursively(source, dest)
		}

		// 2. re-parse the copy and register it (manga row + local_index row)
		val newLocal = LocalMangaParser(dest).getManga(withDetails = true)
		localMangaIndex.put(newLocal)
		val newId = newLocal.manga.id
		if (newId != manga.id) {
			// 3. path-derived id: carry the user data over before the old row cascades away
			db.withTransaction {
				val favouritesDao = db.getFavouritesDao()
				for (f in favouritesDao.findAllRaw(manga.id)) {
					favouritesDao.upsert(f.copy(mangaId = newId))
				}
				db.getHistoryDao().find(manga.id)?.let { h ->
					val ordinal = oldChapters.indexOfFirst { it.id == h.chapterId }
					val newChapterId = newLocal.manga.chapters.orEmpty()
						.getOrNull(ordinal)?.id
						?: newLocal.manga.chapters.orEmpty().firstOrNull()?.id
						?: h.chapterId
					db.getHistoryDao().upsert(h.copy(mangaId = newId, chapterId = newChapterId))
				}
				db.getPreferencesDao().find(manga.id)?.let { p ->
					db.getPreferencesDao().upsert(p.copy(mangaId = newId))
				}
				val bookmarksDao = db.getBookmarksDao()
				val bookmarks = bookmarksDao.findAll(manga.id)
				if (bookmarks.isNotEmpty()) {
					bookmarksDao.upsert(bookmarks.map { b ->
						val ordinal = oldChapters.indexOfFirst { it.id == b.chapterId }
						val newChapterId = newLocal.manga.chapters.orEmpty()
							.getOrNull(ordinal)?.id
							?: newLocal.manga.chapters.orEmpty().firstOrNull()?.id
							?: b.chapterId
						b.copy(mangaId = newId, chapterId = newChapterId)
					})
				}
				if (deleteSource) {
					favouritesDao.delete(manga.id)
					db.getHistoryDao().delete(manga.id)
					db.getPreferencesDao().delete(manga.id)
					bookmarksDao.deleteByMangaId(manga.id)
				}
			}
		}

		// 4. for a move, remove the original after everything succeeded
		if (deleteSource) {
			localMangaIndex.delete(manga.id)
			withContext(Dispatchers.IO) {
				source.deleteRecursively()
			}
		}
		localStorageChanges.emit(null)
	}

	private suspend fun findLocal(manga: Manga): LocalManga = if (manga.isLocal) {
		LocalManga(manga)
	} else {
		localMangaRepository.findSavedManga(manga)
			?: throw IOException("Cannot find the downloaded copy of ${manga.title}")
	}

	private fun copyRecursively(source: File, dest: File) {
		if (source.isDirectory) {
			dest.mkdirs()
			source.walkTopDown().forEach { f ->
				val target = File(dest, f.relativeTo(source).path)
				if (f.isDirectory) {
					target.mkdirs()
				} else {
					f.copyTo(target, overwrite = false)
				}
			}
		} else {
			source.copyTo(dest, overwrite = false)
		}
	}
}
