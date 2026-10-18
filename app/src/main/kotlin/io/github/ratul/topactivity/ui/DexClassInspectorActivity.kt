package io.github.ratul.topactivity.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
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
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
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
            hint = "Search classes, methods, fields, types, and strings…"
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
            setOnClickListener { val uri = lastSelectedUri; if (uri == null) filterReport(searchInput.text.toString()) else searchAllDex(uri, searchInput.text.toString()) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        searchButtons.addView(Button(this).apply {
            text = "Full report"
            setOnClickListener {
                resultText.text = exportText
                statusText.text = "Showing full report"
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(searchButtons, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Trace behavior / controller"
            setOnClickListener {
                val uri = lastSelectedUri
                val term = searchInput.text.toString().trim()
                if (uri == null) {
                    Toast.makeText(this@DexClassInspectorActivity, "Select an APK first.", Toast.LENGTH_SHORT).show()
                } else if (term.isBlank()) {
                    Toast.makeText(this@DexClassInspectorActivity, "Enter a class, method, or field name first.", Toast.LENGTH_SHORT).show()
                } else {
                    findReferences(uri, term)
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
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
        polishScreen(root)
        setContentView(root)
    }

    /**
     * Static cross-reference search. Reports DEX instructions that reference the
     * requested class/member and the enclosing class/method. This is not runtime
     * tracing and cannot reveal server-side decisions or reflection-only links.
     */
    private fun findReferences(uri: Uri, query: String) {
        if (inspectionRunning) return
        inspectionRunning = true
        statusText.text = "Tracing class, method, field, string, and call references: $query"
        resultText.text = "Tracing matching definitions, string constants, field accesses, and method calls…"
        Thread({
            val out = StringBuilder()
            val normalized = query.trim()
            val variants = setOf(
                normalized,
                normalized.replace('.', '/'),
                "L" + normalized.replace('.', '/') + ";"
            ).filter { it.isNotBlank() }
            var dexCount = 0
            var parsedCount = 0
            var hits = 0
            var skipped = 0
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null && out.length < MAX_REPORT_CHARS && hits < MAX_REFERENCE_HITS) {
                            if (!entry.isDirectory && DEX_NAME.matches(entry.name)) {
                                dexCount++
                                val dexName = entry.name
                                val bytes = readBounded(zip, MAX_DEX_BYTES)
                                if (bytes == null) {
                                    skipped++
                                    out.appendLine("\nDEX $dexName: skipped (over safety limit)")
                                } else {
                                    try {
                                        val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
                                        parsedCount++
                                        for (clazz in dex.classes) {
                                            if (local.length >= MAX_REPORT_CHARS / 2) break
                                            val className = type(clazz.type)
                                            if (variants.any { className.contains(it, true) }) {
                                                out.appendLine("\nCLASS DEFINITION: $className [$dexName]")
                                                out.appendLine("  Superclass: ${clazz.superclass?.let { type(it) } ?: "<none>"}")
                                                out.appendLine("  Fields: ${clazz.fields.joinToString { it.name }.ifBlank { "<none>" }}")
                                                out.appendLine("  Methods: ${clazz.methods.joinToString { it.name + "()" }.ifBlank { "<none>" }}")
                                            }
                                            for (method in clazz.methods) {
                                                val impl = method.implementation ?: continue
                                                var address = 0
                                                for (ins in impl.instructions) {
                                                    if (out.length >= MAX_REPORT_CHARS || hits >= MAX_REFERENCE_HITS) break
                                                    if (ins is ReferenceInstruction) {
                                                        val refText = ins.reference.toString()
                                                        if (variants.any { refText.contains(it, true) } ||
                                                            (refText.contains(normalized, true))) {
                                                            out.appendLine("\nREFERENCE #${hits + 1} [$dexName]")
                                                            out.appendLine("  In class: $className")
                                                            out.appendLine("  In method: ${method.name}(${method.parameterTypes.joinToString { type(it.toString()) }})")
                                                            out.appendLine("  Offset: 0x${address.toString(16)}")
                                                            val opcode = ins.opcode.name
                                                            val kind = when {
                                                                opcode.startsWith("INVOKE") -> "METHOD CALL / DISPATCH"
                                                                opcode.contains("IGET") || opcode.contains("SGET") -> "FIELD READ"
                                                                opcode.contains("IPUT") || opcode.contains("SPUT") -> "FIELD WRITE"
                                                                opcode.startsWith("CONST_STRING") -> "STRING CONSTANT"
                                                                refText.startsWith("L") -> "CLASS / TYPE REFERENCE"
                                                                refText.contains("->") && refText.contains(":") -> "FIELD / MEMBER REFERENCE"
                                                                else -> "DEX REFERENCE"
                                                            }
                                                            out.appendLine("  Relationship: $kind")
                                                            out.appendLine("  Opcode: $opcode")
                                                            out.appendLine("  Target: $refText")
                                                            if (opcode.startsWith("INVOKE")) {
                                                                out.appendLine("  Trace hint: inspect the target method next; this instruction is a possible call edge.")
                                                            } else if (kind == "FIELD READ" || kind == "FIELD WRITE") {
                                                                out.appendLine("  Trace hint: this instruction accesses the field; inspect its declaration and all other reads/writes.")
                                                            }
                                                            hits++
                                                        }
                                                    }
                                                    address += ins.codeUnits
                                                }
                                                if (hits >= MAX_REFERENCE_HITS) break
                                            }
                                            if (hits >= MAX_REFERENCE_HITS || out.length >= MAX_REPORT_CHARS) break
                                        }
                                    } catch (ex: Exception) {
                                        skipped++
                                        out.appendLine("\nDEX $dexName: parse failed (${ex.message ?: ex.javaClass.simpleName})")
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: out.appendLine("Could not open selected APK.")
            } catch (oom: OutOfMemoryError) {
                out.appendLine("\nSearch stopped to protect device memory.")
            } catch (ex: Exception) {
                out.appendLine("\nSearch incomplete: ${ex.message ?: ex.javaClass.simpleName}")
            }
            if (hits == 0 && out.isEmpty()) out.appendLine("No matching class/member references found for: $normalized")
            out.appendLine("\nREFERENCE MAP SUMMARY: DEX files=$dexCount; parsed=$parsedCount; skipped=$skipped; instruction references=$hits.")
            out.appendLine("How to use this for a controller (e.g. Premium): search one term at a time such as Premium, Renew, Billing, subscription, a displayed price, or a product ID. Follow METHOD CALL targets to handlers and FIELD READ/WRITE results to state variables. Inspect each enclosing class/method in the report.")
            out.appendLine("Limitations: this is static reference tracing, not live execution or a guaranteed complete data-flow graph. Reflection, native code, dynamically loaded DEX, obfuscation, resource-only UI, and server-side decisions may not be resolved.")
            val report = out.toString().take(MAX_REPORT_CHARS)
            runOnUiThread {
                inspectionRunning = false
                exportText = report
                displayedReport = report
                resultText.text = report
                statusText.text = "Reference map complete — $hits matches; $skipped DEX entries skipped"
            }
        }, "DexReferenceMapWorker").start()
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
            .toList()
        resultText.text = if (matches.isEmpty()) {
            "No lines matched: $term"
        } else {
            "SEARCH RESULTS FOR: $term\nMatches shown: ${matches.size}" +
                "\n\n" + matches.joinToString("\n")
        }
        statusText.text = "Search complete: ${matches.size} matching lines"
    }

    private fun searchAllDex(uri: Uri, query: String) {
        val term = query.trim()
        if (term.isEmpty()) { filterReport(""); return }
        if (inspectionRunning) return
        inspectionRunning = true
        statusText.text = "Searching all DEX files for: " + term
        resultText.text = "Search started…\nResults will appear as each DEX is scanned."
        Thread({
            val output = StringBuilder()
            var dexCount = 0
            var parsedCount = 0
            var skippedCount = 0
            var totalMatches = 0
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null && output.length < MAX_REPORT_CHARS) {
                            if (!entry.isDirectory && DEX_NAME.matches(entry.name)) {
                                dexCount++
                                val dexName = entry.name
                                val bytes = readBounded(zip, MAX_DEX_BYTES)
                                if (bytes == null) {
                                    skippedCount++
                                    output.appendLine("\nDEX: " + dexName)
                                    output.appendLine("SKIPPED: exceeds " + (MAX_DEX_BYTES / (1024 * 1024)) + " MB safety limit.")
                                } else {
                                    try {
                                        val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
                                        parsedCount++
                                        val local = StringBuilder()
                                        local.appendLine("\n========== DEX: " + dexName + " ==========")
                                        var localMatches = 0
                                        for (clazz in dex.classes) {
                                            val className = type(clazz.type)
                                            val classHit = className.contains(term, ignoreCase = true)
                                            val matchingFields = clazz.fields.filter { it.name.contains(term, true) || type(it.type).contains(term, true) }
                                            val matchingMethods = clazz.methods.filter { method ->
                                                method.name.contains(term, true) ||
                                                type(method.returnType).contains(term, true) ||
                                                method.parameterTypes.any { type(it.toString()).contains(term, true) }
                                            }
                                            if (classHit || matchingFields.isNotEmpty() || matchingMethods.isNotEmpty()) {
                                                local.appendLine("\nCLASS: " + className)
                                                local.appendLine("ACCESS: " + flags(clazz.accessFlags))
                                                local.appendLine("SUPERCLASS: " + (clazz.superclass?.let { type(it) } ?: "<none>"))
                                                if (classHit) local.appendLine("MATCH: class name")
                                                matchingFields.forEach { local.appendLine("MATCH FIELD: " + type(it.type) + " " + it.name) }
                                                matchingMethods.forEach { method ->
                                                    local.appendLine("MATCH METHOD: " + type(method.returnType) + " " + method.name + "(" + method.parameterTypes.joinToString { type(it.toString()) } + ")")
                                                }
                                                localMatches++
                                            }
                                        }
                                        local.appendLine("\n--- DEX STRING POOL matches ---")
                                        var stringMatches = 0
                                        for (ref in dex.stringReferences) {
                                            if (local.length >= MAX_REPORT_CHARS / 2) break
                                            if (ref.string.contains(term, ignoreCase = true)) {
                                                local.appendLine("STRING: " + ref.string.replace("\n", "\\n").replace("\r", "\\r"))
                                                stringMatches++
                                            }
                                        }
                                        local.appendLine("Class/member matches: " + localMatches + "; string matches: " + stringMatches)
                                        totalMatches += localMatches + stringMatches
                                        if (localMatches + stringMatches == 0) local.appendLine("No matches in this DEX.")
                                        synchronized(output) {
                                            val remaining = MAX_REPORT_CHARS - output.length
                                            if (remaining > 0) output.append(local, 0, minOf(local.length, remaining))
                                        }
                                    } catch (ex: Exception) {
                                        skippedCount++
                                        output.appendLine("\nDEX: " + dexName)
                                        output.appendLine("SKIPPED: parse failed (" + (ex.message ?: ex.javaClass.simpleName) + ").")
                                    }
                                }
                                val snapshot = synchronized(output) { output.toString() }
                                runOnUiThread {
                                    if (inspectionRunning) {
                                        resultText.text = snapshot
                                        statusText.text = "Scanning " + dexName + " — " + parsedCount + " parsed, " + skippedCount + " skipped; " + totalMatches + " matches so far"
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: output.appendLine("Could not open selected APK.")
            } catch (oom: OutOfMemoryError) {
                output.appendLine("\nSEARCH INCOMPLETE: stopped to protect phone memory (out of memory). Try a smaller APK.")
            } catch (ex: Exception) {
                output.appendLine("\nSEARCH INCOMPLETE: " + (ex.message ?: ex.javaClass.simpleName))
            }
            if (output.length >= MAX_REPORT_CHARS) output.setLength(MAX_REPORT_CHARS)
            output.appendLine("\nSEARCH SUMMARY: DEX entries found=" + dexCount + "; parsed=" + parsedCount + "; skipped=" + skippedCount + ".")
            if (dexCount == 0) output.appendLine("No supported classes.dex through classes60.dex entries found.")
            if (skippedCount > 0) output.appendLine("WARNING: Search is incomplete because one or more DEX files were skipped.")
            val finalText = synchronized(output) { output.toString() }
            runOnUiThread {
                inspectionRunning = false
                exportText = finalText
                displayedReport = finalText
                resultText.text = finalText
                statusText.text = if (skippedCount > 0) "Search finished — incomplete; see warnings" else "Search finished: " + totalMatches + " matches across " + parsedCount + " DEX files"
                loadMoreButton.isEnabled = lastSelectedUri != null
            }
        }, "DexSearchWorker").start()
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
                    var retainedDexBytes = 0L
                    var omittedDexCount = 0
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && DEX_NAME.matches(entry.name)) {
                                if (dexEntries.size >= MAX_INSPECT_DEX_FILES) {
                                    omittedDexCount++
                                } else {
                                    val bytes = readBounded(zip, MAX_DEX_BYTES)
                                    if (bytes == null || retainedDexBytes + bytes.size > MAX_INSPECT_TOTAL_BYTES) {
                                        dexEntries.add(entry.name to ByteArray(0))
                                    } else {
                                        dexEntries.add(entry.name to bytes)
                                        retainedDexBytes += bytes.size
                                    }
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
                        appendLine("DEX files inspected: ${dexEntries.size}; omitted after safety limit: $omittedDexCount")
                        appendLine("Memory guard: up to $MAX_INSPECT_DEX_FILES DEX files and $MAX_INSPECT_TOTAL_MB MB total DEX data retained per inspection.")
                        dexEntries.forEach { (name, bytes) ->
                            appendLine("\n================ DEX: $name ================")
                            if (bytes.isEmpty()) {
                                appendLine("Skipped: this DEX exceeded the per-file or total memory safety limit.")
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

    /**
     * Consistent mobile-first dark UI: readable hierarchy, rounded controls,
     * clear primary actions, and high-contrast report surfaces.
     */
    private fun polishScreen(view: ViewGroup) {
        val background = Color.rgb(12, 18, 32)
        val surface = Color.rgb(23, 32, 51)
        val accent = Color.rgb(250, 190, 72)
        val textPrimary = Color.rgb(241, 245, 249)
        val textSecondary = Color.rgb(172, 185, 205)
        view.setBackgroundColor(background)
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            when (child) {
                is MaterialToolbar -> {
                    child.setBackgroundColor(background)
                    child.setTitleTextColor(textPrimary)
                }
                is Button -> {
                    val label = child.text?.toString().orEmpty()
                    val primary = label.contains("Select APK", true) ||
                        label.contains("Trace behavior", true) ||
                        label.contains("Apply class filter", true) ||
                        label == "Search" || label.contains("Find callers", true)
                    child.isAllCaps = false
                    child.textSize = 14f
                    child.minHeight = dp(48)
                    child.setPadding(dp(14), dp(10), dp(14), dp(10))
                    child.backgroundTintList = ColorStateList.valueOf(if (primary) accent else surface)
                    child.setTextColor(if (primary) Color.rgb(31, 35, 42) else textPrimary)
                    child.elevation = dp(if (primary) 2 else 0).toFloat()
                }
                is EditText -> {
                    child.setTextColor(textPrimary)
                    child.setHintTextColor(textSecondary)
                    child.textSize = 15f
                    child.setPadding(dp(14), dp(12), dp(14), dp(12))
                    child.background = GradientDrawable().apply {
                        setColor(surface)
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), Color.rgb(58, 72, 96))
                    }
                }
                is TextView -> {
                    if (child.textSize >= 11f) child.setTextColor(textSecondary)
                }
                is ScrollView -> {
                    child.setBackgroundColor(background)
                    if (child.childCount > 0 && child.getChildAt(0) is ViewGroup) {
                        polishNestedContent(child.getChildAt(0) as ViewGroup, surface, textPrimary, textSecondary)
                    }
                }
                is ViewGroup -> {
                    polishScreen(child)
                }
            }
        }
    }

    private fun polishNestedContent(container: ViewGroup, surface: Int, textPrimary: Int, textSecondary: Int) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView) {
                child.setTextColor(textPrimary)
                child.setPadding(dp(12), dp(10), dp(12), dp(10))
                child.setTextIsSelectable(true)
                child.background = GradientDrawable().apply {
                    setColor(surface)
                    cornerRadius = dp(12).toFloat()
                }
            } else if (child is ViewGroup) {
                polishNestedContent(child, surface, textPrimary, textSecondary)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_DEX_FILES = 60
        private const val MAX_INSPECT_DEX_FILES = 8
        private const val MAX_INSPECT_TOTAL_MB = 96
        private const val MAX_INSPECT_TOTAL_BYTES = MAX_INSPECT_TOTAL_MB * 1024 * 1024
        private const val MAX_DEX_BYTES = 48 * 1024 * 1024
        private const val INITIAL_CLASSES_PER_DEX = 120
        private const val MAX_CLASSES_PER_DEX_HARD = 600
        private const val MAX_INSTRUCTIONS = 300
        private const val MAX_STRINGS = 1000
        private const val MAX_REPORT_CHARS = 1_500_000
        private const val MAX_REFERENCE_HITS = 5000
        private val DEX_NAME = Regex("(^|.*/)classes([0-9]+)?\\.dex")
    }
}
