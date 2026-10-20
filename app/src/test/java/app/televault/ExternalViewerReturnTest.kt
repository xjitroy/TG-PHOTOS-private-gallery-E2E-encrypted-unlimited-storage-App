package app.televault

import org.junit.Assert.*
import org.junit.Test

class ExternalViewerReturnTest {
    @Test fun onlyCurrentExternalViewerCanFinishAndOnlyOnce() {
        val owner = ExternalViewerReturn()
        owner.accept("chrome")
        owner.accept("downloads")
        assertFalse(owner.close("chrome"))
        assertTrue(owner.close("downloads"))
        assertFalse(owner.close("downloads"))
    }

    @Test fun launcherEntryOrShareFlowClearsExternalViewReturn() {
        val owner = ExternalViewerReturn("chrome")
        owner.accept(null)
        assertFalse(owner.close("chrome"))
    }

    @Test fun recreatedActivityKeepsItsExternalReturnUntilConsumed() {
        val before = ExternalViewerReturn("files")
        val after = ExternalViewerReturn(before.requestId)
        assertTrue(after.close("files"))
        assertNull(after.requestId)
    }
}
