package com.neura.app

/** Some compatible endpoints emit a leading <think> block inside ordinary text deltas. */
internal class ThoughtStream(
    private val onText: suspend (String) -> Unit,
    private val onReasoning: suspend (String) -> Unit
) {
    private enum class Phase { Start, Thinking, Answer }
    private var phase = Phase.Start
    private var pending = ""

    suspend fun accept(chunk: String) {
        if (phase == Phase.Answer) {
            onText(chunk)
            return
        }
        pending += chunk
        if (phase == Phase.Start) {
            val prefix = pending.trimStart()
            if (prefix.startsWith("<think>", true)) {
                phase = Phase.Thinking
                pending = prefix.drop(7)
            } else if (pending.length < 128 && "<think>".startsWith(prefix, true)) {
                return
            } else {
                phase = Phase.Answer
                onText(pending)
                pending = ""
                return
            }
        }
        val end = pending.indexOf("</think>", ignoreCase = true)
        if (end >= 0) {
            if (end > 0) onReasoning(pending.take(end))
            val answer = pending.drop(end + 8)
            pending = ""
            phase = Phase.Answer
            if (answer.isNotEmpty()) onText(answer)
            return
        }
        val suffix = (minOf(pending.length, 7) downTo 1).firstOrNull {
            "</think>".startsWith(pending.takeLast(it), true)
        } ?: 0
        if (pending.length > suffix) onReasoning(pending.dropLast(suffix))
        pending = pending.takeLast(suffix)
    }

    suspend fun finish() {
        if (pending.isEmpty()) return
        if (phase == Phase.Thinking) onReasoning(pending) else onText(pending)
        pending = ""
    }
}
