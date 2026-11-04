package com.nuvio.tv.core.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearKeyDrmTest {

    private val kidHex = "cc91508324ce9dcaf425a43d58f1d9d4"
    private val keyHex = "643e5474d9edd87c7d9091c8c97994ca"

    @Test
    fun `hex para bytes roundtrip`() {
        val bytes = ClearKeyDrm.hexToBytes("00" + "ff" + "10" + "7f")
        assertEquals(4, bytes.size)
        assertEquals(0, bytes[0].toInt())
        assertEquals(-1, bytes[1].toInt())
        assertEquals(16, bytes[2].toInt())
        assertEquals(127, bytes[3].toInt())
    }

    @Test(expected = IllegalStateException::class)
    fun `hex invalido rejeitado`() {
        ClearKeyDrm.hexToBytes("zzzz")
    }

    @Test
    fun `hex para base64url sem padding`() {
        // 16 bytes conhecidos: base64 padrão "ABEiM0RVZneImaq7zN3u/w=="
        assertEquals(
            "ABEiM0RVZneImaq7zN3u_w",
            ClearKeyDrm.hexToBase64Url("00112233445566778899aabbccddeeff")
        )
        // 15 bytes (resto 1) e 14 bytes (resto 2) não podem ter '='
        assertFalse(ClearKeyDrm.hexToBase64Url("00112233445566778899aabbccddeE").contains("="))
        assertFalse(ClearKeyDrm.hexToBase64Url("00112233445566778899aabbccdD").contains("="))
    }

    @Test
    fun `key set json no formato W3C`() {
        val json = ClearKeyDrm.buildKeySetJson(kidHex, keyHex)
        val expectedKid = ClearKeyDrm.hexToBase64Url(kidHex)
        val expectedKey = ClearKeyDrm.hexToBase64Url(keyHex)
        assertTrue(json.contains("\"kty\":\"oct\""))
        assertTrue(json.contains("\"k\":\"$expectedKey\""))
        assertTrue(json.contains("\"kid\":\"$expectedKid\""))
        assertTrue(json.contains("\"type\":\"temporary\""))
        assertFalse(json.contains("="))
    }

    @Test
    fun `caixa pssh v1 com systemid common e kid`() {
        val box = ClearKeyDrm.buildCommonPsshV1Box(kidHex)
        assertEquals(52, box.size)
        val systemId = ByteArray(16)
        System.arraycopy(box, 12, systemId, 0, 16)
        val expectedSystemId = ByteArray(16)
        val msb = C.COMMON_PSSH_UUID.mostSignificantBits
        val lsb = C.COMMON_PSSH_UUID.leastSignificantBits
        for (i in 0 until 8) {
            expectedSystemId[i] = (msb ushr ((7 - i) * 8)).toByte()
            expectedSystemId[8 + i] = (lsb ushr ((7 - i) * 8)).toByte()
        }
        assertTrue(expectedSystemId.contentEquals(systemId))
        val kid = ByteArray(16)
        System.arraycopy(box, 36, kid, 0, 16)
        assertTrue(ClearKeyDrm.hexToBytes(kidHex).contentEquals(kid))
        // marca 'pssh' nos bytes 4..7
        assertEquals('p'.code.toByte(), box[4])
        assertEquals('s'.code.toByte(), box[5])
        assertEquals('s'.code.toByte(), box[6])
        assertEquals('h'.code.toByte(), box[7])
    }

    @Test(expected = IllegalStateException::class)
    fun `kid com tamanho errado rejeitado`() {
        ClearKeyDrm.buildCommonPsshV1Box("aabb")
    }
}
