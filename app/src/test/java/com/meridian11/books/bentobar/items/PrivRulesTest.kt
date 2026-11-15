package com.meridian11.books.bentobar.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure state, backend selection, fallback rules and wire codec for optional Shizuku / root elevation. */
class PrivRulesTest {
    @Test fun shizukuStatusFromPackageBinderAndPermission() {
        assertEquals(PrivRules.ShizukuStatus.NOT_INSTALLED, PrivRules.shizukuStatus(installed = false, binderAlive = false, preV11 = false, granted = false))
        assertEquals(PrivRules.ShizukuStatus.NOT_RUNNING, PrivRules.shizukuStatus(installed = true, binderAlive = false, preV11 = false, granted = false))
        assertEquals(PrivRules.ShizukuStatus.PRE_V11, PrivRules.shizukuStatus(installed = true, binderAlive = true, preV11 = true, granted = true))
        assertEquals(PrivRules.ShizukuStatus.NEEDS_PERMISSION, PrivRules.shizukuStatus(installed = true, binderAlive = true, preV11 = false, granted = false))
        assertEquals(PrivRules.ShizukuStatus.GRANTED, PrivRules.shizukuStatus(installed = true, binderAlive = true, preV11 = false, granted = true))
        // Even if the package query missed it while binder arrived, granted binder wins.
        assertEquals(PrivRules.ShizukuStatus.GRANTED, PrivRules.shizukuStatus(installed = false, binderAlive = true, preV11 = false, granted = true))
    }

    @Test fun backendSelectionPrefersShizukuOverSuFallback() {
        assertEquals(PrivRules.Backend.SHIZUKU_ROOT, PrivRules.backend(PrivRules.ShizukuStatus.GRANTED, shizukuUid = 0, suAvailable = true))
        assertEquals(PrivRules.Backend.SHIZUKU_SHELL, PrivRules.backend(PrivRules.ShizukuStatus.GRANTED, shizukuUid = 2000, suAvailable = true))
        assertEquals(PrivRules.Backend.ROOT, PrivRules.backend(PrivRules.ShizukuStatus.NEEDS_PERMISSION, shizukuUid = null, suAvailable = true))
        assertEquals(PrivRules.Backend.NONE, PrivRules.backend(PrivRules.ShizukuStatus.NOT_RUNNING, shizukuUid = null, suAvailable = false))
    }

    @Test fun rootOnlyCommandsSkipAdbShellAndStillFallbackToSu() {
        assertTrue(PrivRules.tryShizuku(PrivRules.ShizukuStatus.GRANTED, shizukuUid = 2000, rootOnly = false))
        assertFalse(PrivRules.tryShizuku(PrivRules.ShizukuStatus.GRANTED, shizukuUid = 2000, rootOnly = true))
        assertTrue(PrivRules.tryShizuku(PrivRules.ShizukuStatus.GRANTED, shizukuUid = 0, rootOnly = true))
        assertFalse(PrivRules.tryShizuku(PrivRules.ShizukuStatus.NEEDS_PERMISSION, shizukuUid = null, rootOnly = false))

        assertTrue(PrivRules.trySu(PrivRules.ShizukuStatus.GRANTED, shizukuUid = 2000, suRefused = false))
        assertFalse(PrivRules.trySu(PrivRules.ShizukuStatus.GRANTED, shizukuUid = 0, suRefused = false))
        assertFalse(PrivRules.trySu(PrivRules.ShizukuStatus.NOT_INSTALLED, shizukuUid = null, suRefused = true))
    }

    @Test fun wireCodecPreservesExitCodeAndMultilineOutput() {
        val text = "line 1\nline 2\n"
        val encoded = PrivRules.encode(0, text)
        assertEquals(PrivRules.Result(0, text), PrivRules.decode(encoded))
        assertEquals(PrivRules.Result(127, ""), PrivRules.decode("127\n"))
        assertEquals(PrivRules.Result(1, "no match"), PrivRules.decode("1\nno match"))
        assertNull(PrivRules.decode(null))
        assertNull(PrivRules.decode("not-a-number\nhi"))
    }
}
