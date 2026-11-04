#!/usr/bin/env python3
"""TV Minimal — teste simulado de saúde dos addons de fábrica.

Testa exatamente o pack pré-configurado do flavor minimal (AddonPreferences.kt)
na ordem das fileiras da Home: FayaTV (ao vivo BR), NTC TV, Meu Futebol,
FrostView, Cinemeta, Veyra, FenixFlix, Anime Kitsu, FrostStream e Torrentio BR
(pack da 1.6.2 — Brazuca Play saiu, pedido do dono).

Para cada addon: manifest → catálogo (quando exposto) → streams. Para addons
de catálogo de filme usa o título de referência (tt0111161, Um Sonho de
Liberdade — sempre disponível); para addons de TV ao vivo usa o PRIMEIRO canal
do catálogo (stream de filme em addon de TV ao vivo é 0 streams por natureza).
Para o FayaTV valida ainda a PÁGINA /watch/ que o app resolve para reproduzir
no player interno (manifesto + ClearKey expostos no HTML).
Para o Brazuca valida a ORIGEM HLS de verdade (master → variante → segmento
com Range): em 05/11/2026 as playlists respondiam 200 mas TODOS os segmentos
davam 404 (origem watchug.watch/box4.sbs caída) — vídeo morto para todo
mundo enquanto o health antigo via "200 OK". Enquanto a origem estiver
quebrada o Brazuca é NÃO-crítico (avisa, não bloqueia a release).
Mede latência e valida a forma da resposta JSON do protocolo Stremio.

Saída: tabela + exit code. CRÍTICOS falhos (Cinemeta/FayaTV/Torrentio) =>
exit 1 para o CI sinalizar; não críticos => aviso (exit 0).

Histórico: Tvvio SAÍU na 1.5.2 — fora do ar para todo mundo desde 27/10/2026
(streams cdn10embed.xyz 403 Cloudflare + relay baby-beamup.club 502; relatos
no r/StremioAddons). Entrou FayaTV instância configurada BR (verificado
04/11/2026: manifest+catálogos+streams 200 OK; canais BR do catálogo são
geo-bloqueados fora do Brasil — no CI o teste de stream do canal pode falhar
sem significar addon morto, por isso o teste usa o 1º canal e falha só se
a RESPOSTA do addon quebrar, não se a URL final for 403).
1.6.0: PlavioHD SAIU (fraco pra PT-BR: fontes em áudio original/russo e 0
streams em títulos populares — pedido do dono). Entrou Brazuca Play (VOD HTTP
direto DUBLADO PT-BR, sem login; o catálogo de TV ao vivo dele é Pluto TV dos
EUA e não é testado). FrostStream é 403 do CI por bloqueio Cloudflare de IP de
datacenter — funciona em rede residencial (confirmado pelo dono); não-crítico.
1.6.1: OpenSubtitles SAÍU do pack (pedido do dono). O app passou a pedir
addons com UA de navegador (Cloudflare desafiava o UA "Nuvio/x" — o Frost nem
aparecia no app).
1.6.2: curadoria fechada com o relatório do LABORATÓRIO na rede do dono
(04/11/2026 17:22 — evidência decisiva; Cloudflare e geo-bloqueio mentem para
o IP de datacenter). Entram NTC TV, Meu Futebol, FrostView, Veyra e FenixFlix,
todos SAUDÁVEIS na rede residencial do dono e NÃO-críticos aqui: NTC TV e
Meu Futebol geo-bloqueiam datacenter (timeout), FrostView/Veyra/FenixFlix
devolvem 403 do Cloudflare. A falha desses cinco no CI é ESPERADA e não
bloqueia release — a validação viva deles é o app na TV (rede residencial).
Brazuca Play SAIU na 1.6.2 (pedido do dono: "odiei — não reproduz e suja o
catálogo"). Diagnóstico: NÃO é VIP/login — a origem (watchug.watch/box4.sbs)
está morrendo pra todo mundo (segmentos 404 em 04/11; piscou 200 e voltou a
502/0-6 em 05/11). Não re-adicionar sem pedido explícito do dono E origem
estável — se um dia testar de novo, a prova é a ladder de origem abaixo.

Uso: python3 ci/addon_health.py [--timeout 10]
"""

import argparse
import json
import re
import sys
import time
import urllib.request
import urllib.error

REF_IMDB = "tt0111161"  # Um Sonho de Liberdade — alvo estável de referência

# (nome, base_url, crítico?, [ref de prova HLS de origem])
# Mesma ordem do pack de fábrica (AddonPreferences.getDefaultAddons):
# canais ao vivo primeiro, como na Home do app. O 4º campo (opcional) pede a
# prova de ORIGEM HLS: abre o master do 1º stream HLS do título indicado,
# desce até o segmento e espera 200 — é o que reprova origem morta tipo a do
# Brazuca em 05/11/2026 (playlists 200, segmentos 404).
ADDONS = [
    (
        "FayaTV (ao vivo BR)",
        "https://fayatv.stravo.site/d:br@cat=Sports:General:News:Kids:Entertainment",
        True,
        None,
    ),
    # — NTC TV / Meu Futebol: geo-bloqueiam o datacenter do CI (conexão cai);
    #   SAUDÁVEIS na rede residencial do dono (lab 04/11/2026). Não-críticos.
    ("NTC TV (ao vivo BR)", "https://ntcstudios.com.br/ntctv", False, None),
    ("Meu Futebol (esportes BR)", "https://meufutebol.oneplayhd.com", False, None),
    # — FrostView: mesma casa do FrostStream (cloutteam); Globo/SBT/Record/ESPN.
    #   403 do Cloudflare no datacenter; a rede residencial passa (como no
    #   FrostStream). Validação final no app com UA de navegador.
    ("FrostView (abertas BR)", "https://frostview.cloutteam.com", False, None),
    ("Cinemeta (catálogo)", "https://v3-cinemeta.strem.io", True, None),
    # — Veyra / FenixFlix: Cloudflare 403 no datacenter; SAUDÁVEIS na rede do
    #   dono (Veyra 12 streams HTTP PT-BR; FenixFlix 3/3 dublado HD/4K). São os
    #   donos do VOD HTTP desde a saída do Brazuca Play na 1.6.2.
    ("Veyra (VOD HTTP BR)", "https://veyra.shardweb.app", False, None),
    ("FenixFlix (dublado BR)", "https://fenixflix.fenixhub.online", False, None),
    ("Anime Kitsu (catálogo)", "https://anime-kitsu.strem.fun", False, None),
    ("FrostStream", "https://froststream.cloutteam.com", False, None),
    (
        "Torrentio BR (torrent)",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,"
        "torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        True,
        None,
    ),
]

# UA de navegador: endpoints atrás de Cloudflare (FayaTV, FrostStream) bloqueiam
# UAs de bot antes mesmo do rate limit — e o app resolve páginas /watch/ com UA
# de navegador, então o teste medindo o caminho real do app também deve usar.
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

FILE_URL_RE = re.compile(r'var\s+fileUrl\s*=\s*"([^"]+)"')


def fetch(url: str, timeout: float):
    """GET com medição de latência. Retorna (status, json_dict|None, ms, erro)."""
    status, body, ms, err = fetch_raw(url, timeout)
    if err is not None:
        return status, None, ms, err
    try:
        return status, json.loads(body.decode("utf-8", "replace")), ms, None
    except json.JSONDecodeError:
        return status, None, ms, "resposta não-JSON"


def fetch_raw(url: str, timeout: float):
    """GET sem parse. Retorna (status, bytes|None, ms, erro)."""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read(4 * 1024 * 1024)
            ms = int((time.monotonic() - start) * 1000)
            return resp.status, body, ms, None
    except urllib.error.HTTPError as e:
        return e.code, None, int((time.monotonic() - start) * 1000), f"HTTP {e.code}"
    except Exception as e:  # noqa: BLE001 — relatório genérico
        return 0, None, int((time.monotonic() - start) * 1000), type(e).__name__


def probe_hls_origin(base: str, imdb: str, timeout: float):
    """Prova de origem HLS: master do 1º stream HLS do título → variante →
    segmento (Range 1 KB). Retorna (ok, ms_total, detalhe)."""
    t0 = time.monotonic()
    s, data, ms, err = fetch(f"{base}/stream/movie/{imdb}.json", timeout)
    streams = (data or {}).get("streams") or []
    master = next((st.get("url") for st in streams
                   if isinstance(st.get("url"), str) and ".m3u8" in st.get("url")), None)
    if not master:
        return False, int((time.monotonic() - t0) * 1000), "sem stream HLS na resposta"
    s2, body2, ms2, err2 = fetch_raw(master, timeout)
    if s2 != 200 or not body2:
        return False, int((time.monotonic() - t0) * 1000), f"master {err2 or s2}"
    variant = next((ln.strip() for ln in body2.decode("utf-8", "replace").splitlines()
                    if ln.strip() and not ln.startswith("#")), None)
    if not variant:
        return False, int((time.monotonic() - t0) * 1000), "master sem variante"
    if not variant.startswith("http"):
        variant = master.rsplit("/", 1)[0] + "/" + variant
    s3, body3, ms3, err3 = fetch_raw(variant, timeout)
    if s3 != 200 or not body3:
        return False, int((time.monotonic() - t0) * 1000), f"variante {err3 or s3}"
    seg = next((ln.strip() for ln in body3.decode("utf-8", "replace").splitlines()
                if ln.strip() and not ln.startswith("#")), None)
    if not seg:
        return False, int((time.monotonic() - t0) * 1000), "variante sem segmento"
    if not seg.startswith("http"):
        seg = variant.rsplit("/", 1)[0] + "/" + seg
    req = urllib.request.Request(seg, headers={"User-Agent": UA, "Accept": "*/*",
                                               "Range": "bytes=0-1023"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            n = len(resp.read(2048))
            return resp.status == 200 and n > 0, int((time.monotonic() - t0) * 1000), f"segmento 200 ({n}B)"
    except urllib.error.HTTPError as e:
        return False, int((time.monotonic() - t0) * 1000), f"segmento HTTP {e.code} — ORIGEM QUEBRADA"
    except Exception as e:  # noqa: BLE001
        return False, int((time.monotonic() - t0) * 1000), type(e).__name__


def check(name: str, base: str, critical: bool, timeout: float, hls_ref: str | None = None):
    rows, failed_critical = [], False

    def row(label: str, ok: bool, ms: int, detail: str):
        rows.append((name, label, "OK" if ok else "FALHA", ms, detail))
        return ok

    status, manifest, ms, err = fetch(f"{base}/manifest.json", timeout)
    if err or not isinstance(manifest, dict):
        row("manifest.json", False, ms, err or "sem JSON")
        return rows, critical  # falha de manifest é crítica se o addon for
    row("manifest.json", True, ms, f"id={manifest.get('id', '?')} v{manifest.get('version', '?')}")

    # Catálogo: primeiro catálogo exposto pelo manifest.
    catalogs = manifest.get("catalogs") or []
    if catalogs:
        cat = catalogs[0]
        type_, cid = cat.get("type"), cat.get("id")
        if type_ and cid:
            s, data, ms, err = fetch(f"{base}/catalog/{type_}/{cid}.json", timeout)
            metas = (data or {}).get("metas") or []
            row(
                f"catalog/{type_}/{cid}",
                bool(metas),
                ms,
                f"{len(metas)} metas" + (f" | {err}" if err else ""),
            )
            if critical and not metas:
                failed_critical = True

            # Addon de TV ao vivo: testa o stream do PRIMEIRO canal real (stream
            # de filme em addon de TV ao vivo devolve 0 streams por natureza).
            # Valida a RESPOSTA do addon (HTTP 200 + JSON com streams), não a URL
            # final do vídeo — canais geo-bloqueados falham fora do país sem
            # significar addon morto (caso dos canais BR do FayaTV no CI).
            if metas and type_ in ("tv", "channel", "sports"):
                first_id = str(metas[0].get("id", "")).replace("/", "")
                if first_id:
                    s2, data2, ms2, err2 = fetch(f"{base}/stream/{type_}/{first_id}.json", timeout)
                    streams2 = (data2 or {}).get("streams") or []
                    row(
                        f"stream/{type_}/1º canal",
                        s2 == 200,
                        ms2,
                        f"{len(streams2)} streams" + (f" | {err2}" if err2 else ""),
                    )
                    # FayaTV devolve páginas /watch/ que o app resolve para
                    # reproduzir no player interno (manifesto + ClearKey no HTML).
                    # Prova o caminho interno ponta a ponta: página 200 + fileUrl
                    # extraível (e kid/key quando o canal é CENC).
                    for s in streams2:
                        ext = str(s.get("externalUrl") or "")
                        if "fayatv.stravo.site/watch/" in ext:
                            s3, body3, ms3, err3 = fetch_raw(ext, timeout)
                            if err3 or s3 != 200:
                                row("página /watch/ → player interno", False, ms3,
                                    err3 or f"HTTP {s3}")
                                if critical:
                                    failed_critical = True
                            else:
                                html = body3.decode("utf-8", "replace")
                                file_url = FILE_URL_RE.search(html)
                                ok = bool(file_url) and bool(
                                    re.search(r"\.(m3u8|mpd|mp4|ts)(\?.*)?$", file_url.group(1), re.I)
                                )
                                row("página /watch/ → player interno", ok, ms3,
                                    (file_url.group(1)[-60:] if ok else "sem fileUrl reproduzível"))
                                if critical and not ok:
                                    failed_critical = True
                            break

    # Streams do título de referência — só para addons que DECLARAM o recurso
    # "stream" para o tipo (Cinemeta/OpenSubtitles não servem streams: 404 é o
    # comportamento correto, não falha). O protocolo aceita recursos como
    # string ("stream") ou objeto {"name": "stream", "types": [...]}.
    serves_movie_stream = False
    for r in manifest.get("resources") or []:
        if isinstance(r, str):
            if r == "stream":
                serves_movie_stream = True
        elif isinstance(r, dict) and r.get("name") == "stream":
            types = set(r.get("types") or [])
            if not types or "movie" in types:
                serves_movie_stream = True
    if serves_movie_stream:
        s, data, ms, err = fetch(f"{base}/stream/movie/{REF_IMDB}.json", timeout)
        streams = (data or {}).get("streams") or []
        row(
            f"stream/movie/{REF_IMDB}",
            s == 200,
            ms,
            f"{len(streams)} streams" + (f" | {err}" if err else ""),
        )

    # Prova de ORIGEM HLS (quando pedida): 200 na API não garante vídeo — no
    # Brazuca de 05/11/2026 os segmentos davam 404 para todo mundo. Falha aqui
    # só derruba o CI se o addon for crítico.
    if hls_ref:
        ok, ms_probe, detail = probe_hls_origin(base, hls_ref, timeout)
        row(f"origem HLS ({hls_ref})", ok, ms_probe, detail)
        if critical and not ok:
            failed_critical = True

    return rows, failed_critical and critical


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--timeout", type=float, default=10.0)
    args = ap.parse_args()

    all_rows, any_critical_fail = [], False
    for item in ADDONS:
        name, base, critical = item[0], item[1], item[2]
        hls_ref = item[3] if len(item) > 3 else None
        rows, crit_failed = check(name, base, critical, args.timeout, hls_ref)
        all_rows.extend(rows)
        if crit_failed:
            any_critical_fail = True

    print(f"{'ADDON':<24} {'ENDPOINT':<46} {'STATUS':<7} {'ms':>6}  DETALHE")
    print("-" * 110)
    for name, label, st, ms, detail in all_rows:
        print(f"{name:<24} {label:<46} {st:<7} {ms:>6}  {detail}")

    ok = sum(1 for r in all_rows if r[2] == "OK")
    print("-" * 110)
    print(f"{ok}/{len(all_rows)} verificações OK | críticos falhos: {'SIM' if any_critical_fail else 'não'}")
    sys.exit(1 if any_critical_fail else 0)


if __name__ == "__main__":
    main()
