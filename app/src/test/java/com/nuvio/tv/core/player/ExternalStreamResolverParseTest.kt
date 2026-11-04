package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalStreamResolverParseTest {

    /** Trecho real da página /watch/ do FayaTV (capturado em 05/11/2026). */
    private val realWatchPageHtml = """
        <!DOCTYPE html>
        <html lang="en">
        <head><title>TNT SPORTS FHD | Live Stream</title></head>
        <body>
        <div id="jwplayerDiv"></div>
        <script type="text/javascript">

        var fileUrl = "https://otte.cache.aiv-cdn.net/bom-nitro/live/clients/dash/enc/dev1hjwzh9/out/v1/a5f0ee7ad7b24906b14f43bebbbe4678/cenc.mpd";


          var kid = "cc91508324ce9dcaf425a43d58f1d9d4";



          var key = "643e5474d9edd87c7d9091c8c97994ca";


        var isMpd = /\.mpd(\?.*)?${'$'}/i.test(fileUrl);
        </script>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun `pagina real do fayatv extrai manifesto e clearkey`() {
        val resolved = ExternalStreamResolver.parseWatchPage(realWatchPageHtml)
        assertEquals(
            "https://otte.cache.aiv-cdn.net/bom-nitro/live/clients/dash/enc/dev1hjwzh9/out/v1/a5f0ee7ad7b24906b14f43bebbbe4678/cenc.mpd",
            resolved?.url
        )
        assertEquals("cc91508324ce9dcaf425a43d58f1d9d4", resolved?.clearKeyKid)
        assertEquals("643e5474d9edd87c7d9091c8c97994ca", resolved?.clearKeyKey)
    }

    @Test
    fun `canal limpo sem kid e key devolve stream sem clearkey`() {
        val html = """
            <script>
            var fileUrl = "https://cdn.stravo.site/live/canal1/index.m3u8";
            </script>
        """.trimIndent()
        val resolved = ExternalStreamResolver.parseWatchPage(html)
        assertEquals("https://cdn.stravo.site/live/canal1/index.m3u8", resolved?.url)
        assertNull(resolved?.clearKeyKid)
        assertNull(resolved?.clearKeyKey)
    }

    @Test
    fun `html sem manifesto retorna null`() {
        assertNull(ExternalStreamResolver.parseWatchPage("<html><body>erro</body></html>"))
    }

    @Test
    fun `manifesto que nao e midia direta retorna null`() {
        val html = """
            <script>
            var fileUrl = "https://payments.stravo.site/packages/abc/";
            </script>
        """.trimIndent()
        assertNull(ExternalStreamResolver.parseWatchPage(html))
    }
}
