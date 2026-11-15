package com.meridian11.books.bentobar.ui

/**
 * Rules for [LinkActivity]: its lifecycle states, how a create or re-entry intent advances it, and what a failure
 * from Play services' device picker means to the user. Pure, unit-tested (`LinkRulesTest`).
 */
object LinkRules {
    /** How long [LinkActivity] waits for Play services to answer a picker request before closing and telling the user. */
    const val PICKER_TIMEOUT_MS = 8_000L

    enum class State { IDLE, REQUESTING_INTENT, AWAITING_PICKER, DONE }

    enum class CreateStep { START_PICKER, ACCEPT_AND_FINISH, RESUME_AWAITING, FINISH }

    data class NewIntentStep(val acceptWake: Boolean, val restartPicker: Boolean, val finish: Boolean, val nextState: State)

    enum class PickerProblem { DISABLED, TIMEOUT, FAILED }

    fun onCreate(isWake: Boolean, hasSavedState: Boolean, wasAwaitingPicker: Boolean): CreateStep = when {
        hasSavedState && wasAwaitingPicker -> CreateStep.RESUME_AWAITING
        hasSavedState -> CreateStep.FINISH
        isWake -> CreateStep.ACCEPT_AND_FINISH
        else -> CreateStep.START_PICKER
    }

    fun onNewIntent(state: State, isWake: Boolean): NewIntentStep = when {
        isWake && state == State.AWAITING_PICKER -> NewIntentStep(acceptWake = true, restartPicker = false, finish = false, nextState = State.AWAITING_PICKER)
        isWake -> NewIntentStep(acceptWake = true, restartPicker = false, finish = true, nextState = State.DONE)
        state == State.AWAITING_PICKER -> NewIntentStep(acceptWake = false, restartPicker = false, finish = false, nextState = State.AWAITING_PICKER)
        else -> NewIntentStep(acceptWake = false, restartPicker = true, finish = false, nextState = State.REQUESTING_INTENT)
    }

    fun shouldHandleTaskCallback(state: State, activeRequestId: Int, callbackRequestId: Int): Boolean =
        state == State.REQUESTING_INTENT && activeRequestId == callbackRequestId

    /** Maps a Play services failure to what the user sees, or `null` when Play services already report it canceled. */
    fun pickerProblem(statusCode: Int?, message: String?): PickerProblem? {
        if (statusCode == STATUS_CANCELED) return null
        if (statusCode == STATUS_TIMEOUT) return PickerProblem.TIMEOUT
        val text = message.orEmpty()
        if (statusCode in DISABLED_STATUS_CODES ||
            text.contains("Cross-Device Services is disabled", ignoreCase = true) ||
            (text.contains("Cross-device services", ignoreCase = true) && text.contains("disabled", ignoreCase = true))
        ) return PickerProblem.DISABLED
        return PickerProblem.FAILED
    }

    private const val STATUS_SERVICE_MISSING = 1
    private const val STATUS_SERVICE_VERSION_UPDATE_REQUIRED = 2
    private const val STATUS_SERVICE_DISABLED = 3
    private const val STATUS_TIMEOUT = 15
    private const val STATUS_CANCELED = 16
    private const val STATUS_API_NOT_CONNECTED = 17
    private val DISABLED_STATUS_CODES = setOf(
        STATUS_SERVICE_MISSING,
        STATUS_SERVICE_VERSION_UPDATE_REQUIRED,
        STATUS_SERVICE_DISABLED,
        STATUS_API_NOT_CONNECTED,
    )
}
