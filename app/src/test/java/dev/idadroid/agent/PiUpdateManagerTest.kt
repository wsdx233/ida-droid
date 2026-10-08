package dev.idadroid.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiUpdateManagerTest {
    @Test fun numericVersionOrderingDoesNotOfferOlderOrIdenticalReleases() {
        assertTrue(isNewerPiVersion("0.74.0", "1.0.0"))
        assertTrue(isNewerPiVersion("1.9.12", "1.10.0"))
        assertFalse(isNewerPiVersion("2.0.0", "1.99.99"))
        assertFalse(isNewerPiVersion("1.0.0+installed", "1.0.0+registry"))
    }

    @Test fun stableReleaseSupersedesPrereleaseWithoutDowngradingStableUsers() {
        assertTrue(isNewerPiVersion("1.0.0-rc.2", "1.0.0-rc.10"))
        assertTrue(isNewerPiVersion("1.0.0-rc.10", "1.0.0"))
        assertFalse(isNewerPiVersion("1.0.0", "1.0.0-rc.11"))
        assertFalse(isNewerPiVersion("1.0.0-rc.10", "1.0.0-rc.2"))
    }
}
