package com.nuvio.tv.core.player

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolve streams "externais" de TV ao vivo para reprodução no player interno
 * do app, antes de recorrer ao navegador.
 *
 * Caso principal: páginas /watch/ do FayaTV (addon de TV ao vivo de fábrica).
 * A página expõe o manifesto real em `var fileUrl = "..."` e, quando o canal é
 * CENC, as chaves ClearKey em claro (`var kid`/`var key`, hex) que o jwplayer
 * da página usa no navegador. Com manifesto + kid/key o ExoPlayer reproduz no
 * app via ClearKey (ver [ClearKeyDrm]).
 */
object ExternalStreamResolver {

    private const val TAG = "ExternalStreamResolver"

    /** UA de navegador: a página /watch/ é servida a browsers. */
    const val PAGE_USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    data class ResolvedExternalStream(
        val url: String,
        val clearKeyKid: String? = null,
        val clearKeyKey: String? = null
    )

    /** Delegações da classificação pura — um único ponto de chamada nas telas. */
    fun isWatchPageUrl(url: String?): Boolean = ExternalStreamUrlPolicy.isWatchPageUrl(url)

    fun isDirectMediaUrl(url: String?): Boolean = ExternalStreamUrlPolicy.isDirectMediaUrl(url)

    private val FILE_URL_REGEX = Regex("""var\s+fileUrl\s*=\s*"([^"]+)"""")
    private val KID_REGEX = Regex("""var\s+kid\s*=\s*"([0-9a-fA-F]{32})"""")
    private val KEY_REGEX = Regex("""var\s+key\s*=\s*"([0-9a-fA-F]{32})"""")

    /** Extrai manifesto + ClearKey do HTML da página. Puro e testável. */
    fun parseWatchPage(html: String): ResolvedExternalStream? {
        val fileUrl = FILE_URL_REGEX.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        if (!ExternalStreamUrlPolicy.isDirectMediaUrl(fileUrl)) return null
        val kid = KID_REGEX.find(html)?.groupValues?.get(1)?.lowercase()
        val key = KEY_REGEX.find(html)?.groupValues?.get(1)?.lowercase()
        return ResolvedExternalStream(
            url = fileUrl,
            clearKeyKid = kid?.takeIf { key != null },
            clearKeyKey = key?.takeIf { kid != null }
        )
    }

    /**
     * Busca a página /watch/ e extrai o stream real. Retorna null para página
     * inacessível, template alterado ou manifesto não-reproduzível — o chamador
     * cai para o navegador nesses casos.
     */
    suspend fun resolve(watchPageUrl: String): ResolvedExternalStream? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(watchPageUrl)
                    .header("User-Agent", PAGE_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
                httpClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    parseWatchPage(response.body?.string().orEmpty())
                }
            }.onFailure { Log.w(TAG, "Falha ao resolver página de TV ao vivo: ${it.message}") }
                .getOrNull()
        }

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}
