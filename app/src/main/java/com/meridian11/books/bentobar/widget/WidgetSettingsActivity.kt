package com.meridian11.books.bentobar.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.items.DevicesRules
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.Link
import com.meridian11.books.bentobar.ui.BentoBarTheme
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.widget.WidgetRules.Cell
import com.meridian11.books.bentobar.widget.WidgetRules.Grid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One widget's settings: what each cell shows and, for the custom widget, its rows and columns; and for every widget
 * the text colour and the device order. The launcher opens it when a custom widget is added and from a widget's
 * Settings. Backing out of a new custom widget doesn't add it.
 */
class WidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Env.init(this)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        val provider = WidgetUpdater.providerOf(this, id)
        if (provider == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            BentoBarTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Settings(id, provider) {
                        WidgetUpdater.updateAsync(this, intArrayOf(id))
                        setResult(RESULT_OK, result)
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings(id: Int, provider: BaseWidget, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val custom = provider is CustomWidget
    val saved = remember { WidgetStore.grid(ctx, id) }
    var cols by remember { mutableIntStateOf(saved.cols) }
    var rows by remember { mutableIntStateOf(saved.rows) }
    val fixed = remember { provider.cells(ctx, id) }
    val count = if (custom) cols * rows else fixed
    val shownCols = when {
        custom -> cols
        provider is MainWidget -> WidgetRules.MAX
        else -> fixed
    }
    val slots = remember {
        mutableStateListOf<String>().apply {
            val s = WidgetStore.slots(ctx, id)
            repeat(WidgetRules.MAX * WidgetRules.MAX) { add(s.getOrNull(it) ?: WidgetRules.AUTO) }
        }
    }
    // Bumped when the order, a switch or the text colour changes, to read the devices again.
    var version by remember { mutableIntStateOf(0) }
    val devices by produceState<Pair<List<Cell>, Map<String, Cell>>?>(null, version) {
        value = withContext(Dispatchers.IO) { WidgetUpdater.cells(ctx) to WidgetStore.known(ctx) }
    }
    val (now, known) = devices ?: (emptyList<Cell>() to emptyMap())
    // Devices here and those seen before, in the order: the order lists both.
    val all = remember(devices) {
        WidgetRules.ordered(now + known.values.filter { k -> now.none { it.key == k.key } }, WidgetStore.priority(ctx))
    }
    val hidden = remember(version) { WidgetStore.hidden(ctx) }
    val night = isSystemInDarkTheme()
    val dark = remember(version, night) { WidgetArc.darkText(ctx) }
    fun changed() {
        version++
        WidgetUpdater.updateAsync(ctx)
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.widget_settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        Text(stringResource(R.string.widget_settings_help), style = MaterialTheme.typography.bodyMedium)

        if (custom) {
            Stepper(stringResource(R.string.widget_columns), cols) { cols = it }
            Stepper(stringResource(R.string.widget_rows), rows) { rows = it }
            Text(stringResource(R.string.widget_resize_note), style = MaterialTheme.typography.bodySmall)
        }

        val preview = WidgetRules.assign(slots.take(count), count, now.filter { it.key !in hidden }, now, known)
        for (r in 0 until (count + shownCols - 1) / shownCols) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (c in 0 until shownCols) {
                    val i = r * shownCols + c
                    Box(Modifier.weight(1f)) {
                        if (i < count) SlotCell(slots[i], preview[i], all, now, dark) { slots[i] = it }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (custom) WidgetStore.setGrid(ctx, id, Grid(cols, rows))
                WidgetStore.setSlots(ctx, id, slots.take(count).dropLastWhile { it == WidgetRules.AUTO })
                onSaved()
            }) { Text(stringResource(R.string.widget_save)) }
            OutlinedButton(onClick = { for (i in slots.indices) slots[i] = WidgetRules.AUTO }) { Text(stringResource(R.string.widget_all_auto)) }
        }

        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.widget_every), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.widget_text), style = MaterialTheme.typography.titleSmall)
        val text = remember(version) { WidgetStore.text(ctx) }
        // Dark mode first: the default.
        val choices = listOf(
            WidgetStore.Text.SYSTEM to R.string.widget_text_system, WidgetStore.Text.AUTO to R.string.widget_text_auto,
            WidgetStore.Text.DARK to R.string.widget_text_dark, WidgetStore.Text.LIGHT to R.string.widget_text_light,
        ).map { (t, label) -> t to stringResource(label) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { i, (t, label) ->
                SegmentedButton(selected = text == t, onClick = { WidgetStore.setText(ctx, t); changed() },
                    shape = SegmentedButtonDefaults.itemShape(i, choices.size)) { Text(label, maxLines = 2, textAlign = TextAlign.Center) }
            }
        }
        Text(stringResource(R.string.widget_order), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.widget_order_help), style = MaterialTheme.typography.bodySmall)
        all.forEachIndexed { i, cell ->
            OrderRow(cell, away = now.none { it.key == cell.key }, dark = dark, shown = cell.key !in hidden,
                canUp = i > 0, canDown = i < all.lastIndex,
                onMove = { to ->
                    val keys = all.map { it.key }.toMutableList()
                    keys.add(to, keys.removeAt(i))
                    WidgetStore.setPriority(ctx, keys)
                    changed()
                },
                onForget = { WidgetStore.forget(ctx, cell.key); changed() },
                onShow = { WidgetStore.setHidden(ctx, cell.key, !it); changed() },
                index = i)
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange(value - 1) }, enabled = value > 1) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = { onChange(value + 1) }, enabled = value < WidgetRules.MAX) { Text("+") }
    }
}

/** One cell: what it shows now, what it is set to, and a menu to change it. */
@Composable
private fun SlotCell(slot: String, shown: Cell?, options: List<Cell>, here: List<Cell>, dark: Boolean, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val bmp = remember(shown, dark) { WidgetArc.render(ctx, shown, dark).asImageBitmap() }
    val label = when (slot) {
        WidgetRules.AUTO -> stringResource(R.string.widget_auto)
        WidgetRules.EMPTY -> stringResource(R.string.widget_empty)
        else -> shown?.name ?: stringResource(R.string.widget_device)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable { open = true }) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(bmp, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(WidgetArc.W / WidgetArc.H.toFloat()))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.widget_auto)) }, onClick = { onPick(WidgetRules.AUTO); open = false })
            DropdownMenuItem(text = { Text(stringResource(R.string.widget_empty)) }, onClick = { onPick(WidgetRules.EMPTY); open = false })
            options.forEach { c ->
                val now = here.firstOrNull { it.key == c.key }
                val name = when {
                    now == null -> stringResource(R.string.widget_away, c.name)
                    now.percent != null -> stringResource(R.string.widget_level, c.name, DevicesRules.percentText(now.percent))
                    else -> c.name
                }
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(c.key); open = false })
            }
        }
    }
}

@Composable
private fun OrderRow(
    cell: Cell, away: Boolean, dark: Boolean, shown: Boolean, canUp: Boolean, canDown: Boolean, index: Int,
    onMove: (Int) -> Unit, onForget: () -> Unit, onShow: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val bmp = remember(cell, dark) { WidgetArc.render(ctx, cell, dark).asImageBitmap() }
    val up = stringResource(R.string.widget_move_up)
    val down = stringResource(R.string.widget_move_down)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column {
            IconButton(onClick = { onMove(index - 1) }, enabled = canUp, modifier = Modifier.size(36.dp).semantics { contentDescription = up }) { Text("▲") }
            IconButton(onClick = { onMove(index + 1) }, enabled = canDown, modifier = Modifier.size(36.dp).semantics { contentDescription = down }) { Text("▼") }
        }
        Image(bmp, contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(cell.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val peer = Link.peer
            val note = when {
                cell.seenAt > 0 -> stringResource(R.string.widget_seen, Fmt.duration(System.currentTimeMillis() - cell.seenAt))
                cell.linked && peer != null -> stringResource(R.string.widget_from_link, peer)
                else -> null
            }
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
        }
        if (away && cell.key != WidgetRules.SELF) TextButton(onClick = onForget) { Text(stringResource(R.string.widget_forget)) }
        Switch(checked = shown, onCheckedChange = onShow)
    }
}
