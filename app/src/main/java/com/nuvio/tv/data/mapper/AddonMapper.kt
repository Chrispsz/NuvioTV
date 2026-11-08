package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.AddonBehaviorHintsDto
import com.nuvio.tv.data.remote.dto.CatalogDescriptorDto
import com.nuvio.tv.data.remote.dto.StremioAddonsConfigDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonBehaviorHints
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.StremioAddonsConfig
import com.nuvio.tv.domain.model.supportsExtra

/**
 * Catálogos ocultos na conversão de manifesto para que nunca virem rows na
 * Home nem apareçam no gerenciador de addons:
 * - "last-videos"/"calendar-videos" (Cinemeta): duplicam conteúdo já coberto
 *   pelas demais rows da Home;
 * - "country_filter" (FayaTV): catálogo de navegação que lista PAÍSES como
 *   metas — inútil como fileira (a instância de fábrica já vem filtrada por
 *   d:br@cat=...);
 * - "bp_pluto_tv" (Brazuca Play): TV ao vivo do Pluto TV dos EUA — canais
 *   geo-bloqueados fora dos EUA (stream responde 400/403 no teste de
 *   05/11/2026); canais ao vivo do pack ficam por conta do FayaTV;
 * - "bp_trilogies"/"bp_desenhos"/"bp_novelas" (Brazuca Play): o addon expõe 9
 *   catálogos e a Home ficava poluída com fileiras redundantes (pedido do
 *   dono, 05/11/2026). Trilogias duplica Séries/Filmes; Desenhos é coberto por
 *   Animes (Brazuca) + Anime Kitsu; Novelas é nicho com pouca rotação.
 *   Mantidos no Brazuca: Lançamentos, Filmes, Séries, Animes, Doramas.
 * - Catálogos do Tvvio que são SUBCONJUNTOS do catálogo principal "tvvio"
 *   (geral/ao vivo, 156 canais + eventos): "tvvio.filmes-e-series",
 *   "tvvio.variedades", "tvvio.noticias", "tvvio.canais-abertos",
 *   "tvvio.infantil" e "tvvio.religiosos". Pedido do dono (1.6.4, 05/11/2026:
 *   "tá cheio e poluído a home — deixa só os melhores"): o Tvvio fica com
 *   canais gerais, esportes e ao vivo — ou seja, SOMENTE "tvvio" +
 *   "tvvio.esportes" visíveis; o resto é fileira redundante que polui a
 *   Home. Os ids são namespaced (tvvio.*) — risco zero de colisão.
 * - 1.6.5 (08/11/2026): FayaTV com a instância completa (/d:br) por pedido
 *   do dono ("tenho que ter acesso aos canais brasileiros mais fácil"). O
 *   "country_filter" SAIU da lista oculta: com a instância completa ele é o
 *   catálogo de TODOS os canais BR (238, paginado) e volta renomeado para
 *   "Todos os Canais (BR)". Em contrapartida "tv_Undefined" (canais sem
 *   classificação — os "sem imagem/irrelevantes" do dono) e "tv_Movies"
 *   (filmes de canal, fora do foco esporte/geral) entram ocultos. Modelo
 *   freemium do FayaTV medido: parte dos canais é Premium 🔒
 *   (payments.stravo.site) — mantidos visíveis por decisão do dono; a
 *   meta-anúncio "Upgrade to Premium" é filtrada no CatalogRepositoryImpl.
 */
private val HIDDEN_DUPLICATED_CATALOG_IDS = setOf(
    "last-videos",
    "calendar-videos",
    "tv_Undefined",
    "tv_Movies",
    "bp_pluto_tv",
    "bp_trilogies",
    "bp_desenhos",
    "bp_novelas",
    "tvvio.filmes-e-series",
    "tvvio.variedades",
    "tvvio.noticias",
    "tvvio.canais-abertos",
    "tvvio.infantil",
    "tvvio.religiosos"
)

/**
 * 1.6.5 (07/11/2026): catálogos do Tvvio que RESPONDEM a skip no servidor mas
 * NÃO o declaram no manifest — medido ao vivo: /catalog/tv/tvvio/skip=100.json
 * devolve os 55 canais restantes dos 155 e skip=155 devolve 0 (parada limpa);
 * tvvio.esportes totaliza 70 canais e também aceita skip. Sem a declaração,
 * o app não pagina (supportsSkip=false → hasMore=false) e o dono "não consegue
 * passar" pela lista de canais — a reclamação que motivou a 1.6.5. Injetar o
 * extra "skip" aqui liga a paginação sem depender de correção do addon; se um
 * dia o servidor parar de aceitar skip, o bloco extra vem vazio e a fileira
 * para no primeiro carregamento (hasMore=false), sem loop.
 */
private val FORCED_SKIP_CATALOG_IDS = setOf(
    "tvvio",
    "tvvio.esportes"
)

fun AddonManifestDto.toDomain(baseUrl: String): Addon {
    val manifestTypes = types.map { it.trim() }.filter { it.isNotEmpty() }
    val mappedCatalogs = catalogs
        .filter { descriptor -> descriptor.id.trim().lowercase() !in HIDDEN_DUPLICATED_CATALOG_IDS }
        .map { it.toDomain() }
        .map { descriptor ->
            // 1.6.5: Tvvio responde skip sem declarar — liga a paginação.
            if (descriptor.id in FORCED_SKIP_CATALOG_IDS && !descriptor.supportsExtra("skip")) {
                descriptor.copy(extraSupported = descriptor.extraSupported + "skip")
            } else {
                descriptor
            }
        }
        .map { descriptor ->
            // 1.6.5: o country_filter do FayaTV (instância /d:br) é o
            // catálogo de TODOS os canais BR (238, paginado) — nome honesto
            // para a Home, no lugar de "Countries Filter".
            if (descriptor.id.equals("country_filter", ignoreCase = true)) {
                descriptor.copy(name = "Todos os Canais (BR)")
            } else {
                descriptor
            }
        }
        .let { list ->
            // 1.6.5: foco do dono é esporte/futebol BR ("meu foco são esses
            // tipos de canais que cobrem todo o futebol brasileiro") — a
            // fileira Esportes do Tvvio sobe para o topo do addon. sortedBy
            // é estável: a ordem relativa dos demais catálogos se preserva.
            if (list.any { it.id == "tvvio.esportes" }) {
                list.sortedBy { if (it.id == "tvvio.esportes") 0 else 1 }
            } else {
                list
            }
        }
    return Addon(
        id = id,
        name = name,
        version = version,
        description = description,
        logo = logo,
        background = background,
        baseUrl = baseUrl,
        catalogs = mappedCatalogs,
        types = manifestTypes.map { ContentType.fromString(it) },
        rawTypes = manifestTypes,
        resources = parseResources(resources, manifestTypes),
        idPrefixes = idPrefixes,
        behaviorHints = behaviorHints?.toDomain(),
        stremioAddonsConfig = stremioAddonsConfig?.toDomain(),
        manifestLanguage = language,
        configVersion = configVersion,
        timestamp = timestamp
    )
}

fun CatalogDescriptorDto.toDomain(): CatalogDescriptor {
    val manifestType = type.trim()
    return CatalogDescriptor(
        type = ContentType.fromString(manifestType),
        rawType = manifestType,
        id = id,
        name = name,
        extra = parseCatalogExtras(extra),
        pageSize = pageSize,
        showInHome = showInHome == true,
        hasExplicitShowInHome = showInHome != null,
        extraSupported = extraSupported.orEmpty(),
        extraRequired = extraRequired.orEmpty()
    )
}

private fun parseResources(resources: List<Any>, defaultTypes: List<String>): List<AddonResource> {
    return resources.mapNotNull { resource ->
        when (resource) {
            is String -> {
                // Simple resource format: "meta", "stream", etc.
                AddonResource(
                    name = resource,
                    types = defaultTypes,
                    idPrefixes = null
                )
            }
            is Map<*, *> -> {
                // Complex resource format with types and idPrefixes
                val name = resource["name"] as? String ?: return@mapNotNull null
                val types = (resource["types"] as? List<*>)?.filterIsInstance<String>() ?: defaultTypes
                val idPrefixes = (resource["idPrefixes"] as? List<*>)?.filterIsInstance<String>()
                AddonResource(
                    name = name,
                    types = types,
                    idPrefixes = idPrefixes
                )
            }
            else -> null
        }
    }
}

private fun parseCatalogExtras(rawExtras: List<Any>?): List<CatalogExtra> {
    return rawExtras.orEmpty().mapNotNull { raw ->
        when (raw) {
            is String -> {
                val name = raw.trim().lowercase()
                if (name.isBlank()) {
                    null
                } else {
                    CatalogExtra(name = name)
                }
            }
            is Map<*, *> -> {
                val name = (raw["name"] as? String)?.trim()?.lowercase().orEmpty()
                if (name.isBlank()) return@mapNotNull null

                val isRequired = when (val required = raw["isRequired"]) {
                    is Boolean -> required
                    is String -> required.equals("true", ignoreCase = true)
                    is Number -> required.toInt() != 0
                    else -> false
                }
                val options = (raw["options"] as? List<*>)?.mapNotNull { option ->
                    when (option) {
                        null -> null
                        is String -> option
                        else -> option.toString()
                    }
                }?.takeIf { it.isNotEmpty() }

                CatalogExtra(
                    name = name,
                    isRequired = isRequired,
                    options = options,
                    defaultValue = (raw["default"] as? String)?.takeIf { it.isNotBlank() },
                    optionsLimit = when (val value = raw["optionsLimit"]) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull()
                        else -> null
                    }
                )
            }
            else -> null
        }
    }.distinct()
}

private fun AddonBehaviorHintsDto.toDomain(): AddonBehaviorHints {
    return AddonBehaviorHints(
        configurable = configurable,
        configurationRequired = configurationRequired,
        newEpisodeNotifications = newEpisodeNotifications
    )
}

private fun StremioAddonsConfigDto.toDomain(): StremioAddonsConfig {
    return StremioAddonsConfig(
        issuer = issuer?.takeIf { it.isNotBlank() },
        signature = signature?.takeIf { it.isNotBlank() }
    )
}
