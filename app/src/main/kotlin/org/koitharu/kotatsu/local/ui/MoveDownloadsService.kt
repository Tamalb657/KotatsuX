package org.koitharu.kotatsu.local.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.CheckResult
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ErrorReporterReceiver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.CoroutineIntentService
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.computeSize
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.withPartialWakeLock
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.MoveLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
@SuppressLint("InlinedApi")
class MoveDownloadsService : CoroutineIntentService() {

	@Inject
	lateinit var moveLocalMangaUseCase: MoveLocalMangaUseCase

	@Inject
	lateinit var localMangaRepository: LocalMangaRepository

	@Inject
	lateinit var storageManager: LocalStorageManager

	@Inject
	lateinit var settings: AppSettings

	private lateinit var notificationManager: NotificationManagerCompat

	override fun onCreate() {
		super.onCreate()
		notificationManager = NotificationManagerCompat.from(applicationContext)
		createNotificationChannel()
	}

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		val targetPath = requireNotNull(intent.getStringExtra(EXTRA_TARGET_DIR)) { "No target directory specified" }
		val targetDir = File(targetPath)
		val isMove = intent.getBooleanExtra(EXTRA_IS_MOVE, true)
		val singleSourcePath = intent.getStringExtra(EXTRA_SOURCE_FILE)

		val targetName = storageManager.getDirectoryDisplayName(targetDir, isFullPath = true)
		val initialNotification = buildProgressNotification(
			isMove = isMove,
			targetName = targetName,
			contentText = getString(R.string.processing_),
			progressPermille = 0,
			isIndeterminate = true,
		)
		setForeground(
			FOREGROUND_NOTIFICATION_ID,
			initialNotification,
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)

		powerManager.withPartialWakeLock(TAG) {
			// Register custom directory so KotatsuX indexes the target saved location
			val appDirs = storageManager.getApplicationStorageDirs()
			if (targetDir !in appDirs && targetDir !in settings.userSpecifiedMangaDirectories) {
				settings.userSpecifiedMangaDirectories = settings.userSpecifiedMangaDirectories + targetDir
				storageManager.setDirIsNoMedia(targetDir)
			}

			val allCandidates: List<LocalManga> = if (singleSourcePath != null) {
				val file = File(singleSourcePath)
				val parsed = LocalMangaParser.getOrNull(file)?.getManga(withDetails = false)
				listOfNotNull(parsed)
			} else {
				localMangaRepository.getRawListAsFlow().toList()
			}

			if (allCandidates.isEmpty()) {
				showCompletionNotification(
					title = getString(if (isMove) R.string.downloads_moved else R.string.downloads_copied),
					message = getString(R.string.no_downloads_to_move),
				)
				withContext(Dispatchers.Main) {
					Toast.makeText(applicationContext, R.string.no_downloads_to_move, Toast.LENGTH_SHORT).show()
				}
				return@withPartialWakeLock
			}

			val targetCanonical = targetDir.canonicalPath
			val toProcess = allCandidates.filter {
				it.file.parentFile?.canonicalPath != targetCanonical
			}

			if (toProcess.isEmpty()) {
				if (singleSourcePath == null && isMove) {
					settings.mangaStorageDir = targetDir
				}
				val msg = getString(R.string.downloads_already_in_location, targetName)
				showCompletionNotification(
					title = getString(if (isMove) R.string.downloads_moved else R.string.downloads_copied),
					message = msg,
				)
				withContext(Dispatchers.Main) {
					Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
				}
				return@withPartialWakeLock
			}

			val sizes = withContext(Dispatchers.IO) {
				toProcess.map { it.file.computeSize().coerceAtLeast(1L) }
			}
			val totalBytes = sizes.sum().coerceAtLeast(1L)
			var completedBytesBefore = 0L
			var movedCount = 0
			var lastNotifyMs = 0L

			for ((index, localManga) in toProcess.withIndex()) {
				val itemSize = sizes[index]
				val title = localManga.manga.title

				notifyProgressIfPermitted(
					buildProgressNotification(
						isMove = isMove,
						targetName = targetName,
						contentText = getString(
							R.string.move_progress_pattern,
							index + 1,
							toProcess.size,
							title,
							((completedBytesBefore * 100L) / totalBytes).toInt().coerceIn(0, 100),
						),
						progressPermille = ((completedBytesBefore * 1000L) / totalBytes).toInt().coerceIn(0, 1000),
						isIndeterminate = false,
					),
				)

				val didProcess = moveLocalMangaUseCase(
					manga = localManga.manga,
					targetDir = targetDir,
					deleteSource = isMove,
					skipIfExists = singleSourcePath == null,
				) { itemCopiedBytes ->
					val now = SystemClock.elapsedRealtime()
					if (now - lastNotifyMs >= NOTIFY_INTERVAL_MS) {
						lastNotifyMs = now
						val currentTotal = (completedBytesBefore + itemCopiedBytes).coerceAtMost(totalBytes)
						val permille = ((currentTotal * 1000L) / totalBytes).toInt().coerceIn(0, 1000)
						val percent = (permille / 10).coerceIn(0, 100)
						notifyProgressIfPermitted(
							buildProgressNotification(
								isMove = isMove,
								targetName = targetName,
								contentText = getString(
									R.string.move_progress_pattern,
									index + 1,
									toProcess.size,
									title,
									percent,
								),
								progressPermille = permille,
								isIndeterminate = false,
							),
						)
					}
				}
				if (didProcess) {
					movedCount++
				}
				completedBytesBefore += itemSize
			}

			if (singleSourcePath == null && isMove) {
				settings.mangaStorageDir = targetDir
			}

			val doneTitle = getString(if (isMove) R.string.downloads_moved else R.string.downloads_copied)
			val doneMessage = if (singleSourcePath != null) {
				getString(if (isMove) R.string.move_done else R.string.copy_done, targetName)
			} else {
				getString(
					if (isMove) R.string.downloads_moved_summary else R.string.downloads_copied_summary,
					movedCount,
					targetName,
				)
			}
			showCompletionNotification(title = doneTitle, message = doneMessage)
			withContext(Dispatchers.Main) {
				Toast.makeText(applicationContext, doneMessage, Toast.LENGTH_SHORT).show()
			}
		}
	}

	override fun IntentJobContext.onError(error: Throwable) {
		if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			return
		}
		val message = error.getDisplayMessage(applicationContext.resources)
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(getString(R.string.error_occurred))
			.setContentText(message)
			.setStyle(NotificationCompat.BigTextStyle().bigText(message))
			.setSmallIcon(android.R.drawable.stat_notify_error)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setAutoCancel(true)
			.apply {
				ErrorReporterReceiver.getNotificationAction(
					context = applicationContext,
					e = error,
					notificationId = startId,
					notificationTag = TAG,
				)?.let(::addAction)
			}
			.build()
		notificationManager.notify(TAG, startId, notification)
	}

	@SuppressLint("MissingPermission")
	private fun notifyProgressIfPermitted(notification: Notification) {
		if (applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
		}
	}

	@SuppressLint("MissingPermission")
	private fun IntentJobContext.showCompletionNotification(title: String, message: String) {
		if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			return
		}
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(title)
			.setContentText(message)
			.setStyle(NotificationCompat.BigTextStyle().bigText(message))
			.setSmallIcon(R.drawable.ic_stat_done)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setDefaults(0)
			.setSilent(true)
			.setAutoCancel(true)
			.setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					0,
					AppRouter.homeIntent(this@MoveDownloadsService),
					0,
					false,
				),
			)
			.build()
		notificationManager.notify(TAG, startId, notification)
	}

	private fun IntentJobContext.buildProgressNotification(
		isMove: Boolean,
		targetName: String,
		contentText: String,
		progressPermille: Int,
		isIndeterminate: Boolean,
	): Notification {
		return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(getString(if (isMove) R.string.moving_downloads else R.string.copying_downloads))
			.setContentText(contentText)
			.setSubText(targetName)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setDefaults(0)
			.setSilent(true)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setProgress(1000, progressPermille.coerceIn(0, 1000), isIndeterminate)
			.setSmallIcon(android.R.drawable.stat_sys_upload)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.addAction(
				appcompatR.drawable.abc_ic_clear_material,
				applicationContext.getString(android.R.string.cancel),
				getCancelIntent(),
			)
			.build()
	}

	private fun createNotificationChannel() {
		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
			.setName(getString(R.string.move_your_downloads))
			.setShowBadge(true)
			.setVibrationEnabled(false)
			.setSound(null, null)
			.setLightsEnabled(false)
			.build()
		notificationManager.createNotificationChannel(channel)
	}

	companion object {

		private const val TAG = "MOVE_DOWNLOADS"
		const val CHANNEL_ID = "move_downloads"
		const val FOREGROUND_NOTIFICATION_ID = 39
		private const val NOTIFY_INTERVAL_MS = 120L

		private const val EXTRA_TARGET_DIR = "target_dir"
		private const val EXTRA_IS_MOVE = "is_move"
		private const val EXTRA_SOURCE_FILE = "source_file"

		@CheckResult
		fun startAll(context: Context, targetDir: File, isMove: Boolean): Boolean = try {
			val intent = Intent(context, MoveDownloadsService::class.java)
				.putExtra(EXTRA_TARGET_DIR, targetDir.absolutePath)
				.putExtra(EXTRA_IS_MOVE, isMove)
			ContextCompat.startForegroundService(context, intent)
			true
		} catch (e: Exception) {
			e.printStackTraceDebug()
			false
		}

		@CheckResult
		fun startSingle(context: Context, sourceFile: File, targetDir: File, isMove: Boolean): Boolean = try {
			val intent = Intent(context, MoveDownloadsService::class.java)
				.putExtra(EXTRA_TARGET_DIR, targetDir.absolutePath)
				.putExtra(EXTRA_IS_MOVE, isMove)
				.putExtra(EXTRA_SOURCE_FILE, sourceFile.absolutePath)
			ContextCompat.startForegroundService(context, intent)
			true
		} catch (e: Exception) {
			e.printStackTraceDebug()
			false
		}
	}
}
