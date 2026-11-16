package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkCardRulesTest {
    @Test fun aDeviceIsDrawnAsWhatItsNameSays() {
        assertEquals(Sym.TABLET_ANDROID, LinkCardRules.symbol("My Tab S12 Ultra"))
        assertEquals(Sym.WATCH, LinkCardRules.symbol("Galaxy Watch7"))
        assertEquals(Sym.SMARTPHONE, LinkCardRules.symbol("My S25 Ultra"))
    }
}
