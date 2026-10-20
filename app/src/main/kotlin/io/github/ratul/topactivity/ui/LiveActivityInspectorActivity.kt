package io.github.ratul.topactivity.ui

import android.content.Intent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.text.DateFormat
import java.util.Date

/** Displays the live activity feed on its own screen using Android Usage Access. */
class LiveActivityInspectorActivity : AppCompatActivity() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var resultText: TextView
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
            title = "Live Activity Inspector"
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = "LIVE RESULTS\nShows the latest resumed app/activity events Android makes available. Updates every second while this page is open."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(12))
        })
        root.addView(Button(this).apply {
            text = "Grant / check Usage Access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }, LinearLayout.LayoutParams(-1, -2))
        resultText = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val scroll = ScrollView(this)
        scroll.addView(resultText, LinearLayout.LayoutParams(-1, -2))
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

    @Suppress("DEPRECATION")
    private fun refreshForeground() {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - 120_000L, now)
        } catch (_: Exception) {
            resultText.text = "Android could not provide usage events. Grant Usage Access in Settings, then return to this page."
            return
        }
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
        resultText.text = if (pkg.isNullOrBlank()) {
            "No foreground activity event was returned. Grant Usage Access in Android Settings, open another app, then return here. Android may limit event visibility."
        } else {
            "LIVE ACTIVITY RESULT\n\nPackage: $pkg\nActivity/class: ${cls ?: "(not exposed)"}\nLast resume: ${DateFormat.getDateTimeInstance().format(Date(lastTime))}\nStatus: live polling active\n\nNote: Android usage events show activity transitions; they do not expose another app's private memory or internal state."
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
