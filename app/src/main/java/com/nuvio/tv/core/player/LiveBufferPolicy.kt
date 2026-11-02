package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

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

    fun buildLiveLoadControl(): LoadControl = DefaultLoadControl.Builder()
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
