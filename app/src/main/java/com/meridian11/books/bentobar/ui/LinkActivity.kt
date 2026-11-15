package com.meridian11.books.bentobar.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ambient.crossdevice.discovery.Discovery
import com.google.android.gms.common.api.ApiException
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Link

/**
 * Device batteries' link ([Link]), with no window of its own. Started from the menu, it shows Google's device picker and
 * opens a link to the device picked; started by Google Play services with [Link.ACTION_WAKE] (the other side picked this
 * one), it accepts. Either way it then finishes: the link lives in the process, not here.
 */
class LinkActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var state = LinkRules.State.IDLE
    private var requestId = 0
    private var timeout: Runnable? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        Link.picked(this, r.data)?.let { Link.open(it) }
        done()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Env.init(this)
        val isWake = intent?.action == Link.ACTION_WAKE
        val wasAwaiting = savedInstanceState?.getBoolean(STATE_AWAITING_PICKER, false) == true
        when (LinkRules.onCreate(isWake, savedInstanceState != null, wasAwaiting)) {
            LinkRules.CreateStep.RESUME_AWAITING -> state = LinkRules.State.AWAITING_PICKER
            LinkRules.CreateStep.FINISH -> done()
            LinkRules.CreateStep.ACCEPT_AND_FINISH -> {
                acceptWake(intent)
                done()
            }
            LinkRules.CreateStep.START_PICKER -> requestPicker()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val step = LinkRules.onNewIntent(state, intent.action == Link.ACTION_WAKE)
        if (step.acceptWake) acceptWake(intent)
        state = step.nextState
        when {
            step.finish -> done()
            step.restartPicker -> requestPicker()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_AWAITING_PICKER, state == LinkRules.State.AWAITING_PICKER)
    }

    override fun onDestroy() {
        clearTimeout()
        state = LinkRules.State.DONE
        super.onDestroy()
    }

    private fun acceptWake(intent: Intent?) {
        val wakeIntent = intent ?: return
        runCatching { Discovery.create(applicationContext).getParticipantFromIntent(wakeIntent) }
            .getOrNull()?.let { Link.accept(it) }
    }

    private fun requestPicker() {
        clearTimeout()
        val id = ++requestId
        state = LinkRules.State.REQUESTING_INTENT
        val watchdog = Runnable {
            if (!LinkRules.shouldHandleTaskCallback(state, requestId, id)) return@Runnable
            Log.w(TAG, "device picker request timed out")
            showProblem(LinkRules.PickerProblem.TIMEOUT)
            done()
        }
        timeout = watchdog
        main.postDelayed(watchdog, LinkRules.PICKER_TIMEOUT_MS)

        val task = runCatching { Link.pickerIntent(this, getString(R.string.link_picker_title)) }
            .getOrElse { e ->
                handleFailure(e)
                return
            }
        task.addOnSuccessListener { sender ->
            if (!LinkRules.shouldHandleTaskCallback(state, requestId, id) || isFinishing || isDestroyed) return@addOnSuccessListener
            clearTimeout()
            state = LinkRules.State.AWAITING_PICKER
            runCatching { picker.launch(IntentSenderRequest.Builder(sender).build()) }
                .onFailure { e ->
                    Log.w(TAG, "could not launch device picker: ${e.javaClass.simpleName}")
                    showProblem(LinkRules.PickerProblem.FAILED)
                    done()
                }
        }.addOnFailureListener { e ->
            if (!LinkRules.shouldHandleTaskCallback(state, requestId, id)) return@addOnFailureListener
            handleFailure(e)
        }.addOnCanceledListener {
            if (!LinkRules.shouldHandleTaskCallback(state, requestId, id)) return@addOnCanceledListener
            done()
        }
    }

    private fun handleFailure(error: Throwable) {
        val api = error as? ApiException
        val statusCode = api?.statusCode
        val message = api?.status?.statusMessage ?: error.message
        Log.w(TAG, "device picker failed: ${error.javaClass.simpleName} code=$statusCode")
        LinkRules.pickerProblem(statusCode, message)?.let(::showProblem)
        done()
    }

    private fun showProblem(problem: LinkRules.PickerProblem) {
        val text = when (problem) {
            LinkRules.PickerProblem.DISABLED -> R.string.link_disabled
            LinkRules.PickerProblem.TIMEOUT -> R.string.link_timeout
            LinkRules.PickerProblem.FAILED -> R.string.link_failed
        }
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
    }

    private fun clearTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    private fun done() {
        clearTimeout()
        state = LinkRules.State.DONE
        if (!isFinishing) finish()
    }

    private companion object {
        const val TAG = "BentoBarLink"
        const val STATE_AWAITING_PICKER = "awaiting_picker"
    }
}
