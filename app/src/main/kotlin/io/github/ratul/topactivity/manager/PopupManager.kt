/*
 *   Copyright (C) 2022 Ratul Hasan
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.ratul.topactivity.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import io.github.ratul.topactivity.App
import io.github.ratul.topactivity.R
import io.github.ratul.topactivity.extensions.getScreenSize
import io.github.ratul.topactivity.extensions.value
import io.github.ratul.topactivity.repository.DataRepository
import io.github.ratul.topactivity.utils.DatabaseUtil
import io.github.ratul.topactivity.utils.WindowManagerUtil.getLayoutParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PopupManager(private val context: Context) {

    private val popupScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var baseView: View? = null
    private var historyManager: HistoryManager? = null

    fun show() {
        if (baseView != null) return

        val view = LayoutInflater.from(context)
            .inflate(R.layout.layout_activity_info, null)
        baseView = view

        val (displayWidth, _) = windowManager.getScreenSize()
        val scaleFactor = mapPreferenceToWindowSize(DatabaseUtil.windowSize)
        val viewSize = (displayWidth * scaleFactor).toInt()

        val layoutParams = getLayoutParams()
        layoutParams.gravity = Gravity.CENTER
        layoutParams.width = viewSize

        try {
            windowManager.addView(view, layoutParams)
        } catch (e: SecurityException) {
            baseView = null
            DataRepository.updateStatus(false)
            Toast.makeText(context, "Floating window permission is unavailable. Enable display-over-other-apps permission and try again.", Toast.LENGTH_LONG).show()
            return
        } catch (e: WindowManager.BadTokenException) {
            baseView = null
            DataRepository.updateStatus(false)
            Toast.makeText(context, "Android rejected the floating window. Please try starting monitoring again.", Toast.LENGTH_LONG).show()
            return
        }

        val appName = view.findViewById<TextView>(R.id.app_name)
        val packageName = view.findViewById<TextView>(R.id.package_name)
        val className = view.findViewById<TextView>(R.id.class_name)
        val clickedElementInfo = view.findViewById<TextView>(R.id.clicked_element_info)
        val closeBtn = view.findViewById<ImageView>(R.id.closeBtn)
        val historyBtn = view.findViewById<ImageView>(R.id.historyBtn)
        val shareBtn = view.findViewById<TextView>(R.id.shareBtn)

        val copyAction: (View) -> Unit = { target ->
            val textView = target as TextView
            val message = when (target.id) {
                R.id.package_name -> context.getString(R.string.package_copied)
                R.id.class_name -> context.getString(R.string.class_copied)
                else -> context.getString(R.string.copied)
            }
            App.copyString(context, textView.value(), message)
        }

        val copyLongPress = View.OnLongClickListener { target ->
            copyAction(target)
            true
        }

        closeBtn.setOnClickListener { DataRepository.updateStatus(false) }
        historyBtn.setOnClickListener {
            if (historyManager?.isActive() == true) return@setOnClickListener
            historyManager = HistoryManager(context)
            historyManager?.show()
        }
        shareBtn.setOnClickListener {
            val state = DataRepository.appState.value
            val details = "Current Activity\nApp: ${appName.text}\nPackage: ${state.pkg}\nActivity class: ${state.cls}\nLast tapped element: ${state.clickedElement}"
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Current Activity details")
                putExtra(Intent.EXTRA_TEXT, details)
            }
            context.startActivity(
                Intent.createChooser(sendIntent, "Share current activity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        packageName.setOnClickListener(copyAction)
        className.setOnClickListener {
            val state = DataRepository.appState.value
            try {
                val appInfo = context.packageManager.getApplicationInfo(state.pkg, 0)
                val intent = Intent(context, io.github.ratul.topactivity.ui.DeveloperToolkitActivity::class.java).apply {
                    putExtra(io.github.ratul.topactivity.ui.DeveloperToolkitActivity.EXTRA_APK_PATH, appInfo.sourceDir)
                    putExtra(io.github.ratul.topactivity.ui.DeveloperToolkitActivity.EXTRA_PACKAGE_NAME, state.pkg)
                    putExtra(io.github.ratul.topactivity.ui.DeveloperToolkitActivity.EXTRA_CLASS_NAME, state.cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                App.copyString(context, className.value(), "Could not open the app APK. Class name copied instead.")
            }
        }
        packageName.setOnLongClickListener(copyLongPress)
        className.setOnLongClickListener(copyLongPress)
        view.setOnTouchListener(DragTouchManager(windowManager, layoutParams))

        val serviceState = DataRepository.appState.value
        val defaultAppName = context.getString(R.string.unknown)
        className.text = serviceState.cls
        packageName.text = serviceState.pkg
        appName.text = getAppName(serviceState.pkg) ?: defaultAppName
        clickedElementInfo.text = serviceState.clickedElement.ifBlank { serviceState.accessibilityDiagnostic }

        popupScope.launch {
            DataRepository.appState.collectLatest { state ->
                if (!state.running) {
                    hide()
                    return@collectLatest
                }

                val isPackageChanged = state.pkg != packageName.value()
                val isClassChanged = state.cls != className.value()
                clickedElementInfo.text = state.clickedElement.ifBlank { state.accessibilityDiagnostic }

                if (isClassChanged) {
                    className.text = state.cls
                }

                if (isPackageChanged) {
                    packageName.text = state.pkg
                    appName.text = defaultAppName
                    val fetchedData = withContext(Dispatchers.IO) {
                        getAppName(state.pkg) ?: defaultAppName
                    }
                    appName.text = fetchedData
                }
            }
        }
    }

    private fun hide() {
        baseView?.let {
            try {
                if (it.isAttachedToWindow) windowManager.removeView(it)
            } catch (_: IllegalArgumentException) {
                // The window was already detached by the system.
            } catch (_: WindowManager.BadTokenException) {
                // The window token is no longer valid.
            } finally {
                baseView = null
            }
        }
        popupScope.cancel()
    }

    private fun getAppName(pkg: String): String? {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    companion object {
        fun mapPreferenceToWindowSize(value: String): Double {
            return when (value) {
                "0" -> 0.80
                "1" -> 0.65
                else -> 0.50
            }
        }
    }
}