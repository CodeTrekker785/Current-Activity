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

/**
 * Foreground activity monitor plus privacy-respecting runtime diagnostics.
 *
 * Android does not permit ordinary apps to read another app's private heap,
 * locals/registers, arbitrary method calls, or private logcat. This screen
 * reports platform-exposed metadata and explains the debug-only path.
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
            text = "LEVEL 2 · DEEP RUNTIME INSPECTOR\nEnter the target app package (or tap Use foreground app). Shows package metadata, debug eligibility and exported activity declarations when Android allows access. This is not arbitrary process-memory instrumentation."
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

    @Suppress("DEPRECATION")
    private fun inspectTarget() {
        val target = packageInput.text.toString().trim()
        if (target.isBlank()) {
            detailText.text = "Enter a package name first."
            return
        }
        try {
            val info: PackageInfo = packageManager.getPackageInfo(target, PackageManagerFlags.activities())
            val appInfo = info.applicationInfo
            val debuggable = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val activities = info.activities.orEmpty()
            val processes = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
                .runningAppProcesses.orEmpty()
                .filter { it.processName == packageName }
                .joinToString("\n") { "• ${it.processName} (this inspector process only)" }
                .ifBlank { "Other-app process details are restricted by Android." }
            detailText.text = buildString {
                appendLine("Target: $target")
                appendLine("Version: ${info.versionName ?: "(unknown)"}")
                appendLine("Version code: ${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()}")
                appendLine("UID: ${appInfo?.uid ?: "not exposed"}")
                appendLine("Debuggable: $debuggable")
                appendLine("System app: ${appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0}")
                appendLine("APK path: ${appInfo?.sourceDir ?: "(not exposed)"}")
                appendLine("Declared activities visible to this app: ${activities.size}")
                activities.take(100).forEach { a ->
                    appendLine("• ${a.name} | exported=${a.exported} | process=${a.processName ?: target}")
                }
                if (activities.size > 100) appendLine("Activity list capped at 100 entries for memory safety.")
                appendLine()
                appendLine("Process visibility:")
                appendLine(processes)
                appendLine()
                appendLine("Deep runtime boundary:")
                if (debuggable) {
                    appendLine("Target is marked debuggable. For method breakpoints, stack frames and local variables, attach an authorized debugger through Android Studio/ADB on a development device.")
                } else {
                    appendLine("Target is not marked debuggable (or this flag is unavailable). Android blocks third-party apps from reading its private heap, local variables/registers and method execution.")
                }
                appendLine("No target process was modified, injected into, or terminated.")
            }
        } catch (e: Exception) {
            detailText.text = "Could not inspect '$target': ${e.javaClass.simpleName}: ${e.message ?: "package details unavailable"}.\n\nTry the exact package name from the Live Activity panel. Android package visibility and profile/OS restrictions can prevent metadata access."
        }
    }

    /**
     * Reports only memory metrics Android exposes to this process. This does not
     * read heap contents, secrets, object values, or another app's private files.
     */
    @Suppress("DEPRECATION")
    private fun inspectMemory() {
        val target = packageInput.text.toString().trim()
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val system = ActivityManager.MemoryInfo()
        am.getMemoryInfo(system)
        val own = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(own)

        detailText.text = buildString {
            appendLine("MEMORY INSPECTOR")
            appendLine("System memory")
            appendLine("Total RAM: \${system.totalMem / 1024L / 1024L} MiB")
            appendLine("Available RAM: \${system.availMem / 1024L / 1024L} MiB")
            appendLine("Low-memory state: \${system.lowMemory}")
            appendLine()
            appendLine("Current inspector process (\${packageName}, PID \${android.os.Process.myPid()})")
            appendLine("PSS: \${own.totalPss} kB")
            appendLine("Private dirty: \${own.totalPrivateDirty} kB")
            appendLine("Shared dirty: \${own.totalSharedDirty} kB")
            appendLine()
            appendLine("Target app process memory")
            if (target.isBlank()) {
                appendLine("Enter a target package name above, then tap this button again.")
            } else {
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
                            appendLine("Process: \${process.processName} (PID \${process.pid})")
                            try {
                                val stats = am.getProcessMemoryInfo(intArrayOf(process.pid)).firstOrNull()
                                if (stats == null) {
                                    appendLine("Memory metrics unavailable for this process.")
                                } else {
                                    appendLine("PSS: \${stats.totalPss} kB")
                                    appendLine("Private dirty: \${stats.totalPrivateDirty} kB")
                                    appendLine("Shared dirty: \${stats.totalSharedDirty} kB")
                                }
                            } catch (security: SecurityException) {
                                appendLine("Android denied access to this process's memory metrics.")
                            }
                        }
                    }
                } catch (security: SecurityException) {
                    appendLine("Android denied visibility into other app processes.")
                }
            }
            appendLine()
            appendLine("Privacy boundary: these are aggregate memory counters only, when available. They do not reveal heap objects, passwords, tokens, local variables, or raw private memory. For heap dumps and object-level analysis, use an authorized debugger/profiler with an app you own or are permitted to test.")
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
