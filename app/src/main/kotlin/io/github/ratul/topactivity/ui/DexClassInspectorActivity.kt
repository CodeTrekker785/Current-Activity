/*
 * DEX Class Inspector - reads DEX class descriptors from a user-selected APK.
 * File access is limited to the APK selected through Android's document picker.
 */
package io.github.ratul.topactivity.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class DexClassInspectorActivity : AppCompatActivity() {
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private var exportText: String = "No APK inspected yet."

    private val apkPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) inspectSelectedApk(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "DEX Class Inspector"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))

        root.addView(TextView(this).apply {
            text = "Choose an APK file to inspect classes.dex, classes2.dex, and other DEX files. Inspection runs locally on this device."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
        })

        root.addView(Button(this).apply {
            text = "Select APK file"
            setOnClickListener {
                apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
            }
        }, LinearLayout.LayoutParams(-1, -2))

        root.addView(Button(this).apply {
            text = "Export / Share report"
            setOnClickListener {
                if (exportText == "No APK inspected yet.") {
                    MaterialAlertDialogBuilder(this@DexClassInspectorActivity)
                        .setMessage("Select an APK first.")
                        .setPositiveButton(android.R.string.ok, null).show()
                } else {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "DEX Class Inspector report")
                        putExtra(Intent.EXTRA_TEXT, exportText)
                    }
                    startActivity(Intent.createChooser(send, "Share DEX report"))
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))

        statusText = TextView(this).apply {
            text = "No APK selected"
            textSize = 14f
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(statusText)

        val scroll = ScrollView(this)
        resultText = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll.addView(resultText, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun inspectSelectedApk(uri: Uri) {
        statusText.text = "Reading APK…"
        resultText.text = ""
        Thread {
            val report = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val dexReports = mutableListOf<Pair<String, List<String>>>()
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.matches(Regex("(^|.*/)classes([0-9]+)?\\.dex"))) {
                                val bytes = zip.readBytes()
                                dexReports.add(entry.name to parseDexClasses(bytes))
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                    if (dexReports.isEmpty()) {
                        "No classes.dex file was found in the selected APK."
                    } else {
                        buildString {
                            appendLine("DEX CLASS INSPECTOR REPORT")
                            appendLine("APK: \${uri.lastPathSegment ?: "Selected APK"}")
                            appendLine("DEX files found: \${dexReports.size}")
                            appendLine()
                            dexReports.forEach { (name, classes) ->
                                appendLine("=== $name ===")
                                appendLine("Class count: \${classes.size}")
                                appendLine()
                                if (classes.isEmpty()) appendLine("No class descriptors could be read.")
                                classes.forEach { appendLine(it) }
                                appendLine()
                            }
                            appendLine("Note: this report lists class descriptors only; it does not decompile method bodies or recover original source code.")
                        }
                    }
                } ?: "Could not open the selected file."
            } catch (e: Exception) {
                "Could not inspect this file: \${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                exportText = report
                statusText.text = if (report.startsWith("Could not") || report.startsWith("No classes.dex")) "Inspection did not complete" else "Inspection complete"
                resultText.text = report
            }
        }.start()
    }

    private fun parseDexClasses(data: ByteArray): List<String> {
        if (data.size < 112 || !data.copyOfRange(0, 4).contentEquals(byteArrayOf(0x64, 0x65, 0x78, 0x0a))) {
            return emptyList()
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val stringCount = buffer.getInt(56)
        val stringOffset = buffer.getInt(60)
        val typeCount = buffer.getInt(64)
        val typeOffset = buffer.getInt(68)
        val classCount = buffer.getInt(96)
        val classOffset = buffer.getInt(100)
        if (!validTable(stringOffset, stringCount, 4, data.size) ||
            !validTable(typeOffset, typeCount, 4, data.size) ||
            !validTable(classOffset, classCount, 32, data.size)) return emptyList()

        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val offset = buffer.getInt(stringOffset + i * 4)
            strings.add(readDexString(data, offset))
        }

        val typeDescriptors = IntArray(typeCount) { i -> buffer.getInt(typeOffset + i * 4) }
        val result = ArrayList<String>(classCount)
        for (i in 0 until classCount) {
            val classIdx = buffer.getInt(classOffset + i * 32)
            if (classIdx in typeDescriptors.indices) {
                val descriptorIdx = typeDescriptors[classIdx]
                val descriptor = strings.getOrNull(descriptorIdx)
                if (descriptor != null) result.add(descriptorToName(descriptor))
            }
        }
        return result
    }

    private fun validTable(offset: Int, count: Int, itemSize: Int, size: Int): Boolean {
        if (offset < 0 || count < 0) return false
        return offset.toLong() + count.toLong() * itemSize <= size.toLong()
    }

    private fun readDexString(data: ByteArray, offset: Int): String {
        if (offset !in data.indices) return "<invalid string offset>"
        var index = offset
        var shift = 0
        while (index < data.size) {
            val value = data[index++].toInt() and 0xff
            if (value and 0x80 == 0) break
            shift += 7
            if (shift > 28) return "<invalid string length>"
        }
        val start = index
        while (index < data.size && data[index].toInt() != 0) index++
        if (index >= data.size) return "<invalid string data>"
        return String(data, start, index - start, Charsets.UTF_8)
    }

    private fun descriptorToName(descriptor: String): String =
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        } else descriptor

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
