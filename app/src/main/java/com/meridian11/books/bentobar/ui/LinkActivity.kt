package com.meridian11.books.bentobar.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import android.view.WindowManager
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

    /**
     * Nearby devices' scan (BLUETOOTH_SCAN), asked for once before the picker: with it the Device link tile finds the
     * device last linked by itself. The picker opens whatever the answer.
     */
    private val scanAsk = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCard() else requestPicker()
    }

    // Once refused for good, Android answers at once with no dialog, and the picker opens straight away.
    private fun pickerOrAsk() {
        if (Link.canScan()) showCard() else scanAsk.launch(android.Manifest.permission.BLUETOOTH_SCAN)
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
            LinkRules.CreateStep.START_PICKER -> pickerOrAsk()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The card is up: asked again (the tile or the menu once more), it stays; a wake from the other side is accepted.
        if (cardShown) {
            if (intent.action == Link.ACTION_WAKE) acceptWake(intent)
            return
        }
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
        stopScan?.invoke(); stopScan = null
        clearTimeout()
        state = LinkRules.State.DONE
        super.onDestroy()
    }

    private fun acceptWake(intent: Intent?) {
        val wakeIntent = intent ?: return
        runCatching { Discovery.create(applicationContext).getParticipantFromIntent(wakeIntent) }
            .getOrNull()?.let { Link.accept(it) }
    }

    // ---- BentoBar's own pairing card (LinkCard), in place of Google's picker ----

    private var cardShown = false
    private var stopScan: (() -> Unit)? = null
    private val found = mutableStateOf<List<Link.Found>>(emptyList())
    private val searching = mutableStateOf(true)

    /**
     * The card over the screen blurred behind it (a dim where the device has no cross-window blur), the devices coming
     * in as the scan finds them. Google's picker stays one tap away ("Other devices"), and is what opens when the scan
     * can't run.
     */
    private fun showCard() {
        val stop = Link.scan { found.value = it }
        if (stop == null) { requestPicker(); return }
        stopScan = stop
        cardShown = true
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.25f)
        // Samsung's blur, as Quick Settings and BentoBar's popups have it (Android's own is off on Galaxy devices); else
        // Android's, where the device has it; else the dim alone.
        if (com.meridian11.books.bentobar.bar.SemBlur.wanted) {
            val decor = window.decorView
            decor.post { if (cardShown) com.meridian11.books.bentobar.bar.SemBlur.apply(decor, com.meridian11.books.bentobar.bar.SemBlur.RADIUS_PX, 0f) }
            window.setDimAmount(0.10f)
        } else if (android.os.Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = (48 * resources.displayMetrics.density).toInt() }
            window.setDimAmount(0.12f)
        }
        main.postDelayed({ searching.value = false }, LinkRules.PICKER_TIMEOUT_MS)
        setContent {
            BentoBarTheme {
                LinkCard(found.value, searching.value, Link.peer.takeIf { Link.linked },
                    onPick = { d -> stopScan?.invoke(); stopScan = null; Link.linkTo(d); done() },
                    onOther = { Log.i(TAG, "card: other devices"); leaveCard(); requestPicker() },
                    onCancel = { done() })
            }
        }
    }

    /** The card goes (for Google's picker): the scan stops, the screen behind is clear again. */
    private fun leaveCard() {
        stopScan?.invoke(); stopScan = null
        cardShown = false
        if (com.meridian11.books.bentobar.bar.SemBlur.wanted) com.meridian11.books.bentobar.bar.SemBlur.apply(window.decorView, 0, 0f)
        setContent { }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
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
