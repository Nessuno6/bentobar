package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.DevicesRules.Case
import com.meridian11.books.bentobar.items.DevicesRules.Device
import com.meridian11.books.bentobar.items.DevicesRules.Ears
import com.meridian11.books.bentobar.items.DevicesRules.Kind
import com.meridian11.books.bentobar.items.DevicesShareRules.Table
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What other apps read of Device batteries: the tables' names, columns and values are a promise to their code. */
class DevicesShareRulesTest {
    @Test fun theTablesAreDevicesAndCases() {
        assertEquals(Table.DEVICES, DevicesShareRules.table(listOf("devices")))
        assertEquals(Table.CASES, DevicesShareRules.table(listOf("cases")))
        assertNull(DevicesShareRules.table(emptyList()))
        assertNull(DevicesShareRules.table(listOf("devices", "1")))
        assertNull(DevicesShareRules.table(listOf("other")))
    }

    @Test fun theColumnsStayAsPublished() {
        assertArrayEquals(arrayOf("kind", "name", "percent", "charging", "ears"), DevicesShareRules.DEVICE_COLUMNS)
        assertArrayEquals(arrayOf("name", "percent", "seen_at"), DevicesShareRules.CASE_COLUMNS)
        assertEquals(1, DevicesShareRules.VERSION)
    }

    @Test fun aDeviceIsItsKindNameLevelAndCharging() {
        assertArrayEquals(arrayOf<Any?>("mouse", "Pebble", 74, 0, null),
            DevicesShareRules.row(Device("d1", "Pebble", Kind.MOUSE, 74, charging = false)))
        assertArrayEquals(arrayOf<Any?>("keyboard", "", null, 1, null),
            DevicesShareRules.row(Device("d2", "", Kind.KEYBOARD, null, charging = true)))
    }

    @Test fun earbudsSayWhichAreOut() {
        val buds = Device("b", "Buds", Kind.HEADPHONES, 60, false)
        assertEquals("both", DevicesShareRules.row(buds.copy(ears = Ears.BOTH))[4])
        assertEquals("left", DevicesShareRules.row(buds.copy(ears = Ears.LEFT))[4])
        assertEquals("right", DevicesShareRules.row(buds.copy(ears = Ears.RIGHT))[4])
        assertEquals("one", DevicesShareRules.row(buds.copy(ears = Ears.ONE))[4])
    }

    @Test fun aCaseIsItsBudsNameLevelAndWhenItWasSeen() {
        assertArrayEquals(arrayOf<Any?>("Buds", 82, 1_700_000_000_000L), DevicesShareRules.row(Case("Buds", 82, 1_700_000_000_000L)))
    }

    @Test fun watchersAreToldOfNewRowsOnly() {
        val a = listOf(Device("d1", "Pebble", Kind.MOUSE, 74, false))
        assertTrue(DevicesShareRules.differs(null, a))
        assertFalse(DevicesShareRules.differs(a, a.map { it.copy() }))
        assertTrue(DevicesShareRules.differs(a, listOf(a[0].copy(percent = 73))))
        assertTrue(DevicesShareRules.differs(a, emptyList()))
        // The descriptor is BentoBar's own and isn't shared: a new one alone changes nothing an app sees.
        assertFalse(DevicesShareRules.differs(a, listOf(a[0].copy(descriptor = "d9"))))
    }
}
