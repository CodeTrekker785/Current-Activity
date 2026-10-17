package io.github.ratul.topactivity.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Field
import org.jf.dexlib2.iface.Method
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Memory-bounded APK DEX inspector. Large APKs are sampled rather than loading an
 * unlimited report into the Android heap; users can inspect smaller APKs fully.
 */
class DexClassInspectorActivity : AppCompatActivity() {
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private var exportText = "No APK inspected yet."
    private var displayedReport = ""
    private lateinit var searchInput: EditText
    private lateinit var loadMoreButton: Button
    private var lastSelectedUri: Uri? = null
    private var classLimitPerDex = INITIAL_CLASSES_PER_DEX

    private val saveReportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportText) }
                Toast.makeText(this, "Report saved.", Toast.LENGTH_SHORT).show()
            } catch (ex: Exception) {
                Toast.makeText(this, "Could not save report: ${ex.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }
    @Volatile private var inspectionRunning = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspect(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "DEX Class Inspector"
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = "Inspect class hierarchy, fields, methods, constructors, annotations and DEX instructions. Large APK reports are limited to protect phone memory. Processing is local."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
        })
        root.addView(Button(this).apply {
            text = "Select APK file"
            setOnClickListener {
                if (!inspectionRunning) picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
            }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Open Developer Toolkit"
            setOnClickListener {
                startActivity(Intent(this@DexClassInspectorActivity, DeveloperToolkitActivity::class.java))
            }
        }, LinearLayout.LayoutParams(-1, -2))
        loadMoreButton = Button(this).apply {
            text = "Load more classes (first $classLimitPerDex per DEX)"
            isEnabled = false
            setOnClickListener {
                if (inspectionRunning) return@setOnClickListener
                if (classLimitPerDex >= MAX_CLASSES_PER_DEX_HARD) {
                    Toast.makeText(this@DexClassInspectorActivity, "Maximum class limit reached. Try another APK or use a smaller DEX.", Toast.LENGTH_LONG).show()
                } else {
                    classLimitPerDex = minOf(classLimitPerDex + INITIAL_CLASSES_PER_DEX, MAX_CLASSES_PER_DEX_HARD)
                    lastSelectedUri?.let { inspect(it) }
                }
            }
        }
        root.addView(loadMoreButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Export / Share report"
            setOnClickListener {
                if (exportText == "No APK inspected yet.") {
                    MaterialAlertDialogBuilder(this@DexClassInspectorActivity).setMessage("Select an APK first.")
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

        searchInput = EditText(this).apply {
            hint = "Search class, method, field, package…"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(searchInput, LinearLayout.LayoutParams(-1, -2))
        val searchButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        searchButtons.addView(Button(this).apply {
            text = "Search"
            setOnClickListener { filterReport(searchInput.text.toString()) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        searchButtons.addView(Button(this).apply {
            text = "Full report"
            setOnClickListener {
                resultText.text = exportText
                statusText.text = "Showing full report"
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(searchButtons, LinearLayout.LayoutParams(-1, -2))
        val reportButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        reportButtons.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                if (exportText == "No APK inspected yet.") {
                    Toast.makeText(this@DexClassInspectorActivity, "Inspect an APK first.", Toast.LENGTH_SHORT).show()
                } else {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("DEX report", exportText))
                    Toast.makeText(this@DexClassInspectorActivity, "Full report copied.", Toast.LENGTH_SHORT).show()
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        reportButtons.addView(Button(this).apply {
            text = "Save TXT"
            setOnClickListener {
                if (exportText == "No APK inspected yet.") {
                    Toast.makeText(this@DexClassInspectorActivity, "Inspect an APK first.", Toast.LENGTH_SHORT).show()
                } else {
                    saveReportPicker.launch("dex-report.txt")
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(reportButtons, LinearLayout.LayoutParams(-1, -2))
        statusText = TextView(this).apply { text = "No APK selected"; textSize = 14f; setPadding(0, dp(12), 0, dp(8)) }
        root.addView(statusText)
        val scroll = ScrollView(this)
        resultText = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll.addView(resultText, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun filterReport(query: String) {
        val term = query.trim()
        if (exportText == "No APK inspected yet.") {
            Toast.makeText(this, "Inspect an APK first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (term.isEmpty()) {
            resultText.text = exportText
            statusText.text = "Showing full report"
            return
        }
        val matches = exportText.lineSequence()
            .filter { it.contains(term, ignoreCase = true) }
            .take(MAX_SEARCH_MATCHES)
            .toList()
        resultText.text = if (matches.isEmpty()) {
            "No lines matched: $term"
        } else {
            "SEARCH RESULTS FOR: $term\nMatches shown: ${matches.size}" +
                (if (matches.size == MAX_SEARCH_MATCHES) " (result limit reached)" else "") +
                "\n\n" + matches.joinToString("\n")
        }
        statusText.text = "Search complete: ${matches.size} matching lines"
    }

    private fun inspect(uri: Uri) {
        if (inspectionRunning) return
        lastSelectedUri = uri
        inspectionRunning = true
        loadMoreButton.isEnabled = false
        loadMoreButton.text = "Reading classes… (limit $classLimitPerDex per DEX)"
        statusText.text = "Reading APK and DEX contents…"
        resultText.text = ""
        Thread({
            val report = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val dexEntries = mutableListOf<Pair<String, ByteArray>>()
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null && dexEntries.size < MAX_DEX_FILES) {
                            if (!entry.isDirectory && DEX_NAME.matches(entry.name)) {
                                val bytes = readBounded(zip, MAX_DEX_BYTES)
                                if (bytes == null) {
                                    dexEntries.add(entry.name to ByteArray(0))
                                } else {
                                    dexEntries.add(entry.name to bytes)
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                    if (dexEntries.isEmpty()) "No classes.dex file was found in the selected APK."
                    else buildString {
                        appendLine("DEX CLASS INSPECTOR REPORT")
                        appendLine("APK: ${uri.lastPathSegment ?: "Selected APK"}")
                        appendLine("DEX files examined: ${dexEntries.size} (maximum $MAX_DEX_FILES)")
                        dexEntries.forEach { (name, bytes) ->
                            appendLine("\n================ DEX: $name ================")
                            if (bytes.isEmpty()) {
                                appendLine("Skipped: this DEX exceeds the ${MAX_DEX_BYTES / (1024 * 1024)} MB safety limit.")
                            } else {
                                try {
                                    val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
                                    val classes = dex.classes
                                    appendLine("Class count: ${classes.count()}")
                                    var emittedClasses = 0
                                    for (clazz in classes) {
                                        if (emittedClasses >= classLimitPerDex || length >= MAX_REPORT_CHARS) break
                                        appendClass(clazz)
                                        emittedClasses++
                                    }
                                    if (emittedClasses < classes.count()) {
                                        appendLine("\n[Output limited: displayed $emittedClasses classes from this DEX (current limit $classLimitPerDex).]")
                                    }
                                    if (length < MAX_REPORT_CHARS) {
                                        appendLine("\n============= DEX STRING POOL (first $MAX_STRINGS) =============")
                                        dex.stringReferences.take(MAX_STRINGS).forEach {
                                            if (length < MAX_REPORT_CHARS) appendLine(it.string.replace("\n", "\\n").replace("\r", "\\r"))
                                        }
                                    }
                                } catch (ex: Exception) {
                                    appendLine("Could not parse $name: ${ex.message ?: ex.javaClass.simpleName}")
                                }
                            }
                        }
                        appendLine("\nNOTE: Output is intentionally limited for stability. Instructions are disassembled DEX bytecode, not original Java source.")
                        if (length > MAX_REPORT_CHARS) setLength(MAX_REPORT_CHARS)
                        if (length >= MAX_REPORT_CHARS) appendLine("\n[Report truncated to protect phone memory. Select a smaller APK or inspect fewer classes.]")
                    }
                } ?: "Could not open selected file."
            } catch (ex: Exception) {
                "Could not inspect file: ${ex.message ?: ex.javaClass.simpleName}"
            } catch (oom: OutOfMemoryError) {
                "Inspection stopped because this APK needs more memory than the phone can provide. Try a smaller APK."
            }
            runOnUiThread {
                inspectionRunning = false
                loadMoreButton.isEnabled = lastSelectedUri != null && classLimitPerDex < MAX_CLASSES_PER_DEX_HARD
                loadMoreButton.text = if (classLimitPerDex >= MAX_CLASSES_PER_DEX_HARD) "Class limit reached ($classLimitPerDex per DEX)" else "Load more classes (up to $classLimitPerDex per DEX)"
                exportText = report
                displayedReport = report
                statusText.text = if (report.startsWith("Could not") || report.startsWith("No classes.dex") || report.startsWith("Inspection stopped")) "Inspection did not complete" else "Inspection complete (output may be limited)"
                resultText.text = report
            }
        }, "DexInspectorWorker").start()
    }

    /** Reads at most maxBytes + 1, preventing ZipInputStream.readBytes() from allocating without a bound. */
    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(64 * 1024, maxBytes))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun StringBuilder.appendClass(c: ClassDef) {
        if (length >= MAX_REPORT_CHARS) return
        appendLine("\n============================================================")
        appendLine("CLASS: ${type(c.type)}")
        appendLine("ACCESS: ${flags(c.accessFlags)} [${c.accessFlags}]")
        appendLine("SUPERCLASS: ${c.superclass?.let { type(it) } ?: "<none>"}")
        appendLine("INTERFACES: ${c.interfaces.joinToString { type(it) }.ifBlank { "<none>" }}")
        appendLine("SOURCE FILE: ${c.sourceFile ?: "<not recorded>"}")
        appendLine("CLASS ANNOTATIONS:")
        if (c.annotations.isEmpty()) appendLine("  <none>")
        c.annotations.forEach { a ->
            appendLine("  @${type(a.type)} visibility=${a.visibility}")
            a.elements.forEach { appendLine("    ${it.name} = ${it.value}") }
        }
        val fields = c.fields.toList()
        appendLine("\nFIELDS (${fields.size}):")
        if (fields.isEmpty()) appendLine("  <none>")
        fields.forEach { if (length < MAX_REPORT_CHARS) appendField(it) }
        val methods = c.methods.toList()
        appendLine("\nMETHODS / CONSTRUCTORS (${methods.size}):")
        if (methods.isEmpty()) appendLine("  <none>")
        methods.forEach { if (length < MAX_REPORT_CHARS) appendMethod(it) }
    }

    private fun StringBuilder.appendField(f: Field) {
        appendLine("  ${flags(f.accessFlags)} ${type(f.type)} ${f.name}")
        if (f.initialValue != null) appendLine("    Initial value: ${f.initialValue}")
        f.annotations.forEach { a ->
            appendLine("    @${type(a.type)}")
            a.elements.forEach { appendLine("      ${it.name} = ${it.value}") }
        }
    }

    private fun StringBuilder.appendMethod(m: Method) {
        val label = if (m.name == "<init>" || m.name == "<clinit>") "CONSTRUCTOR" else "METHOD"
        appendLine("\n  [$label] ${flags(m.accessFlags)} ${type(m.returnType)} ${m.name}(${m.parameterTypes.joinToString { type(it.toString()) }})")
        m.parameters.forEachIndexed { i, p ->
            appendLine("    PARAM #$i ${p.name ?: "<unnamed>"}: ${type(p.type)}")
            p.annotations.forEach { appendLine("      @${type(it.type)}") }
        }
        m.annotations.forEach { a ->
            appendLine("    @${type(a.type)}")
            a.elements.forEach { appendLine("      ${it.name} = ${it.value}") }
        }
        val impl = m.implementation
        if (impl == null) appendLine("    BODY: <none — abstract/native or no code item>")
        else {
            appendLine("    REGISTERS: ${impl.registerCount}")
            appendLine("    DEX INSTRUCTIONS (first $MAX_INSTRUCTIONS):")
            var address = 0
            var emitted = 0
            for (ins in impl.instructions) {
                if (emitted >= MAX_INSTRUCTIONS || length >= MAX_REPORT_CHARS) break
                appendLine("      %04x: %s".format(address, ins.toString()))
                address += ins.codeUnits
                emitted++
            }
            if (emitted == MAX_INSTRUCTIONS) appendLine("      [Instructions truncated for memory safety]")
            impl.tryBlocks.take(100).forEach { appendLine("    TRY: start=${it.startCodeAddress}, units=${it.codeUnitCount}, handlers=${it.exceptionHandlers}") }
            impl.debugItems.take(200).forEach { appendLine("    DEBUG: $it") }
        }
    }

    private fun type(d: String): String {
        if (d.startsWith("L") && d.endsWith(";")) return d.substring(1, d.length - 1).replace('/', '.')
        if (d.startsWith("[")) return type(d.substring(1)) + "[]"
        return when (d) {
            "V" -> "void"; "Z" -> "boolean"; "B" -> "byte"; "S" -> "short"; "C" -> "char"
            "I" -> "int"; "J" -> "long"; "F" -> "float"; "D" -> "double"; else -> d
        }
    }

    private fun flags(f: Int): String {
        val names = listOf(1 to "public", 2 to "private", 4 to "protected", 8 to "static", 16 to "final",
            32 to "synchronized", 64 to "volatile/bridge", 128 to "transient/varargs", 256 to "native",
            512 to "interface", 1024 to "abstract", 2048 to "strict", 4096 to "synthetic",
            8192 to "annotation", 16384 to "enum", 65536 to "constructor", 131072 to "declared-synchronized")
        return names.filter { f and it.first != 0 }.joinToString(" ") { it.second }.ifBlank { "package-private" }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_DEX_FILES = 5
        private const val MAX_DEX_BYTES = 48 * 1024 * 1024
        private const val INITIAL_CLASSES_PER_DEX = 120
        private const val MAX_CLASSES_PER_DEX_HARD = 600
        private const val MAX_INSTRUCTIONS = 300
        private const val MAX_STRINGS = 1000
        private const val MAX_REPORT_CHARS = 1_500_000
        private const val MAX_SEARCH_MATCHES = 5000
        private val DEX_NAME = Regex("(^|.*/)classes([0-9]+)?\\.dex")
    }
}
