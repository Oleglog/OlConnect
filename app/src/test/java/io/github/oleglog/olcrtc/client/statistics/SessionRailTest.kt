package io.github.oleglog.olcrtc.client.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRailTest {

    // Noon, cut to a minute boundary so the slots are easy to reason about.
    private val now = 1_760_000_000_000L - (1_760_000_000_000L % MINUTE_MILLIS)
    private fun minutesAgo(minutes: Long) = now - minutes * MINUTE_MILLIS

    @Test
    fun emptyHourHasNoData() {
        val slots = railSlots(emptyList(), now)
        assertEquals(RAIL_SLOTS, slots.size)
        assertEquals(List(RAIL_SLOTS) { RailCode.NONE }, slots)
    }

    @Test
    fun theMinuteInProgressFollowsTheLiveState() {
        val slots = railSlots(emptyList(), now, current = RailCode.LIVE)
        assertEquals(RailCode.LIVE, slots.last())
        assertEquals(RailCode.NONE, slots[RAIL_SLOTS - 2])
    }

    @Test
    fun aRunningSessionFillsTheMinutesItCovers() {
        val slots = railSlots(listOf(RailSession(startedAt = minutesAgo(10), endedAt = null)), now)
        assertEquals(RailCode.NONE, slots[RAIL_SLOTS - 12])
        // The minute the session started in counts, and so does every one after it.
        assertEquals(RailCode.LIVE, slots[RAIL_SLOTS - 11])
        assertEquals(RailCode.LIVE, slots.last())
    }

    @Test
    fun theMinutesBetweenTwoSessionsAreFlapping() {
        val slots = railSlots(
            listOf(
                RailSession(startedAt = minutesAgo(30), endedAt = minutesAgo(20)),
                RailSession(startedAt = minutesAgo(10), endedAt = null),
            ),
            now,
        )
        assertEquals(RailCode.LIVE, slots[RAIL_SLOTS - 25])
        assertEquals(RailCode.DEGRADED, slots[RAIL_SLOTS - 15])
        assertEquals(RailCode.LIVE, slots.last())
    }

    @Test
    fun minutesAfterTheLastSessionAreStopped() {
        val slots = railSlots(
            listOf(RailSession(startedAt = minutesAgo(20), endedAt = minutesAgo(5))),
            now,
        )
        assertEquals(RailCode.LIVE, slots[RAIL_SLOTS - 10])
        assertEquals(RailCode.STOPPED, slots[RAIL_SLOTS - 3])
    }

    @Test
    fun theNewestSlotIsPinnedByTheCurrentCode() {
        val slots = railSlots(
            listOf(RailSession(startedAt = minutesAgo(30), endedAt = null)),
            now,
            current = RailCode.DOWN,
        )
        assertEquals(RailCode.DOWN, slots.last())
        assertEquals(RailCode.LIVE, slots[RAIL_SLOTS - 2])
    }

    @Test
    fun watchedMinutesWinOverTheReconstruction() {
        val watched = List(RAIL_SLOTS) { RailCode.NONE }.toMutableList().also {
            it[RAIL_SLOTS - 1] = RailCode.LIMIT
        }
        val reconstructed = List(RAIL_SLOTS) { RailCode.LIVE }
        val merged = mergeRail(watched, reconstructed)
        assertEquals(RailCode.LIMIT, merged.last())
        assertEquals(RailCode.LIVE, merged[RAIL_SLOTS - 2])
    }

    @Test
    fun theRingSlidesForwardAndPadsTheNewMinutes() {
        val stored = List(RAIL_SLOTS) { RailCode.NONE }.toMutableList().also {
            it[RAIL_SLOTS - 1] = RailCode.LIVE
            it[RAIL_SLOTS - 2] = RailCode.DEGRADED
        }
        val slid = shiftRail(stored, fromMinute = now, toMinute = now + 5 * MINUTE_MILLIS)
        assertEquals(RailCode.LIVE, slid[RAIL_SLOTS - 6])
        assertEquals(RailCode.DEGRADED, slid[RAIL_SLOTS - 7])
        assertEquals(RailCode.NONE, slid.last())
    }

    @Test
    fun readingAnOlderNowPadsTheFront() {
        val stored = List(RAIL_SLOTS) { RailCode.NONE }.toMutableList().also {
            it[RAIL_SLOTS - 1] = RailCode.LIVE
        }
        val slid = shiftRail(stored, fromMinute = now, toMinute = now - 5 * MINUTE_MILLIS)
        // The newest stored minute is in the future now, so it falls out.
        assertEquals(RailCode.NONE, slid.last())
        assertEquals(RAIL_SLOTS, slid.size)
    }

    @Test
    fun aLongGapWipesTheRing() {
        val stored = List(RAIL_SLOTS) { RailCode.LIVE }
        assertEquals(
            List(RAIL_SLOTS) { RailCode.NONE },
            shiftRail(stored, fromMinute = now, toMinute = now + RAIL_SLOTS * MINUTE_MILLIS),
        )
    }

    @Test
    fun lettersRoundTripAndUnknownOnesAreNoData() {
        val codes = RailCode.entries
        assertEquals(codes, RailCode.parse(codes.joinToString("") { it.letter.toString() }))
        assertEquals(RailCode.NONE, RailCode.parse('?'))
        assertEquals(emptyList<RailCode>(), RailCode.parse(null as String?))
        assertEquals(emptyList<RailCode>(), RailCode.parse(""))
    }
}
