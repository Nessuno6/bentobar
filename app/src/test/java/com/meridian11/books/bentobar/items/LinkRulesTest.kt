package com.meridian11.books.bentobar.items

import com.meridian11.books.bentobar.items.DevicesRules.Device
import com.meridian11.books.bentobar.items.DevicesRules.Ears
import com.meridian11.books.bentobar.items.DevicesRules.Kind
import com.meridian11.books.bentobar.items.LinkRules.Battery
import com.meridian11.books.bentobar.items.LinkRules.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkRulesTest {
    private val phone = Status(Battery(Kind.PHONE, "My Phone", 74, charging = true, speed = ChargeSpeed.FAST), listOf(
        Battery(Kind.WATCH, "Watch7", 50, charging = false),
        Battery(Kind.HEADPHONES, "Buds4 Pro", 13, charging = false, ears = Ears.BOTH),
        Battery(Kind.STYLUS, "Pen", null, charging = false),
    ))

    @Test fun aStatusComesBackAsItWasSent() {
        assertEquals(phone, LinkRules.decode(LinkRules.encode(phone)))
    }

    @Test fun theChargersSpeedTravelsWithIt() {
        val fast = Status(Battery(Kind.TABLET, "Tab", 50, charging = true, speed = ChargeSpeed.SUPER), emptyList())
        val back = LinkRules.decode(LinkRules.encode(fast))!!.self
        assertEquals(ChargeSpeed.SUPER, back.speed)
        assertEquals(ChargeSpeed.SUPER, LinkRules.incoming(LinkRules.Status(back, emptyList()), emptyList())[0].speed)
        // From a side that doesn't say the speed: charging, at a speed not known.
        val old = LinkRules.decode(LinkRules.HEADER + "\nself\tPHONE\t80\t1\t-\tPhone")!!.self
        assertEquals(true, old.charging)
        assertEquals(ChargeSpeed.NORMAL, old.speed)
        assertEquals("0", LinkRules.encode(Status(Battery(Kind.PHONE, "P", 5, charging = false, speed = ChargeSpeed.SUPER), emptyList()))
            .lines()[1].split('\t')[3])
    }

    @Test fun aNameCantBreakTheLines() {
        val sent = Status(Battery(Kind.TABLET, "Tab\twith\nbreaks", 5, charging = false), emptyList())
        assertEquals("Tab with breaks", LinkRules.decode(LinkRules.encode(sent))!!.self.name)
    }

    @Test fun whatCantBeReadIsLeftOut() {
        assertNull(LinkRules.decode("hello from a phone"))
        assertNull(LinkRules.decode(LinkRules.HEADER + "\ndev\tWATCH\t50\t0\t-\tWatch"))
        val odd = LinkRules.HEADER + "\nself\tPHONE\t80\t0\t-\tPhone\ndev\tTOASTER\t50\t0\t-\tToaster\ndev\tWATCH\t500\t0\t-\tWatch\ndev\tMOUSE\t9\t0\t-\tMouse"
        assertEquals(listOf("Mouse"), LinkRules.decode(odd)!!.devices.map { it.name })
    }

    @Test fun theOtherSidesDevicesFollowItsOwnAndOnesSeenHereCountOnce() {
        val here = listOf(Device("bt:1", "Watch7", Kind.WATCH, 49, charging = false))
        val rows = LinkRules.incoming(phone, here)
        assertEquals(listOf("My Phone", "Buds4 Pro", "Pen"), rows.map { it.name })
        assertEquals(Kind.PHONE, rows[0].kind)
        assertEquals(Ears.BOTH, rows[1].ears)
        assertEquals(null, rows[2].percent)
    }

    @Test fun onlyDevicesFoundHereAreSentOn() {
        val shown = listOf(Device("bt:1", "Mouse", Kind.MOUSE, 40, charging = false),
            Device(LinkRules.PREFIX + "0", "My Phone", Kind.PHONE, 74, charging = true))
        assertEquals(listOf(Battery(Kind.MOUSE, "Mouse", 40, charging = false)), LinkRules.outgoing(shown))
    }
}
