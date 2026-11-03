#!/usr/bin/env python3
"""TV Minimal — teste simulado de saúde dos addons de fábrica.

Testa exatamente o pack pré-configurado do flavor minimal (AddonPreferences.kt):
FayaTV (ao vivo BR), Cinemeta, OpenSubtitles v3, FrostStream, PlavioHD,
Torrentio BR e Anime Kitsu.

Para cada addon: manifest → catálogo (quando exposto) → streams. Para addons
de catálogo de filme usa o título de referência (tt0111161, Um Sonho de
Liberdade — sempre disponível); para addons de TV ao vivo usa o PRIMEIRO canal
do catálogo (stream de filme em addon de TV ao vivo é 0 streams por natureza).
Mede latência e valida a forma da resposta JSON do protocolo Stremio.

Saída: tabela + exit code. CRÍTICOS falhos (Cinemeta/FayaTV/Torrentio) => exit 1
para o CI sinalizar; não críticos => aviso (exit 0).

Histórico: Tvvio SAÍU na 1.5.2 — fora do ar para todo mundo desde 27/10/2026
(streams cdn10embed.xyz 403 Cloudflare + relay baby-beamup.club 502; relatos
no r/StremioAddons). Entrou FayaTV instância configurada BR (verificado
04/11/2026: manifest+catálogos+streams 200 OK; canais BR do catálogo são
geo-bloqueados fora do Brasil — no CI o teste de stream do canal pode falhar
sem significar addon morto, por isso o teste usa o 1º canal e falha só se
a RESPOSTA do addon quebrar, não se a URL final for 403).

Uso: python3 ci/addon_health.py [--timeout 10]
"""

import argparse
import json
import sys
import time
import urllib.request
import urllib.error

REF_IMDB = "tt0111161"  # Um Sonho de Liberdade — alvo estável de referência

# (nome, base_url, crítico?)
# Mesma ordem do pack de fábrica (AddonPreferences.getDefaultAddons):
# canais ao vivo primeiro, como na Home do app.
ADDONS = [
    (
        "FayaTV (ao vivo BR)",
        "https://fayatv.stravo.site/d:br@cat=Sports:General:News:Kids:Entertainment",
        True,
    ),
    ("Cinemeta (catálogo)", "https://v3-cinemeta.strem.io", True),
    ("Anime Kitsu (catálogo)", "https://anime-kitsu.strem.fun", False),
    ("FrostStream", "https://froststream.cloutteam.com", False),
    ("PlavioHD (HTTP 4K)", "https://hd.plavio.site", True),
    (
        "Torrentio BR (torrent)",
        "https://torrentio.strem.fun/providers=yts,eztv,1337x,thepiratebay,"
        "torrentgalaxy,magnetdl,comando,bludv,micoleaodublado|sort=qualityseeders|limit=100",
        True,
    ),
    ("OpenSubtitles v3", "https://opensubtitles-v3.strem.io", False),
]

UA = "TV-Minimal-AddonHealth/1.0 (NuvioTV fork CI)"


def fetch(url: str, timeout: float):
    """GET com medição de latência. Retorna (status, json_dict|None, ms, erro)."""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read(4 * 1024 * 1024)
            ms = int((time.monotonic() - start) * 1000)
            status = resp.status
    except urllib.error.HTTPError as e:
        return e.code, None, int((time.monotonic() - start) * 1000), f"HTTP {e.code}"
    except Exception as e:  # noqa: BLE001 — relatório genérico
        return 0, None, int((time.monotonic() - start) * 1000), type(e).__name__

    try:
        return status, json.loads(body.decode("utf-8", "replace")), ms, None
    except json.JSONDecodeError:
        return status, None, ms, "resposta não-JSON"


def check(name: str, base: str, critical: bool, timeout: float):
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

    return rows, failed_critical and critical


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--timeout", type=float, default=10.0)
    args = ap.parse_args()

    all_rows, any_critical_fail = [], False
    for name, base, critical in ADDONS:
        rows, crit_failed = check(name, base, critical, args.timeout)
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
