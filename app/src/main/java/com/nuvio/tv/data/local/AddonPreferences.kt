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
                if (isUnmodifiedFactoryPack(parsed)) getDefaultAddons().toList() else parsed
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
    // Depois: Cinemeta (catálogo/metadata global de filmes e séries), FrostStream
    // (VOD BR rápido via HTTP), Torrentio BR (fallback torrent: providers
    // comando/bludv/micoleaodublado, ordenação qualidade+seeders) e OpenSubtitles.
    // Só conteúdo HTTP nos live: Torrentio é o único torrent do pack.
    // Testado em 02/11/2026 (health-check 8/8 no CI): Tvvio 200 OK v2.1.0.
    // FrostStream pode devolver 403 para IP de datacenter (Cloudflare), mas
    // responde na rede residencial — e o Torrentio cobre o VOD se ele falhar.
    // Meu Futebol REMOVIDO (manifest inacessível, timeout 12s no teste).
    // BestCine permanece FORA do pack: passou a exigir login (deixou de ser
    // zero-config); segue disponível por instalação manual de 1 clique no site.
    private fun getDefaultAddons(): Set<String> = setOf(
        "https://tvvio.megaembed-frost.workers.dev",
        "https://v3-cinemeta.strem.io",
        "https://froststream.cloutteam.com",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        "https://opensubtitles-v3.strem.io"
    )

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
