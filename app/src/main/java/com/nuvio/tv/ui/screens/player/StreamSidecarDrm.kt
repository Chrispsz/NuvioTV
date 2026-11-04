package com.nuvio.tv.ui.screens.player

/**
 * Chaves ClearKey (kid/key hex) de um stream externo resolvido, entregues ao
 * player por URL de reprodução — mesmo padrão do [StreamSidecarSubtitles]:
 * evita alargar os argumentos de navegação do Player para dados que só
 * existem quando o app resolveu o stream na hora.
 */
internal object StreamSidecarDrm {

    @Volatile
    private var playbackUrl: String? = null

    @Volatile
    private var kid: String? = null

    @Volatile
    private var key: String? = null

    fun set(url: String?, kid: String?, key: String?) {
        val playbackUrl = url?.takeIf { it.isNotBlank() }
        this.playbackUrl = playbackUrl
        val valid = playbackUrl != null && !kid.isNullOrBlank() && !key.isNullOrBlank()
        this.kid = if (valid) kid else null
        this.key = if (valid) key else null
    }

    /** (kid, key) pendentes para a URL, ou null quando ela não tem ClearKey. */
    fun clearKeyFor(url: String): Pair<String, String>? {
        val storedUrl = playbackUrl ?: return null
        if (storedUrl != url) return null
        val storedKid = kid ?: return null
        val storedKey = key ?: return null
        return storedKid to storedKey
    }
}
