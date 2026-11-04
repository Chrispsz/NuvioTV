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
                    isFactoryPackV161(parsed) -> getDefaultAddons().toList()
                    isFactoryPackV160(parsed) -> getDefaultAddons().toList()
                    isFactoryPackV152(parsed) -> getDefaultAddons().toList()
                    isFactoryPackV151(parsed) -> getDefaultAddons().toList()
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
    // (FayaTV, instância configurada d:br@cat=) vem PRIMEIRO: abrir o app já
    // mostra canais, como TV de verdade.
    // Depois: Cinemeta (catálogo/metadata global), Brazuca Play (VOD HTTP DIRETO
    // DUBLADO PT-BR: Lançamentos/Filmes/Trilogias/Séries/Animes/Doramas/Desenhos/
    // Novelas), Anime Kitsu, Torrentio BR (fonte primária de streams torrent:
    // providers comando/bludv/micoleaodublado, ordenação qualidade+seeders) e
    // OpenSubtitles.
    // Tvvio FORA na 1.5.2: fora do ar para todo mundo desde 27/10/2026 — streams
    // cdn10embed.xyz respondem 403 Cloudflare e o relay baby-beamup.club 502
    // (sintoma: lista de canais/EPG aparece, nada reproduz, direto ou replay;
    // relatos no r/StremioAddons). FayaTV ENTRA no lugar: instância configurada
    // d:br@cat=Sports:General:News:Kids:Entertainment (filtro BR + esportes gerais
    // + eventos ao vivo), testada 04/11/2026 — manifest/catálogos/streams 200 OK,
    // canais brasileiros reais (SBT Interior, Rede TV!, Rede Brasil, TV Paraense)
    // com streams 200 OK. O catálogo "country_filter" é ocultado no AddonMapper.
    // 1.6.0: canais do FayaTV agora reproduzem DENTRO do app — a página /watch/
    // que o addon devolve expõe o manifesto DASH + chaves ClearKey, e o player
    // resolve ambos (ClearKeyDrm/ExternalStreamResolver); navegação externa só
    // se a resolução falhar.
    // anime-kitsu.strem.fun 200 OK em 40ms (community.anime.kitsu v0.0.10).
    // Bludv VIVO — para tt0317248 devolve REMUX em PORTUGUÊS exclusivo dos
    // providers BR (torrentio testado: +1 stream só-no-BR vs padrão).
    // Torrentio cobre anime com IDs kitsu (100 streams p/ kitsu:7442, providers
    // gerais + 1337x/TPB). MediaFusion Midnight FORA desde a 1.5.0 (pedido do
    // dono: "é sem graça porque parece um torrentio"); torrent é o ÚLTIMO tier
    // nas opções (StreamAutoPlaySelector.tierHttpFirst).
    // FrostStream CONTINUA (dono confirmou 05/11/2026: "o Frost não morreu"): o
    // 403/404 visto em testes é bloqueio Cloudflare de IP de datacenter — em rede
    // residencial o addon responde normal. No CI ele é não-crítico por isso.
    // PlavioHD FORA na 1.6.0 (pedido do dono: "não é muito bom pra ptbr"): teste
    // 05/11/2026 — 40 streams para tt1630029 com fontes PixalDrain/MovieBox em
    // áudio original/russo, 0 streams para tt9363680; dublagem PT-BR praticamente
    // inexistente. BRAZUCA PLAY ENTRA no lugar (brazuca-play-stremio.vercel.app,
    // v2.1.5, sem login): VOD HTTP DIRETO dublado PT-BR FHD (testado 05/11/2026:
    // tt1630029 "Dublado PT-BR - FHD" + Dual/Leg, URLs 200 OK) + catálogos BR
    // (Lançamentos/Filmes/Séries/Animes/Doramas/Novelas). O catálogo de TV ao
    // vivo dele é Pluto TV dos EUA (canais geo-bloqueados fora dos EUA) e fica
    // oculto no AddonMapper — canais ao vivo ficam por conta do FayaTV.
    // Zeus BR e HdHub-antigo FORA na época do datacenter-only: manifest 403
    // (Cloudflare bloqueia IP de datacenter). A partir da 1.6.2 o relatório do
    // laboratório na rede do dono é a evidência decisiva — ver bloco 1.6.2.
    // BestCine permanece FORA: passou a exigir login (deixou de ser zero-config).
    // Ordem = ordem das fileiras da Home: canais ao vivo primeiro.
    // 1.6.1 (05/11/2026): OpenSubtitles FORA (pedido do dono — legendas não
    // justificam o peso/requests do addon; o app mantém legendas embutidas e
    // seleção manual). Brazuca Play permanece no pack apesar de a ORIGEM dos
    // streams estar com os segmentos 404 no dia (playlists OK, vídeo morto na
    // fonte watchug.watch/box4.sbs — não é VIP/login; addon volta quando a
    // origem recuperar; no health é não-crítico até lá). O FrostStream passa a
    // ser pedido com UA de NAVEGADOR (Cloudflare desafiava o UA "Nuvio/x" e o
    // addon nem aparecia no app — NetworkModule BROWSER_USER_AGENT).
    // 1.6.2 (06/11/2026): curadoria fechada com o RELATÓRIO DO LABORATÓRIO na
    // rede do dono (04/11/2026 17:22 — evidência decisiva, pois Cloudflare e
    // geo-bloqueio mentem para o IP de datacenter do CI). Entram 5 addons, todos
    // SAUDÁVEIS na rede residencial do dono e não-críticos no health:
    //   • NTC TV (ntcstudios.com.br/ntctv) — canais BR, domínio .com.br,
    //     atualizado 03/11; geo-bloqueia datacenter (timeout) = só liga no BR.
    //   • Meu Futebol (meufutebol.oneplayhd.com) — esportes ao vivo BR;
    //     geo-bloqueia datacenter igual ao NTC.
    //   • FrostView (frostview.cloutteam.com) — mesma casa do FrostStream;
    //     Globo/SBT/Record/ESPN. CF barra o datacenter; no navegador do dono
    //     deu CORS (mesma assinatura do FrostStream, que funciona no app) —
    //     validação final é no app com o UA de navegador da 1.6.1.
    //   • Veyra (veyra.shardweb.app) — 12 streams HTTP puros PT-BR na rede do
    //     dono (shardweb, mesma infra do Zeus); reforço de VOD HTTP.
    //   • FenixFlix (fenixflix.fenixhub.online) — 3/3 streams marcados DUBLADO
    //     na rede do dono, HD/4K, novelas/séries.
    // Brazuca Play FORA na 1.6.2 (pedido do dono: "odiei — não reproduz na
    // 1.6.1 e ainda suja o catálogo"). Diagnóstico técnico: NÃO é VIP/login —
    // a ORIGEM dos streams (watchug.watch/box4.sbs) está morrendo pra todo
    // mundo: segmentos 404 em 04/11, piscou 200 e voltou a 502/0-6 em 05/11.
    // Não re-adicionar sem pedido explícito do dono E origem estável. O VOD
    // HTTP dublado fica por conta de Veyra + FenixFlix + Torrentio BR
    // (providers comando/bludv/micoleaodublado). Minha TV/Iracemaflix
    // FORA (catálogo/stream estouram 12s de timeout até na rede do dono —
    // ferem a regra "não atrasar"); PlavioLive FORA (origem 403 no datacenter
    // + mesmo dev do PlavioHD removido); PenguPlay FORA (manifest 6s e
    // inconsistente + histórico de login); Flix-Streams FORA (0 catálogos sem
    // config); MediaFusion/Zeus/Watchio/AIOStreams FORA (0 streams sem
    // debrid/config — regra: só Torrentio de torrent).
    private fun getDefaultAddons(): Set<String> = setOf(
        // — Canais ao vivo BR (3 casas independentes: se uma cai, as outras seguram) —
        "https://fayatv.stravo.site/d:br@cat=Sports:General:News:Kids:Entertainment/manifest.json",
        "https://ntcstudios.com.br/ntctv/manifest.json",
        "https://meufutebol.oneplayhd.com/manifest.json",
        "https://frostview.cloutteam.com/manifest.json",
        // — VOD —
        "https://v3-cinemeta.strem.io",
        "https://veyra.shardweb.app/manifest.json",
        "https://fenixflix.fenixhub.online/manifest.json",
        // — Catálogos/fontes finais —
        "https://anime-kitsu.strem.fun",
        "https://froststream.cloutteam.com",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100"
    )

    // Pack da 1.6.1 (sem OpenSubtitles) — migra automaticamente quem atualizou
    // da 1.6.1 sem personalizar a lista.
    private val factoryPackV161: Set<String> = setOf(
        "https://fayatv.stravo.site/d:br@cat=Sports:General:News:Kids:Entertainment/manifest.json",
        "https://v3-cinemeta.strem.io",
        "https://brazuca-play-stremio.vercel.app/manifest.json",
        "https://anime-kitsu.strem.fun",
        "https://froststream.cloutteam.com",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100"
    )

    private fun isFactoryPackV161(urls: List<String>): Boolean {
        if (urls.size != factoryPackV161.size) return false
        return urls.map { it.trim().lowercase() }.toSet() ==
            factoryPackV161.map { it.lowercase() }.toSet()
    }

    // Pack da 1.6.0 (com OpenSubtitles) — migra automaticamente quem atualizou
    // da 1.6.0 sem personalizar a lista.
    private val factoryPackV160: Set<String> = setOf(
        "https://fayatv.stravo.site/d:br@cat=Sports:General:News:Kids:Entertainment/manifest.json",
        "https://v3-cinemeta.strem.io",
        "https://brazuca-play-stremio.vercel.app/manifest.json",
        "https://anime-kitsu.strem.fun",
        "https://froststream.cloutteam.com",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        "https://opensubtitles-v3.strem.io"
    )

    private fun isFactoryPackV160(urls: List<String>): Boolean {
        if (urls.size != factoryPackV160.size) return false
        return urls.map { it.trim().lowercase() }.toSet() ==
            factoryPackV160.map { it.lowercase() }.toSet()
    }

    // Pack da 1.5.2 (igual à 1.5.1, com Tvvio morto) — migra automaticamente
    // quem atualizou da 1.5.2 sem personalizar a lista.
    private val factoryPackV152: Set<String> = setOf(
        "https://fayatv.stravo.site/d:br@cat=Sports:General:News:Kids:Entertainment/manifest.json",
        "https://v3-cinemeta.strem.io",
        "https://anime-kitsu.strem.fun",
        "https://froststream.cloutteam.com",
        "https://hd.plavio.site",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        "https://opensubtitles-v3.strem.io"
    )

    private fun isFactoryPackV152(urls: List<String>): Boolean {
        if (urls.size != factoryPackV152.size) return false
        return urls.map { it.trim().lowercase() }.toSet() ==
            factoryPackV152.map { it.lowercase() }.toSet()
    }

    // Pack da 1.5.1 (com Tvvio morto) — migra automaticamente quem atualizou
    // da 1.5.1 sem personalizar a lista.
    private val factoryPackV151: Set<String> = setOf(
        "https://tvvio.megaembed-frost.workers.dev",
        "https://v3-cinemeta.strem.io",
        "https://anime-kitsu.strem.fun",
        "https://froststream.cloutteam.com",
        "https://hd.plavio.site",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        "https://opensubtitles-v3.strem.io"
    )

    private fun isFactoryPackV151(urls: List<String>): Boolean {
        if (urls.size != factoryPackV151.size) return false
        return urls.map { it.trim().lowercase() }.toSet() ==
            factoryPackV151.map { it.lowercase() }.toSet()
    }

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
