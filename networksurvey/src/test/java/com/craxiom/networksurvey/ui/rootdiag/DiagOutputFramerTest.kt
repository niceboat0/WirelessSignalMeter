package com.craxiom.networksurvey.ui.rootdiag

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagOutputFramerTest {
    @Test
    fun framesLinesAcrossChunksAndPreservesUtf8() {
        val framer = DiagOutputFramer(128)
        val expected = "{\"name\":\"\u7f51\"}"
        val bytes = (expected + "\n").toByteArray(Charsets.UTF_8)
        val split = bytes.indexOfFirst { it < 0 }

        val first = framer.append(bytes, split)
        val remainder = bytes.copyOfRange(split, bytes.size)
        val second = framer.append(remainder, remainder.size)

        assertTrue(first.lines.isEmpty())
        assertEquals(listOf(expected), second.lines)
        assertEquals(0, first.invalidLines + second.invalidLines)
    }

    @Test
    fun dropsOverlongLineAndResumesAtNextLine() {
        val framer = DiagOutputFramer(5)
        val bytes = "123456\nOK\n".toByteArray()

        val result = framer.append(bytes, bytes.size)

        assertEquals(listOf("OK"), result.lines)
        assertEquals(1, result.invalidLines)
    }

    @Test
    fun largeJsonUsesCaptureDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "diag-parse-test") }
        val delegate = executor.asCoroutineDispatcher()
        val used = AtomicBoolean()
        val dispatcher = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = delegate.isDispatchNeeded(context)
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                delegate.dispatch(context, Runnable {
                    used.set(true)
                    block.run()
                })
            }
        }
        try {
            val json = """{"type":"sample","payload":"${"x".repeat(DIAG_JSON_PARSE_DISPATCH_THRESHOLD)}"}"""

            val parsed = parseDiagJson(json, dispatcher)

            assertEquals("sample", parsed.optString("type"))
            assertEquals(DIAG_JSON_PARSE_DISPATCH_THRESHOLD, parsed.getString("payload").length)
            assertTrue(used.get())
        } finally {
            delegate.close()
        }
    }
}
