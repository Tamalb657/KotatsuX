package org.koitharu.kotatsu.settings.storage

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hannesdorfmann.adapterdelegates4.AsyncListDifferDelegationAdapter
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ToastErrorObserver
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.ui.AlertDialogFragment
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.databinding.DialogDirectorySelectBinding
import org.koitharu.kotatsu.local.ui.MoveDownloadsService

@AndroidEntryPoint
class MoveDownloadsDialog : AlertDialogFragment<DialogDirectorySelectBinding>(),
	OnListItemClickListener<DirectoryModel> {

	private val viewModel: MoveDownloadsViewModel by viewModels()
	private var pendingIsMove: Boolean? = null

	private val pickFileTreeLauncher = OpenDocumentTreeHelper(
		activityResultCaller = this,
		flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
			or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
	) {
		if (it != null) viewModel.onCustomDirectoryPicked(it)
	}

	private val storagePermissionLauncher = registerForActivityResult(
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			RequestStorageManagerPermissionContract()
		} else {
			ActivityResultContracts.RequestPermission()
		},
	) { granted ->
		if (granted) {
			viewModel.refresh()
			if (!pickFileTreeLauncher.tryLaunch(null)) {
				Toast.makeText(
					context ?: return@registerForActivityResult,
					R.string.operation_not_supported,
					Toast.LENGTH_SHORT,
				).show()
			}
		}
	}

	private val notificationPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) {
		val isMove = pendingIsMove
		pendingIsMove = null
		if (isMove != null) {
			launchTransferService(isMove)
		}
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): DialogDirectorySelectBinding {
		return DialogDirectorySelectBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: DialogDirectorySelectBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = AsyncListDifferDelegationAdapter(DirectoryDiffCallback(), directoryAD(this))
		binding.root.adapter = adapter
		viewModel.items.observe(viewLifecycleOwner) { adapter.items = it }
		viewModel.onPickDirectory.observeEvent(viewLifecycleOwner) { pickCustomDirectory() }
		viewModel.onError.observeEvent(viewLifecycleOwner, ToastErrorObserver(binding.root, this))
	}

	override fun onBuildDialog(builder: MaterialAlertDialogBuilder): MaterialAlertDialogBuilder {
		return super.onBuildDialog(builder)
			.setCancelable(true)
			.setTitle(R.string.select_saved_location)
			.setPositiveButton(R.string.move_action, null)
			.setNeutralButton(R.string.copy_action, null)
			.setNegativeButton(android.R.string.cancel, null)
	}

	override fun onStart() {
		super.onStart()
		val alertDialog = dialog as? AlertDialog ?: return
		alertDialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
			onActionClicked(isMove = true)
		}
		alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
			onActionClicked(isMove = false)
		}
	}

	override fun onItemClick(item: DirectoryModel, view: View) {
		viewModel.onItemClick(item)
	}

	private fun onActionClicked(isMove: Boolean) {
		val ctx = context ?: return
		if (viewModel.selectedDirectory.value == null) {
			Toast.makeText(ctx, R.string.select_saved_location, Toast.LENGTH_SHORT).show()
			return
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
		) {
			pendingIsMove = isMove
			if (!notificationPermissionLauncher.tryLaunch(Manifest.permission.POST_NOTIFICATIONS)) {
				pendingIsMove = null
				launchTransferService(isMove)
			}
			return
		}
		launchTransferService(isMove)
	}

	private fun launchTransferService(isMove: Boolean) {
		val ctx = context ?: return
		val targetDir = viewModel.selectedDirectory.value ?: return
		if (!MoveDownloadsService.startAll(ctx, targetDir, isMove)) {
			Toast.makeText(ctx, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
		dismissAllowingStateLoss()
	}

	private fun pickCustomDirectory() {
		if (!storagePermissionLauncher.tryLaunch(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
			Toast.makeText(context ?: return, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}
}
