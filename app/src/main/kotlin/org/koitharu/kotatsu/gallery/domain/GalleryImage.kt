package org.koitharu.kotatsu.gallery.domain

import android.net.Uri

/** One saved image shown in the gallery grid. */
data class GalleryImage(
	val uri: Uri,
	val name: String,
	val dateModified: Long,
	val size: Long,
)
