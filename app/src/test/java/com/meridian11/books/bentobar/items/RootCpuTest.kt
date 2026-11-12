package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Test

class RootCpuTest {
    /** As `top -b -n 2 … -o %CPU,NAME` printed it on an 8-core tablet: the second frame counts. */
    private val out = """
        800%cpu  63%user   7%nice  86%sys 619%idle
        [%CPU]NAME
         90.0 com.google.android.youtube
        800%cpu  60%user   5%nice  80%sys 650%idle
        [%CPU]NAME
         44.0 surfaceflinger
         27.0 com.google.android.youtube
         16.0 com.meridian11.books.bentobar
          4.0 com.google.android.youtube:remote
          2.0 top
          0.0 vold
    """.trimIndent()

    @Test fun theLastFrameAsSharesOfAllCores() {
        val users = RootCpu.parse(out, cores = 8, limit = 5)
        assertEquals(listOf("surfaceflinger", "com.google.android.youtube", "com.meridian11.books.bentobar"), users.map { it.name })
        // An app's processes together, top itself and idle processes left out; 44% of one core of eight is 5.5% of the CPU.
        assertEquals(0.055, users[0].share, 1e-9)
        assertEquals(0.03875, users[1].share, 1e-9)
    }

    @Test fun nothingToReadIsAnEmptyList() {
        assertEquals(emptyList<RootCpu.User>(), RootCpu.parse("Permission denied", cores = 8, limit = 5))
        assertEquals(1, RootCpu.parse(out, cores = 8, limit = 1).size)
    }

    @Test fun coreGroupsAreNamedByTheirTopSpeed() {
        // The tablet's three groups: cores 0-3 at 2.70 GHz, 4-6 at 3.50, core 7 at 4.21.
        assertEquals(listOf(CoreRoles.Role.EFFICIENCY, CoreRoles.Role.PERFORMANCE, CoreRoles.Role.PRIME),
            CoreRoles.of(listOf(2_700_000L, 3_500_000L, 4_210_000L)))
        assertEquals(listOf(CoreRoles.Role.EFFICIENCY, CoreRoles.Role.PERFORMANCE), CoreRoles.of(listOf(1_800_000L, 2_900_000L)))
        assertEquals(listOf(null, null), CoreRoles.of(listOf(2_000_000L, 2_000_000L)))
    }
}
