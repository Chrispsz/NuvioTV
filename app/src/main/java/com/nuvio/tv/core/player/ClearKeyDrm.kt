package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import java.nio.ByteBuffer

/**
 * ClearKey DRM para a TV ao vivo do pack TV Minimal.
 *
 * Canais CENC do FayaTV expõem, na página /watch/, o manifesto DASH e as
 * chaves em claro (kid/key hex). O ExoPlayer reproduz esse conteúdo com o
 * plugin ClearKey do MediaDrm do Android — sem servidor de licença: o
 * [LocalMediaDrmCallback] devolve o key set JSON quando o plugin pede chaves.
 *
 * Detalhe fino: o gerente padrão do Media3 só abre sessão quando o manifesto
 * traz SchemeData COM dados para o UUID ClearKey/Common PSSH. Manifestos
 * "widevine+playready sem pssh" (caso do FayaTV) não têm nada aproveitável,
 * então o wrapper abaixo injeta um SchemeData Common PSSH sintético (caixa
 * PSSH v1 com o KID) em acquireSession/getCryptoType — o mesmo init data que
 * o player JavaScript da página usa no navegador.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object ClearKeyDrm {

    private const val B64URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Key set JSON no formato aceito pelo plugin ClearKey do Android (W3C EME). */
    fun buildKeySetJson(kidHex: String, keyHex: String): String {
        val kid = hexToBase64Url(kidHex)
        val key = hexToBase64Url(keyHex)
        return """{"keys":[{"kty":"oct","k":"$key","kid":"$kid"}],"type":"temporary"}"""
    }

    /**
     * Gerente de sessão ClearKey para usar via `setDrmSessionManagerProvider`.
     * A provider deve chamar esta função a cada MediaSource novo — cada playback
     * precisa de um gerente próprio (o Media3 conta referências por instância).
     */
    fun createSessionManager(keySetJson: String, kidHex: String): DrmSessionManager {
        val base = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(LocalMediaDrmCallback(keySetJson.toByteArray(Charsets.UTF_8)))
        return CommonPsshInjectingSessionManager(base, buildCommonPsshV1Box(kidHex))
    }

    /**
     * Caixa PSSH v1 com o SystemID Common (C.COMMON_PSSH_UUID) e o KID do
     * conteúdo — o plugin ClearKey do Android extrai o KID dela para casar com
     * as chaves do key set JSON.
     */
    fun buildCommonPsshV1Box(kidHex: String): ByteArray {
        val kid = hexToBytes(kidHex)
        check(kid.size == 16) { "KID ClearKey deve ter 16 bytes hex" }
        val box = ByteBuffer.allocate(52)
        box.putInt(52) // tamanho da caixa
        box.putInt(0x70737368) // 'pssh'
        box.putInt(0x01000000) // version=1, flags=0
        box.putLong(C.COMMON_PSSH_UUID.mostSignificantBits)
        box.putLong(C.COMMON_PSSH_UUID.leastSignificantBits)
        box.putInt(1) // KID_count
        box.put(kid) // KID
        box.putInt(0) // dataSize
        return box.array()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().lowercase()
        check(clean.length % 2 == 0 && clean.all { it in '0'..'9' || it in 'a'..'f' }) {
            "valor hex inválido: ${hex.take(8)}…"
        }
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[2 * i], 16) shl 4) + Character.digit(clean[2 * i + 1], 16)).toByte()
        }
    }

    /** Base64url SEM padding, como o key set JSON do ClearKey exige. */
    fun hexToBase64Url(hex: String): String {
        val bytes = hexToBytes(hex)
        val out = StringBuilder()
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(B64URL_ALPHABET[(n shr 18) and 63])
                .append(B64URL_ALPHABET[(n shr 12) and 63])
                .append(B64URL_ALPHABET[(n shr 6) and 63])
                .append(B64URL_ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(B64URL_ALPHABET[(n shr 18) and 63])
                    .append(B64URL_ALPHABET[(n shr 12) and 63])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(B64URL_ALPHABET[(n shr 18) and 63])
                    .append(B64URL_ALPHABET[(n shr 12) and 63])
                    .append(B64URL_ALPHABET[(n shr 6) and 63])
            }
        }
        return out.toString()
    }

    /**
     * Injeta um SchemeData Common PSSH sintético nos formats com drmInitData,
     * contornando a exigência do DefaultDrmSessionManager de SchemeData com
     * dados para o UUID dele (manifesto só-widevine/playready não tem).
     */
    private class CommonPsshInjectingSessionManager(
        private val base: DefaultDrmSessionManager,
        private val commonPssh: ByteArray
    ) : DrmSessionManager {

        private fun formatWithInjectedPssh(format: Format): Format {
            val existing = format.drmInitData ?: return format
            val synthetic = DrmInitData(
                existing.schemeType ?: C.CENC_TYPE_cenc,
                DrmInitData.SchemeData(C.COMMON_PSSH_UUID, MimeTypes.VIDEO_MP4, commonPssh)
            )
            return format.buildUpon().setDrmInitData(synthetic).build()
        }

        override fun acquireSession(
            eventDispatcher: DrmSessionEventListener.EventDispatcher?,
            format: Format
        ): DrmSession? = base.acquireSession(eventDispatcher, formatWithInjectedPssh(format))

        override fun getCryptoType(format: Format): Int =
            base.getCryptoType(formatWithInjectedPssh(format))

        override fun prepare() = base.prepare()

        override fun release() = base.release()

        override fun setPlayer(playbackLooper: android.os.Looper, playerId: PlayerId) =
            base.setPlayer(playbackLooper, playerId)

        override fun preacquireSession(
            eventDispatcher: DrmSessionEventListener.EventDispatcher?,
            format: Format
        ): DrmSessionManager.DrmSessionReference =
            base.preacquireSession(eventDispatcher, formatWithInjectedPssh(format))
    }
}
