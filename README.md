<p align="center">
  <img src="docs/images/icon.png" width="112" alt="BentoBar icon">
</p>

<h1 align="center">BentoBar</h1>

<p align="center">
  <b>Add, hide and organise items in your status bar: on a Googlebook, and on Samsung tablets and phones with One UI.</b><br>
  Your own items next to the system's icons, as an ordinary Android app: no root, no adb. With root, it reads more.
</p>

<p align="center">
  <a href="../../releases/latest/download/BentoBar.apk"><b>⬇ Download BentoBar.apk</b></a>
  &nbsp;·&nbsp; <a href="#install">Install</a>
  &nbsp;·&nbsp; <a href="#privacy">Privacy</a>
  &nbsp;·&nbsp; <a href="../../releases">What's new</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/One_UI-9-1259E6" alt="One UI 9">
  <img src="https://img.shields.io/badge/Googlebook_OS-Android_17-133F43" alt="Googlebook OS, Android 17">
  <img src="https://img.shields.io/badge/root-optional-6A4C93" alt="Root optional">
  <img src="https://img.shields.io/badge/goes_online-for_five_items_you_set_up_and_update_checks-2E7D32" alt="Goes online for five items you set up, and to check for updates">
  <img src="https://img.shields.io/badge/license-MIT-555555" alt="MIT license">
  <img src="https://img.shields.io/badge/fork_tested_on-a_Galaxy_tablet-1F6F66" alt="This fork tested on a Galaxy tablet">
</p>

> **This is a fork** of [Meridian11Labs/bentobar](https://github.com/Meridian11Labs/bentobar), on the `notch-items`
> branch, made for Samsung tablets and phones with One UI. Everything it adds is in
> [What this fork adds](#what-this-fork-adds), with the details in [How it works](HOW-IT-WORKS.md). Its releases are
> signed with a different key than the official app's: uninstall the official BentoBar before installing one.

## What this fork adds

- **BentoBar in a Dynamic Island:** your items as wings of [Fonta's Dynamic Notch for Android](https://github.com/Nessuno6/FontaDynamicNotch/tree/android),
  their menus a tap (or a mouse hover) away, and BentoBar's own bar out of the way while the notch shows them.
  [How it works](HOW-IT-WORKS.md#bentobar-in-a-dynamic-island)
- **On a second display:** a bar of its own in a monitor's taskbar (DeX or desktop mode), which the notch never takes.
  [How it works](HOW-IT-WORKS.md#on-a-second-display)
- **One UI's look:** every icon in One UI's style, or Material 3 Expressive (**Look › Icons**), and One UI's own dialogs.
  [How it works](HOW-IT-WORKS.md#one-uis-look)
- **Widgets:** Device batteries on the home screen and on **One UI's lock screen**. [How it works](HOW-IT-WORKS.md#widgets)
- **Another device's batteries:** link your phone and tablet over Google's Cross device SDK, and each shows the other's
  battery and devices. [How it works](HOW-IT-WORKS.md#another-devices-batteries-cross-device-sdk)
- **Charging cases and charge speed:** the earbuds' case as last seen, and Samsung's charging steps up to Super fast
  2.0. [How it works](HOW-IT-WORKS.md#device-batteries-further)
- **Updates from inside the app:** checks six times a day, and installs with your OK. [How it works](HOW-IT-WORKS.md#updates-from-inside-the-app)
- **Root (optional):** temperatures, the apps using the CPU and memory, the battery's real cycles and health, a Galaxy
  watch's and earbuds' levels. [What root adds, and why](HOW-IT-WORKS.md#root)

<p align="center"><sub>BentoBar is made by Meridian11 LLC, developed on a Googlebook. This fork adds to it, and is tested on a
Samsung Galaxy tablet with One UI 9; Meridian11 doesn't make or support it (<a href="#about">more</a>).</sub></p>

<p align="center">
  <img src="docs/images/hero.png" width="880" alt="BentoBar's items as wings of Fonta's Dynamic Notch on a One UI tablet, with the Heat menu open under the Heat item">
</p>

## What it does

BentoBar puts **your own items** into the empty part of the status bar, right next to the
system's icons, in the same font and colour. Items you don't need all the time can show only while
they have something to say, or wait **behind the ‹ button**: one click reveals them, another folds
them away. Like a bento box: everything in its compartment, and the whole thing neat.

<p align="center">
  <img src="docs/images/bar.png" width="880" alt="BentoBar in a One UI tablet's status bar, over an app and over the wallpaper with an item's menu open">
  <br><sub>BentoBar after the clock on a One UI tablet: over an app (top), and over the wallpaper with Device batteries' menu open (bottom).</sub>
</p>

- **Show when active:** a hidden item can pop out on its own while it has something to say, such
  as a running timer, a meeting about to start or a busy CPU, and disappear again after. Its
  settings say the rule in words ("Show when CPU load is above 80%") and let you set the number.
- **Drag to reorder** in settings, within a section or into another one.
- **Click any item** for its menu: charts, details and actions.
- **Right-click an item** to hide it, move it or change how it looks.
- **Scroll** over the sound item to change the volume, or over the timer to add minutes. The sound
  item can also draw a **volume slider** right in the bar.
- **Quick Settings tiles** switch BentoBar off (for presenting), keep the screen awake, or start a
  25-minute timer.
- **Live Update chip:** when BentoBar is off, a running timer still shows as Android's own status
  bar chip.

BentoBar stays out of the way. It moves aside when the system's own icons or chips change, and
hides when an app goes full screen or the screen locks.

## Items

| Item | In the bar | In its menu |
|---|---|---|
| **CPU load** | busy %; can also pop out when the device runs hot | chart, per-core load, clock speeds, GPU load; with [root](HOW-IT-WORKS.md#root), CPU and GPU temperatures and the apps using the CPU |
| **Network speed** | ↓ download ↑ upload | chart, totals since start-up, connection |
| **Memory** | RAM in use | used, available, chart; with [root](HOW-IT-WORKS.md#root), the apps holding the most memory |
| **Battery details** | watts, %, temperature or time to full; while charging, its icon shows the charge speed (a double bolt for Super fast 2.0) | power in or out, charge speed, voltage, current, health, cycles (the real ones and health in percent with [root](HOW-IT-WORKS.md#root)), thermal state |
| **Storage** | free space | used and free, shortcuts to clean up |
| **Date and calendar** | the date, in your format | a month view, and the day's events (all-day ones too) with **Join** and **Directions**; tasks that apps like Todoist sync to your calendar are left out, here and in Next meeting |
| **Next meeting** | title and countdown, until 3 a.m. tomorrow; just the icon when there are no more meetings | today's meetings with **Join** and **Directions** |
| **Clock** | a second clock: seconds, 24-hour or another city | world clocks: add cities in the menu, and **Plan a time**, a slider that shows any time of day in every city (copy the line, or start a calendar event there) |
| **Timer** | countdown, stopwatch or Pomodoro | start, pause, +1 min, stop |
| **Countdown** | days and hours to a date | exact time left |
| **Keep awake** | coffee cup: click to switch | 15 min to "until I turn it off" |
| **Sound** | volume, as a number or as a slider you click or drag | volume slider, play/pause/next |
| **Now playing** | the title while something plays | artwork, position, previous, play and next, other players. The title needs notification access (optional); without it the item still shows that something plays and controls it |
| **Device batteries** | the lowest of your mouse, keyboard, stylus, controller, earbuds or watch, when it's low | every device that reports a battery, the earbuds' cases as last seen, and a linked phone or tablet running BentoBar; a Galaxy watch, ring or earbuds through Samsung's own list with [root](HOW-IT-WORKS.md#root) |
| **External displays** | connected monitor resolution & refresh rate (`4K 60Hz`), when plugged in | every display's resolution, refresh rate and HDR status, plus **Keep awake while connected** |
| **Heat** | a word when Android slows a hot device | heat level, battery temperature |
| **Clipboard & scratchpad** | latest snippet preview or count | pinned snippets and session clipboard history; click any row to copy and optionally paste into the active field |
| **Weather** *(online)* | conditions and temperature of a city you pick or of where you are; rain or snow that is coming; optionally a word for the sky ("Partly cloudy", "Windy") | the next hours and days, sunrise and sunset. From [Open-Meteo](https://open-meteo.com) |
| **Air quality & UV** *(online)* | US or European AQI and/or UV index for your city or location; pops out when AQI or UV is high | AQI band, PM2.5, PM10, Ozone ($\text{O}_3$), current and max daily UV. From [Open-Meteo](https://open-meteo.com) |
| **Flight** *(online)* | a line with the plane where the flight is, a countdown to departure with the gate, then to landing, and delays | times at both airports, terminal, gate, baggage belt; a choice where a number flies more than once a day. From AirLabs, with a free key of your own |
| **US stocks** *(online)* | US stocks you pick, one at a time (`AAPL 227.52 ▲1.2%`) for 3 seconds to a minute each, as you choose; can pop out on a big move | each stock's price, day change and range, and the market's hours. From [Finnhub](https://finnhub.io), with a free key of your own |
| **Exchange rates** *(online)* | currency pairs you follow (`EUR/USD 1.0942`), rotating in the bar; can pop out on a big daily move | rate and daily % change for up to 6 pairs, plus an inline currency converter. From the European Central Bank via [Frankfurter](https://frankfurter.dev) (no key needed) |
| **Tools** | toolbox | screenshot, lock, overview, all apps, power, settings shortcuts |
| **Shortcut** | one action, as its icon, its name or both (📷 Screenshot) | none: a click does it. Screenshot, Report a bug (on a Googlebook, its Feedback app), lock, overview, all apps, notifications, Quick settings |
| **App folder** / **App shortcut** | your apps | a grid of apps / one click to open |
| **Text or emoji**, **Spacer** | anything you like | optional link |

<p align="center">
  <img src="docs/images/menus.png" width="880" alt="BentoBar menus with One UI icons: battery, CPU, memory and device batteries (names and apps blurred)">
  <br><sub>Battery, CPU, Memory and Device batteries, each under its item. With root, the CPU menu adds temperatures and the apps using it, and Memory the apps holding it. Names and apps are blurred.</sub>
</p>

## Settings

A live preview of your bar at the top; below it, your items in three groups (**in the bar**,
**hidden** and **off**; drag a row to move it) with each item's options on the right. Changes apply to the
status bar right away.

<p align="center">
  <img src="docs/images/settings.png" width="880" alt="BentoBar settings with One UI icons: the bar preview, the item list and the CPU item's options">
</p>

<p align="center">
  <img src="docs/images/settings-pages.png" width="880" alt="Adding items, and the look and behaviour options with the Icons choice: Material 3 Expressive or One UI">
  <br><sub>Adding items, and choosing where BentoBar sits and how it looks, with Icons: Material 3 Expressive or One UI.</sub>
</p>

## Install

BentoBar was made for Googlebooks (Googlebook OS, Android 17); this fork is made and tested on a Samsung Galaxy tablet
with One UI 9 (Android 17), and runs on Android 14 or newer. If you have the official BentoBar, uninstall it first:
this fork is signed with a different key, so Android won't install it over the official one.

1. On your device, download **[BentoBar.apk](../../releases/latest/download/BentoBar.apk)** from the latest release.
2. Open it from your browser's downloads or My Files. If Android asks, allow that app to install apps, then tap
   **Install**. Google Play Protect may then say *"App blocked to protect your device"* because it hasn't seen an app
   from this developer before: tap **More details** › **Install anyway**.
3. Open **BentoBar**. It says what its accessibility service does and asks whether you agree:
   **Agree** opens Android's Accessibility settings. (Later, the same is under **Setup** › **Turn on**.)
4. Because BentoBar came from a download, Android guards this switch the first time:
   1. Open **BentoBar** and tap the switch. Android says *"Restricted setting"*. Tap **OK**.
   2. Back in BentoBar, open **Setup** and tap **App info**, then **⋮** (top right) › **Allow restricted
      settings**, and confirm with your PIN.
   3. Return to Accessibility › **BentoBar** and turn it on.
5. Optional: in **Setup**, allow notifications (timer alerts, the Live Update chip) and calendar
   access (Next meeting, events in the month view).

About a day later, Android shows *"Review app with full device access"*. That's a standard check
for every app that uses an accessibility service. Keep BentoBar if you're happy with what it does
(below).

To update, tap the update notification (or **Setup › Online services › Check now**): BentoBar downloads the new
release and Android installs it over the old one. Installing a newer `BentoBar.apk` by hand works too. Your layout
stays either way.

<a id="privacy"></a>
## Privacy

- **Accessibility service:** Android lets an app draw on the status bar only through one. BentoBar uses it to see
  windows appear, go or move (never their content), to read the status bar's layout and its clock's colour, and to run
  the system actions you pick. It doesn't read other apps' windows or watch your keyboard, mouse or touches.
- **Online:** only for five items you set up (Weather, Air quality, Exchange rates, Flight, US stocks), each to its own
  service, and for this fork's update check on GitHub. **Setup › Online services** switches each off. Nothing else
  leaves the device, except to your own other device when you [link them](HOW-IT-WORKS.md#another-devices-batteries-cross-device-sdk).
- **Optional access**, each for the items that use it: calendar, approximate location (rounded to about 10 km),
  notification access (it receives no notifications), usage access, installing its own updates, and [root](HOW-IT-WORKS.md#root).
- **Sharing with other apps** (Device batteries, the bar's items) is off by default, and each app needs your OK.
- No accounts, ads or analytics, and nothing is sent to its developer.

Everything it reads and sends, item by item, and every permission: [Privacy in full](HOW-IT-WORKS.md#privacy-in-full).

## About

BentoBar is made and published by Meridian11 LLC, with no affiliation with any employer, and was developed on a
Googlebook, on the device itself. This fork adds to it and is tested on a Samsung Galaxy tablet with One UI 9; it isn't
made, reviewed or supported by Meridian11 or Samsung, so report its problems on [this fork's issues](../../issues).
For developers: the interfaces for other apps and how to build BentoBar are in [HOW-IT-WORKS.md](HOW-IT-WORKS.md).

## License

BentoBar is free software under the [MIT License](LICENSE). It includes Google's
[Material Symbols](https://fonts.google.com/icons) (Apache License 2.0) and uses Jetpack Compose,
AndroidX, Kotlin and kotlinx.serialization (Apache License 2.0). This fork also includes icons from the One UI icon
pack (MIT, see [ONEUI-ICONS-LICENSE](ONEUI-ICONS-LICENSE)); One UI is Samsung's, and the pack isn't affiliated with
or endorsed by Samsung.

BentoBar is an independent project.
