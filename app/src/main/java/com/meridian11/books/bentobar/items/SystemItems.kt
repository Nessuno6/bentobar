package com.meridian11.books.bentobar.items

import android.content.Intent
import android.os.PowerManager
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.Meter
import com.meridian11.books.bentobar.ui.Sparkline
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Fmt
import com.meridian11.books.bentobar.util.Sym
import java.util.Locale

object NetworkItem : ItemType("network", R.string.item_network_title, Sym.SWAP_VERT, R.string.item_network_desc) {
    override val canBeActive = true
    private val above = Threshold("activeKBs", 500, 50..5000, step = 50) { Fmt.bytes(it * 1000.0) + "/s" }
    override val trigger = Trigger(R.string.trigger_network, R.string.trigger_network_short, above)

    override fun state(item: ItemConfig): ItemState {
        val n = Env.net
        val down = "↓" + Fmt.rate(n.down)
        val up = "↑" + Fmt.rate(n.up)
        val text = when (item.opt("show", "both")) {
            "down" -> down
            "up" -> up
            "total" -> Fmt.rate(n.down + n.up) + "/s"
            else -> "$down $up"
        }
        val threshold = above.of(item) * 1000.0
        return ItemState(icon = Sym.SWAP_VERT, text = text, active = maxOf(n.down, n.up) >= threshold, widthKey = "net",
            desc = Env.str(R.string.network_state_desc, Fmt.bytes(n.down), Fmt.bytes(n.up)))
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host ->
        rememberTick()
        val n = Env.net
        MenuCard(Sym.SWAP_VERT, stringResource(R.string.network_menu_title), Env.net.describe(Env.app)) {
            InfoRow(stringResource(R.string.network_download), "${Fmt.bytes(n.down)}/s", MaterialTheme.colorScheme.primary)
            InfoRow(stringResource(R.string.network_upload), "${Fmt.bytes(n.up)}/s", MaterialTheme.colorScheme.tertiary)
            Sparkline(n.downHistory.toList(), second = n.upHistory.toList())
            InfoRow(stringResource(R.string.network_received), Fmt.bytes(n.rxTotal.toDouble()))
            InfoRow(stringResource(R.string.network_sent), Fmt.bytes(n.txTotal.toDouble()))
            TopAppsSection(Usage.data, 10_000, stringResource(R.string.usage_top_data), stringResource(R.string.usage_optin_data),
                stringResource(R.string.usage_none_data), host)
            MenuDivider()
            MenuEntry(Sym.WIFI, stringResource(R.string.network_internet_settings)) { host.close(); Env.launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
            MenuEntry(Sym.DATA_USAGE, stringResource(R.string.network_data_usage)) { host.close(); Env.launch(Intent(Settings.ACTION_DATA_USAGE_SETTINGS)) }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        ChoiceRow(stringResource(R.string.option_show), listOf("both" to stringResource(R.string.network_show_both), "down" to stringResource(R.string.network_download),
            "up" to stringResource(R.string.network_upload), "total" to stringResource(R.string.network_show_total)),
            item.opt("show", "both")) { set(item.with("show", it)) }
    }
}

object MemoryItem : ItemType("memory", R.string.item_memory_title, Sym.MEMORY_ALT, R.string.item_memory_desc) {
    override val canBeActive = true
    private val above = Threshold("activePct", 85, 50..99) { "$it%" }
    override val trigger = Trigger(R.string.trigger_memory, R.string.trigger_memory_short, above)

    override fun state(item: ItemConfig): ItemState {
        val m = Env.mem
        val limit = above.of(item) / 100.0
        return ItemState(icon = Sym.MEMORY_ALT, text = Fmt.percent(m.used), active = m.used >= limit || m.low, widthKey = "mem",
            tone = if (m.low) Tone.WARN else Tone.NORMAL, desc = Env.str(R.string.memory_state_desc, Fmt.percent(m.used)))
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host ->
        rememberTick()
        val m = Env.mem
        // On a rooted device, with "Apps using memory" on: which apps hold the most, read every few seconds while open.
        val shownApps = AppsShown.of(item)
        val showApps = shownApps != AppsShown.OFF
        if (showApps) androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { RootMem.want(); kotlinx.coroutines.delay(1_000) } }
        val apps = if (showApps) RootMem.users else null
        MenuCard(Sym.MEMORY_ALT, stringResource(R.string.item_memory_title), stringResource(R.string.memory_in_use, Fmt.percent(m.used))) {
            Meter(m.used.toFloat(), if (m.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            InfoRow(stringResource(R.string.common_used), Fmt.bytes((m.total - m.avail).toDouble()))
            InfoRow(stringResource(R.string.memory_available), Fmt.bytes(m.avail.toDouble()))
            InfoRow(stringResource(R.string.memory_total), Fmt.bytes(m.total.toDouble()))
            // The sold-with size, when it differs from what Android can use.
            if (m.advertised > m.total) InfoRow(stringResource(R.string.memory_installed), Fmt.bytes(m.advertised.toDouble()))
            InfoRow(stringResource(R.string.memory_state), stringResource(if (m.low) R.string.memory_low else R.string.memory_normal),
                if (m.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (m.threshold > 0) InfoRow(stringResource(R.string.memory_low_below), Fmt.bytes(m.threshold.toDouble()))
            Sparkline(m.usedHistory.toList(), max = 1.0)
            if (!apps.isNullOrEmpty()) {
                for ((system, title) in listOf(false to R.string.cpu_top_apps, true to R.string.cpu_top_system)) {
                    if (system && !shownApps.system) continue
                    val rows = apps.filter { it.system == system }.take(RootCpu.EACH)
                    if (rows.isEmpty()) continue
                    MenuDivider()
                    com.meridian11.books.bentobar.ui.SectionLabel(stringResource(title))
                    rows.forEach { InfoRow(it.name, Fmt.bytes(it.bytes.toDouble())) }
                }
            }
            Spacer(Modifier.height(6.dp))
            MenuNote(stringResource(if (apps != null) R.string.memory_per_app_root_note else R.string.memory_per_app_note))
            MenuDivider()
            MenuEntry(Sym.APPS, stringResource(R.string.common_apps)) { host.close(); Env.launch(Intent(Settings.ACTION_APPLICATION_SETTINGS)) }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        AppsShownRow(item, R.string.memory_show_apps, R.string.memory_show_apps_help, set)
    }
}

object BatteryItem : ItemType("battery", R.string.item_battery_title, Sym.BOLT, R.string.item_battery_desc) {
    override val canBeActive = true
    /** A newer key: 20 was the fixed cutoff before it, so configs without it behave as they did. */
    private val below = Threshold("lowPct", 20, 5..50, step = 5) { "$it%" }
    override val trigger = Trigger(R.string.trigger_battery, R.string.trigger_battery_short, below)

    override fun state(item: ItemConfig): ItemState {
        val b = Env.battery
        val hot = b.tempC >= 42 || b.thermal >= PowerManager.THERMAL_STATUS_MODERATE
        val low = !b.charging && b.level < 0.2
        // The trigger has its own cutoff; the colors keep the fixed 20% and 10% warnings.
        val belowLimit = !b.charging && b.level < below.of(item) / 100.0
        val text = when (item.opt("show", "watts")) {
            "percent" -> Fmt.percent(b.level)
            "temp" -> String.format(Locale.ROOT, "%.0f°", b.tempC)
            "time" -> if (b.chargeTimeMs > 0) Fmt.duration(b.chargeTimeMs) else Fmt.percent(b.level)
            else -> Fmt.oneDecimal(kotlin.math.abs(b.watts)) + "W"
        }
        return ItemState(
            // Charging: the bolt in the charger's colour (green, blue for super fast), two of them for super fast 2.0.
            icon = if (b.charging) b.speed.glyph else if (b.level < 0.1) Sym.BATTERY_0_BAR else Sym.BATTERY_FULL,
            iconColor = if (b.charging) b.speed.color else null,
            filled = b.charging, text = text, active = belowLimit || hot, widthKey = "bat",
            tone = when { !b.charging && b.level < 0.1 -> Tone.ALERT; low || hot -> Tone.WARN; else -> Tone.NORMAL },
            desc = Env.str(R.string.battery_state_desc, Fmt.percent(b.level), b.statusText(), Fmt.oneDecimal(kotlin.math.abs(b.watts))),
        )
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host ->
        rememberTick()
        val b = Env.battery
        // On a rooted device, the real cycle count and health percentage that the system keeps from apps.
        androidx.compose.runtime.LaunchedEffect(Unit) { RootBattery.want() }
        val root = RootBattery.info
        MenuCard(if (b.charging) b.speed.glyph else Sym.BATTERY_FULL, stringResource(R.string.battery_menu_title), "${Fmt.percent(b.level)} · ${b.statusText()}") {
            Meter(b.level.toFloat(), when (BatteryBand.of(b.level)) {
                BatteryBand.FULL -> BatteryBand.GREEN
                BatteryBand.LOW -> BatteryBand.ORANGE
                BatteryBand.CRITICAL -> BatteryBand.RED
            })
            InfoRow(stringResource(if (b.watts >= 0) R.string.battery_charging_at else R.string.battery_using), String.format(Locale.ROOT, "%.1f W", kotlin.math.abs(b.watts)))
            Sparkline(b.wattHistory.toList())
            InfoRow(stringResource(R.string.battery_power_source), b.sourceText())
            if (b.plugged != 0) InfoRow(stringResource(R.string.battery_speed), stringResource(b.speed.words), b.speed.color)
            InfoRow(stringResource(R.string.battery_voltage), String.format(Locale.ROOT, "%.2f V", b.voltageMv / 1000.0))
            InfoRow(stringResource(R.string.battery_current), String.format(Locale.ROOT, "%d mA", kotlin.math.abs(b.currentUa) / 1000))
            InfoRow(stringResource(R.string.battery_temperature), String.format(Locale.ROOT, "%.1f °C", b.tempC),
                if (b.tempC >= 42) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            InfoRow(stringResource(R.string.battery_health), root?.healthPct?.let { "$it% · ${b.healthText()}" } ?: b.healthText())
            // Android's count, unless it says 0: some devices (a Samsung tablet months old) fill it with 0 and keep the real one to
            // themselves, which root can read.
            val cycles = root?.cycles ?: b.cycles.takeIf { it > 0 }
            if (cycles != null) InfoRow(stringResource(R.string.battery_cycles), cycles.toString())
            if (b.chargeTimeMs > 0) InfoRow(stringResource(R.string.battery_full_in), Fmt.duration(b.chargeTimeMs))
            InfoRow(stringResource(R.string.battery_thermal_state), b.thermalText())
            MenuDivider()
            MenuEntry(Sym.BOLT, stringResource(R.string.battery_usage)) { host.close(); Env.launch(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
            MenuEntry(Sym.SETTINGS, stringResource(R.string.battery_saver)) { host.close(); Env.launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
        }
    }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        ChoiceRow(stringResource(R.string.option_show), listOf("watts" to stringResource(R.string.battery_show_watts),
            "percent" to stringResource(R.string.battery_show_percent), "temp" to stringResource(R.string.battery_temperature),
            "time" to stringResource(R.string.battery_show_time)),
            item.opt("show", "watts")) { set(item.with("show", it)) }
    }
}

object StorageItem : ItemType("storage", R.string.item_storage_title, Sym.HARD_DRIVE, R.string.item_storage_desc) {
    override val refreshMs = 30_000L
    override val canBeActive = true
    /** A newer key: 10 was the fixed cutoff before it. */
    private val below = Threshold("freePct", 10, 1..50) { "$it%" }
    override val trigger = Trigger(R.string.trigger_storage, R.string.trigger_storage_short, below)

    override fun state(item: ItemConfig): ItemState {
        val s = Env.storage
        val freeFrac = if (s.total == 0L) 1.0 else s.free.toDouble() / s.total
        return ItemState(icon = Sym.HARD_DRIVE, text = Fmt.bytes(s.free.toDouble()).replace(" ", ""),
            active = freeFrac < below.of(item) / 100.0, tone = if (freeFrac < 0.05) Tone.ALERT else if (freeFrac < 0.1) Tone.WARN else Tone.NORMAL,
            desc = Env.str(R.string.storage_state_desc, Fmt.bytes(s.free.toDouble())))
    }

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { _, host ->
        rememberTick() // the sampler's fields aren't observable: redraw with the tick, like the other menus
        val s = Env.storage
        val used = if (s.total == 0L) 0.0 else (s.total - s.free).toDouble() / s.total
        MenuCard(Sym.HARD_DRIVE, stringResource(R.string.item_storage_title), stringResource(R.string.storage_free_of, Fmt.bytes(s.free.toDouble()), Fmt.bytes(s.total.toDouble()))) {
            Meter(used.toFloat())
            InfoRow(stringResource(R.string.common_used), Fmt.bytes((s.total - s.free).toDouble()))
            InfoRow(stringResource(R.string.storage_free), Fmt.bytes(s.free.toDouble()))
            TopAppsSection(Usage.storage, 60_000, stringResource(R.string.usage_top_storage), stringResource(R.string.usage_optin_storage),
                stringResource(R.string.usage_none_storage), host)
            MenuDivider()
            MenuEntry(Sym.HARD_DRIVE, stringResource(R.string.storage_settings)) { host.close(); Env.launch(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            MenuEntry(Sym.DELETE, stringResource(R.string.storage_free_up)) { host.close(); Env.launch(Intent(StorageManager.ACTION_MANAGE_STORAGE)) }
        }
    }
}

/** The battery bar's colour by level, as One UI's: green down to 20%, orange from 20% to 15%, red below 15%. */
enum class BatteryBand {
    FULL, LOW, CRITICAL;

    companion object {
        /** [level]: 0 to 1. */
        fun of(level: Double): BatteryBand = when {
            level < 0.15 -> CRITICAL
            level < 0.20 -> LOW
            else -> FULL
        }
        val GREEN = androidx.compose.ui.graphics.Color(0xFF3DB35B)
        val ORANGE = androidx.compose.ui.graphics.Color(0xFFF59E0B)
        /** A red that stays red in either theme (the theme's error colour is pink in dark). */
        val RED = androidx.compose.ui.graphics.Color(0xFFE5484D)
    }
}
