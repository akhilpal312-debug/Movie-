package com.jarvis.assistant.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PcmResamplerTest {

    @Test
    fun `same rate is returned unchanged`() {
        val input = shortArrayOf(1, 2, 3, 4)
        assertThat(PcmResampler.resample(input, 16_000, 16_000)).isEqualTo(input)
    }

    @Test
    fun `48k to 16k keeps one third of the samples and the signal level`() {
        val input = ShortArray(4_800) { 1_000 }
        val output = PcmResampler.resample(input, 48_000, 16_000)

        assertThat(output.size).isEqualTo(1_600)
        assertThat(output.all { it.toInt() == 1_000 }).isTrue()
    }

    @Test
    fun `44_1k to 16k produces the expected length`() {
        val input = ShortArray(4_410) { 500 }
        val output = PcmResampler.resample(input, 44_100, 16_000)

        assertThat(output.size).isAtLeast(1_598)
        assertThat(output.size).isAtMost(1_602)
        assertThat(output.first().toInt()).isAtLeast(495)
        assertThat(output.first().toInt()).isAtMost(505)
    }

    @Test
    fun `handles empty input`() {
        assertThat(PcmResampler.resample(ShortArray(0), 48_000, 16_000)).isEmpty()
    }

    @Test
    fun `byte and short conversions round-trip`() {
        val samples = shortArrayOf(0, 1, -1, 32_767, -32_768)
        val bytes = PcmResampler.shortsToBytes(samples)
        val restored = PcmResampler.bytesToShorts(bytes)

        assertThat(restored.toList()).isEqualTo(samples.toList())
    }

    @Test
    fun `decoding is little endian`() {
        val bytes = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte())
        val samples = PcmResampler.bytesToShorts(bytes)

        assertThat(samples[0].toInt()).isEqualTo(1)
        assertThat(samples[1].toInt()).isEqualTo(-1)
    }
}
