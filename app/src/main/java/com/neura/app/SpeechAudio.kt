package com.neura.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object SpeechAudio {
    fun format(model: String): String {
        val id = model.lowercase()
        return if (id.contains("heygen") || (id.contains("gemini") && id.contains("tts"))) "pcm" else "mp3"
    }

    fun voice(model: String, selected: String): String = when {
        selected.isNotBlank() && selected != "alloy" -> selected
        model.contains("heygen", true) -> "nadine"
        model.contains("gemini", true) -> "Kore"
        else -> "alloy"
    }

    fun wav(pcm: ByteArray, contentType: String, model: String = ""): ByteArray {
        fun parameter(name: String, default: Int): Int = Regex("(?:^|;)\\s*$name=\"?(\\d+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)?.toIntOrNull() ?: default
        val rate = parameter("rate", if (model.contains("heygen", true)) 44100 else 24000)
        val channels = parameter("channels", 1)
        require(rate in 8000..192000 && channels in 1..2) { "Неподдерживаемые параметры PCM: $rate Гц, $channels каналов" }
        require(pcm.isNotEmpty() && pcm.size % (channels * 2) == 0) { "API вернул повреждённое PCM-аудио" }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        header.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }
}
