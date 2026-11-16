package com.meridian11.books.bentobar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.items.Link
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

/**
 * BentoBar's own pairing card, in place of Google's device picker: One UI's dialog (a large rounded card at the bottom,
 * the title centred, the device's colours and font, text buttons in the accent colour), over the screen blurred behind
 * it (`LinkActivity` asks the window for that). [devices] grows as the scan finds them; [searching] while it may still.
 */
@Composable
fun LinkCard(
    devices: List<Link.Found>,
    searching: Boolean,
    linkedTo: String?,
    onPick: (Link.Found) -> Unit,
    onOther: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(220)) }
    val outside = remember { MutableInteractionSource() }
    val inside = remember { MutableInteractionSource() }
    // A tap outside the card closes it, as a One UI dialog's does.
    Box(
        Modifier.fillMaxSize().clickable(outside, indication = null, onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .graphicsLayer { alpha = appear.value; translationY = (1f - appear.value) * 40.dp.toPx() }
                .clip(RoundedCornerShape(26.dp))
                // Glass over the blurred screen: the card's surface a little see-through.
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.90f))
                // Taps on the card stay on it.
                .clickable(inside, indication = null) {}
                .padding(top = 24.dp, bottom = 8.dp),
        ) {
            Text(stringResource(R.string.link_card_title), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).semantics { heading() })
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.link_card_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerLowest.copy(alpha = 0.7f))) {
                devices.forEach { d -> DeviceRow(d, linked = linkedTo == d.name) { onPick(d) } }
                if (searching || devices.isEmpty()) Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.primary)
                    else SymIcon(Sym.SEARCH, size = 18.sp, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(if (searching) R.string.link_card_searching else R.string.link_card_none),
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            // One UI's dialog buttons: words in the accent colour, side by side, a thin line between them.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                DialogButton(stringResource(R.string.link_card_cancel), Modifier.weight(1f), onCancel)
                Box(Modifier.width(1.dp).height(20.dp).background(scheme.outlineVariant))
                DialogButton(stringResource(R.string.link_card_other), Modifier.weight(1f), onOther)
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Link.Found, linked: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
            DeviceIcon(device.name, scheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Text(device.name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (linked) Text(stringResource(R.string.link_card_linked), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
    }
}

/** One UI's icon where this build has one, else the Material symbol: a tablet, a watch, else a phone, by the device's name. */
@Composable
private fun DeviceIcon(name: String, tint: Color) {
    val sym = LinkCardRules.symbol(name)
    val context = LocalContext.current
    val oneUi = remember(sym) { com.meridian11.books.bentobar.util.OneUiIcons.drawable(context, sym) }
    if (oneUi != 0) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(oneUi), null, tint = tint, modifier = Modifier.size(22.dp))
    else SymIcon(sym, size = 22.sp, filled = true, color = tint)
}

@Composable
private fun DialogButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.alpha(1f))
    }
}

/** What the card shows of a device, pure (`LinkCardRulesTest`). */
object LinkCardRules {
    /** The device's kind as its name says it: Google gives no kind, and the user's devices are named for what they are. */
    fun symbol(name: String): String {
        val n = name.lowercase()
        return when {
            listOf("tab", "pad", "tablet").any { it in n } -> Sym.TABLET_ANDROID
            listOf("watch").any { it in n } -> Sym.WATCH
            else -> Sym.SMARTPHONE
        }
    }
}
