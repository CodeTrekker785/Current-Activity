package io.github.ratul.topactivity.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * Best-effort Logcat viewer. Android normally restricts third-party apps to
 * their own logs; this screen does not bypass log permissions or use root.
 */
class LogcatViewerActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var filterInput: EditText
    private lateinit var output: TextView
    private lateinit var status: TextView
    private var latestLog = ""
    @Volatile private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "Logcat Viewer"
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = "Best-effort Android logs. On ordinary Android, apps generally cannot read all system or other apps' logs. For full device Logcat, use an authorized ADB/debugging session. This tool does not bypass permissions."
            textSize = 13f
            setPadding(0, dp(6), 0, dp(8))
        })
        filterInput = EditText(this).apply {
            hint = "Filter text (e.g. ActivityManager, crash)"
            setSingleLine(true)
        }
        root.addView(filterInput, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { loadLogs() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Logcat output", output.text))
                Toast.makeText(this@LogcatViewerActivity, "Visible log output copied", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Share"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    setType("text/plain")
                    putExtra(Intent.EXTRA_SUBJECT, "Logcat report")
                    putExtra(Intent.EXTRA_TEXT, output.text.toString())
                }
                startActivity(Intent.createChooser(send, "Share log output"))
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        status = TextView(this).apply { text = "Tap Refresh to read logs exposed to this app."; textSize = 12f }
        root.addView(status)
        val scroll = ScrollView(this)
        output = TextView(this).apply {
            text = "No log loaded."
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        scroll.addView(output, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun loadLogs() {
        status.text = "Reading the latest 500 lines available to this app…"
        worker.execute {
            val result = try {
                val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "500")
                    .redirectErrorStream(true).start()
                val text = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val exit = process.waitFor()
                if (exit != 0 && text.isBlank()) "Logcat is unavailable or Android denied access (exit $exit)." else text
            } catch (e: Exception) {
                "Could not run logcat: ${e.javaClass.simpleName}: ${e.message ?: "access denied"}. Android may block log access for ordinary apps. Use an authorized ADB session for full device logs."
            }
            runOnUiThread {
                if (closed || isFinishing) return@runOnUiThread
                latestLog = result
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = filterInput.text.toString().trim()
        val lines = latestLog.lineSequence()
        val filtered = if (query.isEmpty()) lines.toList() else lines.filter { it.contains(query, true) }.toList()
        output.text = filtered.takeLast(MAX_LINES).joinToString("\n").ifBlank { "No lines matched the filter." }
        status.text = "Showing ${minOf(filtered.size, MAX_LINES)} of ${filtered.size} matching lines. Logs may be limited by Android permissions."
    }

    override fun onDestroy() {
        closed = true
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val MAX_LINES = 500 }
}
