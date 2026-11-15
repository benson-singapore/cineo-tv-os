package com.nova.tv

import org.junit.Assert.*
import org.junit.Test

class OrbitArtworkDecoderTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun decryptsCapturedImageBlocks() {
        val ciphertext = hex("4fe897a45e77d7d20e34ac4b0af97d431d7131eaf8c546c959c069b445d7b9c5")
        val expected = hex("ffd8ffdb008400100b0c0e0c0a100e0d0e1211101318281a181616183123251d")
        assertArrayEquals(expected, OrbitArtworkDecoder.decrypt(ciphertext))
        // A partial final cipher block is zero-filled; preceding complete blocks stay intact.
        assertArrayEquals(expected.copyOf(16), OrbitArtworkDecoder.decrypt(ciphertext.copyOf(19)).copyOf(16))
        assertEquals(19, OrbitArtworkDecoder.decrypt(ciphertext.copyOf(19)).size)
    }

    @Test fun limitsCompatibilityToExactImageHosts() {
        assertTrue(OrbitArtworkDecoder.supports("https://pic.drleao.cn/upload/a.jpeg"))
        assertFalse(OrbitArtworkDecoder.supports("https://pic.drleao.cn.example.org/a.jpeg"))
        assertFalse(OrbitArtworkDecoder.supports("file:///a.jpeg"))
        assertEquals("https://new2.drleao.cn/upload/a.jpeg.webp",
            OrbitArtworkDecoder.webpFallback("https://pic.drleao.cn/upload/a.jpeg?auth_key=expired"))
        assertNull(OrbitArtworkDecoder.webpFallback("https://example.org/a.jpeg"))
        assertNull(OrbitArtworkDecoder.webpFallback("https://pic.drleao.cn/static/a.jpeg"))
    }
}
