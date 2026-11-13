package com.jarvis.assistant.voice

/**
 * Converts microphone PCM16 to the 16 kHz stream the Live API requires.
 *
 * Device microphones commonly run at 44.1 or 48 kHz and cannot always be opened at
 * 16 kHz. Integer ratios use a box filter (a cheap anti-alias step) and other ratios
 * use linear interpolation, which is adequate for speech and costs almost nothing.
 */
object PcmResampler {

    fun resample(input: ShortArray, inputRate: Int, outputRate: Int): ShortArray {
        if (input.isEmpty() || inputRate <= 0 || outputRate <= 0) return ShortArray(0)
        if (inputRate == outputRate) return input

        val outputLength = ((input.size.toLong() * outputRate) / inputRate).toInt().coerceAtLeast(1)
        val output = ShortArray(outputLength)

        if (outputRate < inputRate && inputRate % outputRate == 0) {
            val ratio = inputRate / outputRate
            for (index in 0 until outputLength) {
                val start = index * ratio
                if (start >= input.size) break
                val end = minOf(start + ratio, input.size)
                var sum = 0L
                for (position in start until end) sum += input[position]
                output[index] = (sum / (end - start)).toInt().toShort()
            }
            return output
        }

        val step = inputRate.toDouble() / outputRate
        for (index in 0 until outputLength) {
            val position = index * step
            val lower = position.toInt().coerceIn(0, input.size - 1)
            val upper = (lower + 1).coerceAtMost(input.size - 1)
            val fraction = position - lower
            val sample = input[lower] * (1 - fraction) + input[upper] * fraction
            output[index] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }

    /** Little-endian PCM16 bytes to samples. */
    fun bytesToShorts(bytes: ByteArray, length: Int = bytes.size): ShortArray {
        val count = (length / 2).coerceAtMost(bytes.size / 2)
        val samples = ShortArray(count)
        for (index in 0 until count) {
            val low = bytes[index * 2].toInt() and 0xFF
            val high = bytes[index * 2 + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort()
        }
        return samples
    }

    /** Samples to little-endian PCM16 bytes for AudioTrack. */
    fun shortsToBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            val value = samples[index].toInt()
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
