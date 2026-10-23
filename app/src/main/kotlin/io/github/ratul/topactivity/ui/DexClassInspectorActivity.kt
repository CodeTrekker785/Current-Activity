package io.github.ratul.topactivity.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
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
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
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
    private lateinit var toolbar: MaterialToolbar
    private lateinit var reportScroll: ScrollView
    private var showingReportPage = false
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
        toolbar = MaterialToolbar(this).apply {
            title = "DEX Class Inspector"
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { if (showingReportPage) showReportPage(false) else finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = "Inspect class hierarchy, fields, methods, constructors, annotations, strings and DEX instructions across APK DEX files. Search scans DEX entries independently of the visible class-page limit. Very large files/reports remain bounded to protect phone memory; processing is local."
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
            hint = "Search any class, field, method, string, API, ID, or value…"
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
            text = "Universal DEX search (definitions + references)"
            setOnClickListener {
                val uri = lastSelectedUri
                if (uri == null) {
                    Toast.makeText(this@DexClassInspectorActivity, "Select an APK first.", Toast.LENGTH_SHORT).show()
                } else if (!inspectionRunning) {
                    val terms = searchInput.text.toString().trim()
                    if (terms.isBlank()) {
                        Toast.makeText(this@DexClassInspectorActivity, "Enter any term, class, method, field, API, ID, or value to search.", Toast.LENGTH_LONG).show()
                    } else {
                        searchAllDex(uri, terms)
                    }
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
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
        root.addView(Button(this).apply {
            text = "Register & value flow tracker"
            setOnClickListener {
                val uri = lastSelectedUri
                val term = searchInput.text.toString().trim()
                if (uri == null) {
                    Toast.makeText(this@DexClassInspectorActivity, "Select an APK first.", Toast.LENGTH_SHORT).show()
                } else if (term.isBlank()) {
                    Toast.makeText(this@DexClassInspectorActivity, "Enter a class/method name, value (for example 0x1), or register (v0) first.", Toast.LENGTH_LONG).show()
                } else {
                    traceRegisterValueFlow(uri, term)
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
        root.addView(Button(this).apply {
            text = "Open report / results page"
            setOnClickListener { showReportPage(true) }
        }, LinearLayout.LayoutParams(-1, -2))
        statusText = TextView(this).apply { text = "No APK selected"; textSize = 14f; setPadding(0, dp(12), 0, dp(8)) }
        root.addView(statusText)
        reportScroll = ScrollView(this)
        resultText = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        reportScroll.addView(resultText, ViewGroup.LayoutParams(-1, -2))
        root.addView(reportScroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
                                            if (out.length >= MAX_REPORT_CHARS / 2) break
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

    /**
     * Render dexlib instructions as readable Smali-like text. Instruction.toString()
     * is not a disassembly API and can return a Java object identity string.
     */
    private fun renderInstruction(instruction: org.jf.dexlib2.iface.instruction.Instruction): String {
        val operands = mutableListOf<String>()
        when (instruction) {
            is RegisterRangeInstruction -> {
                val count = instruction.registerCount
                val end = instruction.startRegister + count - 1
                operands += if (count <= 0) "{}" else "{v${instruction.startRegister} .. v$end}"
            }
            is FiveRegisterInstruction -> {
                // The encoded format has five register slots, but only registerCount
                // of them are real arguments. Printing all slots invents false v0 uses.
                val registers = listOf(
                    instruction.registerC,
                    instruction.registerD,
                    instruction.registerE,
                    instruction.registerF,
                    instruction.registerG
                ).take(instruction.registerCount.coerceIn(0, 5))
                operands += registers.joinToString(prefix = "{", postfix = "}") { "v$it" }
            }
            is ThreeRegisterInstruction -> {
                operands += "v${instruction.registerA}"
                operands += "v${instruction.registerB}"
                operands += "v${instruction.registerC}"
            }
            is TwoRegisterInstruction -> {
                operands += "v${instruction.registerA}"
                operands += "v${instruction.registerB}"
            }
            is OneRegisterInstruction -> operands += "v${instruction.registerA}"
        }
        when (instruction) {
            is NarrowLiteralInstruction -> operands += instruction.narrowLiteral.toString()
            is WideLiteralInstruction -> operands += instruction.wideLiteral.toString()
        }
        if (instruction is OffsetInstruction) operands += ":${if (instruction.codeOffset >= 0) "+" else ""}${instruction.codeOffset}"
        val reference = (instruction as? ReferenceInstruction)?.reference?.toString()
        if (!reference.isNullOrBlank()) operands += reference
        return instruction.opcode.name.lowercase() + if (operands.isEmpty()) "" else " " + operands.joinToString(", ")
    }

    private fun searchAllDex(uri: Uri, query: String) {
        val rawTerm = query.trim()
        if (rawTerm.isEmpty()) { filterReport(""); return }
        val terms = rawTerm.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (inspectionRunning) return
        inspectionRunning = true
        statusText.text = "Searching every supported DEX entry for: " + terms.joinToString(", ")
        resultText.text = "Search started. Every supported classes*.dex entry will be checked sequentially; the report is capped to protect phone memory."
        Thread({
            val output = StringBuilder()
            var dexCount = 0
            var parsedCount = 0
            var skippedCount = 0
            var classCount = 0
            var fieldCount = 0
            var methodCount = 0
            var instructionCount = 0
            var stringCount = 0
            var displayedHits = 0
            var reportTruncated = false
            val reportLimit = MAX_REPORT_CHARS - 1800

            fun emit(line: String = "") {
                if (output.length + line.length + 1 <= reportLimit) {
                    output.appendLine(line)
                } else {
                    reportTruncated = true
                }
            }
            fun matches(vararg values: String?): Boolean =
                values.filterNotNull().any { value -> terms.any { value.contains(it, ignoreCase = true) } }

            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && DEX_NAME.matches(entry.name)) {
                                dexCount++
                                val dexName = entry.name
                                val bytes = readBounded(zip, MAX_DEX_BYTES)
                                if (bytes == null) {
                                    skippedCount++
                                    emit("")
                                    emit("DEX: $dexName")
                                    emit("SKIPPED: DEX exceeds the per-file safety limit of ${MAX_DEX_BYTES / (1024 * 1024)} MB. It was not parsed.")
                                } else {
                                    try {
                                        val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
                                        parsedCount++
                                        emit("")
                                        emit("========== DEX: $dexName ==========")
                                        emit("Declared classes: ${dex.classes.size}")
                                        var dexClassHits = 0
                                        var dexFieldHits = 0
                                        var dexMethodHits = 0
                                        var dexInstructionHits = 0
                                        var dexStringHits = 0

                                        for (clazz in dex.classes) {
                                            classCount++
                                            val className = type(clazz.type)
                                            val superName = clazz.superclass?.let { type(it) } ?: "<none>"
                                            val interfaces = clazz.interfaces.map { type(it) }
                                            val classHit = matches(className, superName, interfaces.joinToString(" "))
                                            val matchingFields = clazz.fields.filter { field ->
                                                matches(field.name, type(field.type), field.initialValue?.toString()) ||
                                                    field.annotations.any { annotation ->
                                                        matches(type(annotation.type), annotation.elements.joinToString { "${it.name}=${it.value}" })
                                                    }
                                            }
                                            val matchingMethods = clazz.methods.filter { method ->
                                                matches(
                                                    method.name,
                                                    type(method.returnType),
                                                    method.parameterTypes.joinToString { type(it.toString()) },
                                                    method.parameters.joinToString { it.name.orEmpty() + ":" + type(it.type) }
                                                ) || method.annotations.any { annotation ->
                                                    matches(type(annotation.type), annotation.elements.joinToString { "${it.name}=${it.value}" })
                                                }
                                            }

                                            if (classHit || matchingFields.isNotEmpty() || matchingMethods.isNotEmpty()) {
                                                dexClassHits++
                                                displayedHits++
                                                emit("")
                                                emit("CLASS RESULT: $className")
                                                emit("  DEX: $dexName")
                                                emit("  Access: ${flags(clazz.accessFlags)} (0x${clazz.accessFlags.toString(16)})")
                                                emit("  Superclass: $superName")
                                                emit("  Interfaces: ${interfaces.joinToString().ifBlank { "<none>" }}")
                                                emit("  Source file: ${clazz.sourceFile ?: "<not recorded>"}")
                                                if (classHit) emit("  Match category: class / hierarchy")
                                                if (classHit) {
                                                    emit("  DECLARED FIELDS (${clazz.fields.count()}):")
                                                    clazz.fields.forEach { field ->
                                                        emit("    ${flags(field.accessFlags)} ${type(field.type)} ${field.name}" +
                                                            (field.initialValue?.let { " = $it" } ?: ""))
                                                    }
                                                    emit("  DECLARED METHODS (${clazz.methods.count()}):")
                                                    clazz.methods.forEach { method ->
                                                        emit("    ${flags(method.accessFlags)} ${type(method.returnType)} ${method.name}(${method.parameterTypes.joinToString { type(it.toString()) }})")
                                                    }
                                                }
                                                matchingFields.forEach { field ->
                                                    fieldCount++
                                                    dexFieldHits++
                                                    emit("  MATCH FIELD: ${flags(field.accessFlags)} ${type(field.type)} ${field.name}")
                                                    emit("    Initial value: ${field.initialValue ?: "<none encoded>"}")
                                                    field.annotations.forEach { annotation ->
                                                        emit("    Annotation: ${type(annotation.type)} ${annotation.elements.joinToString { it.name + "=" + it.value }}")
                                                    }
                                                }
                                                matchingMethods.forEach { method ->
                                                    methodCount++
                                                    dexMethodHits++
                                                    val signature = "${type(method.returnType)} ${className}->${method.name}(${method.parameterTypes.joinToString { type(it.toString()) }})"
                                                    emit("  MATCH METHOD: $signature")
                                                    emit("    Access: ${flags(method.accessFlags)} (0x${method.accessFlags.toString(16)})")
                                                    emit("    Parameters: ${method.parameters.mapIndexed { index, p -> "#$index ${p.name ?: "<unnamed>"}:${type(p.type)}" }.joinToString().ifBlank { "<none>" }}")
                                                    method.annotations.forEach { annotation ->
                                                        emit("    Annotation: ${type(annotation.type)} ${annotation.elements.joinToString { it.name + "=" + it.value }}")
                                                    }
                                                    val impl = method.implementation
                                                    if (impl == null) {
                                                        emit("    Body: <no code item; possibly abstract/native>")
                                                    } else {
                                                        emit("    Registers: ${impl.registerCount}; code items: ${impl.instructions.count()}")
                                                        var address = 0
                                                        var shown = 0
                                                        for (ins in impl.instructions) {
                                                            val rendered = renderInstruction(ins)
                                                            val ref = (ins as? ReferenceInstruction)?.reference?.toString()
                                                            if (matches(rendered, ref)) {
                                                                instructionCount++
                                                                dexInstructionHits++
                                                                displayedHits++
                                                                emit("    MATCH INSTRUCTION @0x${address.toString(16)}: $rendered")
                                                                if (ref != null) emit("      Target reference: $ref")
                                                                shown++
                                                            }
                                                            address += ins.codeUnits
                                                        }
                                                        if (shown == 0) emit("    No matching instruction text in this method; match was in its signature/metadata.")
                                                    }
                                                }
                                            } else {
                                                // Still scan every method body, even after the visible report reaches its cap.
                                                for (method in clazz.methods) {
                                                    val impl = method.implementation ?: continue
                                                    var address = 0
                                                    for (ins in impl.instructions) {
                                                        val rendered = renderInstruction(ins)
                                                        val ref = (ins as? ReferenceInstruction)?.reference?.toString()
                                                        if (matches(rendered, ref)) {
                                                            instructionCount++
                                                            dexInstructionHits++
                                                            displayedHits++
                                                            emit("")
                                                            emit("INSTRUCTION REFERENCE: $className")
                                                            emit("  DEX: $dexName")
                                                            emit("  Method: ${type(method.returnType)} ${method.name}(${method.parameterTypes.joinToString { type(it.toString()) }})")
                                                            emit("  Offset: 0x${address.toString(16)}")
                                                            emit("  Instruction: $rendered")
                                                            if (ref != null) emit("  Target: $ref")
                                                        }
                                                        address += ins.codeUnits
                                                    }
                                                }
                                            }
                                        }

                                        // Scan the complete DEX string pool, not only strings referenced by visible results.
                                        for (ref in dex.stringReferences) {
                                            if (terms.any { ref.string.contains(it, ignoreCase = true) }) {
                                                stringCount++
                                                dexStringHits++
                                                displayedHits++
                                                emit("STRING MATCH [$dexName]: " + ref.string.replace("\n", "\\n").replace("\r", "\\r"))
                                            }
                                        }
                                        emit("DEX SUMMARY: classes checked=$classCount; class results=$dexClassHits; field results=$dexFieldHits; method results=$dexMethodHits; instruction results=$dexInstructionHits; string results=$dexStringHits.")
                                    } catch (oom: OutOfMemoryError) {
                                        throw oom
                                    } catch (ex: Exception) {
                                        skippedCount++
                                        emit("")
                                        emit("DEX: $dexName")
                                        emit("PARSE ERROR: ${ex.message ?: ex.javaClass.simpleName}")
                                    }
                                }
                                val snapshot = synchronized(output) { output.toString() }
                                runOnUiThread {
                                    if (inspectionRunning) {
                                        resultText.text = snapshot
                                        statusText.text = "Scanned $dexName — $parsedCount parsed, $skippedCount skipped; $displayedHits matching items counted"
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: emit("Could not open selected APK.")
            } catch (oom: OutOfMemoryError) {
                emit("")
                emit("SEARCH INCOMPLETE: stopped to protect phone memory. Not every DEX entry could be checked.")
            } catch (ex: Exception) {
                emit("")
                emit("SEARCH INCOMPLETE: ${ex.message ?: ex.javaClass.simpleName}")
            }

            val summary = buildString {
                appendLine()
                appendLine("SEARCH SUMMARY")
                appendLine("DEX entries found=$dexCount; parsed=$parsedCount; skipped/errors=$skippedCount.")
                appendLine("Class definitions checked=$classCount; matching fields=$fieldCount; matching methods=$methodCount.")
                appendLine("Matching instructions=$instructionCount; matching string-pool entries=$stringCount.")
                appendLine("Report output capped=$reportTruncated.")
                if (dexCount == 0) appendLine("No supported classes*.dex entries found in the selected file.")
                if (skippedCount > 0) appendLine("WARNING: Search is incomplete because one or more DEX entries could not be parsed.")
                if (reportTruncated) appendLine("WARNING: The report text reached the memory-safety cap. Scanning continued for remaining DEX entries, but some matching detail lines were omitted. Narrow the search term to display more detail.")
                appendLine("Coverage: every supported classes*.dex ZIP entry was attempted. Individual DEX entries over the per-file byte limit or malformed entries are explicitly counted as skipped.")
                appendLine("This is static analysis; dynamically loaded DEX, native code, reflection-only behavior, and server-side logic may not be visible.")
            }
            val finalText = synchronized(output) { output.toString() + summary }
            runOnUiThread {
                inspectionRunning = false
                exportText = finalText
                displayedReport = finalText
                resultText.text = finalText
                statusText.text = when {
                    dexCount == 0 -> "No supported DEX files found"
                    skippedCount > 0 -> "Search finished — incomplete; see skipped-entry warnings"
                    reportTruncated -> "All DEX entries scanned; report details capped"
                    else -> "Search finished: $displayedHits matching items across $parsedCount DEX files"
                }
                loadMoreButton.isEnabled = lastSelectedUri != null
            }
        }, "DexSearchWorker").start()
    }

    /**
     * Static, intraprocedural register/value-flow view. It follows the latest
     * visible definition of each DEX register and annotates subsequent uses.
     * This is a heuristic bytecode view, not runtime tracing or full SSA analysis.
     */
    private fun traceRegisterValueFlow(uri: Uri, query: String) {
        if (inspectionRunning) return
        inspectionRunning = true
        statusText.text = "Tracing register/value flow for: $query"
        resultText.text = "Scanning DEX methods and following register definitions…"
        Thread({
            val out = StringBuilder()
            val term = query.trim()
            val registerQuery = Regex("(?i)^v(\\d+)$").matchEntire(term)?.groupValues?.get(1)?.toIntOrNull()
            var dexCount = 0
            var parsedCount = 0
            var matchedMethods = 0
            var skipped = 0
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null && out.length < MAX_REPORT_CHARS && matchedMethods < MAX_VALUE_FLOW_METHODS) {
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
                                            if (out.length >= MAX_REPORT_CHARS || matchedMethods >= MAX_VALUE_FLOW_METHODS) break
                                            val className = type(clazz.type)
                                            for (method in clazz.methods) {
                                                if (out.length >= MAX_REPORT_CHARS || matchedMethods >= MAX_VALUE_FLOW_METHODS) break
                                                val impl = method.implementation ?: continue
                                                val signature = className + "->" + method.name + "(" + method.parameterTypes.joinToString { type(it.toString()) } + "):" + type(method.returnType)
                                                val instructions = impl.instructions.toList()
                                                val rendered = instructions.map { renderInstruction(it) }
                                                val hexValue = if (term.startsWith("0x", true)) term.substring(2).toLongOrNull(16) else null
                                                val decimalAlias = hexValue?.toString()
                                                val methodMatches = signature.contains(term, true) ||
                                                    rendered.any { line -> line.contains(term, true) || (decimalAlias != null && line.contains(decimalAlias)) } ||
                                                    (registerQuery != null && rendered.any { it.contains("v" + registerQuery) })
                                                if (!methodMatches) continue

                                                matchedMethods++
                                                out.appendLine("\n========== REGISTER / VALUE FLOW: $dexName ==========")
                                                out.appendLine("METHOD: $signature")
                                                out.appendLine("REGISTERS: " + impl.registerCount + "; instructions: " + instructions.size)
                                                out.appendLine("Legend: DEF = likely destination register; USE = register's latest earlier visible definition.")
                                                val latestDefinition = mutableMapOf<Int, Pair<Int, String>>()
                                                var address = 0
                                                for (index in instructions.indices) {
                                                    if (out.length >= MAX_REPORT_CHARS) break
                                                    val instruction = instructions[index]
                                                    val opcode = instruction.opcode.name
                                                    val line = rendered[index]
                                                    val destination = if (
                                                        opcode.startsWith("CONST") ||
                                                        opcode.startsWith("MOVE") ||
                                                        opcode.startsWith("NEW_") ||
                                                        opcode.startsWith("SGET") ||
                                                        opcode.startsWith("IGET") ||
                                                        opcode.startsWith("AGET") ||
                                                        opcode.startsWith("ARRAY_LENGTH") ||
                                                        opcode.startsWith("INSTANCE_OF") ||
                                                        opcode.startsWith("CHECK_CAST") ||
                                                        opcode.startsWith("CMP") ||
                                                        opcode.startsWith("ADD_") ||
                                                        opcode.startsWith("SUB_") ||
                                                        opcode.startsWith("MUL_") ||
                                                        opcode.startsWith("DIV_") ||
                                                        opcode.startsWith("REM_") ||
                                                        opcode.startsWith("AND_") ||
                                                        opcode.startsWith("OR_") ||
                                                        opcode.startsWith("XOR_") ||
                                                        opcode.startsWith("SHL_") ||
                                                        opcode.startsWith("SHR_") ||
                                                        opcode.startsWith("USHR_") ||
                                                        opcode.startsWith("NEG_") ||
                                                        opcode.startsWith("NOT_") ||
                                                        opcode.endsWith("_TO_INT") ||
                                                        opcode.endsWith("_TO_LONG") ||
                                                        opcode.endsWith("_TO_FLOAT") ||
                                                        opcode.endsWith("_TO_DOUBLE") ||
                                                        opcode.endsWith("_TO_BYTE") ||
                                                        opcode.endsWith("_TO_CHAR") ||
                                                        opcode.endsWith("_TO_SHORT")
                                                    ) (instruction as? OneRegisterInstruction)?.registerA
                                                        ?: (instruction as? TwoRegisterInstruction)?.registerA
                                                        ?: (instruction as? ThreeRegisterInstruction)?.registerA
                                                    else null
                                                    val regs = Regex("v(\\d+)").findAll(line)
                                                        .mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
                                                    val sourceRegs = if (destination != null && regs.firstOrNull() == destination) regs.drop(1) else regs
                                                    val useNotes = sourceRegs.mapNotNull { reg ->
                                                        latestDefinition[reg]?.let { (defIndex, defText) -> "v" + reg + " from @" + defIndex + " (" + defText + ")" }
                                                    }
                                                    val isRelevantRegister = registerQuery == null ||
                                                        destination == registerQuery || sourceRegs.contains(registerQuery)
                                                    val isRelevantLine = line.contains(term, true) ||
                                                        signature.contains(term, true) ||
                                                        (registerQuery != null && isRelevantRegister)
                                                    val defText = if (destination != null) "DEF v" + destination else null
                                                    if (isRelevantLine || useNotes.isNotEmpty() || opcode.startsWith("MOVE_RESULT")) {
                                                        out.appendLine("%04x  %s".format(address, line))
                                                        if (defText != null) out.appendLine("      $defText")
                                                        if (useNotes.isNotEmpty()) out.appendLine("      USE " + useNotes.joinToString("; "))
                                                        // DEX requires move-result immediately after the producing call.
                                                        // Surface that link so the result register isn't mistaken for an unrelated value.
                                                        if (opcode.startsWith("MOVE_RESULT") && index > 0) {
                                                            val producer = instructions[index - 1]
                                                            val producerOpcode = producer.opcode.name
                                                            if (producerOpcode.startsWith("INVOKE") || producerOpcode == "FILLED_NEW_ARRAY" || producerOpcode == "FILLED_NEW_ARRAY_RANGE") {
                                                                val producerAddress = address - producer.codeUnits
                                                                out.appendLine("      RESULT OF @%04x: %s".format(producerAddress, rendered[index - 1]))
                                                            }
                                                        }
                                                    }
                                                    if (destination != null) latestDefinition[destination] = index to line
                                                    address += instruction.codeUnits
                                                }
                                            }
                                        }
                                    } catch (ex: Exception) {
                                        skipped++
                                        out.appendLine("\nDEX $dexName: parse failed (" + (ex.message ?: ex.javaClass.simpleName) + ")")
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: out.appendLine("Could not open selected APK.")
            } catch (oom: OutOfMemoryError) {
                out.appendLine("\nFlow scan stopped to protect device memory.")
            } catch (ex: Exception) {
                out.appendLine("\nFlow scan incomplete: " + (ex.message ?: ex.javaClass.simpleName))
            }
            if (matchedMethods == 0) out.appendLine("\nNo methods matched '$term'. Try a class/method name, a value such as 0x1, or a register such as v0.")
            out.appendLine("\nFLOW SUMMARY: DEX entries=" + dexCount + "; parsed=" + parsedCount + "; methods shown=" + matchedMethods + "; skipped=" + skipped + ".")
            out.appendLine("Limitations: flow is static and method-local. Branch merges, wide register pairs, exception paths, reflection, native code, and runtime/server decisions are not fully resolved. A constant such as 0x1 is not by itself proof of a premium or entitlement decision.")
            val report = out.toString().take(MAX_REPORT_CHARS)
            runOnUiThread {
                inspectionRunning = false
                exportText = report
                displayedReport = report
                resultText.text = report
                statusText.text = "Value flow complete — " + matchedMethods + " methods; " + skipped + " skipped"
                loadMoreButton.isEnabled = lastSelectedUri != null
            }
        }, "DexValueFlowWorker").start()
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
                    val out = StringBuilder()
                    var dexCount = 0
                    var parsedCount = 0
                    var skippedCount = 0
                    out.appendLine("DEX CLASS INSPECTOR REPORT")
                    out.appendLine("APK: ${uri.lastPathSegment ?: "Selected APK"}")
                    out.appendLine("DEX processing: one file at a time to reduce peak memory.")
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null && out.length < MAX_REPORT_CHARS && dexCount < MAX_INSPECT_DEX_FILES) {
                            if (!entry.isDirectory && DEX_NAME.matches(entry.name)) {
                                dexCount++
                                val name = entry.name
                                val bytes = readBounded(zip, MAX_DEX_BYTES)
                                if (bytes == null) {
                                    skippedCount++
                                    out.appendLine("\\nDEX: $name — skipped (over $MAX_DEX_BYTES byte per-file safety limit)")
                                } else {
                                    try {
                                        val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
                                        parsedCount++
                                        out.appendLine("\\n================ DEX: $name ================")
                                        val classes = dex.classes
                                        out.appendLine("Class count: ${classes.size}")
                                        var emitted = 0
                                        for (clazz in classes) {
                                            if (emitted >= classLimitPerDex || out.length >= MAX_REPORT_CHARS) break
                                            out.appendClass(clazz)
                                            emitted++
                                        }
                                        if (emitted < classes.size) out.appendLine("[Classes displayed: $emitted / ${classes.size}; current per-DEX limit: $classLimitPerDex]")
                                        if (out.length < MAX_REPORT_CHARS) {
                                            out.appendLine("\\n============= DEX STRING POOL (first $MAX_STRINGS) =============")
                                            var stringCount = 0
                                            for (ref in dex.stringReferences) {
                                                if (stringCount++ >= MAX_STRINGS || out.length >= MAX_REPORT_CHARS) break
                                                out.appendLine(ref.string.replace("\\n", "\\\\n").replace("\\r", "\\\\r"))
                                            }
                                        }
                                    } catch (oom: OutOfMemoryError) {
                                        throw oom
                                    } catch (ex: Exception) {
                                        skippedCount++
                                        out.appendLine("Could not parse $name: ${ex.message ?: ex.javaClass.simpleName}")
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                    if (dexCount == 0) out.appendLine("No supported DEX files found. Confirm the selected file is an APK or DEX file.")
                    out.appendLine("\\nDEX entries found: $dexCount; parsed: $parsedCount; skipped/errors: $skippedCount")
                    out.appendLine("NOTE: Output is bounded for device stability.")
                    if (out.length > MAX_REPORT_CHARS) out.setLength(MAX_REPORT_CHARS)
                    out.toString()
                } ?: "Could not open selected file."
            } catch (oom: OutOfMemoryError) {
                "Inspection stopped to protect the phone from running out of memory. Try a smaller APK or lower the class limit."
            } catch (ex: Exception) {
                "Could not inspect APK: ${ex.message ?: ex.javaClass.simpleName}"
            }
            runOnUiThread {
                inspectionRunning = false
                loadMoreButton.isEnabled = lastSelectedUri != null && classLimitPerDex < MAX_CLASSES_PER_DEX_HARD
                loadMoreButton.text = if (classLimitPerDex >= MAX_CLASSES_PER_DEX_HARD) "Class limit reached ($classLimitPerDex per DEX)" else "Load more classes (up to $classLimitPerDex per DEX)"
                exportText = report
                displayedReport = report
                statusText.text = if (report.startsWith("Could not") || report.startsWith("No supported DEX") || report.startsWith("Inspection stopped")) "Inspection did not complete" else "Inspection complete (some output may be limited)"
                resultText.text = report
            }
        }, "DexInspectorWorker").start()
    }

    private fun showReportPage(show: Boolean) {
        showingReportPage = show
        toolbar.title = if (show) "APK Report / Results" else "DEX Class Inspector"
        val root = toolbar.parent as? ViewGroup ?: return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child === toolbar) continue
            val isReport = child === statusText || child === reportScroll
            val isOpenReportButton = child is Button && child.text.toString() == "Open report / results page"
            child.visibility = if (show) {
                if (isReport) View.VISIBLE else View.GONE
            } else {
                if (isReport) View.GONE else View.VISIBLE
            }
            if (isOpenReportButton) child.visibility = if (show) View.GONE else View.VISIBLE
        }
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
        c.annotations.take(30).forEach { a ->
            if (length < MAX_REPORT_CHARS) appendLine("  @${type(a.type)} visibility=${a.visibility}")
            a.elements.take(30).forEach { if (length < MAX_REPORT_CHARS) appendLine("    ${it.name} = ${it.value}") }
        }
        val fields = c.fields
        appendLine("\nFIELDS:")
        fields.forEach { if (length < MAX_REPORT_CHARS) appendField(it) }
        if (length >= MAX_REPORT_CHARS) return
        val methods = c.methods
        appendLine("\nMETHODS / CONSTRUCTORS:")
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
            p.annotations.take(20).forEach { if (length < MAX_REPORT_CHARS) appendLine("      @${type(it.type)}") }
        }
        m.annotations.take(20).forEach { a ->
            if (length < MAX_REPORT_CHARS) appendLine("    @${type(a.type)}")
            a.elements.take(30).forEach { if (length < MAX_REPORT_CHARS) appendLine("      ${it.name} = ${it.value}") }
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
                appendLine("      %04x: %s".format(address, renderInstruction(ins)))
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
        private const val MAX_INSPECT_DEX_FILES = 100000
        private const val MAX_INSPECT_TOTAL_MB = 32
        private const val MAX_INSPECT_TOTAL_BYTES = MAX_INSPECT_TOTAL_MB * 1024 * 1024
        private const val MAX_DEX_BYTES = 16 * 1024 * 1024
        private const val INITIAL_CLASSES_PER_DEX = 120
        private const val MAX_CLASSES_PER_DEX_HARD = 10000
        private const val MAX_INSTRUCTIONS = 150
        private const val MAX_STRINGS = 2000
        private const val MAX_REPORT_CHARS = 600_000
        private const val MAX_REFERENCE_HITS = 7500
        private const val MAX_VALUE_FLOW_METHODS = 500
        private val DEX_NAME = Regex("(^|.*/)classes([0-9]+)?\\.dex")
    }
}
