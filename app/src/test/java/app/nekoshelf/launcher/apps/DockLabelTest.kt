package app.nekoshelf.launcher.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class DockLabelTest {
    @Test
    fun anyDefaultBrowserInTheBrowserSlotReadsBrowser() {
        assertEquals("Браузер", AppsRepository.dockLabel(2, "org.mozilla.firefox", "Firefox", isDefault = true))
        assertEquals("Браузер", AppsRepository.dockLabel(2, AppsRepository.EINKBRO, "EinkBro", isDefault = true))
    }

    @Test
    fun aChosenAppKeepsItsOwnOrShortLabel() {
        assertEquals("Firefox", AppsRepository.dockLabel(2, "org.mozilla.firefox", "Firefox", isDefault = false))
        assertEquals("Firefox", AppsRepository.dockLabel(4, "org.mozilla.firefox", "Firefox", isDefault = false))
        assertEquals("Опции", AppsRepository.dockLabel(2, AppsRepository.SETTINGS, "Настройки", isDefault = false))
        assertEquals("KOReader", AppsRepository.dockLabel(0, AppsRepository.KOREADER, "KOReader 2026", isDefault = true))
    }
}
