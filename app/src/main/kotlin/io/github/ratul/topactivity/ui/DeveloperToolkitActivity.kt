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
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Local APK developer toolkit: bounded DEX class browser, method/field details,
 * inheritance relationships, APK manifest metadata, bookmarks, history and JSON export.
 */
class DeveloperToolkitActivity : AppCompatActivity() {
    private data class ClassRecord(
        val dexName: String,
        val name: String,
        val superName: String?,
        val interfaces: List<String>,
        val access: String,
        val fields: List<String>,
        val methods: List<String>
    )

    private val classes = mutableListOf<ClassRecord>()
    private var selectedApkName = ""
    private var apkSummary = "Choose an APK to begin."
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private lateinit var bookmarkButton: Button
    private var selectedClass: ClassRecord? = null
    private var inspectionRunning = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            inspect(uri)
        }
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(buildExportJson()) }
                Toast.makeText(this, "JSON report saved.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(12))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "Developer Toolkit"
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(TextView(this).apply {
            text = "Inspect APK metadata and DEX classes locally. Tap a class to inspect methods, fields and inheritance. Results are bounded to protect phone memory."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(10))
        })
        val topButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topButtons.addView(Button(this).apply {
            text = "Select APK"
            setOnClickListener { if (!inspectionRunning) picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topButtons.addView(Button(this).apply {
            text = "APK Analyzer"
            setOnClickListener { showApkSummary() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(topButtons)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Bookmarks"
            setOnClickListener { showBookmarks() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "History"
            setOnClickListener { showHistory() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "Export JSON"
            setOnClickListener {
                if (classes.isEmpty()) toast("Select an APK first.") else exportPicker.launch("developer-toolkit-report.json")
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        root.addView(Button(this).apply {
            text = "Network / Server API Clues"
            setOnClickListener { showNetworkClues() }
        }, LinearLayout.LayoutParams(-1, -2))
        search = EditText(this).apply {
            hint = "Filter classes by name or package"
            maxLines = 1
            setSingleLine(true)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "Apply class filter"
            setOnClickListener { renderClasses(search.text.toString()) }
        }, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            text = "No APK selected"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        if (!apkPath.isNullOrBlank()) {
            val apkFile = File(apkPath)
            val targetClass = intent.getStringExtra(EXTRA_CLASS_NAME).orEmpty()
            if (apkFile.isFile && apkFile.canRead()) {
                search.setText(targetClass)
                inspect(Uri.fromFile(apkFile), targetClass)
            } else {
                status.text = "Could not read the installed APK. Use Select APK to choose it manually."
                toast("APK access unavailable. Select the APK manually.")
            }
        }
    }

    private fun inspect(uri: Uri, targetClassName: String? = null) {
        if (inspectionRunning) return
        inspectionRunning = true
        status.text = "Reading APK, manifest metadata and DEX classes…"
        list.removeAllViews()
        Thread {
            var tempApk: File? = null
            val result = try {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.take(120) ?: "Selected APK"
                val parsed = mutableListOf<ClassRecord>()
                val dexSummary = mutableListOf<String>()
                contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        var dexCount = 0
                        while (entry != null && dexCount < MAX_DEX_FILES && parsed.size < MAX_CLASSES_TOTAL) {
                            if (!entry.isDirectory && DEX_REGEX.matches(entry.name)) {
                                dexCount++
                                val bytes = readBounded(zip, MAX_DEX_BYTES)
                                if (bytes == null) {
                                    dexSummary.add("${entry.name}: skipped (over ${MAX_DEX_BYTES / 1048576} MB limit)")
                                } else {
                                    try {
                                        val dex = DexBackedDexFile(Opcodes.getDefault(), bytes)
                                        val dexClasses = dex.classes
                                        dexSummary.add("${entry.name}: ${dexClasses.size} classes")
                                        for (c in dexClasses) {
                                            if (parsed.size >= MAX_CLASSES_TOTAL) break
                                            parsed.add(c.toRecord(entry.name))
                                        }
                                    } catch (e: Exception) {
                                        dexSummary.add("${entry.name}: parse error - ${e.message ?: "unknown"}")
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                        if (dexCount == 0) dexSummary.add("No classes.dex file found.")
                        if (dexCount >= MAX_DEX_FILES) dexSummary.add("DEX file scan limit reached ($MAX_DEX_FILES).")
                        if (parsed.size >= MAX_CLASSES_TOTAL) dexSummary.add("Class scan limit reached ($MAX_CLASSES_TOTAL).")
                    }
                } ?: throw IllegalStateException("Could not open selected APK.")
                tempApk = File(cacheDir, "toolkit-${System.currentTimeMillis()}.apk")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempApk!!).use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_APK_COPY_BYTES) throw IllegalStateException("APK is over the 100 MB metadata analysis limit.")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val info = packageManager.getPackageArchiveInfo(
                        tempApk!!.absolutePath,
                        android.content.pm.PackageManager.GET_ACTIVITIES or
                            android.content.pm.PackageManager.GET_SERVICES or
                            android.content.pm.PackageManager.GET_RECEIVERS or
                            android.content.pm.PackageManager.GET_PROVIDERS or
                            android.content.pm.PackageManager.GET_PERMISSIONS
                    )
                    val meta = if (info == null) "Manifest metadata: Android PackageManager could not parse this APK." else buildString {
                        appendLine("App package: ${info.packageName ?: "unknown"}")
                        appendLine("Version: ${info.versionName ?: "unknown"}")
                        appendLine("Version code: ${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode}")
                        appendLine("Requested permissions (${info.requestedPermissions?.size ?: 0}):")
                        info.requestedPermissions?.take(MAX_METADATA_ITEMS)?.forEach { appendLine("  • $it") }
                        appendLine("Activities (${info.activities?.size ?: 0}):")
                        info.activities?.take(MAX_METADATA_ITEMS)?.forEach { appendLine("  • ${it.name}") }
                        appendLine("Services (${info.services?.size ?: 0}):")
                        info.services?.take(MAX_METADATA_ITEMS)?.forEach { appendLine("  • ${it.name}") }
                        appendLine("Receivers (${info.receivers?.size ?: 0}):")
                        info.receivers?.take(MAX_METADATA_ITEMS)?.forEach { appendLine("  • ${it.name}") }
                        appendLine("Providers (${info.providers?.size ?: 0}):")
                        info.providers?.take(MAX_METADATA_ITEMS)?.forEach { appendLine("  • ${it.name}") }
                    }
                    Triple(name, parsed, "APK ANALYZER\n$meta\nDEX FILES\n${dexSummary.joinToString("\n")}")
                } catch (e: Exception) {
                    Triple(name, parsed, "APK ANALYZER\nManifest metadata unavailable: ${e.message ?: "unknown error"}\nDEX FILES\n${dexSummary.joinToString("\n")}")
                }
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                runOnUiThread {
                    inspectionRunning = false
                    status.text = "Inspection failed: ${e.message ?: "unknown error"}"
                    toast("Could not inspect this APK.")
                }
                null
            } finally {
                tempApk?.delete()
            }
            if (result != null) {
                runOnUiThread {
                    selectedApkName = result.first
                    classes.clear()
                    classes.addAll(result.second)
                    apkSummary = result.third
                    recordHistory("APK: $selectedApkName (${classes.size} classes)")
                    inspectionRunning = false
                    status.text = "$selectedApkName — ${classes.size} classes loaded"
                    val target = targetClassName?.takeIf { it.isNotBlank() }
                    renderClasses(target ?: "")
                    if (target != null) {
                        val match = classes.firstOrNull { it.name == target }
                        if (match != null) {
                            search.setText(target)
                            showClass(match)
                        } else {
                            search.setText(target)
                            renderClasses(target)
                            toast("The activity class was not found in the scanned DEX files. Search results are shown.")
                        }
                    }                }
            } else if (inspectionRunning) {
                runOnUiThread {
                    inspectionRunning = false
                    status.text = "APK too large for available memory or could not be parsed. Try a smaller APK."
                }
            }
        }.start()
    }

    private fun ClassDef.toRecord(dexName: String): ClassRecord {
        return ClassRecord(
            dexName = dexName,
            name = descriptorToType(type),
            superName = superclass?.let { descriptorToType(it) },
            interfaces = interfaces.map { descriptorToType(it) },
            access = accessFlags(accessFlags),
            fields = fields.take(MAX_MEMBERS_PER_CLASS).map { "${accessFlags(it.accessFlags)} ${descriptorToType(it.type)} ${it.name}" },
            methods = methods.take(MAX_MEMBERS_PER_CLASS).map { method ->
                val args = method.parameterTypes.joinToString(", ") { descriptorToType(it.toString()) }
                "${accessFlags(method.accessFlags)} ${descriptorToType(method.returnType)} ${method.name}($args)" +
                    if (method.implementation == null) " [no code]" else " [${method.implementation!!.instructions.count()} instructions]"
            }
        )
    }

    private fun renderClasses(query: String) {
        list.removeAllViews()
        val q = query.trim()
        val visible = classes.filter { q.isEmpty() || it.name.contains(q, true) || it.dexName.contains(q, true) }
        status.text = "$selectedApkName — ${visible.size} of ${classes.size} classes shown"
        visible.take(MAX_VISIBLE_ROWS).forEach { record ->
            val row = Button(this).apply {
                text = record.name + if (isBookmarked(record.name)) "  ★" else ""
                textAlignment = TextView.TEXT_ALIGNMENT_TEXT_START
                setOnClickListener { showClass(record) }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (visible.size > MAX_VISIBLE_ROWS) {
            list.addView(TextView(this).apply { text = "Showing first $MAX_VISIBLE_ROWS matches. Refine the filter to find more."; setPadding(dp(8), dp(8), dp(8), dp(8)) })
        }
    }

    private fun showClass(record: ClassRecord) {
        selectedClass = record
        val children = classes.filter { it.superName == record.name || record.name in it.interfaces }.map { it.name }
        val details = buildString {
            appendLine(record.name)
            appendLine("DEX: ${record.dexName}")
            appendLine("Access: ${record.access}")
            appendLine("Superclass: ${record.superName ?: "<none>"}")
            appendLine("Interfaces: ${record.interfaces.joinToString().ifBlank { "<none>" }}")
            appendLine("Subclasses / implementers in scanned classes: ${children.joinToString().ifBlank { "<none found>" }}")
            appendLine("\nFIELDS (${record.fields.size}):")
            record.fields.forEach { appendLine("  $it") }
            appendLine("\nMETHODS / CONSTRUCTORS (${record.methods.size}):")
            record.methods.forEach { appendLine("  $it") }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        container.addView(TextView(this).apply { text = details; textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) })
        val scroll = ScrollView(this)
        scroll.addView(container)
        bookmarkButton = Button(this).apply {
            text = if (isBookmarked(record.name)) "Remove bookmark" else "Bookmark class"
            setOnClickListener {
                toggleBookmark(record.name)
                text = if (isBookmarked(record.name)) "Remove bookmark" else "Bookmark class"
                renderClasses(search.text.toString())
            }
        }
        container.addView(bookmarkButton)
        val share = Button(this).apply {
            text = "Copy class details"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Class details", details))
                toast("Class details copied.")
            }
        }
        container.addView(share)
        recordHistory("Class: ${record.name}")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Class details")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showNetworkClues() {
        val hints = linkedSetOf<String>()
        classes.forEach { record ->
            val className = record.name
            if (NETWORK_CLASS_HINTS.any { className.contains(it, true) }) {
                hints.add("Network-related class/library: $className")
            }
            record.methods.forEach { method ->
                if (NETWORK_METHOD_HINTS.any { method.contains(it, true) }) {
                    hints.add("Possible request/callback method in $className: $method")
                }
            }
            record.fields.forEach { field ->
                if (NETWORK_FIELD_HINTS.any { field.contains(it, true) }) {
                    hints.add("Possible endpoint/config field in $className: $field")
                }
            }
        }
        val report = buildString {
            appendLine("STATIC NETWORK / SERVER INDICATORS")
            appendLine("These are clues, not proof that a specific tap contacted a server.")
            appendLine("The server's private implementation is not contained in the APK.")
            appendLine("HTTPS payloads are encrypted; runtime network tracing is needed to correlate requests with a tap.")
            appendLine()
            if (classes.isEmpty()) appendLine("Select an APK first.")
            else if (hints.isEmpty()) appendLine("No obvious network/API indicators found in the scanned class names, methods or fields.")
            else hints.take(MAX_NETWORK_CLUES).forEach { appendLine("• $it") }
            if (hints.size > MAX_NETWORK_CLUES) appendLine("Showing first $MAX_NETWORK_CLUES indicators.")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Network / Server API Clues")
            .setMessage(report)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Network API clues", report))
                toast("Network/API clues copied.")
            }.show()
    }

    private fun showApkSummary() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("APK Analyzer")
            .setMessage(apkSummary)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("APK analysis", apkSummary))
                toast("APK summary copied.")
            }.show()
    }

    private fun showBookmarks() {
        val names = prefs().getStringSet(KEY_BOOKMARKS, emptySet())?.toList()?.sorted().orEmpty()
        if (names.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Bookmarks").setMessage("No bookmarked classes yet. Inspect an APK and tap a class to bookmark it.").setPositiveButton("OK", null).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Bookmarked classes")
            .setItems(names.toTypedArray()) { _, index ->
                val found = classes.firstOrNull { it.name == names[index] }
                if (found != null) showClass(found) else toast("Load the APK containing this class to inspect it.")
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showHistory() {
        val items = prefs().getString(KEY_HISTORY, "").orEmpty().split("\n").filter { it.isNotBlank() }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Developer Toolkit history")
            .setItems((items.ifEmpty { listOf("No history yet") }).toTypedArray()) { _, index ->
                if (items.isNotEmpty()) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("History item", items[index]))
                    toast("History item copied.")
                }
            }
            .setNeutralButton("Clear") { _, _ -> prefs().edit().remove(KEY_HISTORY).apply(); toast("History cleared.") }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun recordHistory(value: String) {
        val timestamped = "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())} — $value"
        val old = prefs().getString(KEY_HISTORY, "").orEmpty().split("\n").filter { it.isNotBlank() }
        prefs().edit().putString(KEY_HISTORY, (listOf(timestamped) + old).take(MAX_HISTORY).joinToString("\n")).apply()
    }

    private fun isBookmarked(name: String): Boolean = prefs().getStringSet(KEY_BOOKMARKS, emptySet())?.contains(name) == true
    private fun toggleBookmark(name: String) {
        val set = prefs().getStringSet(KEY_BOOKMARKS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!set.add(name)) set.remove(name)
        prefs().edit().putStringSet(KEY_BOOKMARKS, set).apply()
        recordHistory((if (name in set) "Bookmarked: " else "Unbookmarked: ") + name)
    }

    private fun buildExportJson(): String {
        val root = JSONObject()
        root.put("apk", selectedApkName)
        root.put("classCount", classes.size)
        root.put("apkAnalysis", apkSummary)
        val array = JSONArray()
        classes.forEach { c ->
            array.put(JSONObject().apply {
                put("dex", c.dexName)
                put("name", c.name)
                put("access", c.access)
                put("superclass", c.superName ?: JSONObject.NULL)
                put("interfaces", JSONArray(c.interfaces))
                put("fields", JSONArray(c.fields))
                put("methods", JSONArray(c.methods))
                put("bookmarked", isBookmarked(c.name))
            })
        }
        root.put("classes", array)
        return root.toString(2)
    }

    private fun prefs() = getSharedPreferences("developer_toolkit", MODE_PRIVATE)
    private fun descriptorToType(value: String): String {
        if (value.startsWith("L") && value.endsWith(";")) return value.substring(1, value.length - 1).replace('/', '.')
        if (value.startsWith("[")) return descriptorToType(value.substring(1)) + "[]"
        return when (value) {
            "V" -> "void"; "Z" -> "boolean"; "B" -> "byte"; "S" -> "short"; "C" -> "char"
            "I" -> "int"; "J" -> "long"; "F" -> "float"; "D" -> "double"; else -> value
        }
    }
    private fun accessFlags(flags: Int): String {
        val values = listOf(1 to "public", 2 to "private", 4 to "protected", 8 to "static", 16 to "final", 512 to "interface", 1024 to "abstract", 16384 to "enum", 4096 to "synthetic")
        return values.filter { flags and it.first != 0 }.joinToString(" ").ifBlank { "package-private" }
    }
    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_NETWORK_CLUES = 250
        private val NETWORK_CLASS_HINTS = listOf("okhttp", "retrofit", "volley", "ktor", "firebase", "graphql", "webview", "socket", "grpc", "httpurlconnection", "api.client", "network")
        private val NETWORK_METHOD_HINTS = listOf("request", "execute", "enqueue", "connect", "fetch", "loadurl", "graphql", "websocket", "send", "getresponse", "post", "callapi")
        private val NETWORK_FIELD_HINTS = listOf("base_url", "baseurl", "endpoint", "api_url", "server_url", "host", "token", "authorization")
        const val EXTRA_APK_PATH = "developer_toolkit_apk_path"
        const val EXTRA_PACKAGE_NAME = "developer_toolkit_package_name"
        const val EXTRA_CLASS_NAME = "developer_toolkit_class_name"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 100
        private const val MAX_CLASSES_TOTAL = 20000
        private const val MAX_VISIBLE_ROWS = 800
        private const val MAX_DEX_FILES = 60
        private const val MAX_DEX_BYTES = 32 * 1024 * 1024
        private const val MAX_APK_COPY_BYTES = 100L * 1024 * 1024
        private const val MAX_MEMBERS_PER_CLASS = 300
        private const val MAX_METADATA_ITEMS = 500
        private val DEX_REGEX = Regex("(^|.*/)classes([0-9]+)?\\.dex")
    }
}
