package com.meridian11.books.bentobar.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ambient.crossdevice.discovery.Discovery
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Link

/**
 * Device batteries' link ([Link]), with no window of its own. Started from the menu, it shows Google's device picker and
 * opens a link to the device picked; started by Google Play services with [Link.ACTION_WAKE] (the other side picked this
 * one), it accepts. Either way it then finishes: the link lives in the process, not here.
 */
class LinkActivity : ComponentActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        Link.picked(this, r.data)?.let { Link.open(it) }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Env.init(this)
        if (savedInstanceState != null) return
        if (intent.action == Link.ACTION_WAKE) {
            runCatching { Discovery.create(applicationContext).getParticipantFromIntent(intent) }.getOrNull()?.let { Link.accept(it) }
            finish()
        } else {
            Link.pickerIntent(this, getString(R.string.link_picker_title))
                .addOnSuccessListener { sender -> picker.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener { finish() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Link.ACTION_WAKE) {
            runCatching { Discovery.create(applicationContext).getParticipantFromIntent(intent) }.getOrNull()?.let { Link.accept(it) }
        }
    }
}
