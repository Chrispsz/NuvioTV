package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AddonPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "addon_preferences"
    }

    private fun effectiveProfileId(): Int {
        val active = profileManager.activeProfile
        return if (active != null && active.usesPrimaryAddons) 1 else profileManager.activeProfileId.value
    }

    private fun store(profileId: Int = effectiveProfileId()) =
        factory.get(profileId, FEATURE)

    private val effectiveProfileIdFlow: Flow<Int> = combine(
        profileManager.activeProfileId,
        profileManager.profiles
    ) { activeProfileId, profiles ->
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
        if (activeProfile?.usesPrimaryAddons == true) 1 else activeProfileId
    }.distinctUntilChanged()

    private val gson = Gson()
    private val orderedUrlsKey = stringPreferencesKey("installed_addon_urls_ordered")
    private val legacyUrlsKey = stringSetPreferencesKey("installed_addon_urls")
    private val userSetNamesKey = stringPreferencesKey("addon_user_set_names")
    private val addonEnabledStatesKey = stringPreferencesKey("installed_addon_enabled_states")
    private val manifestSuffix = "/manifest.json"

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith(manifestSuffix, ignoreCase = true)) {
            path.dropLast(manifestSuffix.length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }

    val installedAddonUrls: Flow<List<String>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val json = preferences[orderedUrlsKey]
            if (json != null) {
                val parsed = parseUrlList(json)
                when {
                    isUnmodifiedFactoryPack(parsed) -> getDefaultAddons().toList()
                    isFactoryPackV15(parsed) -> getDefaultAddons().toList()
                    isFactoryPackV14(parsed) -> getDefaultAddons().toList()
                    isFactoryPackV13(parsed) -> getDefaultAddons().toList()
                    isFactoryPackV12(parsed) -> getDefaultAddons().toList()
                    else -> parsed
                }
            } else {
                val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
                legacySet.toList()
            }
        }
    }

    val addonEnabledStates: Flow<Map<String, Boolean>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            preferences[addonEnabledStatesKey]
                ?.let(::parseEnabledStateMap)
                .orEmpty()
        }
    }

    suspend fun ensureMigrated() {
        val ds = store()
        val prefs = ds.data.first()
        if (prefs[orderedUrlsKey] == null) {
            val legacySet = prefs[legacyUrlsKey] ?: getDefaultAddons()
            ds.edit { preferences ->
                preferences[orderedUrlsKey] = gson.toJson(legacySet.toList())
                preferences.remove(legacyUrlsKey)
            }
        }
    }

    suspend fun addAddon(url: String): Boolean {
           val active = profileManager.activeProfile
           if (active != null && !active.isPrimary && active.usesPrimaryAddons) return false
        var changed = false
        store().edit { preferences ->
            val current = getCurrentList(preferences)
            val normalizedUrl = canonicalizeUrl(url)
            if (current.any { canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true) }) return@edit
            preferences[orderedUrlsKey] = gson.toJson(current + normalizedUrl)
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            states[normalizedUrl] = true
            preferences[addonEnabledStatesKey] = gson.toJson(states)
            changed = true
        }
        return changed
    }

    suspend fun removeAddon(url: String): Boolean {
           val active = profileManager.activeProfile
           if (active != null && !active.isPrimary && active.usesPrimaryAddons) return false
        var changed = false
        store().edit { preferences ->
            val current = getCurrentList(preferences).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)

            val indexToRemove = current.indexOfFirst {
                canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true)
            }
            if (indexToRemove != -1) {
                current.removeAt(indexToRemove)
            }
            if (indexToRemove == -1) return@edit
            preferences[orderedUrlsKey] = gson.toJson(current)
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            states.remove(normalizedUrl)
            preferences[addonEnabledStatesKey] = gson.toJson(states)
            changed = true
        }
        return changed
    }

    suspend fun setAddonOrder(urls: List<String>): Boolean {
            val active = profileManager.activeProfile
            if (active != null && !active.isPrimary && active.usesPrimaryAddons) return false
        var changed = false
        store().edit { preferences ->
            val orderedUrls = urls.map(::canonicalizeUrl)
            val currentUrls = getCurrentList(preferences).map(::canonicalizeUrl)
            if (orderedUrls == currentUrls) return@edit
            preferences[orderedUrlsKey] = gson.toJson(orderedUrls)
            val currentStates = getCurrentEnabledStates(preferences)
            preferences[addonEnabledStatesKey] = gson.toJson(
                orderedUrls.associateWith { url -> currentStates[url] ?: true }
            )
            changed = true
        }
        return changed
    }

    suspend fun setAddonEnabled(url: String, enabled: Boolean): Boolean {
        val active = profileManager.activeProfile
        if (active != null && !active.isPrimary && active.usesPrimaryAddons) return false
        var changed = false
        store().edit { preferences ->
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            val normalizedUrl = canonicalizeUrl(url)
            if ((states[normalizedUrl] ?: true) == enabled) return@edit
            states[normalizedUrl] = enabled
            preferences[addonEnabledStatesKey] = gson.toJson(states)
            changed = true
        }
        return changed
    }

    suspend fun setAddonEnabledStates(states: Map<String, Boolean>) {
        val active = profileManager.activeProfile
        if (active != null && !active.isPrimary && active.usesPrimaryAddons) return
        store().edit { preferences ->
            preferences[addonEnabledStatesKey] = gson.toJson(
                states.mapKeys { (url, _) -> canonicalizeUrl(url) }
            )
        }
    }

    private fun getCurrentList(preferences: Preferences): List<String> {
        val json = preferences[orderedUrlsKey]
        return if (json != null) {
            parseUrlList(json)
        } else {
            val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
            legacySet.toList()
        }
    }

    private fun parseUrlList(json: String): List<String> {
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: getDefaultAddons().toList()
        } catch (e: Exception) {
            getDefaultAddons().toList()
        }
    }

    val userSetNames: Flow<Map<String, String>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val json = preferences[userSetNamesKey]
            if (json != null) parseNameMap(json) else emptyMap()
        }
    }

    suspend fun setUserSetNames(names: Map<String, String>) {
        store().edit { preferences ->
            preferences[userSetNamesKey] = gson.toJson(
                names.mapKeys { (url, _) -> canonicalizeUrl(url) }
            )
        }
    }

    private fun parseNameMap(json: String): Map<String, String> {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val parsed: Map<String, String> = gson.fromJson(json, type) ?: emptyMap()
            parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun getCurrentEnabledStates(preferences: Preferences): Map<String, Boolean> {
        val json = preferences[addonEnabledStatesKey] ?: return emptyMap()
        return parseEnabledStateMap(json)
    }

    private fun parseEnabledStateMap(json: String): Map<String, Boolean> {
        return try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            val parsed: Map<String, Boolean> = gson.fromJson(json, type) ?: emptyMap()
            parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // TV Minimal Edition: pack BR pré-configurado (zero-config para leigos).
    // A ORDEM IMPORTA: sem ordem salva, as rows da Home seguem a ordem dos addons
    // (HomeViewModelCatalogUtils.rebuildCatalogOrder) — por isso CANAIS AO VIVO
    // (Tvvio, 100+ canais de esportes/gerais + eventos, ativamente mantido) vem
    // PRIMEIRO: abrir o app já mostra canais, como TV de verdade.
    // Depois: Cinemeta (catálogo/metadata global de filmes e séries), Torrentio BR
    // (fonte primária de streams torrent: providers comando/bludv/micoleaodublado,
    // ordenação qualidade+seeders), MediaFusion | Midnight (2ª fonte torrent com
    // scrapers independentes — redundância contra rate-limit do Torrentio),
    // Anime Kitsu (catálogos de animes: trending/airing/popular/rating) e OpenSubtitles.
    // Testado em 03/11/2026 (cadeia completa catálogo→meta→streams):
    // Tvvio 200 OK v2.1.0 (100 canais + eventos); anime-kitsu.strem.fun 200 OK
    // em 40ms (community.anime.kitsu v0.0.10, 50/50 metas com imdb_id);
    // Bludv VIVO — para tt0317248 devolve REMUX em PORTUGUÊS exclusivo dos
    // providers BR (torrentio testado: +1 stream só-no-BR vs padrão).
    // Torrentio cobre anime com IDs kitsu (100 streams p/ kitsu:7442, providers
    // gerais + 1337x/TPB). MediaFusion Midnight FORA na 1.5.0 (pedido do dono:
    // "é sem graça porque parece um torrentio") — torrent na nuvem duplica o
    // Torrentio; fica só o Torrentio, e torrent é o ÚLTIMO tier nas opções
    // (StreamAutoPlaySelector.tierHttpFirst).
    // FrostStream DE VOLTA na 1.5.0 (pedido do dono: "o Frost não morreu pois
    // ainda funciona"): o 403/404 visto em testes é bloqueio Cloudflare de IP
    // de datacenter — em rede residencial o addon responde normal. No CI ele é
    // não-crítico exatamente por isso.
    // PlavioHD ENTRA na 1.5.1: VOD internacional via HTTPS DIRETO (sem torrent,
    // sem debrid, sem login) — até 4K + esportes ao vivo; zero-config, verificado
    // 03/11/2026 devolvendo 70 streams HTTPS assinados para tt0111161 sem
    // autenticação (manifest+streams 200 OK, v1.9.151). Reforça o tier HTTP que
    // fica à frente do torrent (tierHttpFirst).
    // Meu Futebol REMOVIDO (manifest inacessível, timeout 12s no teste).
    // BestCine permanece FORA do pack: passou a exigir login (deixou de ser
    // zero-config); segue disponível por instalação manual de 1 clique no site.
    // Ordem = ordem das fileiras da Home: canais ao vivo primeiro.
    private fun getDefaultAddons(): Set<String> = setOf(
        "https://tvvio.megaembed-frost.workers.dev",
        "https://v3-cinemeta.strem.io",
        "https://anime-kitsu.strem.fun",
        "https://froststream.cloutteam.com",
        "https://hd.plavio.site",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        "https://opensubtitles-v3.strem.io"
    )

    // Pack da 1.5.0 (antes do PlavioHD) — migra automaticamente quem atualizou
    // da 1.5.0 sem personalizar a lista.
    private val factoryPackV15: Set<String> = setOf(
        "https://tvvio.megaembed-frost.workers.dev",
        "https://v3-cinemeta.strem.io",
        "https://anime-kitsu.strem.fun",
        "https://froststream.cloutteam.com",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        "https://opensubtitles-v3.strem.io"
    )

    private fun isFactoryPackV15(urls: List<String>): Boolean {
        if (urls.size != factoryPackV15.size) return false
        return urls.map { it.trim().lowercase() }.toSet() ==
            factoryPackV15.map { it.lowercase() }.toSet()
    }

    // Pack da 1.4.0 (com MediaFusion Midnight, sem FrostStream) — migra
    // automaticamente quem atualizou para a 1.4.x sem personalizar a lista.
    private val factoryPackV14: Set<String> = setOf(
        "https://tvvio.megaembed-frost.workers.dev",
        "https://v3-cinemeta.strem.io",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        "https://mediafusionfortheweebs.midnightignite.me",
        "https://anime-kitsu.strem.fun",
        "https://opensubtitles-v3.strem.io"
    )

    private fun isFactoryPackV14(urls: List<String>): Boolean {
        if (urls.size != factoryPackV14.size) return false
        return urls.map { it.trim().lowercase() }.toSet() ==
            factoryPackV14.map { it.lowercase() }.toSet()
    }

    // Pack da 1.3.0 (antes da saída do FrostStream) — migra automaticamente quem
    // atualizou da 1.3.x sem personalizar a lista.
    private val factoryPackV13: Set<String> = setOf(
        "https://tvvio.megaembed-frost.workers.dev",
        "https://v3-cinemeta.strem.io",
        "https://froststream.cloutteam.com",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        "https://anime-kitsu.strem.fun",
        "https://opensubtitles-v3.strem.io"
    )

    private fun isFactoryPackV13(urls: List<String>): Boolean {
        if (urls.size != factoryPackV13.size) return false
        return urls.map { it.trim().lowercase() }.toSet() ==
            factoryPackV13.map { it.lowercase() }.toSet()
    }

    // Pack da 1.2.0 (antes dos animes) — usado para migrar automaticamente quem
    // atualizou da 1.2.x sem personalizar a lista.
    private val factoryPackV12: Set<String> = setOf(
        "https://tvvio.megaembed-frost.workers.dev",
        "https://v3-cinemeta.strem.io",
        "https://froststream.cloutteam.com",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        "https://opensubtitles-v3.strem.io"
    )

    private fun isFactoryPackV12(urls: List<String>): Boolean {
        if (urls.size != factoryPackV12.size) return false
        return urls.map { it.trim().lowercase() }.toSet() ==
            factoryPackV12.map { it.lowercase() }.toSet()
    }

    /**
     * Migração 1.1.0 -> 1.2.0: quem tinha o pack de fábrica na ordem antiga
     * (Cinemeta primeiro) recebe a nova ordem (canais primeiro) ao atualizar.
     * Só troca quando a lista é EXATAMENTE o pack de fábrica sem personalização
     * (mesma quantidade e mesmo conjunto de URLs) — qualquer lista editada pelo
     * usuário é respeitada.
     */
    private fun isUnmodifiedFactoryPack(urls: List<String>): Boolean {
        if (urls.size != getDefaultAddons().size) return false
        return urls.map { it.trim().lowercase() }.toSet() ==
            getDefaultAddons().map { it.lowercase() }.toSet()
    }
}
