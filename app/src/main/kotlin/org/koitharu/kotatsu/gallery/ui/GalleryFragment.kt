package org.koitharu.kotatsu.gallery.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.FragmentGalleryBinding
import org.koitharu.kotatsu.gallery.domain.GalleryImage
import org.koitharu.kotatsu.reader.ui.crop.PageCropActivity

/** A grid of the images saved from the reader/editor with re-edit, share and delete. */
@AndroidEntryPoint
class GalleryFragment : BaseFragment<FragmentGalleryBinding>(), GalleryAdapter.Listener {

	private val viewModel by viewModels<GalleryViewModel>()
	private var adapter: GalleryAdapter? = null

	override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		// no custom inset handling: the grid respects the default padding
		return insets
	}

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): FragmentGalleryBinding = FragmentGalleryBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentGalleryBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = GalleryAdapter(this)
		this.adapter = adapter
		binding.recyclerView.setHasFixedSize(true)
		binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
		binding.recyclerView.adapter = adapter
		viewModel.images.observe(viewLifecycleOwner) { images ->
			adapter.submitList(images)
			binding.textViewEmpty.isVisible = images.isEmpty()
			binding.recyclerView.isVisible = images.isNotEmpty()
		}
		viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
			binding.progressBar.isVisible = loading
		}
	}

	override fun onResume() {
		super.onResume()
		viewModel.refresh()
	}

	override fun onImageClick(image: GalleryImage) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(image.name)
			.setItems(
				arrayOf(
					getString(R.string.edit),
					getString(R.string.share),
					getString(R.string.delete),
				),
			) { _, which ->
				when (which) {
					0 -> startActivity(
						PageCropActivity.newIntent(requireContext(), image.uri),
					)

					1 -> share(image)
					2 -> confirmDelete(image)
				}
			}
			.show()
	}

	private fun share(image: GalleryImage) {
		val intent = Intent(Intent.ACTION_SEND).apply {
			type = "image/*"
			putExtra(Intent.EXTRA_STREAM, image.uri)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		startActivity(Intent.createChooser(intent, getString(R.string.share), null))
	}

	private fun confirmDelete(image: GalleryImage) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.delete)
			.setMessage(getString(R.string._s_deleted_from_local_storage, image.name))
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.delete) { _, _ ->
				viewModel.delete(image)
				Snackbar.make(requireView(), R.string.done, Snackbar.LENGTH_SHORT).show()
			}
			.show()
	}
}
