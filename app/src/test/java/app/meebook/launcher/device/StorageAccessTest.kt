package app.meebook.launcher.device

import app.meebook.launcher.device.StorageAccess.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageAccessTest {
    @Test
    fun automaticAskNeverOpensSettings() {
        // First ever ask, after a plain denial, and after "denied for good" (asked, no rationale).
        assertEquals(Action.REQUEST, StorageAccess.decide(fromCard = false, askedBefore = false, rationale = false))
        assertEquals(Action.REQUEST, StorageAccess.decide(fromCard = false, askedBefore = true, rationale = true))
        assertEquals(Action.NONE, StorageAccess.decide(fromCard = false, askedBefore = true, rationale = false))
    }

    @Test
    fun cardButtonFallsBackToSettingsOnlyWhenDeniedForGood() {
        assertEquals(Action.REQUEST, StorageAccess.decide(fromCard = true, askedBefore = false, rationale = false))
        assertEquals(Action.REQUEST, StorageAccess.decide(fromCard = true, askedBefore = true, rationale = true))
        assertEquals(Action.SETTINGS, StorageAccess.decide(fromCard = true, askedBefore = true, rationale = false))
    }
}
