package com.meridian11.books.bentobar.ui

import com.meridian11.books.bentobar.ui.LinkRules.CreateStep
import com.meridian11.books.bentobar.ui.LinkRules.PickerProblem
import com.meridian11.books.bentobar.ui.LinkRules.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRulesTest {
    @Test fun createStartsPickerOrAcceptsWakeUnlessRestoringAwaitingSheet() {
        assertEquals(CreateStep.START_PICKER, LinkRules.onCreate(isWake = false, hasSavedState = false, wasAwaitingPicker = false))
        assertEquals(CreateStep.ACCEPT_AND_FINISH, LinkRules.onCreate(isWake = true, hasSavedState = false, wasAwaitingPicker = false))
        // Rotation while Play services' half-sheet is open keeps waiting for its result; any other restore finishes cleanly.
        assertEquals(CreateStep.RESUME_AWAITING, LinkRules.onCreate(isWake = false, hasSavedState = true, wasAwaitingPicker = true))
        assertEquals(CreateStep.FINISH, LinkRules.onCreate(isWake = false, hasSavedState = true, wasAwaitingPicker = false))
        assertEquals(CreateStep.FINISH, LinkRules.onCreate(isWake = true, hasSavedState = true, wasAwaitingPicker = false))
    }

    @Test fun newIntentFinishesOnWakeAndRestartsStalledPickerOnRetry() {
        val wakeWhileWaiting = LinkRules.onNewIntent(State.REQUESTING_INTENT, isWake = true)
        assertTrue(wakeWhileWaiting.acceptWake)
        assertTrue(wakeWhileWaiting.finish)
        assertFalse(wakeWhileWaiting.restartPicker)
        assertEquals(State.DONE, wakeWhileWaiting.nextState)

        val wakeWhileSheetOpen = LinkRules.onNewIntent(State.AWAITING_PICKER, isWake = true)
        assertTrue(wakeWhileSheetOpen.acceptWake)
        assertFalse(wakeWhileSheetOpen.finish)
        assertEquals(State.AWAITING_PICKER, wakeWhileSheetOpen.nextState)

        // Tapping Link again when the previous request stalled restarts the picker request cleanly.
        val retry = LinkRules.onNewIntent(State.REQUESTING_INTENT, isWake = false)
        assertFalse(retry.acceptWake)
        assertTrue(retry.restartPicker)
        assertFalse(retry.finish)
        assertEquals(State.REQUESTING_INTENT, retry.nextState)

        val retryWhileSheetOpen = LinkRules.onNewIntent(State.AWAITING_PICKER, isWake = false)
        assertFalse(retryWhileSheetOpen.restartPicker)
        assertFalse(retryWhileSheetOpen.finish)
        assertEquals(State.AWAITING_PICKER, retryWhileSheetOpen.nextState)
    }

    @Test fun onlyCurrentRequestCallbacksSettleWhileWaiting() {
        assertTrue(LinkRules.shouldHandleTaskCallback(State.REQUESTING_INTENT, activeRequestId = 2, callbackRequestId = 2))
        assertFalse(LinkRules.shouldHandleTaskCallback(State.REQUESTING_INTENT, activeRequestId = 2, callbackRequestId = 1))
        assertFalse(LinkRules.shouldHandleTaskCallback(State.DONE, activeRequestId = 2, callbackRequestId = 2))
        assertFalse(LinkRules.shouldHandleTaskCallback(State.AWAITING_PICKER, activeRequestId = 2, callbackRequestId = 2))
    }

    @Test fun failureCodesMapToHelpfulProblemsOrSilentCancel() {
        assertNull(LinkRules.pickerProblem(16, "Canceled"))
        assertEquals(PickerProblem.TIMEOUT, LinkRules.pickerProblem(15, "Timed out"))
        assertEquals(
            PickerProblem.DISABLED,
            LinkRules.pickerProblem(10, "Cannot createDevicePicker intent. Cross-Device Services is disabled."),
        )
        assertEquals(PickerProblem.DISABLED, LinkRules.pickerProblem(3, null))
        assertEquals(PickerProblem.DISABLED, LinkRules.pickerProblem(17, "API_NOT_CONNECTED"))
        assertEquals(PickerProblem.FAILED, LinkRules.pickerProblem(13, "Internal error"))
        assertEquals(PickerProblem.FAILED, LinkRules.pickerProblem(null, null))
    }
}
