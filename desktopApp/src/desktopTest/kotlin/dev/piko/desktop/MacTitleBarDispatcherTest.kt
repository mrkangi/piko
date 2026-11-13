package dev.piko.desktop

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MacTitleBarDispatcherTest {
    @Test
    fun nativeCallsRunSeriallyOffTheCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val workers = mutableSetOf<Thread>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        coroutineScope {
            repeat(4) {
                launch {
                    runMacTitleBarNative {
                        workers += Thread.currentThread()
                        val current = active.incrementAndGet()
                        peak.updateAndGet { previous -> maxOf(previous, current) }
                        Thread.sleep(10)
                        active.decrementAndGet()
                    }
                }
            }
        }
        val worker = workers.single()
        assertEquals("Piko-MacTitleBar", worker.name)
        assertEquals(false, worker === caller)
        assertEquals(1, peak.get())
    }
}
