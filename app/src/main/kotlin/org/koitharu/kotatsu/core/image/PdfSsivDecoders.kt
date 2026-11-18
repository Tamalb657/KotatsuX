package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import java.io.File
import java.util.Objects

private const val PDF_SCHEME = "pdf"

/**
 * Renders a whole PDF page into a bitmap for the zoomable reader view.
 * URIs of the form `pdf:/path/file.pdf#<page index>`.
 */
class PdfBitmapDecoder(
	private val delegate: ImageDecoder,
) : ImageDecoder {

	override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap = if (uri.scheme == PDF_SCHEME) {
		val full = PdfPageRenderer.renderToBitmap(uri)
		val scaled = scaleForSample(full, sampleSize)
		if (scaled !== full) {
			full.recycle()
		}
		scaled
	} else {
		delegate.decode(context, uri, sampleSize)
	}

	override fun decode(context: Context, source: ImageSource, sampleSize: Int): Bitmap =
		if (source is ImageSource.Uri && source.uri.scheme == PDF_SCHEME) {
			decode(context, source.uri, sampleSize)
		} else {
			delegate.decode(context, source, sampleSize)
		}

	private fun scaleForSample(bitmap: Bitmap, sampleSize: Int): Bitmap =
		if (sampleSize <= 1) {
			bitmap
		} else {
			Bitmap.createScaledBitmap(
				bitmap,
				(bitmap.width / sampleSize.toFloat()).toInt().coerceAtLeast(1),
				(bitmap.height / sampleSize.toFloat()).toInt().coerceAtLeast(1),
				true,
			)
		}

	class Factory(
		private val delegate: DecoderFactory<out ImageDecoder>,
	) : DecoderFactory<ImageDecoder> {

		override val bitmapConfig: Bitmap.Config?
			get() = delegate.bitmapConfig

		override fun make(): ImageDecoder = PdfBitmapDecoder(delegate.make())
	}
}

/**
 * Region decoder for the zoomable reader view: renders the PDF page once and answers
 * tile requests by cropping and downsampling it. Other schemes are delegated cleanly.
 */
class PdfRegionDecoder(
	private val delegate: ImageRegionDecoder,
) : ImageRegionDecoder {

	@Volatile
	private var bitmap: Bitmap? = null

	@Volatile
	private var isPdf: Boolean = false

	override fun init(context: Context, uri: Uri): Point {
		if (uri.scheme != PDF_SCHEME) {
			isPdf = false
			return delegate.init(context, uri)
		}
		isPdf = true
		bitmap?.recycle()
		val rendered = PdfPageRenderer.renderToBitmap(uri)
		bitmap = rendered
		return Point(rendered.width, rendered.height)
	}

	override fun init(context: Context, source: ImageSource): Point {
		if (source is ImageSource.Uri && source.uri.scheme == PDF_SCHEME) {
			return init(context, source.uri)
		}
		isPdf = false
		return delegate.init(context, source)
	}

	override val isReady: Boolean
		get() = if (isPdf) bitmap?.isRecycled == false else delegate.isReady

	override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
		if (!isPdf) {
			return delegate.decodeRegion(sRect, sampleSize)
		}
		val base = checkNotNull(bitmap) { "PdfRegionDecoder is not initialized" }
		val left = sRect.left.coerceIn(0, base.width - 1)
		val top = sRect.top.coerceIn(0, base.height - 1)
		val right = sRect.right.coerceIn(left + 1, base.width)
		val bottom = sRect.bottom.coerceIn(top + 1, base.height)
		val cropped = Bitmap.createBitmap(base, left, top, right - left, bottom - top)
		if (sampleSize <= 1) {
			return if (cropped === base) {
				base.copy(base.config ?: Bitmap.Config.ARGB_8888, false)
			} else {
				cropped
			}
		}
		val outW = ((right - left) / sampleSize).coerceAtLeast(1)
		val outH = ((bottom - top) / sampleSize).coerceAtLeast(1)
		val scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true)
		if (scaled !== cropped && cropped !== base) {
			cropped.recycle()
		}
		return if (scaled === base) {
			base.copy(base.config ?: Bitmap.Config.ARGB_8888, false)
		} else {
			scaled
		}
	}

	override fun recycle() {
		if (isPdf) {
			bitmap?.recycle()
			bitmap = null
		} else {
			delegate.recycle()
		}
	}

	class Factory(
		private val delegate: DecoderFactory<out ImageRegionDecoder>,
	) : DecoderFactory<ImageRegionDecoder> {

		override val bitmapConfig: Bitmap.Config?
			get() = delegate.bitmapConfig

		override fun make(): ImageRegionDecoder = PdfRegionDecoder(delegate.make())
	}
}

internal object PdfPageRenderer {

	fun renderToBitmap(uri: Uri): Bitmap {
		val file = File(Objects.requireNonNull(uri.path))
		val pageIndex = uri.fragment?.toIntOrNull()?.coerceAtLeast(0) ?: 0
		return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
			PdfRenderer(pfd).use { renderer ->
				renderer.openPage(pageIndex.coerceAtMost(renderer.pageCount - 1)).use { page ->
					val scale = minOf(3f, 2048f / maxOf(page.width, page.height).coerceAtLeast(1)).coerceAtLeast(1f)
					val width = (page.width * scale).toInt().coerceAtLeast(1)
					val height = (page.height * scale).toInt().coerceAtLeast(1)
					Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
						bitmap.eraseColor(Color.WHITE)
						page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
					}
				}
			}
		}
	}
}
