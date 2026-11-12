package com.meridian11.books.bentobar.items

/**
 * BentoBar's own code run as root, by `app_process` (see [RootBt.companions]): what only the system may ask Android, and
 * root counts as the system. Prints its answer and exits; it reads, and changes nothing.
 *
 *     CLASSPATH=<BentoBar's APK> app_process /system/bin com.meridian11.books.bentobar.items.RootTool batteries
 *
 * `batteries`: Samsung's companion-device battery list (watches, earbuds, rings: what its battery widget shows), one
 * record a line: `device`, address, type, level, status (Android's: 2 charging, 5 full), left, right and case levels,
 * then the left earbud's, the right one's and the case's own statuses, separated by tabs ([DevicesRules.companions]
 * reads them).
 */
object RootTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.firstOrNull()) {
                "batteries" -> batteries()
                else -> println("unknown")
            }
        } catch (e: Throwable) {
            println("error\t${(e.cause ?: e).javaClass.simpleName}")
        }
        System.out.flush()
        // The binder threads would keep the process up.
        System.exit(0)
    }

    private fun batteries() {
        if (android.os.Looper.getMainLooper() == null) @Suppress("DEPRECATION") android.os.Looper.prepareMainLooper()
        val thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
        val context = thread.javaClass.getMethod("getSystemContext").invoke(thread) as android.content.Context
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val infos = bm.javaClass.getMethod("semGetDeviceBatteryInfos").invoke(bm) as? Array<*> ?: return
        for (r in infos) {
            if (r == null) continue
            fun get(name: String): Any? = runCatching { r.javaClass.getMethod(name).invoke(r) }.getOrNull()
            println(listOf("device", get("getAddress"), get("getDeviceType"), get("getBatteryLevel"), get("getBatteryStatus"),
                get("getExtraBatteryLevelLeft"), get("getExtraBatteryLevelRight"), get("getExtraBatteryLevelCradle"),
                get("getExtraBatteryStatusLeft"), get("getExtraBatteryStatusRight"), get("getExtraBatteryStatusCradle")).joinToString("\t"))
        }
    }
}
