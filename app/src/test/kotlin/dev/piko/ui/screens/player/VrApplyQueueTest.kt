package dev.piko.ui.screens.player

import dev.piko.shared.media.player.VrModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VrApplyQueueTest {
    @Test
    fun `requests submitted during apply drain to the latest mode`() {
        val tasks = ArrayDeque<() -> Unit>()
        val applied = mutableListOf<VrApplyRequest>()
        lateinit var queue: VrApplyQueue
        queue = VrApplyQueue(
            execute = { tasks += it },
            stopped = { false },
            apply = { request ->
                applied += request
                if (applied.size == 1) {
                    queue.submit(VrModeState(yawRadians = 0.2f))
                    queue.submit(VrModeState(yawRadians = 0.3f), refreshOutput = true)
                }
            },
        )

        queue.submit(VrModeState(yawRadians = 0.1f))
        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()

        assertEquals(listOf(0.1f, 0.3f), applied.map { it.mode.yawRadians })
        assertFalse(applied[0].refreshOutput)
        assertTrue(applied[1].refreshOutput)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `output refresh survives later view-only updates`() {
        val tasks = ArrayDeque<() -> Unit>()
        val applied = mutableListOf<VrApplyRequest>()
        val queue = VrApplyQueue(
            execute = { tasks += it },
            stopped = { false },
            apply = applied::add,
        )

        queue.submit(VrModeState(yawRadians = 0.1f), refreshOutput = true)
        queue.submit(VrModeState(yawRadians = 0.2f))
        tasks.removeFirst().invoke()

        assertEquals(1, applied.size)
        assertEquals(0.2f, applied.single().mode.yawRadians)
        assertTrue(applied.single().refreshOutput)
    }

    @Test
    fun `concurrent producer cannot lose the final request`() {
        val executor = Executors.newSingleThreadExecutor()
        val firstEntered = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val applied = mutableListOf<VrApplyRequest>()
        val queue = VrApplyQueue(
            execute = { task -> executor.execute(task) },
            stopped = { false },
            apply = { request ->
                if (applied.isEmpty()) {
                    firstEntered.countDown()
                    assertTrue(finishFirst.await(5, TimeUnit.SECONDS))
                }
                synchronized(applied) { applied += request }
                if (request.mode.yawRadians == 1f) finished.countDown()
            },
        )

        try {
            queue.submit(VrModeState(yawRadians = -1f))
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val producer = Thread {
                repeat(100) { index ->
                    queue.submit(VrModeState(yawRadians = index / 100f))
                }
                queue.submit(VrModeState(yawRadians = 1f), refreshOutput = true)
            }
            producer.start()
            producer.join(5_000)
            finishFirst.countDown()

            assertTrue(finished.await(5, TimeUnit.SECONDS))
            val last = synchronized(applied) { applied.last() }
            assertEquals(1f, last.mode.yawRadians)
            assertTrue(last.refreshOutput)
        } finally {
            finishFirst.countDown()
            executor.shutdownNow()
        }
    }
}
