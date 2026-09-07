package com.example.taskbartoggle

import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Native Android Accessibility Service that hooks directly into the system navigation
 * gesture framework and multi-window actions with 0 overlay touch interference.
 */
class TaskbarAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "TaskbarA11yService"
        var isRunning = false
            private set
        private var instance: TaskbarAccessibilityService? = null

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${TaskbarAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true) ||
                    componentName.endsWith(TaskbarAccessibilityService::class.java.simpleName)
                ) {
                    return true
                }
            }
            return false
        }

        fun toggleSplitScreen(): Boolean {
            val current = instance ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                current.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            } else {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        Log.i(TAG, "TaskbarAccessibilityService connected")
    }

    override fun onGesture(gestureId: Int): Boolean {
        Log.d(TAG, "onGesture (Legacy) received: $gestureId")
        when (gestureId) {
            GESTURE_SWIPE_UP_AND_DOWN,
            GESTURE_SWIPE_DOWN_AND_UP -> {
                TaskbarService.showTransientDock(this)
                return true
            }
            GESTURE_SWIPE_UP -> {
                // Also support upward swipe if configured
                TaskbarService.showTransientDock(this)
                return true
            }
        }
        return super.onGesture(gestureId)
    }

    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val gestureId = gestureEvent.gestureId
            Log.d(TAG, "onGesture (API 30+) received: $gestureId")
            when (gestureId) {
                GESTURE_SWIPE_UP_AND_DOWN,
                GESTURE_SWIPE_DOWN_AND_UP -> {
                    TaskbarService.showTransientDock(this)
                    return true
                }
                GESTURE_SWIPE_UP -> {
                    TaskbarService.showTransientDock(this)
                    return true
                }
            }
        }
        return super.onGesture(gestureEvent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty()) {
            if (isLauncherPackage(pkg)) {
                // Auto hide dock and dismiss bubbles when returning to home screen
                TaskbarService.hideTransientDock()
                TaskbarService.dismissFloatingBubbles()
            } else if (TaskbarService.activeFloatingApps.isNotEmpty() &&
                !TaskbarService.activeFloatingApps.containsKey(pkg) &&
                pkg != packageName &&
                pkg != "com.android.systemui"
            ) {
                // When any background fullscreen app receives touch/click/focus, immediately show the bubble stack!
                TaskbarService.showBubblesForActiveApps()
            }
        }
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val resolveInfo = packageManager.resolveActivity(intent, 0)
        return resolveInfo?.activityInfo?.packageName == pkg
    }

    override fun onInterrupt() {
        Log.w(TAG, "TaskbarAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        instance = null
        isRunning = false
        Log.i(TAG, "TaskbarAccessibilityService destroyed")
    }
}
