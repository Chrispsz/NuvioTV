package com.nuvio.tv.core.build

/**
 * TV Minimal Edition — flavor `minimal`.
 *
 * Diferenças em relação ao `full`:
 *  - trailers só externos (nada de preview in-app em dwell — economia de RAM/CPU);
 *  - sem suporte/promo/logos do IMDb (menos superfície e menos rede);
 *  - plugins CloudStream LIGADOS e updater in-app LIGADO (aponta para o
 *    fork Chrispsz/NuvioTV via buildConfigField GITHUB_OWNER/GITHUB_REPO).
 */
object AppFeaturePolicy {
    val pluginsEnabled: Boolean = true
    val inAppUpdatesEnabled: Boolean = true
    val inAppTrailerPlaybackEnabled: Boolean = false
    val externalTrailerPlaybackEnabled: Boolean = true
    val supportNuvioEnabled: Boolean = false
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    val imdbRatingLogoEnabled: Boolean = false
}
