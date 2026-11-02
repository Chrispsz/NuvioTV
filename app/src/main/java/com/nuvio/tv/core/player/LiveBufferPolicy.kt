package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * TV Minimal Edition — perfil de buffer para streams AO VIVO (IPTV), portado do
 * player do tv-minimal (v14.x, tunado em hardware fraco / TCL P7K).
 *
 * Lições consolidadas do tv-minimal que este perfil respeita:
 *  - LIVE 30s/60s (min/max) com PLAY 1s: retoma rápido após underrun sem rebuffer
 *    visível (a v13 usava 8s/30s e causava rebuffer em Wi-Fi disputado);
 *  - back buffer 10s com retain-from-keyframe;
 *  - NUNCA cache de disco em live (segmento ao vivo é uso único — lição v8.5);
 *  - NUNCA seek para trás em live (lição v8.4 — o edge monitor com seek foi removido);
 *  - sem DoH como DNS primário (quebra IPTV com auth por IP).
 *
 * Detecção por URL, conservadora: falso negativo cai no perfil VOD padrão
 * (comportamento atual) — nunca o contrário. "live" solto NÃO é token
 * (daria match em "deliver", "delivery" etc.).
 */
object LiveBufferPolicy {

    const val LIVE_MIN_BUFFER_MS = 30_000
    const val LIVE_MAX_BUFFER_MS = 60_000
    const val LIVE_BUFFER_FOR_PLAYBACK_MS = 1_000
    const val LIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
    const val LIVE_BACK_BUFFER_MS = 10_000

    private val LIVE_URL_KEYWORDS = listOf(
        "/live", "live/", "mpegts", "type=live", "is_live", "livetv", "/hls/live"
    )

    fun isLiveUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return LIVE_URL_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * User-Agent do VLC para conteúdo AO VIVO. Painéis IPTV (Xtream/m3u) e CDNs na
     * frente de Cloudflare comumente devolvem 403 para User-Agents desconhecidos,
     * mas aceitam o VLC — o player mais universal de IPTV. Política conservadora:
     * nunca sobrescreve UA que o addon/usuário definiu explicitamente.
     */
    const val LIVE_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    /**
     * Aplica a política de rede do perfil live: se a URL for live e os headers do
     * stream não trouxerem User-Agent, injeta o UA do VLC. Para VOD e headers com
     * UA explícito, devolve o mapa inalterado.
     */
    fun applyLiveNetworkPolicy(url: String?, headers: Map<String, String>): Map<String, String> {
        if (!isLiveUrl(url)) return headers
        val hasUserAgent = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
        return if (hasUserAgent) headers else headers + ("User-Agent" to LIVE_USER_AGENT)
    }

    // Retorna DefaultLoadControl (não LoadControl) para que a inferência do Kotlin no
    // PlayerRuntimeControllerInitialization produza o mesmo LUB de antes do perfil LIVE
    // (todas as branches atribuem a _loadControl: DefaultLoadControl?).
    @UnstableApi
    fun buildLiveLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            LIVE_MIN_BUFFER_MS,
            LIVE_MAX_BUFFER_MS,
            LIVE_BUFFER_FOR_PLAYBACK_MS,
            LIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        )
        .setBackBuffer(LIVE_BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .setTargetBufferBytes(C.LENGTH_UNSET)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}
