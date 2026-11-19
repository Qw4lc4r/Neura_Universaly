package com.neura.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechAudioTest {
    @Test fun selectsPcmForHeygenAndGeminiIncludingFreeVariants() {
        assertEquals("pcm", SpeechAudio.format("heygen/heygen-voice-1-turbo:free"))
        assertEquals("pcm", SpeechAudio.format("google/gemini-flash-tts"))
        assertEquals("mp3", SpeechAudio.format("minimax/speech"))
        assertEquals("nadine", SpeechAudio.voice("heygen/heygen-voice-1-turbo:free", "alloy"))
        val header = ByteBuffer.wrap(SpeechAudio.wav(byteArrayOf(0, 0), "audio/pcm", "heygen/voice")).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44100, header.getInt(24))
    }
    @Test fun createsPlayableWavWithServerSampleRateAndChannels() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = SpeechAudio.wav(pcm, "audio/pcm;rate=48000;channels=2")
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(2, header.getShort(22).toInt())
        assertEquals(48000, header.getInt(24))
        assertEquals(192000, header.getInt(28))
        assertEquals(4, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
    @Test fun usesDocumented24KhzMonoDefault() {
        val header = ByteBuffer.wrap(SpeechAudio.wav(byteArrayOf(0, 0), "audio/pcm")).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24000, header.getInt(24))
        assertEquals(1, header.getShort(22).toInt())
    }
    @Test fun rejectsBrokenPcmInsteadOfSavingUnplayableAudio() {
        assertTrue(runCatching { SpeechAudio.wav(byteArrayOf(1), "audio/pcm") }.isFailure)
        assertTrue(runCatching { SpeechAudio.wav(byteArrayOf(0, 0), "audio/pcm;rate=0") }.isFailure)
    }
}
