# How BentoBar works (this fork)

The details behind [the README](README.md): what this fork adds and how it behaves, what root adds, the interfaces
for other apps, and how to build it. For what BentoBar reads and sends, see [Privacy in full](#privacy-in-full).

## What this fork adds

### BentoBar in a Dynamic Island

[Fonta's Dynamic Notch for Android](https://github.com/Nessuno6/FontaDynamicNotch/tree/android) is a Dynamic Island
around the camera. With this fork, BentoBar's items live in it (the main picture in [the README](README.md)):

- **Wings:** items you choose sit beside the closed notch as small square wings, each with its icon and live value,
  left or right, in any order and as many as fit. The notch keeps itself centred on the camera and resizes whenever a
  wing, a live notification or the music changes, with no empty gaps.
- **A BentoBar tab** in the open notch lists every item with its value. Hold one to put it on the **Left** or
  **Right** wing, or take it off.
- **Menus where you look:** tap a wing, or an item in the tab, and BentoBar's own menu for it opens right under it,
  as it would under the bar. With a mouse, resting the pointer on a wing opens it too (after the notch's hover delay);
  moving on to the notch closes that menu as the notch opens.
- **One bar, not two:** items on the wings leave BentoBar's bar. With the notch's **Hide BentoBar's own bar** on, the
  whole bar steps aside, ‹ and its pill included, since every item is in the notch's tab.
- **It comes back:** switch the notch off (its Quick Settings tile, or its service) and BentoBar's bar returns at
  once. If the notch stops without saying so, the bar returns by itself within two minutes. After BentoBar itself
  restarts or updates, its bar can show beside the notch for up to a minute, until the notch claims it again.
- **Same look:** the icons the notch shows are BentoBar's, so **Look › Icons** (Material 3 Expressive or One UI)
  applies to the notch too.

To set it up: in BentoBar, turn on **Look › Other apps**; in the notch's settings, turn on **BentoBar › Show BentoBar
in the notch**, allow it to read BentoBar's items when Android asks, and choose **Hide BentoBar's own bar** if you
like. Any other app can do the same: see [The bar's items in your app](#the-bars-items-in-your-app).

### On a second display

Connect a monitor (in Samsung DeX or Android's desktop mode) or a wireless display, and BentoBar puts a bar of its own
in **that display's taskbar**, next to its system icons, with every item and its menus opening upward from the
taskbar. **Look › Show on** chooses where BentoBar appears: **All displays**, **This screen** (the device's own) or
**Other displays** (the monitors only).

- The notch lives on the device's own screen only, so **a second display always keeps BentoBar's whole bar**, even
  while the notch has taken it over on the device.
- Screen recording and screen mirroring (they copy this screen) and private displays get no bar of their own; a display
  without a taskbar shows none until it has one.
- The **External displays** item shows the monitor's resolution and refresh rate (`1440p 60Hz`), and can keep the
  device awake while it's connected.

### One UI's look

- **Icons:** every one of BentoBar's icons has a One UI version, in the bar, its menus and the app. **Look › Icons**
  switches between **Material 3 Expressive** and **One UI**. They come from the One UI icon pack (MIT, see
  `ONEUI-ICONS-LICENSE`) and One UI's own system icons for devices.
- **Dialogs:** the update prompts are the device's own dialogs, so on Samsung they look like the rest of One UI.

### Widgets

- **Home screen:** Device batteries as arcs, in four sizes and a grid of your own.
- **Lock screen:** Device batteries for **One UI's lock screen**, with 1 or 2 devices (long-press the lock screen ›
  Edit › Widgets). One UI keeps a lock screen widget off the home screen, so these are widgets of their own.

### Device batteries, further

- **Another device's batteries:** link your phone and your tablet, and each shows the other's battery and the devices
  it sees (see [below](#another-devices-batteries-cross-device-sdk)).
- **Charging cases:** the earbuds' case level as last seen, since Samsung knows it only while an earbud is in it.
- **Charge speed:** how fast the charger charges, in Samsung's own steps: Adaptive fast, Super fast, Super fast 2.0.

### Another device's batteries (Cross device SDK)

Your phone sees your watch and earbuds; your tablet sees its keyboard and S Pen. Linked, each BentoBar shows both:
the other device's own battery, and the devices that one sees, as rows of **Device batteries** (in its menu, in the
bar when one runs low, and as arcs in the widgets).

It's built on Google's **[Cross device SDK](https://developer.android.com/guide/topics/connectivity/cross-device-sdk/overview)**,
which runs in Google Play services:

1. Install this fork's BentoBar on both devices, signed in to the same Google account.
2. On one, open **Device batteries**' menu and tap **Show another device's batteries**. Google's device picker lists
   your own devices; pick the other one.
3. Google Play services wakes BentoBar on that device, which accepts, and both say **Linked to …**.

While linked, each side sends the other, at once and then every 30 seconds: its own battery (kind, level, charging and
how fast) and the devices it shows itself (kind, name, level, charging, and which earbuds are in), never the devices the
other side told it of. A device both see is listed once. If one side stops sending, its rows go after 90 seconds (the
widgets keep them for two minutes, across a brief drop). The link lasts while BentoBar runs on both; when it drops,
the menu offers **Link to … again**, one tap, and **Unlink** ends it.

Google Play services finds the device and carries the messages, encrypted, between your two devices: BentoBar has no
server, opens no connection of its own for this, and keeps nothing of what it's told on the device. The SDK is still a
preview from Google (`0.1.0-preview01`), so linking may change with Google Play services.

### Updates from inside the app

Six times a day BentoBar asks GitHub for this fork's latest release and notifies you
of a newer one. Tap it: the first time, Android asks you to let BentoBar install apps; then BentoBar downloads the APK
and Android's installer does the rest. **Setup › Online services** has **Check for updates** (off stops the checks)
and **Check now**. See [Privacy](README.md#privacy) for exactly what is asked.

## Privacy in full

Why BentoBar has an accessibility service, and everything it reads and sends.

Android lets an app draw on top of the status bar only through an accessibility service, so
Android warns that BentoBar can "view and control your screen". Here is everything BentoBar does
with that access:

- It listens only for **windows appearing, going or moving**, not their content, so it can hide
  for full-screen apps and shades.
- It reads the **status bar's layout, and no other window**, to find free space.
- It copies the **colour of the status bar clock**, from a picture of the status bar's own window
  (not your screen), so your items match.
- It runs **system actions** (screenshot, lock, overview, all apps, notifications, Quick Settings,
  power) when you pick them in the Tools menu or click a Shortcut item.

BentoBar doesn't read other apps' windows and doesn't watch your keyboard, mouse or touches.

**BentoBar goes online for five items, only after you set them up** on this device, and for this fork's update
check (below):

- **Weather** sends [Open-Meteo](https://open-meteo.com) the city you search for, and for the forecast
  the coordinates of the city you pick, rounded to about a kilometre: about every 30 minutes while the
  bar is on screen, and when you open its menu or press Refresh. Open-Meteo sees your IP address, as
  any website does. With **My location** instead of a city, BentoBar asks Android for the device's
  approximate location (never the precise one) while that item is in the bar or waits in Hidden to show for
  rain or snow, or while BentoBar's settings window is open: every 30 minutes once it knows, and when
  Android doesn't, again after 1, 2, 5 and 15 minutes, then every 30. It sends
  Open-Meteo that location rounded to about 10 km. BentoBar asks only for Android's "while using the
  app" permission, not for background location. Where you are, and its forecast, are kept
  in memory only: never on the device, in your layout or in its backup.
- **Air quality & UV** sends Open-Meteo what Weather does (the city you search for, then the city's
  coordinates or, with My location, where the device is, rounded in the same way): about every 30
  minutes while the bar is on screen, and when you open its menu or press Refresh. Its readings are
  kept in memory only. Weather's switch in Setup › Online services covers it too.
- **Exchange rates** asks [Frankfurter](https://frankfurter.dev) for the European Central Bank's
  reference table of currencies, at most once an hour while the bar is on screen, and when you open
  its menu or press Refresh. No key is needed, and the pairs you follow and the converter's amounts are
  worked out on the device. Frankfurter sees your IP address, as any website does.
- **Flight** sends [AirLabs](https://airlabs.co) the flight number and your own AirLabs key: when you
  track a flight, when you press Refresh, and while it follows the flight (about every 3 hours, then
  every 30 minutes or sooner from 3 hours before departure until it lands). AirLabs sees your IP
  address and can tie lookups to your key's account. BentoBar ships no key.
- **US stocks** sends [Finnhub](https://finnhub.io) your own Finnhub key with the symbol of each stock you
  follow: every 2 minutes while the US market is open and the bar is on screen, once after the close,
  and when you open its menu, press Refresh or save a new key. Its search sends what you type when you
  press Search.
  Finnhub sees your IP address and can tie requests to your key's account. BentoBar ships no key.

**This fork also checks for updates:** six times a day (every 4 hours, when there's a network) it asks
GitHub's API for this fork's latest release, and shows a notification when it's newer than yours. The
request asks for that release only and carries nothing about your device or your items; GitHub sees
your IP address, as any website does. **Setup › Online services › Check for updates** turns it off, and
**Check now** beside it asks right away.
Tapping the notification updates BentoBar from inside it: the first time, Android asks you to let BentoBar
install apps; then it downloads the release's APK from GitHub and Android's installer asks you to confirm.

Requests can go to those four services and GitHub and nowhere else (the code has no way to name another
address), **Setup › Online services** switches each off, and a layout pasted from another install
never turns them on. BentoBar sends nothing else anywhere, except to your own other device when you link
them (below): not your layout, your calendar, your clipboard, what's playing or anything it measures. It has no
accounts of its own, no ads and no analytics, and it sends its developer nothing.

**Linking another device is off until you do it.** When you link your phone and tablet
([how](#another-devices-batteries-cross-device-sdk)), each sends the other its own battery and the batteries and names
of the devices it sees (a watch, earbuds, a mouse), every 30 seconds while linked. Google Play services carries them,
encrypted, between your two devices, which must be signed in to the same Google account; BentoBar has no server and
keeps none of it. **Unlink** in Device batteries' menu ends it.

**Clipboard** reads the text you copy while the item is in use, and keeps your last few copies in
memory only: they are gone when BentoBar stops, and are never stored or sent. The snippets you pin are
saved with the item, in your layout. With *Paste into active field on click* on, the accessibility
service pastes the snippet you click into the text field you are typing in, without reading that field
or its app.

**Now playing** reads the title, artist and artwork your media players publish, which Android shares
only with apps that have notification access. That access is optional, and BentoBar tells Android to
send it no notifications: it receives none. Without it the item still shows that something plays and
controls it. Nothing about what you play is stored or sent.

**Sharing device batteries with other apps is off by default.** If you turn on *Share with other
apps* in Device batteries' settings, an app can read the kind, name and battery level of the devices
BentoBar sees, and the earbuds' cases as last seen, but only after you grant it Android's permission
*read device batteries from BentoBar*, app by app. BentoBar sends nothing anywhere for this: the app
asks BentoBar on this device. Turning the switch off stops every app at once; Android's app settings
take the permission back from one.

If you use Android's backup, Android keeps a copy of BentoBar's settings in your Google account. Your
AirLabs and Finnhub keys, the online switches and the sharing switches are left out of it.

**Usage access is optional and off by default.** If you turn it on (Setup, or the Network and
Storage menus), BentoBar reads how much data and storage each app uses, to list the top five in
those menus, and nothing else. Android's switch also covers which apps you use and when; BentoBar
doesn't read that. Turn it off any time in Android's Usage access settings (Setup › Usage access ›
Open setting takes you there).
Android doesn't share other apps' CPU or memory use with apps at all, so without root the CPU and Memory menus
show the whole system instead.

**Root is optional.** On a rooted device, and only if you allow BentoBar when your root manager asks, BentoBar reads
what's listed under [Root](#root): temperatures, the apps using the CPU and memory, the battery's real cycles and
health, and a Galaxy watch's or earbuds' levels. It reads only while the menu that shows it is open, changes nothing,
and keeps and sends none of it.

**Sharing the bar's items with other apps is off by default.** If you turn on **Look › Other apps**, an app can read
what the bar shows (each item's icon and words, which can be a track's title or a meeting's name), open an item's
menu and hide items from the bar while it shows them, but only after you grant it Android's permission *read the
bar's items from BentoBar*, app by app. Nothing is sent anywhere: the app asks BentoBar on this device. Turning the
switch off stops every app at once.

| Permission | Used for |
|---|---|
| Accessibility service | drawing items on the status bar and running Tools actions |
| Notifications *(optional)* | timer alerts, and the Live Update chip |
| Calendar *(optional)* | Next meeting, and events in the month view |
| Approximate location *(optional)* | Weather's My location, rounded to about 10 km before it is sent |
| Alarms and reminders *(optional)* | timers that ring on the second while the device sleeps |
| Usage access *(optional)* | the apps using the most data today and the largest apps, in the Network and Storage menus |
| Internet | Weather and Air quality (Open-Meteo), Exchange rates (Frankfurter), Flight (AirLabs) and US stocks (Finnhub), only after you set them up; this fork's update check (GitHub); nothing else |
| Install apps *(asked when you first update from inside BentoBar)* | installing this fork's own update, which Android asks you to confirm |
| Notification access *(optional)* | Now playing's title, artist and artwork; BentoBar receives no notifications |
| Root *(optional, asked by your root manager)* | the readings under [Root](#root); nothing is changed |
| Network state, launcher apps | the network menu, and picking apps for shortcuts |

BentoBar also defines two permissions for other apps, *read device batteries from BentoBar* and *read the bar's
items from BentoBar*: see [Device batteries in your app](#device-batteries-in-your-app) and
[The bar's items in your app](#the-bars-items-in-your-app).

## Root

BentoBar works fully without root. On a **rooted** device it can also show what Android keeps from ordinary apps:

| Where | What root adds | Why it helps |
|---|---|---|
| **CPU menu** | the **CPU's and GPU's temperatures**, and **which apps use the CPU** most (yours, or yours and the system's) | see what's heating the device or draining the battery |
| **Memory menu** | **which apps hold the most memory** | find what fills the RAM before the system starts closing apps |
| **Battery menu** | the battery's **real charge-cycle count** and its **health in percent** | Android reports 0 cycles to apps; this is the battery's actual wear |
| **Device batteries** | a **Galaxy watch's, ring's and earbuds'** levels from Samsung's own device list | the same levels One UI's battery widget shows, including each earbud and the case |

How it asks: through `su`, only while the menu that shows it is open (the apps using the CPU every 3 seconds), and
only reading, never changing anything. Where root is refused or missing, those rows just don't show, and BentoBar
doesn't ask again until it restarts. Nothing root reads is kept or logged. The apps lists are set by the CPU item's
**Apps using the CPU** and the Memory item's **Apps using memory** (Off, Apps, Apps and system).

## Good to know

- **Widgets.** *Device batteries* comes as a home screen widget too (4 x 2, one to four devices, or a custom grid): an
  arc for this device and each of your others, the linked phone or tablet included. Long-press it, then Settings, to pin
  devices to cells and set their order. On One UI there are also two lock screen widgets (1 or 2 devices). They refresh
  when a reading changes and every 15 minutes; tap one to refresh now.
- **System icons stay.** Android doesn't let an installed app hide or reorder the system's own
  icons (clock, Wi-Fi, battery, notifications), so BentoBar organises its own items next to them.
- **Keyboard shortcut.** The launcher entry **BentoBar menu** opens BentoBar's menu with every item
  in it: arrows and Enter open an item's menu, Escape closes it. Give it a shortcut in the system's
  keyboard shortcuts (on a Googlebook, Action + / › Customize), e.g. Action + T. BentoBar itself doesn't read keys.
- **One chip.** Android shows one Live Update chip per app, with about seven characters of text,
  and hides it while that app's own window is open.
- **CPU load** comes from each core's idle time (Android doesn't let apps read overall CPU usage),
  and matches the system's `top` within a point or two.
- **Advanced Protection** (Android 17) turns off accessibility services that aren't assistive
  tools, and with it BentoBar's bar. The chip and tiles keep working.
- **Light on battery:** about 0.5% of one CPU core with the CPU and network items updating every
  second, and nothing at all while the screen is off.

## Device batteries in your app

Your app can read what Device batteries sees, once the user turns on **Share with other apps** in that
item's settings and grants your app the permission. Declare and request it like any runtime permission:

```xml
<uses-permission android:name="com.meridian11.books.bentobar.permission.READ_DEVICE_BATTERIES" />
<queries><package android:name="com.meridian11.books.bentobar" /></queries>
```

Then query `content://com.meridian11.books.bentobar.devices/devices` (read only):

| Column | Value |
|---|---|
| `kind` | `mouse`, `keyboard`, `stylus`, `gamepad`, `headphones`, `watch`, `phone` or `tablet` |
| `name` | the device's own name; can be empty |
| `percent` | 0–100, or null when the device has a battery that doesn't say how full |
| `charging` | 1 or 0 |
| `ears` | earbuds only: `both`, `left`, `right` or `one`; null for anything else |

and `…/cases` for earbuds' charging cases as last seen (a case reports its level only while a bud is in it):
`name` (the earbuds'), `percent` and `seen_at` (wall-clock milliseconds).

The cursor's extras hold `enabled` (false while sharing is off, when every table is empty) and `version`
(raised only for a change your code must check for; new columns can appear without one). Register a
`ContentObserver` on `content://com.meridian11.books.bentobar.devices` with descendants to hear when the
rows change. A query is answered from a reading at most 10 seconds old, taken anew when needed.

## The bar's items in your app

This is how [Fonta's Dynamic Notch for Android](https://github.com/Nessuno6/FontaDynamicNotch/tree/android) shows
BentoBar's items. Once the user turns on **Look › Other apps** and grants your app the permission:

```xml
<uses-permission android:name="com.meridian11.books.bentobar.permission.READ_BAR_ITEMS" />
<queries><package android:name="com.meridian11.books.bentobar" /></queries>
```

query `content://com.meridian11.books.bentobar.items/items` (read only), one row an item: `id`, `type`, `title`,
`text` (what the bar shows, or null), `label`, `description`, `active`, `tone`, `icon_png` (the item's icon as a PNG,
One UI's when Look › Icons is One UI) and `in_bar`. The cursor's extras hold `enabled` and `version`; register a
`ContentObserver` on `content://com.meridian11.books.bentobar.items` to hear when the rows change.

`ContentResolver.call` on the same address does the rest:

| Method | What it does |
|---|---|
| `open` (arg: the item's `id`; extras `left`, `top`, `right`, `bottom`: where you show it, in screen pixels) | what a click on the item does in the bar: its menu opens under that place, or the item acts. With extra `hover` true: only the menu, and an open one stays open |
| `close` | closes the menu `open` last opened for you, if it's still showing |
| `claim` (extras `hide`: a list of ids, `watch`, `whole`) | while you show items yourself: `hide` leaves them out of BentoBar's bar, `whole` hides the bar, `watch` keeps every item sampled. Held for two minutes: ask again every minute; ask with nothing to give it up |

## Build

Needs JDK 21 and the Android SDK (platform 37).

```sh
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # signed if ~/.config/bentobar/keystore.jks and keystore.pass exist
```

`./bento` is the original project's development helper (build, install, enable, test hooks, screenshots), made for a
Googlebook connected over Wireless debugging; on another device, `adb install` the APK. [AGENTS.md](AGENTS.md) explains how the code is
organised, and [docs/research](docs/research) has the platform findings behind the design.

The official BentoBar's releases are built and signed by GitHub Actions when a `v<version>` tag is pushed (see
[docs/RELEASING.md](docs/RELEASING.md)). This fork's releases are built on its maintainer's machine and signed with the
fork's own key, which is why they don't install over the official app.
