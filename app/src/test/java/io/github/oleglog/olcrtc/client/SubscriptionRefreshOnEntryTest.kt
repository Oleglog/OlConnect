package io.github.oleglog.olcrtc.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRefreshOnEntryTest {
    @Test
    fun refreshesOnFirstColdStartWhenEnabled() {
        assertTrue(shouldRefreshOnColdStart(alreadyRefreshedThisSession = false, autoRefreshEnabled = true))
    }

    @Test
    fun skipsWhenAlreadyRefreshedThisSession() {
        assertFalse(shouldRefreshOnColdStart(alreadyRefreshedThisSession = true, autoRefreshEnabled = true))
    }

    @Test
    fun skipsWhenToggleDisabled() {
        assertFalse(shouldRefreshOnColdStart(alreadyRefreshedThisSession = false, autoRefreshEnabled = false))
    }

    @Test
    fun skipsWhenBothAlreadyRefreshedAndDisabled() {
        assertFalse(shouldRefreshOnColdStart(alreadyRefreshedThisSession = true, autoRefreshEnabled = false))
    }

    @Test
    fun refreshesOnEntryWhenEnabledAndNeverRefreshed() {
        assertTrue(
            shouldRefreshSubscriptionsOnEntry(
                refreshInProgress = false,
                lastRefreshElapsedMillis = 0L,
                nowElapsedMillis = 10_000L,
                minimumIntervalMillis = 180_000L,
                autoRefreshEnabled = true,
            )
        )
    }

    @Test
    fun skipsWhenRefreshInProgress() {
        assertFalse(
            shouldRefreshSubscriptionsOnEntry(
                refreshInProgress = true,
                lastRefreshElapsedMillis = 0L,
                nowElapsedMillis = 10_000L,
                minimumIntervalMillis = 180_000L,
                autoRefreshEnabled = true,
            )
        )
    }

    @Test
    fun skipsWhenWithinDebounceInterval() {
        assertFalse(
            shouldRefreshSubscriptionsOnEntry(
                refreshInProgress = false,
                lastRefreshElapsedMillis = 100_000L,
                nowElapsedMillis = 200_000L,
                minimumIntervalMillis = 180_000L,
                autoRefreshEnabled = true,
            )
        )
    }

    @Test
    fun refreshesWhenDebounceIntervalElapsed() {
        assertTrue(
            shouldRefreshSubscriptionsOnEntry(
                refreshInProgress = false,
                lastRefreshElapsedMillis = 100_000L,
                nowElapsedMillis = 300_000L,
                minimumIntervalMillis = 180_000L,
                autoRefreshEnabled = true,
            )
        )
    }

    @Test
    fun skipsWhenAutoRefreshDisabled() {
        assertFalse(
            shouldRefreshSubscriptionsOnEntry(
                refreshInProgress = false,
                lastRefreshElapsedMillis = 0L,
                nowElapsedMillis = 300_000L,
                minimumIntervalMillis = 180_000L,
                autoRefreshEnabled = false,
            )
        )
    }
}
