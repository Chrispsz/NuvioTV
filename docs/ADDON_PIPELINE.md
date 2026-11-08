# Pipeline de Addons — como o NuvioTV Minimal resolve cada link

Estudo da estrutura dos addons e de como o app transforma um manifesto Stremio
em vídeo na tela. Referência viva: quando um addon novo entra no pack, o
padrão dele deve se encaixar em um dos casos abaixo (quebra-cabeça fechado).

```
manifest.json ──► AddonPreferences (pack, ordem: canais primeiro)
                     │  AddonMapper (oculta catálogos redundantes)
                     ▼
              catálogos (rows da Home) ──► Cinemeta enriquece (posteres/EPG)
                     │
                     ▼
        /stream/{type}/{id}.json  ──►  StreamAggregator (agrupa todos os addons)
                     │                     StreamAutoPlaySelector (HTTP primeiro,
                     │                     torrent é o ÚLTIMO tier)
                     ▼
        4 tipos de stream (seções 1-4) ──► Player (ExoPlayer/mpv)
```

## 1. `url` direta — http/mp4/m3u8 (o caminho feliz)

O campo `url` aponta para mídia reproduzível. Exemplos do pack: canais do
FayaTV (m3u8/mpd), streams do Torrentio em debrid. O ExoPlayer toca direto com
`PlayerMediaSourceFactory` (UA de navegador Android TV já embutido). HLS/DASH
vêm com ABR automático — o player sobe/desce qualidade conforme a rede, que é
o comportamento desejado em instabilidade ("melhor qualidade possível sem
descer à toa" = adaptação nativa do Media3).

## 2. `externalUrl` — página que o app resolve (FayaTV /watch/)

Alguns addons não devolvem mídia, devolvem uma PÁGINA HTML de player
(`externalUrl: https://fayatv.stravo.site/watch/...`). O
`ExternalStreamUrlPolicy` classifica: direto (mp4/m3u8 → ExoPlayer), página
/watch/ (→ resolver) ou embed de terceiros (→ navegador, último caso). O
`ExternalStreamResolver` baixa a página com UA de navegador e extrai:
- `var fileUrl = "...mpd/m3u8"` → manifesto real do canal;
- chaves **ClearKey** (kid/key) embutidas no HTML quando o canal é CENC →
  `ClearKeyDrm` alimenta o ExoPlayer (mpv não decifra ClearKey, por isso a
  1.6.0 força engine ExoPlayer em streams ClearKey).
Resultado: o canal abre DENTRO do app, sem navegador — e o
`ci/addon_health.py` prova esse caminho ponta a ponta ("página /watch/ →
player interno").

## 3. Proxy HLS do próprio addon (Brazuca Play)

O Brazuca devolve `url` que é um HLS **através de proxy do addon**
(`/proxy/hls/xxx.m3u8?url=...`) montado sobre fontes de terceiros
(watchug.watch/box4.sbs). Atenções:
- o master lista a pista de áudio `LANGUAGE="pt-BR"` como DEFAULT (dublado ✓);
- os CODECS costumam ser **HEVC (hvc1)** — em caixas ARMv7 sem decodificador
  HEVC por hardware o vídeo pode não abrir mesmo com a origem saudável;
- em 05/11/2026 a origem caiu: playlists 200, **segmentos 404 para todo
  mundo** (não é VIP/login, não é o app). O health agora desce até o segmento
  (`origem HLS`) para não confundir "API viva" com "vídeo vivo".
Lição: **200 na API não significa vídeo**; a prova de origem é o segmento.

## 4. Torrent (infoHash) — Torrentio, e só ele

Única fonte torrent do pack (regra da casa). O app resolve magnet/infoHash
via engine interna; os providers BR (comando/bludv/micoleaodublado) são os
que entregam dublagem; ordenação por qualidade+seeders com `limit=100`. Torrent
é o ÚLTIMO tier do `StreamAutoPlaySelector` — qualquer HTTP direto vence.

## Política de User-Agent (por que o Frost "não aparecia")

Até a 1.6.0 o app mandava `User-Agent: Nuvio/1.6.0` em TODOS os requests.
Addons da comunidade atrás de Cloudflare (FrostStream/FrostView, instâncias
shardweb como Zeus/Veyra, FenixFlix) **desafiam UA desconhecido**: o manifest
falha e o addon simplesmente não aparece. Na 1.6.1 o `NetworkModule` manda UA
de navegador Android TV em todo o tráfego de addon (mesma política já usada
pelo `ci/addon_health.py` e pelo `PlayerMediaSourceFactory`) e, desde a 1.6.3,
repete UMA vez com UA desktop quando a resposta é desafio (403/503 + HTML).
Nota corrigida em 05/11/2026: "403 no CI/datacenter" só não é morte quando é
DESAFIO (mira IP/fingerprint). O erro **1014 "CNAME Cross-User Banned"** é
outro animal — é proibição entre contas Cloudflare na resolução da origem,
vale para todo mundo (rede/UA não mudam nada); matou os dois Frost (abaixo).

## Região, latência e qualidade no ao vivo

- **Região**: a instância de fábrica do FayaTV (`d:br@cat=...`) já vem
  filtrada para BR; canais BR podem ser geo-bloqueados fora do Brasil —
  comportamento da fonte, não do app.
- **Atraso/latência**: `LiveBufferPolicy.applyLiveNetworkPolicy` configura o
  alvo de buffer para live (baixa latência sem quebrar em rede fraca) e o
  `LivePlaybackWatchClock/LivePlaybackUiPolicy` tratam o latch de UI ao vivo.
- **Qualidade**: ABR do Media3 sobe até a melhor variante que a rede sustenta;
  a pista de áudio passou a preferir **português** (1.6.1:
  `setPreferredAudioLanguages("por","pt")` — narração BR no ao vivo e dual
  áudio em filmes sem toque nenhum).

## Como testar/validar (checklist antes de trocar addon)

1. `python3 ci/addon_health.py --timeout 12` verde (críticos OK, exit 0);
2. addon HTTP direto (não torrent), sem login, sem debrid obrigatório;
3. preferir dublado PT-BR confirmado por stream real (título/descrição);
4. se o dono trouxer relatório do laboratório (`download/NuvioTV-TesteDeAddons.html`,
   rede residencial), usar como evidência decisiva — Cloudflare e geo-bloqueio
   mentem para o IP de datacenter;
5. atualizar `AddonPreferences.kt` (ordem: canais primeiro) + migração do pack
   de fábrica anterior + este health.

## Validação em duas redes (a partir da 1.6.2)

O CI roda em datacenter: **geo-bloqueio derruba a conexão** (NTC TV, Meu
Futebol — só ligam no Brasil) e **o Cloudflare desafia o IP** (Veyra,
FenixFlix — 403 instantâneo). Nenhum dos dois é morte: a TV do dono está na
rede residencial, onde o laboratório provou todos SAUDÁVEIS (relatório
04/11/2026 17:22). Por isso:

- no `addon_health.py` esses addons são **não-críticos** — a falha deles no
  CI é esperada e documentada no próprio script;
- a **evidência decisiva** para entrar no pack é o relatório do laboratório
  (rede do dono) + o comportamento no app (UA de navegador da 1.6.1);
- a redundância do ao vivo agora é por **casas independentes**: FayaTV
  (stravo), NTC TV (ntcstudios.com.br) e Meu Futebol (oneplayhd) — se uma
  casa cai, as outras duas continuam na Home (a 4ª casa, FrostView, caiu
  com o 1014 e saiu na 1.6.3).

Pack 1.6.5 (ordem = fileiras da Home): Tvvio → FayaTV (/d:br) → NTC TV → Meu
Futebol → Cinemeta → FenixFlix → **FrostStream (conta do dono)** →
**BestCine (conta do dono)** → Kitsu → Torrentio BR.
**FrostStream VOLTOU na 1.6.5** (manifest pessoal do dono — abaixo).
**BestCine ENTROU na 1.6.5** (manifest pessoal do dono — abaixo).
**FayaTV ficou COMPLETA na 1.6.5** (instância /d:br — abaixo).
Pack 1.6.4 (histórico): Tvvio → FayaTV → NTC TV → Meu Futebol → Cinemeta →
FenixFlix → Kitsu → Torrentio BR.
**Tvvio VOLTOU na 1.6.4** (pedido do dono; Home enxuta — abaixo).
**Veyra SAIU na 1.6.4** (origem morta no Shard Cloud, abaixo).
Pack 1.6.3 (histórico): FayaTV → NTC TV → Meu Futebol → Cinemeta → Veyra →
FenixFlix → Kitsu → Torrentio BR.
**FrostView e FrostStream SAÍRAM na 1.6.3** (erro 1014 na borda, abaixo).
Pack 1.6.2 (histórico): FayaTV → NTC TV → Meu Futebol → FrostView → Cinemeta
→ Veyra → FenixFlix → Kitsu → FrostStream → Torrentio BR.
**Brazuca Play saiu na 1.6.2** (pedido do dono: "odiei — não reproduz e suja
o catálogo"; tecnicamente a origem watchug.watch/box4.sbs está morrendo pra
todo mundo — não era VIP nem login). O VOD HTTP dublado fica por conta de
FenixFlix + Torrentio BR (providers comando/bludv/micoleaodublado) desde a
1.6.4.

## FrostStream volta com conta pessoal do dono + Tvvio paginado (1.6.5)

Dois problemas do dono, resolvidos no mesmo dia (07/11/2026):

### 1. FrostStream de volta — agora exige conta (manifest pessoal no pack)

O domínio cloutteam voltou vivo (o 1014 da 1.6.3 acabou — origem reapontada),
mas o serviço passou a exigir conta: o manifest público responde `401`
com `{"error":"Crie uma conta FrostStream para obter seu manifest pessoal.",
"login":"/auth/google"}`. O dono criou a conta, gerou o manifest pessoal
(`usr_98146e13add9493c72f1`) e **autorizou a entrada no pack de fábrica**
("entrar na factory pack, vamos utilizar assim pois só eu utilizo o
aplicativo").

Evidências de 07/11:

1. **Auditoria técnica (datacenter)**: manifest 200 (`com.froststream`
   v2.3.0, movie+series, **0 catálogos** = não polui a Home); sondagem de 13
   títulos com dublagem PT-BR na maioria (Interestelar com 8 fontes incluindo
   **4K HDR dublado**; De Volta para o Futuro 1985 com 4 fontes dubladas;
   Shrek 4/4; Toy Story 3/3; Breaking Bad, The Last of Us, One Piece e Attack
   on Titan com fonte dublada); **entrega real confirmada** com Range GET
   (206 video/mp4 via servidores Argon e Prism, arquivo de 2,7 GB).
2. **Prova do dono (rede dele)**: "ele me deu acesso a 4K e várias outras
   coisas" — o endpoint dinâmico na rede do dono é a prova final da casa.
3. **Furos conhecidos** (medidos): lançamentos 2024/25 (Deadpool & Wolverine,
   Superman), conteúdo nacional (Cidade de Deus) e anime nichado (Naruto,
   SpongeBob) voltam 0 fontes — papel do Torrentio BR
   (comando/bludv/micoleaodublado) e do Kitsu. Divisão perfeita: FrostStream
   = HTTP instantâneo dublado do acervo popular/clássico; Torrentio =
   lançamentos e acervo vasto (P2P); Kitsu = anime.

### 2. Tvvio paginado e Esportes no topo (a "lista com limite pequeno")

O dono: "quando eu vou nos canais de esporte... a lista tem um limite pequeno
e eu não consigo ficar passando e escolher um canal". Diagnóstico ao vivo
(07/11): o Tvvio **responde** a skip corretamente — `skip=100` devolve os 55
canais restantes dos 155, `skip=155` devolve 0 (parada limpa); o catálogo
`tvvio.esportes` totaliza 70 canais (inclusive **Premiere Clubes**; os
Premiere 2-7 aparecem na busca do catálogo principal) — mas **não declara**
skip no manifest, e o app só pagina quando o catálogo declara o extra
(`supportsSkip=false` → `hasMore=false`). Correção no `AddonMapper`: os ids
`tvvio` e `tvvio.esportes` recebem o extra `skip` injetado
(`FORCED_SKIP_CATALOG_IDS`) — a fileira passa a carregar todos os canais em
blocos; se o servidor parar de aceitar skip, o bloco extra vem vazio e a
fileira para sem loop. Na mesma mudança, a fileira **Esportes sobe para o
topo do Tvvio** (foco futebol BR do dono).

Nota sobre o "Fire TV" (FayaTV): o `live-and-upcoming` dele é uma fonte
INTERNACIONAL de eventos — o Vasco × Palmeiras do dia não estava lá (24
eventos, nenhum do Brasileirão) e os canais Esportes da instância BR têm só 7
fileiras. O Premiere do futebol BR mora no Tvvio; o FayaTV fica como casa de
eventos internacionais.

### 3. FayaTV completa — "Todos os Canais (BR)" (pedido do dono)

O dono: "tenho que ter acesso aos canais brasileiros mais fácil... já assisti
Premiere por ele e alguns outros canais como ESPN do Brasil". Medição da
instância completa (`/d:br`, 08/11/2026):

1. **O catálogo completo de canais existe**: o `country_filter` da instância
   `/d:br` traz **238 canais BR navegáveis e paginados** (25/página, aceita
   skip) — ESPN, ESPN 4, SBT Nacional/Interior, TV Globo Brasília, GloboNews,
   CazeTV, Nickelodeon etc. Na 1.6.4 ele estava oculto; na 1.6.5 volta na
   Home renomeado para **"Todos os Canais (BR)"**.
2. **O Premiere NÃO existe como canal no FayaTV** — nem na lista de 238. O
   que o dono assistiu foi Premiere como FONTE DE EVENTO (live-and-upcoming
   do dia). O Premiere navegável do pack é o do Tvvio (Premiere 2-7 +
   Premiere Clubes).
3. **Modelo freemium confirmado**: parte dos canais é 🔒 Premium
   (`payments.stravo.site`, $3/mês) e parte é aberta (CazeTV, SBT) — "um
   aberto e outros bloqueados", como o dono descreveu. Mantidos visíveis por
   decisão dele; o anúncio "Upgrade to Premium | FayaTV" (1º item do
   catálogo) é filtrado no app (`CatalogRepositoryImpl` — promo-meta não é
   canal).
4. **Curadoria**: `tv_Undefined` (canais sem classificação — os "sem
   imagem/irrelevantes") e `tv_Movies` ficam ocultos; a instância do pack
   passa de `d:br@cat=Sports:General` para `/d:br` completa; a busca extra
   do `country_filter` respondeu 0 para "espn" (sem implementação no lado do
   addon) — a navegação é por paginação mesmo.

### 4. BestCine entra com conta do dono; FrostView não voltou

- **BestCine**: o dono criou a conta e mandou o manifest pessoal
  (`usr_53ecf82b641a6e25cd0d8f78`), autorizando a entrada no pack. O domínio
  `bestcine.dpdns.org` fica atrás de desafio CF que bloqueia o CI (403 no
  manifest) — auditoria técnica pendente; a prova atual é o dono na rede
  dele (free trava 4K e itens específicos, segundo ele). Não-crítico no
  health; sai sem dó se poluir a Home ou não tocar no app.
- **FrostView (o "Frost TV" que o dono lembrava) NÃO voltou**: o DNS de
  `frostview.cloutteam.com` nem resolve mais (removido da zona cloutteam) —
  caiu ANTES do FrostStream e ficou morto. Volta só com URL nova oficial
  (prova: manifest+catálogo+stream na rede do dono e no health).

## FrostView e FrostStream: erro 1014 na borda do Cloudflare (1.6.3)

Relato do dono em 05/11/2026: no app 1.6.2 o FrostView "não encontra nada"
enquanto "no Stremio funciona". Investigação do dia fechou o caso — e a
resposta vale para os DOIS addons da casa cloutteam:

1. **O que é**: `frostview.cloutteam.com` virou CNAME para `cname.squareweb.app`
   (SquareCloud) — zona Cloudflare de OUTRA conta (confirmado nos resolvedores
   Google e Cloudflare DoH). O `froststream.cloutteam.com` nem CNAME tem
   (registro direto na zona cloutteam), mas resolve a ORIGEM para o mesmo
   alvo proibido. O Cloudflare se recusa a servir origem atrás de outra conta
   e devolve **403 "error code: 1014" (CNAME Cross-User Banned) ANTES de
   qualquer desafio** — não é UA, não é fingerprint, não é geo-bloqueio.
2. **A prova decisiva foi do dono**: o próprio teste sugerido aqui (abrir
   `/manifest.json` no navegador da rede residencial) devolveu a página 1014
   (Ray a557d4c84a55a3fc, 05/11 00:44 UTC). Navegador = Chromium real — se o
   navegador não passa, nenhum cliente passa.
3. **Como os testes da casa enganaram**: o 403 do datacenter parecia desafio
   de UA (o mesmo padrão que escondeu o FrostStream até a 1.6.1); o retry com
   UA desktop que ficou no `NetworkModule` não tem como vencer 1014 — o erro
   é na resolução da origem. E "no Stremio funciona" é cache do desktop:
   catálogo/manifest servidos do cache e stream é URL direta de terceiros
   que não passa pelo domínio do addon — pedido NOVO morre igual lá.
4. **Decisão (regra da casa, precedente Tvvio 1.5.2)**: morto para todo
   mundo = fora do pack. Os dois saíram na 1.6.3 com migração automática
   (usuário 1.6.2 sem personalização perde os dois na atualização).
5. **Quando voltam**: (a) dono do ADDON corrigir o domínio na SquareCloud —
   prova: manifest + stream 200 na rede do dono E no health local; ou (b)
   URL oficial alternativa saudável (subdomínio `*.squareweb.app` dá 404 e
   espelho comunitário está atrás de desafio próprio — nada utilizável em
   05/11). Ao vivo fica com 3 casas e o VOD HTTP com 2 + Torrentio BR.

## Tvvio volta e Home ao vivo enxuta (1.6.4)

Pedido do dono em 05/11/2026 (noite): "tá cheio e poluído a home... deixa só
os melhores de tv — tvvio que é um dos melhores deve ter canais gerais,
esportes e ao vivo; já o resto só precisa ter os de esporte ou geral".

1. **Tvvio VOLTA ao pack** (saiu na 1.5.2, morto na origem desde 27/10).
   Evidência do dia: manifesto 200 (v2.1.0), catálogo principal "tvvio" com
   156 canais (A&E, AMC, AXN...) e `/stream/tv/ec:aee.json` com 4 fontes —
   todos 200 no datacenter. As fontes FINAIS ainda mostram no datacenter as
   cicatrizes de 27/10 (cdn10embed 403 desafio CF ×2, relay baby-beamup 502,
   livepix 200) — datacenter mente para desafio de CF, então a prova final
   do play é na rede do dono ANTES do commit (mesma lição do Veyra:
   endpoint dinâmico é o que vale; manifesto/catálogo cacheados não provam
   nada). Se o dono relatar que não reproduz, Tvvio sai do tree antes da
   publicação.
2. **Fileiras do Tvvio**: visíveis só `tvvio` (geral/ao vivo — 156 canais
   + eventos) e `tvvio.esportes`. Os outros 6 catálogos (filmes-e-series,
   variedades, noticias, canais-abertos, infantil, religiosos) são
   subconjuntos redundantes e ficam ocultos no `AddonMapper`
   (`HIDDEN_DUPLICATED_CATALOG_IDS`, ids namespaced `tvvio.*`).
3. **FayaTV enxuta**: filtro da instância de fábrica reduzido de
   `d:br@cat=Sports:General:News:Kids:Entertainment` para
   `d:br@cat=Sports:General` — ficam 3 fileiras (live-and-upcoming, Esportes,
   Geral) e o `country_filter` segue oculto. Testado 200 no datacenter.
4. **NTC TV e Meu Futebol**: Meu Futebol já é só esporte; NTC fica como
   está até o próximo relatório do laboratório listar os catálogos dela
   (geo-bloqueia o datacenter — só o dono consegue ler o manifest).
5. **Resultado na Home**: ao vivo com 4 casas e fileiras mínimas — Tvvio
   (geral/ao vivo + esportes), FayaTV (ao vivo + esportes + geral), NTC TV,
   Meu Futebol; VOD com Cinemeta + FenixFlix + Kitsu + Torrentio.

## Veyra: origem morta no Shard Cloud (1.6.4)

Relato do dono em 05/11/2026 (fim de tarde): no app o Veyra "não carrega
nada, abre nem catálogo" — a tela de erro do app mostrou **timeout** no meta
de filme (`tmdb:13683337`). O dia foi uma aula de como cache de borda mente:

1. **Datacenter engana**: o health local vê o `veyra.shardweb.app` responder
   em ~50ms com 403 "Just a moment..." — desafio de borda para IP de
   datacenter, que NUNCA chega à origem. DNS normal, borda viva. Tudo isso
   não diz NADA sobre a saúde da origem.
2. **Cache de borda engana**: o dono abriu o `/manifest.json` e o
   `/catalog/...` no navegador da própria rede residencial e receberam JSON
   bonito — mas são CÓPIA DE CACHE (o catálogo declara `cacheMaxAge: 600`).
   Pareceu origem viva; não era.
3. **A prova decisiva foi do dono, no endpoint DINÂMICO**: o
   `/meta/movie/tmdb:1368337.json` devolveu **502 Bad Gateway do PRÓPRIO
   proxy do Shard Cloud** — "o servidor proxy não conseguiu estabelecer
   conexão com o servidor de destino", com a cadeia explícita:
   navegador ✓ → Shard Cloud ✓ → veyra.shardweb.app ✕ (causas listadas
   pela própria página: servidor indisponível, fora da porta 80, fora do
   0.0.0.0, timeout de conexão). Ou seja: a app/origem por trás do domínio
   está CAÍDA — 502 para TODO MUNDO, navegador incluso. Diferente dos
   Frosts/1014: lá a borda barrava antes da origem; aqui a borda entregaria
   se houvesse origem viva. Não é UA, não é desafio, não é geo-bloqueio.
4. **Decisão (regra da casa, precedentes Tvvio 1.5.2 e Frosts 1.6.3)**:
   morto para todo mundo = fora do pack. O Veyra saiu na 1.6.4 com migração
   automática (usuário 1.6.3 sem personalização perde o Veyra na
   atualização — `factoryPackV163`/`isFactoryPackV163`).
5. **Quando volta**: origem de volta no ar — prova: meta + stream 200 na
   rede do dono E no health local; ou URL oficial alternativa saudável. O
   VOD HTTP fica com FenixFlix (dublado) + Torrentio BR (comando/bludv/
   micoleaodublado) no fim da fila. Nota para o futuro: TESTAR SEMPRE UM
   ENDPOINT DINÂMICO (meta/stream) na rede do dono — manifest/catálogo
   cacheados não provam vida da origem.
