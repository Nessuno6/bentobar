"""
Every BentoBar symbol that tools/oneui_icons.py leaves without a One UI icon, from the One UI icon pack
(@thatjoshguy/oneui-icons on npm, MIT: ONEUI-ICONS-LICENSE): writes oneui_<symbol>.xml for each from the pack's
dist/index.js, and the Kotlin entries for util/OneUiIcons.kt.

    python tools/oneui_icons_pack.py node_modules/@thatjoshguy/oneui-icons/dist/index.js app/src/main/res/drawable entries.kt preview.html
"""
import os, re, sys

SRC, RES, OUT_KT, HTML = sys.argv[1:5]
s = open(SRC, encoding='utf-8').read()

MAP = {
    'ac_unit': 'WeatherIce', 'add': 'Add', 'add_circle': 'AddFilled', 'air': 'WeatherWindy', 'airlines': 'Traveling',
    'airplane_ticket': 'Ticket', 'airplanemode_active': 'AirplaneMode', 'airplanemode_inactive': 'AirplaneMode',
    'album': 'Album', 'apps': 'Apps', 'arrow_back': 'Back', 'arrow_downward': 'ArrowDown', 'arrow_forward': 'ArrowRight',
    'arrow_upward': 'ArrowUp', 'autorenew': 'Sync', 'avg_pace': 'Speed', 'backspace': 'KeyboardBtnDelete',
    'battery_0_bar': 'Battery', 'battery_1_bar': 'Battery', 'battery_3_bar': 'Batterylevel', 'battery_5_bar': 'BatteryFilled',
    'battery_alert': 'StatSysBatteryWarning', 'battery_charging_full': 'Charging', 'battery_full': 'BatteryFilled',
    'battery_low': 'Battery', 'battery_unknown': 'Battery', 'battery_very_low': 'StatSysBatteryWarning',
    'bedtime': 'Beforebed', 'block': 'Block', 'bluetooth_connected': 'BluetoothDeviceConnect', 'bookmark': 'Bookmark',
    'bug_report': 'Report', 'build': 'Tool', 'calendar_add_on': 'CalendarTask', 'calendar_clock': 'CalendarPeriodTime',
    'calendar_month': 'CalendarMonth', 'calendar_today': 'CalendarDay', 'cancel': 'Clear', 'check': 'Selected',
    'check_circle': 'CheckboxChecked', 'chevron_left': 'KeyboardArrowLeft', 'chevron_right': 'KeyboardArrowRight',
    'clear_day': 'WeatherSunny', 'clear_night': 'WeatherNight', 'close': 'Close', 'cloud': 'Cloud', 'cloud_done': 'Cloud',
    'cloud_off': 'Offline', 'cloud_sync': 'AutoSync', 'cloudy_snowing': 'WeatherSnow', 'coffee': 'ScreenTimeout',
    'connecting_airports': 'Traveling', 'content_copy': 'Copy', 'content_paste': 'Clipboard', 'cyclone': 'WeatherHurricane',
    'dark_mode': 'Dark', 'data_usage': 'MobileDataQs', 'delete': 'Delete', 'departure_board': 'Travel',
    'device_thermostat': 'Temperature', 'directions': 'Driving', 'do_not_disturb_on': 'DoNotDisturb', 'done': 'Selected',
    'door_front': 'Leave', 'drag_indicator': 'Reorder', 'earbuds': 'Buds', 'east': 'East', 'edit_calendar': 'Calendar',
    'emoji_objects': 'Tips', 'error': 'Error', 'event': 'Event', 'event_upcoming': 'Reminder',
    'expand_less': 'KeyboardArrowUp', 'expand_more': 'KeyboardArrowDown', 'explore': 'Explore',
    'fast_forward': 'ControlNext2', 'fast_rewind': 'ControlPrevious2', 'favorite': 'FavoriteOn', 'flight': 'AirplaneMode',
    'flight_land': 'AirplaneMode', 'flight_takeoff': 'AirplaneMode', 'foggy': 'WeatherFog', 'gamepad': 'Game',
    'graphic_eq': 'Equalizer', 'grid_view': 'ListGrid', 'groups': 'Group', 'hard_drive': 'AnalyzeStorage',
    'headset_mic': 'ListIcHeadset', 'heat': 'WeatherHot', 'help': 'Help', 'history': 'History', 'hourglass_top': 'Timer',
    'humidity_percentage': 'WaterDrop', 'info': 'Info', 'label': 'Tag', 'language': 'InternetWebsite',
    'light_mode': 'Brightness', 'link': 'Link', 'local_airport': 'AirplaneMode', 'local_cafe': 'ScreenTimeout',
    'local_fire_department': 'WeatherHot', 'location_on': 'Location', 'lock_open': 'Unlock', 'luggage': 'Travel',
    'meeting_room': 'Meeting', 'memory': 'DeviceHealth', 'memory_alt': 'ManageStorage', 'menu': 'List', 'mist': 'WeatherFog',
    'mode_cool': 'WeatherIce', 'mode_fan': 'WeatherWindy', 'mode_heat': 'WeatherHot', 'more_time': 'Time',
    'music_off': 'SoundMute', 'my_location': 'CurrentLocation', 'navigation': 'North', 'near_me': 'CurrentLocationOutline',
    'network_check': 'Speed', 'nights_stay': 'WeatherNight', 'north': 'North', 'notifications_active': 'Notification',
    'notifications_off': 'IcNotificationsMute', 'open_in_new': 'Open', 'palette': 'Palette',
    'partly_cloudy_day': 'WeatherPartlySunny', 'partly_cloudy_night': 'WeatherMostlyClear', 'password': 'Lock2',
    'pause': 'ControlPause', 'pause_circle': 'ControlPauseCircleFilled', 'pending': 'More', 'play_arrow': 'ControlPlay',
    'play_circle': 'ControlPlayCircleFilled', 'priority_high': 'Notice', 'privacy_tip': 'Privacy',
    'public': 'InternetWebsite', 'push_pin': 'Pin', 'queue_music': 'ListAudio', 'rainy': 'WeatherRain',
    'rainy_heavy': 'WeatherHeavyRain', 'rainy_light': 'WeatherShower', 'rainy_snow': 'WeatherRainAndSnowMixed',
    'refresh': 'Refresh', 'remove': 'Minus', 'remove_circle': 'Remove', 'replay': 'Undo', 'restart_alt': 'Restart',
    'rocket_launch': 'Labs', 'schedule': 'Time', 'search': 'Search', 'send': 'Send', 'severe_cold': 'WeatherCold',
    'shield': 'Security', 'show_chart': 'DailyActivity', 'skip_next': 'ControlNext', 'skip_previous': 'ControlPrevious',
    'snowing': 'WeatherSnow', 'south': 'South', 'space_bar': 'KeyboardBtnSpace', 'speaker': 'IcBtLeAudioSpeakers',
    'speed': 'Speed', 'stadia_controller': 'Game', 'star': 'Star', 'stop': 'ControlStop', 'storm': 'WeatherThunderStorm',
    'stylus_note': 'Pen', 'sunny': 'WeatherSunny', 'sunny_snowing': 'WeatherPartlySunnyWithFlurries', 'sync': 'Sync',
    'sync_problem': 'AutoSync', 'text_fields': 'Text', 'thermometer': 'Temperature', 'thermostat': 'Temperature',
    'thunderstorm': 'WeatherThunderStorm', 'timer': 'Timer', 'title': 'Text', 'today': 'CalendarDay',
    'toggle_off': 'Disable', 'toggle_on': 'Enable', 'tornado': 'Whirlwind', 'touch_app': 'AirAction',
    'trackpad_input': 'BeansTouchpad', 'travel': 'Travel', 'travel_explore': 'InternetWebSearch',
    'trending_down': 'ArrowDown2', 'trending_flat': 'ArrowRight2', 'trending_up': 'ArrowUp2', 'tune': 'FilterBy',
    'umbrella': 'WeatherShower', 'update': 'DownloadCircleOutline', 'visibility': 'PasswordShow',
    'visibility_off': 'PasswordHide', 'volume_down': 'VolumeDown', 'volume_mute': 'SoundOutline', 'volume_off': 'SoundMute',
    'vpn_key': 'VpnConnect', 'warning': 'Warning', 'water_drop': 'WaterDrop', 'wb_twilight': 'WeatherWakeUp',
    'weather_hail': 'WeatherHail', 'weather_mix': 'WeatherRainAndSnowMixed', 'weather_snowy': 'WeatherSnow',
    'west': 'West', 'widgets': 'Widget', 'wifi_off': 'WifiNoConnection', 'wysiwyg': 'Document',
}

def attrs(obj):
    d = {}
    for k, v in re.findall(r'(\w+):("[^"]*"|[A-Za-z_$][\w$]*|[\d.]+)', obj):
        d[k] = v[1:-1] if v.startswith('"') else ('@color' if not re.match(r'^[\d.]+$', v) else v)
    return d

def convert(name):
    m = re.search(r'\.displayName="%s"' % name, s)
    if not m: return None
    start = s.rfind('=>i.createElement("svg"', 0, m.start())
    if start < 0: start = s.rfind('createElement("svg"', 0, m.start())
    seg = s[start:m.start()]
    while True:  # <mask> shapes say where to draw; they are not drawn themselves
        k = seg.find('createElement("mask"')
        if k < 0: break
        depth = 0; e = k + len('createElement')
        while e < len(seg):
            if seg[e] == '(': depth += 1
            elif seg[e] == ')':
                depth -= 1
                if depth == 0: break
            e += 1
        seg = seg[:k] + 'null' + seg[e + 1:]
    vb = re.search(r'viewBox:"([^"]+)"', seg).group(1).split()
    xml = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp" android:height="24dp"',
           '    android:viewportWidth="%s" android:viewportHeight="%s">' % (vb[2], vb[3])]
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="%s" fill="none">' % ' '.join(vb)]
    for obj in re.findall(r'createElement\("path",\{([^}]*)\}', seg):
        a = attrs(obj)
        if 'd' not in a: continue
        col = lambda v: '#FFFFFFFF' if v in ('@color', 'white', 'currentColor') else v
        p = ['    <path android:pathData="%s"' % a['d']]; sv = ['<path d="%s"' % a['d']]
        fill = a.get('fill'); op = a.get('opacity') or a.get('fillOpacity')
        if fill and fill != 'none':
            p.append('android:fillColor="#FFFFFFFF"'); sv.append('fill="#fff"')
            if op: p.append('android:fillAlpha="%s"' % op); sv.append('fill-opacity="%s"' % op)
        elif not fill and 'stroke' not in a:
            p.append('android:fillColor="#FFFFFFFF"'); sv.append('fill="#fff"')
        if a.get('fillRule') == 'evenodd': p.append('android:fillType="evenOdd"'); sv.append('fill-rule="evenodd"')
        if a.get('stroke') and a['stroke'] != 'none':
            p.append('android:strokeColor="#FFFFFFFF"'); sv.append('stroke="#fff"')
            p.append('android:strokeWidth="%s"' % a.get('strokeWidth', '1')); sv.append('stroke-width="%s"' % a.get('strokeWidth', '1'))
            if a.get('strokeLinecap'): p.append('android:strokeLineCap="%s"' % a['strokeLinecap'])
            if a.get('strokeLinejoin'): p.append('android:strokeLineJoin="%s"' % a['strokeLinejoin'])
        xml.append(' '.join(p) + ' />'); svg.append(' '.join(sv) + '/>')
    xml.append('</vector>'); svg.append('</svg>')
    return '\n'.join(xml) + '\n', ''.join(svg)

missing, written, html = [], [], ['<body style="background:#333;color:#ddd;font:11px sans-serif;display:flex;flex-wrap:wrap;gap:10px">']
for glyph, name in sorted(MAP.items()):
    path = os.path.join(RES, 'oneui_%s.xml' % glyph)
    if os.path.exists(path): continue  # chosen before, from One UI's own system icons: kept
    r = convert(name)
    if r is None: missing.append((glyph, name)); continue
    open(path, 'w', encoding='utf-8').write(r[0]); written.append(glyph)
    html.append('<div style="width:90px;text-align:center">%s<br>%s</div>' % (r[1], glyph))
open(HTML, 'w', encoding='utf-8').write(''.join(html))
open(OUT_KT, 'w', encoding='utf-8').write(',\n'.join('Sym.%s to "%s"' % (g.upper(), g) for g in written))
print('written', len(written), 'missing', missing)
