package org.koitharu.kotatsu.core.util.ext

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class WithTickerFlowTest {

	@Test
	fun `withTicker emits every item including final 100 percent item without tail hang`() = runBlocking {
		val startMs = System.currentTimeMillis()
		val items = flow {
			for (i in 0 until 24) {
				emit(i)
			}
		}.withTicker(2L, TimeUnit.SECONDS).toList()
		val elapsedMs = System.currentTimeMillis() - startMs

		assertEquals((0 until 24).toList(), items)
		assertEquals(23, items.last())
		assertTrue("Expected immediate completion (<1000ms), got ${elapsedMs}ms", elapsedMs < 1000L)
	}

	@Test
	fun `withTicker re-emits latest value on interval while upstream is active`() = runBlocking {
		val items = flow {
			emit(10)
			delay(130L)
			emit(20)
		}.withTicker(50L, TimeUnit.MILLISECONDS).toList()

		assertEquals(10, items.first())
		assertEquals(20, items.last())
		assertTrue("Expected periodic ticker re-emissions, got $items", items.count { it == 10 } >= 2)
	}
}
