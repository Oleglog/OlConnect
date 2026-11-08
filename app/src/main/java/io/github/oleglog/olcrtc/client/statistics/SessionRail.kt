package io.github.oleglog.olcrtc.client.statistics

import android.content.Context

/**
 * One minute of the session rail.
 *
 * The letters are the panel's own vocabulary (`design-system.md`, §12.2), so a
 * screenshot from the phone and one from the panel read the same way:
 * L жив · U рвётся · P на пределе · D мёртв · I остановлен · . нет данных.
 * The phone cannot see relay 429s, so P is defined here and left for the day the
 * client learns about them; nothing on this screen invents it.
 */
internal enum class RailCode(val letter: Char) {
    LIVE('L'),
    DEGRADED('U'),
    LIMIT('P'),
    DOWN('D'),
    STOPPED('I'),
    NONE('.');

    companion object {
        fun parse(letter: Char): RailCode = entries.firstOrNull { it.letter == letter } ?: NONE

        fun parse(codes: String?): List<RailCode> =
            codes.orEmpty().map(::parse).takeLast(RAIL_SLOTS)
    }
}

/** The rail is one hour: 60 slots, the newest last. */
internal const val RAIL_SLOTS = 60
internal const val MINUTE_MILLIS = 60_000L

/** A session interval as the rail needs it; a null [endedAt] means it still runs. */
internal data class RailSession(val startedAt: Long, val endedAt: Long?)

/**
 * Reconstructs the hour from the sessions the service already persisted: a
 * minute covered by a session is alive, a minute between two sessions is a
 * restart gap, a minute after the tunnel was stopped is stopped, and minutes
 * before the first known session are unknown.
 *
 * [current] pins the minute in progress (the live state beats the reconstruction:
 * a session row is written after the tunnel is up, so the first seconds of a
 * connection would otherwise read as a gap).
 */
internal fun railSlots(
    sessions: List<RailSession>,
    nowMillis: Long,
    current: RailCode? = null,
): List<RailCode> {
    val newestMinute = nowMillis - Math.floorMod(nowMillis, MINUTE_MILLIS)
    val slots = if (sessions.isEmpty()) {
        List(RAIL_SLOTS) { RailCode.NONE }
    } else {
        val firstStart = sessions.minOf { it.startedAt }
        List(RAIL_SLOTS) { index ->
            val from = newestMinute - (RAIL_SLOTS - 1 - index) * MINUTE_MILLIS
            val to = from + MINUTE_MILLIS
            val covered = sessions.any { session ->
                session.startedAt < to && (session.endedAt ?: Long.MAX_VALUE) > from
            }
            when {
                covered -> RailCode.LIVE
                firstStart >= to -> RailCode.NONE
                // A session started after this minute: the tunnel was down here.
                sessions.any { it.startedAt >= to } -> RailCode.DEGRADED
                else -> RailCode.STOPPED
            }
        }
    }
    if (current == null) return slots
    return slots.toMutableList().also { it[RAIL_SLOTS - 1] = current }
}

/**
 * Merges what the app watched itself ([watched]) with what was reconstructed
 * from the sessions ([reconstructed]). Watch wins where it has a record: it is
 * the only source that can tell "stopped" from "the app was not running".
 */
internal fun mergeRail(watched: List<RailCode>, reconstructed: List<RailCode>): List<RailCode> =
    List(RAIL_SLOTS) { index ->
        val live = watched.getOrElse(index) { RailCode.NONE }
        if (live != RailCode.NONE) live else reconstructed.getOrElse(index) { RailCode.NONE }
    }

/** Slides a ring of [RAIL_SLOTS] so its newest slot lands on [toMinute]. */
internal fun shiftRail(codes: List<RailCode>, fromMinute: Long, toMinute: Long): List<RailCode> {
    val delta = (toMinute - fromMinute) / MINUTE_MILLIS
    val padded = codes.takeLast(RAIL_SLOTS).padStart()
    if (delta == 0L) return padded
    if (delta >= RAIL_SLOTS || delta <= -RAIL_SLOTS) return List(RAIL_SLOTS) { RailCode.NONE }
    return if (delta > 0) {
        // Time moved on: the oldest minutes fall off, the new ones have no data.
        val drop = delta.toInt()
        padded.drop(drop) + List(drop) { RailCode.NONE }
    } else {
        // Reading an older "now": the stored tail is in the future, pad the front.
        val add = (-delta).toInt()
        List(add) { RailCode.NONE } + padded.dropLast(add)
    }
}

private fun List<RailCode>.padStart(): List<RailCode> =
    if (size >= RAIL_SLOTS) takeLast(RAIL_SLOTS) else List(RAIL_SLOTS - size) { RailCode.NONE } + this

/**
 * The local ring: 60 one-minute slots in the app's own preferences, written by
 * the screen while it is open. It survives a restart and covers the minutes
 * before the first session row; the rest of the hour is reconstructed from the
 * sessions, which the service keeps writing while the app is closed.
 */
internal class SessionRailStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun record(code: RailCode, nowMillis: Long = clock()) {
        if (code == RailCode.NONE) return
        val nowMinute = nowMillis - Math.floorMod(nowMillis, MINUTE_MILLIS)
        val codes = shiftRail(RailCode.parse(preferences.getString(KEY_CODES, null)), storedMinute(), nowMinute)
        preferences.edit()
            .putString(KEY_CODES, codes.dropLast(1).joinToString("") { it.letter.toString() } + code.letter)
            .putLong(KEY_MINUTE, nowMinute)
            .apply()
    }

    fun read(nowMillis: Long = clock()): List<RailCode> {
        val stored = preferences.getString(KEY_CODES, null) ?: return List(RAIL_SLOTS) { RailCode.NONE }
        val nowMinute = nowMillis - Math.floorMod(nowMillis, MINUTE_MILLIS)
        return shiftRail(RailCode.parse(stored), storedMinute(), nowMinute)
    }

    private fun storedMinute(): Long =
        preferences.getLong(KEY_MINUTE, clock() - Math.floorMod(clock(), MINUTE_MILLIS))

    companion object {
        private const val PREFERENCES = "session_rail"
        private const val KEY_CODES = "codes"
        private const val KEY_MINUTE = "minute"
    }
}
