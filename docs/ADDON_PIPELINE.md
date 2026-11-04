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
pelo `ci/addon_health.py` e pelo `PlayerMediaSourceFactory`). Nota: 403 do
CI/datacenter para o Frost é esperado e não é morte — a rede residencial
passa (o desafio Cloudflare mira IP de datacenter, não o usuário).

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
Futebol — só ligam no Brasil) e **o Cloudflare desafia o IP** (FrostView,
Veyra, FenixFlix, FrostStream — 403 instantâneo). Nenhum dos dois é morte:
a TV do dono está na rede residencial, onde o laboratório provou todos
SAUDÁVEIS (relatório 04/11/2026 17:22). Por isso:

- no `addon_health.py` esses addons são **não-críticos** — a falha deles no
  CI é esperada e documentada no próprio script;
- a **evidência decisiva** para entrar no pack é o relatório do laboratório
  (rede do dono) + o comportamento no app (UA de navegador da 1.6.1);
- a redundância do ao vivo agora é por **casas independentes**: FayaTV
  (stravo), NTC TV (ntcstudios.com.br), Meu Futebol (oneplayhd) e FrostView
  (cloutteam) — se uma casa cai, as outras três continuam na Home.

Pack 1.6.2 (ordem = fileiras da Home): FayaTV → NTC TV → Meu Futebol →
FrostView → Cinemeta → Veyra → FenixFlix → Kitsu → FrostStream → Torrentio BR.
**Brazuca Play saiu na 1.6.2** (pedido do dono: "odiei — não reproduz e suja
o catálogo"; tecnicamente a origem watchug.watch/box4.sbs está morrendo pra
todo mundo — não era VIP nem login). O VOD HTTP dublado fica por conta de
Veyra + FenixFlix + Torrentio BR (providers comando/bludv/micoleaodublado).
