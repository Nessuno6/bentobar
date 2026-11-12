package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootMoreTest {
    @Test fun memoryAsTopPrintsIt() {
        val out = """
            [RES]NAME
            587M com.samsung.android.app.notes
            543M system_server
            470M com.samsung.android.app.smartcapture:aiselect
            1.2G com.example.big
            30M com.samsung.android.app.smartcapture
            900K top
        """.trimIndent()
        val users = RootMem.parse(out, 3)
        assertEquals(listOf("com.example.big", "com.samsung.android.app.notes", "system_server"), users.map { it.name })
        assertEquals((1.2 * (1L shl 30)).toLong(), users[0].bytes)
        // An app's processes together.
        assertEquals(500L shl 20, RootMem.parse(out, 10).first { it.name == "com.samsung.android.app.smartcapture" }.bytes)
        assertEquals(1024L, RootMem.size("1"))
    }

    @Test fun temperaturesFromTheTabletsZones() {
        val out = "ac=26000\nbattery=24000\ncpu-little3-2-3=39244\ncpu-big7-4-2=40828\ngpu0=35721\nsoc_max=40979\napu-a0=\npmic=31230"
        val t = RootThermal.parse(out)!!
        assertEquals(40.828, t.cpu!!, 1e-9)
        assertEquals(35.721, t.gpu!!, 1e-9)
        assertEquals(40.979, t.soc!!, 1e-9)
        assertNull(RootThermal.parse("Permission denied"))
    }

    @Test fun appsInTheMenuReadsTheItemsOption() {
        val base = com.meridian11.books.bentobar.data.ItemConfig(id = "x", type = "cpu")
        assertEquals(AppsShown.ALL, AppsShown.of(base))
        assertEquals(AppsShown.APPS, AppsShown.of(base.with(AppsShown.OPTION, "apps")))
        assertEquals(AppsShown.OFF, AppsShown.of(base.with(AppsShown.OPTION, "off")))
        // The switch before the choice wrote "false" for off.
        assertEquals(AppsShown.OFF, AppsShown.of(base.with(AppsShown.OPTION, "false")))
    }

    @Test fun theBatteryBarsColourByLevel() {
        assertEquals(BatteryBand.FULL, BatteryBand.of(1.0))
        assertEquals(BatteryBand.FULL, BatteryBand.of(0.20))
        assertEquals(BatteryBand.LOW, BatteryBand.of(0.19))
        assertEquals(BatteryBand.LOW, BatteryBand.of(0.15))
        assertEquals(BatteryBand.CRITICAL, BatteryBand.of(0.14))
    }
}
