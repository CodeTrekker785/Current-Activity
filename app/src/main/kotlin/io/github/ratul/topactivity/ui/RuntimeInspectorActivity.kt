package io.github.ratul.topactivity.ui

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * No-root runtime diagnostics. Only reports data Android exposes to this app.
 * Arbitrary memory reads and another app's private heap are not available to
 * ordinary apps; this screen never injects into or modifies the target process.
 */
class RuntimeInspectorActivity : AppCompatActivity() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var foregroundText: TextView
    private lateinit var detailText: TextView
    private lateinit var packageInput: EditText
    private val poll = object : Runnable {
        override fun run() {
            refreshForeground()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "Live & Runtime Inspector"
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = "LEVEL 1 · LIVE ACTIVITY INSPECTOR\nTracks the most recently resumed app/activity using Android Usage Access. Refreshes every second while this screen is open."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(8))
        })
        foregroundText = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(foregroundText, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Grant / check Usage Access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "LEVEL 2 · NO-ROOT PROCESS INSPECTOR\nEnter a package name or use the foreground app. Shows package metadata, exported activities, debug eligibility and permitted memory counters. Raw private heap contents, object values, locals and registers are blocked for ordinary apps by Android."
            textSize = 14f
            setPadding(0, dp(18), 0, dp(8))
        })
        packageInput = EditText(this).apply {
            hint = "Package name, e.g. com.example.app"
            setSingleLine(true)
            setText(intent.getStringExtra(EXTRA_PACKAGE).orEmpty())
        }
        root.addView(packageInput, LinearLayout.LayoutParams(-1, -2))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Use foreground app"
            setOnClickListener {
                val target = latestPackage
                if (!target.isNullOrBlank()) packageInput.setText(target)
                inspectTarget()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply {
            text = "Inspect package"
            setOnClickListener { inspectTarget() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        root.addView(Button(this).apply {
            text = "Inspect permitted memory stats"
            setOnClickListener { inspectMemory() }
        }, LinearLayout.LayoutParams(-1, -2))
        detailText = TextView(this).apply {
            text = "Enter a package name and tap Inspect package."
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, dp(10))
        }
        val scroll = ScrollView(this)
        scroll.addView(detailText, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private var latestPackage: String? = null

    @Suppress("DEPRECATION")
    private fun refreshForeground() {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 120_000L, now)
        val event = UsageEvents.Event()
        var pkg: String? = null
        var cls: String? = null
        var lastTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val resumed = if (Build.VERSION.SDK_INT >= 29) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            if (resumed && event.timeStamp >= lastTime) {
                lastTime = event.timeStamp
                pkg = event.packageName
                cls = event.className
            }
        }
        if (pkg.isNullOrBlank()) {
            latestPackage = null
            foregroundText.text = "Foreground activity unavailable. Grant Usage Access in Android Settings, then return here."
            return
        }
        latestPackage = pkg
        foregroundText.text = "Package: $pkg\nActivity/class: ${cls ?: "(not exposed)"}\nLast resume: ${DateFormat.getDateTimeInstance().format(Date(lastTime))}\nStatus: live polling active"
    }

    /**
     * Accepts either a package ID or a fully-qualified activity/class name.
     * Launcher visibility is declared in the manifest, so class names can be
     * mapped back to their installed package on Android 11+.
     */
    @Suppress("DEPRECATION")
    private fun resolveTargetPackage(input: String): String? {
        val value = input.trim()
        if (value.isBlank()) return null
        try {
            packageManager.getPackageInfo(value, 0)
            return value
        } catch (_: Exception) {
            // It may be an activity class rather than a package ID.
        }
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchers = try {
            packageManager.queryIntentActivities(launcherIntent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        return launchers
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .sortedByDescending { it.length }
            .firstOrNull { pkg -> value == pkg || value.startsWith("$pkg.") }
    }

    @Suppress("DEPRECATION")
    private fun inspectTarget() {
        val entered = packageInput.text.toString().trim()
        if (entered.isBlank()) {
            detailText.text = "Enter a package name or an activity class name first."
            return
        }
        val target = resolveTargetPackage(entered)
        if (target == null) {
            detailText.text = "Could not match '$entered' to an installed app. You can enter either the app package (for example, com.mixvibes.crossdj) or its activity class (for example, com.mixvibes.crossdj.CrossDJActivity). Open the app first and use 'Use foreground app' if available. Android only exposes packages visible to this app; work-profile apps or hidden/non-launchable packages may not be discoverable."
            return
        }
        if (target != entered) packageInput.setText(target)
        try {
            val info: PackageInfo = packageManager.getPackageInfo(target, PackageManagerFlags.activities())
            val appInfo = info.applicationInfo
            val debuggable = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val activities = info.activities.orEmpty()
            detailText.text = buildString {
                appendLine("TARGET APP")
                appendLine("Package: $target")
                appendLine("Version: ${info.versionName ?: "(unknown)"}")
                appendLine("Version code: ${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()}")
                appendLine("UID: ${appInfo?.uid ?: "not exposed"}")
                appendLine("Debuggable: $debuggable")
                appendLine("System app: ${appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0}")
                appendLine("APK path: ${appInfo?.sourceDir ?: "(not exposed)"}")
                appendLine("Target SDK: ${appInfo?.targetSdkVersion ?: "unknown"}")
                appendLine("Native library directory: ${appInfo?.nativeLibraryDir ?: "not exposed"}")
                appendLine("Declared activities visible to this app: ${activities.size}")
                activities.take(100).forEach { a ->
                    appendLine("• ${a.name} | exported=${a.exported} | process=${a.processName ?: target}")
                }
                if (activities.size > 100) appendLine("Activity list capped at 100 entries for memory safety.")
                appendLine()
                appendLine("RUNTIME ACCESS")
                if (debuggable) {
                    appendLine("Target is marked debuggable. This inspector still cannot attach to it by itself. For stack frames, local variables, heap dumps and object inspection, use an authorized debugger/profiler on a development device.")
                } else {
                    appendLine("Target is not marked debuggable (or this flag is unavailable). A normal third-party app cannot read its private heap, locals/registers, or arbitrary memory.")
                }
                appendLine("Package visibility or Android version may hide some metadata.")
                appendLine("No target process was modified, injected into, or terminated.")
            }
        } catch (e: Exception) {
            detailText.text = "The app '$target' was identified, but Android would not return its package metadata (${e.javaClass.simpleName}). This can happen with package-visibility or work-profile restrictions. Try launching the app, then use 'Use foreground app'. The activity name and package ID are not interchangeable."
        }
    }

    /**
     * Shows system totals and this inspector's Java/ART/native heap details.
     * Other-process counters are attempted only through public Android APIs;
     * modern Android may deny or hide them. No heap contents are read.
     */
    @Suppress("DEPRECATION")
    private fun inspectMemory() {
        val entered = packageInput.text.toString().trim()
        val target = resolveTargetPackage(entered)
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val system = ActivityManager.MemoryInfo()
        am.getMemoryInfo(system)
        val own = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(own)
        val runtime = Runtime.getRuntime()
        val runtimeStats = try {
            if (Build.VERSION.SDK_INT >= 23) android.os.Debug.getRuntimeStats() else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        detailText.text = buildString {
            appendLine("NO-ROOT MEMORY REPORT")
            appendLine("Captured: ${DateFormat.getDateTimeInstance().format(Date())}")
            appendLine()
            appendLine("SYSTEM MEMORY (device-wide totals)")
            appendLine("Total RAM: ${system.totalMem / 1024L / 1024L} MiB")
            appendLine("Available RAM: ${system.availMem / 1024L / 1024L} MiB")
            appendLine("Low-memory state: ${system.lowMemory}")
            appendLine("Low-memory threshold: ${system.threshold / 1024L / 1024L} MiB")
            appendLine()
            appendLine("INSPECTOR PROCESS ONLY (${packageName}, PID ${android.os.Process.myPid()})")
            appendLine("PSS: ${own.totalPss} kB")
            appendLine("Private dirty: ${own.totalPrivateDirty} kB")
            appendLine("Shared dirty: ${own.totalSharedDirty} kB")
            appendLine("Java heap used: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024L} KiB")
            appendLine("Java heap committed: ${runtime.totalMemory() / 1024L} KiB")
            appendLine("Java heap maximum: ${runtime.maxMemory() / 1024L} KiB")
            appendLine("Native heap allocated: ${android.os.Debug.getNativeHeapAllocatedSize() / 1024L} KiB")
            appendLine("Native heap size: ${android.os.Debug.getNativeHeapSize() / 1024L} KiB")
            appendLine("Native heap free: ${android.os.Debug.getNativeHeapFreeSize() / 1024L} KiB")
            if (runtimeStats.isNotEmpty()) {
                appendLine()
                appendLine("ART RUNTIME COUNTERS (inspector process only)")
                runtimeStats.toSortedMap().forEach { (key, value) -> appendLine("$key: $value") }
            }
            appendLine()
            appendLine("TARGET APP PROCESS")
            if (entered.isBlank()) {
                appendLine("Enter a package name or activity class above, then tap this button again.")
            } else if (target == null) {
                appendLine("Could not match '$entered' to a visible installed app. Enter its package ID (for example, com.mixvibes.crossdj) or launch it and use 'Use foreground app'.")
            } else {
                if (target != entered) packageInput.setText(target)
                try {
                    val matching = am.runningAppProcesses.orEmpty().filter { process ->
                        process.processName == target ||
                            process.processName.startsWith("$target:") ||
                            process.pkgList?.contains(target) == true
                    }
                    if (matching.isEmpty()) {
                        appendLine("Android did not expose a running process for '$target'. Open the target app and retry. Recent Android versions may hide other apps' process details.")
                    } else {
                        matching.forEach { process ->
                            appendLine("Process: ${process.processName} (PID ${process.pid})")
                            try {
                                val stats = am.getProcessMemoryInfo(intArrayOf(process.pid)).firstOrNull()
                                if (stats == null) {
                                    appendLine("Aggregate counters unavailable for this process.")
                                } else {
                                    appendLine("PSS: ${stats.totalPss} kB")
                                    appendLine("Private dirty: ${stats.totalPrivateDirty} kB")
                                    appendLine("Shared dirty: ${stats.totalSharedDirty} kB")
                                }
                            } catch (_: SecurityException) {
                                appendLine("Android denied access to this process's memory metrics.")
                            } catch (e: Exception) {
                                appendLine("Memory metrics unavailable: ${e.javaClass.simpleName}")
                            }
                        }
                    }
                } catch (_: SecurityException) {
                    appendLine("Android denied visibility into other app processes.")
                }
            }
            appendLine()
            appendLine("WHAT THIS REPORT CANNOT SHOW")
            appendLine("This is not a heap dump. It cannot reveal target object graphs, strings held only in memory, passwords, tokens, method locals/registers, or raw process bytes. Android's app sandbox prevents this without a target-provided debugging interface or privileged access. Metrics above are aggregate counters and may be restricted by OS version.")
        }
    }

    private object PackageManagerFlags {
        @Suppress("DEPRECATION")
        fun activities(): Int = android.content.pm.PackageManager.GET_ACTIVITIES
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PACKAGE = "runtime_inspector_package"
    }
}
