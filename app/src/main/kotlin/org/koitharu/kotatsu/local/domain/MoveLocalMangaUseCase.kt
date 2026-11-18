package org.koitharu.kotatsu.local.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

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
			it.canonicalPath != local.file.parentFile?.canonicalPath
		}
	}

	suspend operator fun invoke(
		manga: Manga,
		targetDir: File,
		deleteSource: Boolean,
		skipIfExists: Boolean = false,
		onProgress: ((copiedBytes: Long) -> Unit)? = null,
	): Boolean {
		val source = findLocal(manga).file
		if (source.parentFile?.canonicalPath == targetDir.canonicalPath) {
			return false
		}
		targetDir.mkdirs()
		val dest = File(targetDir, source.name)
		if (dest.exists()) {
			if (skipIfExists) {
				return false
			}
			throw IOException("A file or folder with the same name (${source.name}) already exists in the target")
		}
		val oldLocal = localMangaIndex.get(manga.id, withDetails = true)
			?: LocalMangaParser(source).getManga(withDetails = true)
		val oldChapters = oldLocal.manga.chapters.orEmpty()

		// 1. Copy to a .tmp staging path so partial/cancelled copies are ignored and cleaned up cleanly
		val tempDest = File(targetDir, "${source.name}.tmp")
		if (tempDest.exists()) {
			withContext(Dispatchers.IO) { tempDest.deleteRecursively() }
		}
		val callerContext = currentCoroutineContext()
		try {
			withContext(Dispatchers.IO) {
				copyRecursivelyWithProgress(source, tempDest, callerContext, onProgress)
				if (!tempDest.renameTo(dest)) {
					copyRecursivelyWithProgress(tempDest, dest, callerContext, null)
					tempDest.deleteRecursively()
				}
			}
		} catch (e: Throwable) {
			withContext(Dispatchers.IO) { tempDest.deleteRecursively() }
			throw e
		}

		// 2. Re-parse the copy and register it (manga row + local_index row)
		val newLocal = LocalMangaParser(dest).getManga(withDetails = true)
		localMangaIndex.put(newLocal)
		val newId = newLocal.manga.id
		if (newId != manga.id) {
			// 3. Path-derived id: carry the user data over before the old row cascades away
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
					bookmarksDao.upsert(
						bookmarks.map { b ->
							val ordinal = oldChapters.indexOfFirst { it.id == b.chapterId }
							val newChapterId = newLocal.manga.chapters.orEmpty()
								.getOrNull(ordinal)?.id
								?: newLocal.manga.chapters.orEmpty().firstOrNull()?.id
								?: b.chapterId
							b.copy(mangaId = newId, chapterId = newChapterId)
						},
					)
				}
				if (deleteSource) {
					favouritesDao.delete(manga.id)
					db.getHistoryDao().delete(manga.id)
					db.getPreferencesDao().delete(manga.id)
					bookmarksDao.deleteByMangaId(manga.id)
				}
			}
		}

		// 4. For a move, remove the original after everything succeeded
		if (deleteSource) {
			localMangaIndex.delete(manga.id)
			withContext(Dispatchers.IO) {
				source.deleteRecursively()
			}
		}
		localStorageChanges.emit(null)
		return true
	}

	private suspend fun findLocal(manga: Manga): LocalManga = if (manga.isLocal) {
		LocalManga(manga)
	} else {
		localMangaRepository.findSavedManga(manga)
			?: throw IOException("Cannot find the downloaded copy of ${manga.title}")
	}

	private fun copyRecursivelyWithProgress(
		source: File,
		dest: File,
		coroutineContext: CoroutineContext,
		onProgress: ((copiedBytes: Long) -> Unit)?,
	) {
		var copiedBytes = 0L
		val buffer = ByteArray(BUFFER_SIZE)
		if (source.isDirectory) {
			dest.mkdirs()
			source.walkTopDown().forEach { f ->
				coroutineContext.ensureActive()
				val target = File(dest, f.relativeTo(source).path)
				if (f.isDirectory) {
					target.mkdirs()
				} else {
					target.parentFile?.mkdirs()
					copiedBytes = copyFileChunked(f, target, buffer, copiedBytes, coroutineContext, onProgress)
				}
			}
		} else {
			dest.parentFile?.mkdirs()
			copyFileChunked(source, dest, buffer, copiedBytes, coroutineContext, onProgress)
		}
	}

	private fun copyFileChunked(
		source: File,
		target: File,
		buffer: ByteArray,
		initialCopied: Long,
		coroutineContext: CoroutineContext,
		onProgress: ((copiedBytes: Long) -> Unit)?,
	): Long {
		var copied = initialCopied
		FileInputStream(source).use { input ->
			FileOutputStream(target).use { output ->
				while (true) {
					coroutineContext.ensureActive()
					val read = input.read(buffer)
					if (read < 0) break
					output.write(buffer, 0, read)
					copied += read
					onProgress?.invoke(copied)
				}
				output.fd.sync()
			}
		}
		return copied
	}

	private companion object {
		const val BUFFER_SIZE = 64 * 1024
	}
}
