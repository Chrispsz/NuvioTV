package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalStreamUrlPolicyTest {

    @Test
    fun `url de midia direta e reconhecida`() {
        assertTrue(
            ExternalStreamUrlPolicy.isDirectMediaUrl(
                "https://otte.cache.aiv-cdn.net/live/out/v1/abc/cenc.mpd"
            )
        )
        assertTrue(
            ExternalStreamUrlPolicy.isDirectMediaUrl(
                "https://cdn.exemplo.tv/live/index.m3u8?token=abc"
            )
        )
        assertTrue(
            ExternalStreamUrlPolicy.isDirectMediaUrl("https://cdn.exemplo.tv/video.mp4")
        )
        assertTrue(ExternalStreamUrlPolicy.isDirectMediaUrl("https://cdn.exemplo.tv/live.ts"))
        assertTrue(ExternalStreamUrlPolicy.isDirectMediaUrl("HTTPS://CDN.EXEMPO.TV/A.M3U8"))
    }

    @Test
    fun `url de pagina nao e midia direta`() {
        assertFalse(
            ExternalStreamUrlPolicy.isDirectMediaUrl("https://fayatv.stravo.site/watch/494214d8-7c56")
        )
        assertFalse(ExternalStreamUrlPolicy.isDirectMediaUrl("https://payments.stravo.site/packages/abc/"))
        assertFalse(ExternalStreamUrlPolicy.isDirectMediaUrl(null))
        assertFalse(ExternalStreamUrlPolicy.isDirectMediaUrl(""))
        assertFalse(ExternalStreamUrlPolicy.isDirectMediaUrl("https://cdn.exemplo.tv/embed/player"))
    }

    @Test
    fun `pagina watch do fayatv e reconhecida`() {
        assertTrue(
            ExternalStreamUrlPolicy.isWatchPageUrl(
                "https://fayatv.stravo.site/watch/494214d8-7c56-40bc-a5de-06cc697adfdf"
            )
        )
        assertTrue(
            ExternalStreamUrlPolicy.isWatchPageUrl(
                "https://FAYATV.STRAVO.SITE/watch/494214d8"
            )
        )
        // subdomínio da mesma plataforma também é página de assistência
        assertTrue(
            ExternalStreamUrlPolicy.isWatchPageUrl(
                "https://fayatv2.stravo.site/watch/494214d8"
            )
        )
    }

    @Test
    fun `outras urls nao sao pagina watch`() {
        assertFalse(
            ExternalStreamUrlPolicy.isWatchPageUrl(
                "https://payments.stravo.site/packages/oP4ahL8kCFPc/"
            )
        )
        assertFalse(
            ExternalStreamUrlPolicy.isWatchPageUrl(
                "https://fayatv.stravo.site/catalog/494214d8"
            )
        )
        assertFalse(
            ExternalStreamUrlPolicy.isWatchPageUrl("https://otte.cache.aiv-cdn.net/live/cenc.mpd")
        )
        assertFalse(ExternalStreamUrlPolicy.isWatchPageUrl(null))
        assertFalse(ExternalStreamUrlPolicy.isWatchPageUrl(""))
    }
}
