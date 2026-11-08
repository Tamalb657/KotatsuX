package org.koitharu.kotatsu.gallery.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.image.CoilImageView
import org.koitharu.kotatsu.databinding.ItemGalleryImageBinding
import org.koitharu.kotatsu.gallery.domain.GalleryImage

/** Simple 3-column grid adapter for the saved-images gallery. */
class GalleryAdapter(
	private val listener: Listener,
) : RecyclerView.Adapter<GalleryAdapter.Holder>() {

	interface Listener {
		fun onImageClick(image: GalleryImage)
	}

	private val differ = AsyncListDiffer(this, DIFF)

	fun submitList(list: List<GalleryImage>) = differ.submitList(list)

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
		val binding = ItemGalleryImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		return Holder(binding, listener)
	}

	override fun onBindViewHolder(holder: Holder, position: Int) {
		holder.bind(differ.currentList[position])
	}

	override fun getItemCount(): Int = differ.currentList.size

	class Holder(
		private val binding: ItemGalleryImageBinding,
		listener: Listener,
	) : RecyclerView.ViewHolder(binding.root) {

		private var item: GalleryImage? = null

		init {
			binding.root.setOnClickListener { item?.let(listener::onImageClick) }
		}

		fun bind(item: GalleryImage) {
			this.item = item
			binding.imageView.setImageAsync(item.uri.toString())
			binding.root.contentDescription = item.name
		}
	}

	private companion object {
		val DIFF = object : DiffUtil.ItemCallback<GalleryImage>() {
			override fun areItemsTheSame(oldItem: GalleryImage, newItem: GalleryImage) = oldItem.uri == newItem.uri
			override fun areContentsTheSame(oldItem: GalleryImage, newItem: GalleryImage) = oldItem == newItem
		}
	}
}
