package com.nuvio.tv.core.player

/**
 * Classificação pura de URLs de streams "externais" (campo `externalUrl` do
 * protocolo Stremio) — decide o que o app consegue reproduzir no player interno
 * antes de recorrer ao navegador.
 *
 * Dois casos são tocáveis no app:
 * 1. URL de mídia direta (.m3u8/.mpd/.mp4/.ts): alguns addons devolvem o vídeo
 *    direto em `externalUrl` em vez de `url`. O ExoPlayer toca normalmente —
 *    trata-lo como link de navegador é classificação errada, não limitação.
 * 2. Página /watch/ do FayaTV (plataforma stravo.site, addon de TV ao vivo de
 *    fábrica): HTML com o manifesto real (fileUrl) e, quando o canal é CENC,
 *    as chaves ClearKey em claro (kid/key). Verificado na página real em
 *    05/11/2026: jwplayer com drm.clearkey {keyId, key}.
 *
 * Qualquer outro `externalUrl` (páginas de compra, embeds de terceiros,
 * portais) segue para o navegador como sempre.
 */
object ExternalStreamUrlPolicy {

    /** Plataforma do addon de TV ao vivo do pack (instância configurada BR). */
    private const val WATCH_PAGE_HOST_SUFFIX = "fayatv.stravo.site"
    private const val WATCH_PAGE_PATH_PREFIX = "/watch/"

    private val DIRECT_MEDIA_REGEX =
        Regex("""\.(m3u8|mpd|mp4|ts)(\?.*)?$""", RegexOption.IGNORE_CASE)

    /**
     * True quando a URL aponta para mídia que o player interno reproduz
     * diretamente (HLS/DASH/MP4/TS), com ou sem query string.
     */
    fun isDirectMediaUrl(url: String?): Boolean =
        url?.let(DIRECT_MEDIA_REGEX::containsMatchIn) == true

    /**
     * True quando a URL é uma página de assistência da plataforma de TV ao
     * vivo do pack — resolvível para mídia direta (+ ClearKey) em tempo de
     * reprodução por [ExternalStreamResolver].
     */
    fun isWatchPageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path?.lowercase() ?: return false
        return (host == WATCH_PAGE_HOST_SUFFIX || host.endsWith(".$WATCH_PAGE_HOST_SUFFIX")) &&
            path.startsWith(WATCH_PAGE_PATH_PREFIX)
    }
}
