package com.nuvio.tv.ui.screens.player

/**
 * TV Minimal Edition — escada de recuperação de stall/queda AO VIVO, portada do
 * watchdog do tv-minimal (PlayerActivity v14.x: buffering timeout 20 s, stall
 * 10 s + poll 3 s, STATE_ENDED em live reinicia).
 *
 * O [PlayerStallWatchdogPolicy] upstream assume VOD (precisa de duração
 * conhecida); em live as decisões Skip* desistem — stream congelada até o
 * usuário reagir. Este objeto cobre o gap com a escada do tv-minimal:
 *
 *  1ª violação → REJOIN_LIVE_EDGE: seekToDefaultPosition força busca de
 *     segmento fresco no edge — barato, resolve a maioria das quedas de
 *     segmento/HLS sem reconstruir o player;
 *  2ª/3ª violação → RECONNECT_SOURCE: re-prepare completo da fonte
 *     (scheduleDeferredPlayerReinitialize), o "restartPlayback" do tv-minimal;
 *  teto: 3 tentativas dentro da janela de 2 min → NONE (parqueia; evita
 *     martelada no servidor/CDN e bateria — lição MAX_RETRIES do tv-minimal).
 *
 * A janela se auto-renova: quando todas as tentativas ficarem mais velhas que
 * a janela, a contagem recomeça do zero. VOD NUNCA passa por aqui — os
 * chamadores filtram por live de verdade (janela do player / tipo `channel` /
 * latch); falso positivo de live não pode reintroduzir o bug de seek em VOD
 * (lição tv-minimal v8.4).
 */
internal object LiveStallRecoveryPolicy {

    /**
     * Stall mínimo para agir em live. No tv-minimal era 10 s de stall + poll de
     * 3 s (pior caso ~13 s); aqui o poll do watchdog é de 1 s, então 12 s dá o
     * mesmo tempo percebido sem flanco prematuro.
     */
    const val LIVE_STALL_THRESHOLD_MS = 12_000L

    /** Janela em que tentativas contam para o teto (ms). */
    const val RECOVERY_WINDOW_MS = 120_000L

    /** Teto de tentativas dentro da janela (spirit do MAX_RETRIES do tv-minimal). */
    const val MAX_ATTEMPTS_PER_WINDOW = 3

    enum class Action { NONE, REJOIN_LIVE_EDGE, RECONNECT_SOURCE }

    data class Input(
        val stalledForMs: Long,
        val attempts: List<Long>,
        val nowMs: Long,
        val thresholdMs: Long = LIVE_STALL_THRESHOLD_MS,
        val windowMs: Long = RECOVERY_WINDOW_MS,
        val maxAttempts: Int = MAX_ATTEMPTS_PER_WINDOW,
    )

    fun evaluate(input: Input): Action {
        if (input.stalledForMs < input.thresholdMs) return Action.NONE
        val window = input.attempts.filter { input.nowMs - it in 0..input.windowMs }
        return when {
            window.isEmpty() -> Action.REJOIN_LIVE_EDGE
            window.size < input.maxAttempts -> Action.RECONNECT_SOURCE
            else -> Action.NONE
        }
    }

    /** Registra uma tentativa agora, descartando as que saíram da janela. */
    fun nextAttempts(attempts: List<Long>, nowMs: Long, windowMs: Long = RECOVERY_WINDOW_MS): List<Long> =
        attempts.filter { nowMs - it in 0..windowMs } + nowMs

    /** Janela inteira expirada → contagem pode recomeçar do zero. */
    fun resetNeeded(attempts: List<Long>, nowMs: Long, windowMs: Long = RECOVERY_WINDOW_MS): Boolean =
        attempts.isNotEmpty() && attempts.none { nowMs - it in 0..windowMs }
}
