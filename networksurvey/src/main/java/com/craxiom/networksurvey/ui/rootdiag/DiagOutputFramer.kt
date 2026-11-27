package com.craxiom.networksurvey.ui.rootdiag

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal data class DiagOutputLines(
    val lines: List<String> = emptyList(),
    val invalidLines: Int = 0,
)

internal class DiagOutputFramer(private val maxLineBytes: Int) {
    init {
        require(maxLineBytes > 0)
    }
    private val line = ByteArrayOutputStream()
    private var overlong = false

    fun append(buffer: ByteArray, size: Int): DiagOutputLines {
        require(size in 0..buffer.size)
        if (size == 0) return DiagOutputLines()

        val lines = ArrayList<String>()
        var invalidLines = 0
        for (index in 0 until size) {
            val byte = buffer[index].toInt()
            when {
                byte == '\n'.code -> {
                    if (overlong) invalidLines++
                    else if (line.size() > 0) lines.add(line.toString(Charsets.UTF_8.name()))
                    line.reset()
                    overlong = false
                }
                !overlong && line.size() < maxLineBytes -> line.write(byte)
                else -> overlong = true
            }
        }
        return DiagOutputLines(lines, invalidLines)
    }
}

internal const val DIAG_JSON_PARSE_DISPATCH_THRESHOLD = 4_096

internal suspend fun parseDiagJson(
    text: String,
    dispatcher: CoroutineDispatcher,
): JSONObject = if (text.length >= DIAG_JSON_PARSE_DISPATCH_THRESHOLD) {
    withContext(dispatcher) { JSONObject(text) }
} else {
    JSONObject(text)
}
