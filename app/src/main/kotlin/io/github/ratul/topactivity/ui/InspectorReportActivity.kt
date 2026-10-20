package io.github.ratul.topactivity.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.text.DateFormat
import java.util.Date

/** Read-only report page for a saved inspector result, with copy and share/export actions. */
class InspectorReportActivity : AppCompatActivity() {
    private lateinit var reportText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
        val title = if (type == TYPE_LIVE) "Live Activity Report" else "Runtime Report"
        val supplied = intent.getStringExtra(EXTRA_CONTENT).orEmpty()
        val captured = intent.getStringExtra(EXTRA_CAPTURED).orEmpty()
        val report = buildString {
            appendLine(title.uppercase())
            appendLine("Report opened: ${DateFormat.getDateTimeInstance().format(Date())}")
            if (captured.isNotBlank()) appendLine("Snapshot captured: $captured")
            appendLine()
            append(supplied.ifBlank { "No report snapshot is available yet. Return to the inspector, run an inspection, and open this report again." })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        root.addView(MaterialToolbar(this).apply {
            this.title = title
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, report))
                Toast.makeText(this@InspectorReportActivity, "Report copied", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "Share / export"
            setOnClickListener {
                val share = Intent(Intent.ACTION_SEND).apply {
                    setType("text/plain")
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(share, "Share report"))
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))

        reportText = TextView(this).apply {
            text = report
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val scroll = ScrollView(this)
        scroll.addView(reportText, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TYPE = "inspector_report_type"
        const val EXTRA_CONTENT = "inspector_report_content"
        const val EXTRA_CAPTURED = "inspector_report_captured"
        const val TYPE_LIVE = "live"
        const val TYPE_RUNTIME = "runtime"
    }
}
