package org.koitharu.kotatsu.reader.ui.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomographyMathTest {

	@Test
	fun `computeHomography maps unit square to arbitrary convex quad accurately`() {
		val src = listOf(
			NormPt(0f, 0f),
			NormPt(1f, 0f),
			NormPt(1f, 1f),
			NormPt(0f, 1f),
		)
		val dst = listOf(
			NormPt(0.16f, 0.14f),
			NormPt(0.84f, 0.12f),
			NormPt(0.95f, 0.88f),
			NormPt(0.06f, 0.85f),
		)

		assertTrue(HomographyMath.isQuadConvex(dst))
		val h = HomographyMath.computeHomography(src, dst)
		assertNotNull(h)

		for (i in 0 until 4) {
			val mapped = HomographyMath.applyHomographyPt(h, src[i].x, src[i].y)
			assertEquals(dst[i].x, mapped.x, 1e-4f)
			assertEquals(dst[i].y, mapped.y, 1e-4f)
		}
	}

	@Test
	fun `isAxisAlignedRectQuad distinguishes upright rectangles from trapezoids`() {
		val full = EditorDocumentState.createDefaultCropQuad()
		assertTrue(HomographyMath.isDefaultFullCropQuad(full))
		assertTrue(HomographyMath.isAxisAlignedRectQuad(full))

		val subRect = listOf(
			NormPt(0.1f, 0.2f),
			NormPt(0.8f, 0.2f),
			NormPt(0.8f, 0.9f),
			NormPt(0.1f, 0.9f),
		)
		assertFalse(HomographyMath.isDefaultFullCropQuad(subRect))
		assertTrue(HomographyMath.isAxisAlignedRectQuad(subRect))

		val trap = listOf(
			NormPt(0.18f, 0.2f),
			NormPt(0.82f, 0.2f),
			NormPt(0.92f, 0.9f),
			NormPt(0.08f, 0.9f),
		)
		assertFalse(HomographyMath.isAxisAlignedRectQuad(trap))
		assertTrue(HomographyMath.isQuadConvex(trap))
	}

	@Test
	fun `pointInPoly and deepCopy isolate document history snapshots`() {
		val poly = listOf(
			NormPt(10f, 10f),
			NormPt(90f, 10f),
			NormPt(90f, 90f),
			NormPt(10f, 90f),
		)
		assertTrue(HomographyMath.pointInPoly(50f, 50f, poly))
		assertFalse(HomographyMath.pointInPoly(5f, 50f, poly))

		val doc1 = EditorDocumentState()
		val doc2 = doc1.deepCopy()
		doc2.cropQuad[0] = NormPt(0.25f, 0.25f)
		assertEquals(0f, doc1.cropQuad[0].x, 1e-5f)
		assertEquals(0.25f, doc2.cropQuad[0].x, 1e-5f)
	}
}
