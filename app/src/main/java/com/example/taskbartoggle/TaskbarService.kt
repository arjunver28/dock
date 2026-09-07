package com.example.taskbartoggle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TaskbarService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    private var taskbarView: View? = null
    private var gestureEdgeView: View? = null
    private var drawerPopupView: View? = null
    private var optionsPopupView: View? = null
    private var dragOverlayView: View? = null
    private var multitaskingMenuPopup: View? = null
    private var floatingBubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var isTransient = true
    private var isTaskbarShowing = false
    private var isMenuClosing = false
    private var autoHideJob: Job? = null
    private var cachedAppsList: List<AppInfo> = emptyList()
    private var lastShowTimestamp = 0L

    // Detects OS-level Home swipe and Recents overview gesture -> Hides dock on Home screen
    private val homeGestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                // Ignore if the dock was just triggered within the last 1200ms (prevents touch conflict race condition)
                if (System.currentTimeMillis() - lastShowTimestamp < 1200) {
                    return
                }
                val reason = intent.getStringExtra("reason")
                // When user goes to Home screen or opens Recents, hide the dock
                if (reason == "homekey" || reason == "recentapps") {
                    hideTaskbarTransient()
                    closeAppDrawer()
                    closeOptionsPopup()
                    closeMultitaskingMenu()
                }
            }
        }
    }

    companion object {
        private const val TAG = "TaskbarService"
        private const val NOTIFICATION_ID = 9901
        private const val CHANNEL_ID = "taskbar_overlay_channel"
        private const val PREFS_KEY_ALWAYS_SHOW = "always_show_taskbar"
        const val PREFS_KEY_GESTURE_HEIGHT = "gesture_trigger_height_dp"
        const val PREFS_KEY_GESTURE_WIDTH = "gesture_trigger_width_dp"
        const val PREFS_KEY_GESTURE_POSITION = "gesture_trigger_position"
        const val PREFS_KEY_GESTURE_INDICATOR = "gesture_show_indicator"
        const val PREFS_KEY_GESTURE_MODE = "gesture_trigger_mode"
        const val PREFS_KEY_ENABLE_SPLIT_SCREEN = "enable_split_screen_option"
        const val DEFAULT_GESTURE_HEIGHT_DP = 80
        const val DEFAULT_GESTURE_WIDTH_DP = 280
        const val DEFAULT_GESTURE_POSITION = "center"
        const val DEFAULT_GESTURE_MODE = "both" // "both", "hold_only", "swipe_only"
        const val PREFS_KEY_SERVICE_ENABLED = "service_enabled_state"

        var isRunning = false
            private set

        val activeFloatingApps = LinkedHashMap<String, AppInfo>()

        private var instance: TaskbarService? = null

        fun reload() {
            instance?.reloadDockApps()
        }

        fun addActiveFloatingApp(app: AppInfo) {
            activeFloatingApps[app.packageName] = app
            instance?.updateFloatingBubblesStack()
        }

        fun removeActiveFloatingApp(packageName: String) {
            activeFloatingApps.remove(packageName)
            if (activeFloatingApps.isEmpty()) {
                instance?.hideFloatingBubblesStack()
            } else {
                instance?.updateFloatingBubblesStack()
            }
        }

        fun showBubblesForActiveApps() {
            if (activeFloatingApps.isNotEmpty()) {
                instance?.showFloatingBubblesStack()
            }
        }

        fun dismissFloatingBubbles() {
            activeFloatingApps.clear()
            instance?.hideFloatingBubblesStack()
        }

        fun updateGestureSensor(
            context: Context,
            heightDp: Int,
            widthDp: Int,
            position: String,
            showIndicator: Boolean,
            gestureMode: String = DEFAULT_GESTURE_MODE
        ) {
            val prefs = context.getSharedPreferences("taskbar_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(PREFS_KEY_GESTURE_HEIGHT, heightDp)
                .putInt(PREFS_KEY_GESTURE_WIDTH, widthDp)
                .putString(PREFS_KEY_GESTURE_POSITION, position)
                .putBoolean(PREFS_KEY_GESTURE_INDICATOR, showIndicator)
                .putString(PREFS_KEY_GESTURE_MODE, gestureMode)
                .apply()
            instance?.applyGestureSensor(heightDp, widthDp, position, showIndicator)
        }

        fun updateGestureHeight(context: Context, heightDp: Int) {
            val prefs = context.getSharedPreferences("taskbar_prefs", Context.MODE_PRIVATE)
            val widthDp = prefs.getInt(PREFS_KEY_GESTURE_WIDTH, DEFAULT_GESTURE_WIDTH_DP)
            val position = prefs.getString(PREFS_KEY_GESTURE_POSITION, DEFAULT_GESTURE_POSITION) ?: DEFAULT_GESTURE_POSITION
            val showIndicator = prefs.getBoolean(PREFS_KEY_GESTURE_INDICATOR, false)
            updateGestureSensor(context, heightDp, widthDp, position, showIndicator)
        }

        fun showTransientDock(context: Context) {
            if (!isRunning) {
                start(context)
            }
            instance?.showTaskbarTransient()
        }

        fun hideTransientDock() {
            instance?.hideTaskbarTransient()
        }

        fun isServiceRunning(context: Context): Boolean {
            if (instance != null && isRunning) return true
            val prefs = context.getSharedPreferences("taskbar_prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean(PREFS_KEY_SERVICE_ENABLED, false)
        }

        fun start(context: Context) {
            val prefs = context.getSharedPreferences("taskbar_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREFS_KEY_SERVICE_ENABLED, true).apply()
            val intent = Intent(context, TaskbarService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TaskbarService", e)
                try {
                    context.startService(intent)
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "Fallback startService failed", fallbackEx)
                }
            }
        }

        fun stop(context: Context) {
            val prefs = context.getSharedPreferences("taskbar_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREFS_KEY_SERVICE_ENABLED, false).apply()
            val intent = Intent(context, TaskbarService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        setTheme(R.style.Theme_TaskbarToggle)
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("taskbar_prefs", Context.MODE_PRIVATE)
        isTransient = !prefs.getBoolean(PREFS_KEY_ALWAYS_SHOW, false)

        startInForeground()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission missing.")
            stopSelf()
            return
        }

        createTaskbarOverlay()
        createGestureEdgeDetector()
        preloadAppsList()

        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(homeGestureReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(homeGestureReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering home gesture receiver", e)
        }
    }

    private fun startInForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Taskbar Overlay Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the dynamic taskbar dock active in background."
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)

                val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Dynamic Taskbar Active")
                    .setContentText("Taskbar dock is active without changing system DPI.")
                    .setSmallIcon(android.R.drawable.ic_menu_today)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed safely", e)
        }
    }

    @SuppressLint("InflateParams")
    private fun createTaskbarOverlay() {
        try {
            val themedContext = ContextThemeWrapper(this, R.style.Theme_TaskbarToggle)
            val layoutInflater = LayoutInflater.from(themedContext)
            val view = layoutInflater.inflate(R.layout.view_taskbar, null)
            taskbarView = view

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 8
            }

            // Immediately dismiss the taskbar when any touch occurs outside the dock
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (isTransient && isTaskbarShowing && drawerPopupView == null && dragOverlayView == null) {
                        hideTaskbarTransient()
                        true
                    } else false
                } else false
            }

            if (isTransient) {
                view.visibility = View.GONE
                view.translationY = 200f
                view.alpha = 0f
                isTaskbarShowing = false
                layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                view.visibility = View.VISIBLE
                view.translationY = 0f
                view.alpha = 1f
                isTaskbarShowing = true
            }

            setupTaskbarInteractions(view)
            windowManager.addView(view, layoutParams)
            loadPinnedAndRecentApps(view)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating taskbar overlay view", e)
            Toast.makeText(this, "Failed to display taskbar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Bottom sensor to detect upward/downward swipe gestures when the dock is hidden.
     * Includes a descended visual indicator pill that directly overlaps the system navigation gesture bar.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createGestureEdgeDetector() {
        val container = FrameLayout(this)
        gestureEdgeView = container

        val density = resources.displayMetrics.density
        val heightDp = prefs.getInt(PREFS_KEY_GESTURE_HEIGHT, DEFAULT_GESTURE_HEIGHT_DP)
        val widthDp = prefs.getInt(PREFS_KEY_GESTURE_WIDTH, DEFAULT_GESTURE_WIDTH_DP)
        val position = prefs.getString(PREFS_KEY_GESTURE_POSITION, DEFAULT_GESTURE_POSITION) ?: DEFAULT_GESTURE_POSITION
        val showIndicator = prefs.getBoolean(PREFS_KEY_GESTURE_INDICATOR, false)

        val widthPx = if (position == "center") {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            (widthDp * density).toInt()
        }
        val heightPx = (heightDp * density).toInt()

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
            when (position) {
                "left" -> {
                    gravity = Gravity.BOTTOM or Gravity.START
                    x = (16 * density).toInt()
                }
                "right" -> {
                    gravity = Gravity.BOTTOM or Gravity.END
                    x = (16 * density).toInt()
                }
                else -> {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    x = 0
                }
            }
            y = 0
        }

        // Exclude sensor region from system gesture consumption (<= 200dp compliant for Android OS acceptance)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val w = right - left
                val h = bottom - top
                if (w > 0 && h > 0) {
                    val exclW = Math.min((180 * density).toInt(), w)
                    val exclH = Math.min((40 * density).toInt(), h)
                    val exclLeft = (w - exclW) / 2
                    val exclTop = h - exclH
                    try {
                        container.systemGestureExclusionRects = listOf(Rect(exclLeft, exclTop, exclLeft + exclW, h))
                    } catch (ignored: Exception) {}
                }
            }
        }

        // Descended Visual Pill: Aligned to the absolute bottom edge directly over the system navigation gesture hint
        val pillView = View(this).apply {
            id = View.generateViewId()
            setBackgroundResource(R.drawable.bg_gesture_indicator)
            visibility = if (showIndicator) View.VISIBLE else View.GONE
        }
        val pillWidthPx = (140 * density).toInt()
        val pillHeightPx = (4.5 * density).toInt() // Matches standard Android system gesture bar thickness
        val pillParams = FrameLayout.LayoutParams(pillWidthPx, pillHeightPx).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (4 * density).toInt() // Descended to perfectly overlap the system navigation gesture hint
        }
        container.addView(pillView, pillParams)

        var startY = 0f
        var startX = 0f
        var minRawY = Float.MAX_VALUE
        var maxRawY = Float.MIN_VALUE
        var isSwiping = false
        var downTimeMs = 0L
        val holdHandler = Handler(Looper.getMainLooper())
        var isHoldTriggered = false

        val holdRunnable = Runnable {
            if (!isTaskbarShowing && isSwiping) {
                isHoldTriggered = true
                try {
                    container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                } catch (ignored: Exception) {}
                showTaskbarTransient()
            }
        }

        container.setOnTouchListener { _, event ->
            if (isTaskbarShowing) {
                holdHandler.removeCallbacks(holdRunnable)
                isSwiping = false
                isHoldTriggered = false
                return@setOnTouchListener false
            }

            if (event.pointerCount > 1) {
                isSwiping = false
                holdHandler.removeCallbacks(holdRunnable)
                return@setOnTouchListener true
            }

            val gestureMode = prefs.getString(PREFS_KEY_GESTURE_MODE, DEFAULT_GESTURE_MODE) ?: DEFAULT_GESTURE_MODE
            val allowHold = gestureMode == "both" || gestureMode == "hold_only"
            val allowSwipe = gestureMode == "both" || gestureMode == "swipe_only"

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startX = event.rawX
                    minRawY = event.rawY
                    maxRawY = event.rawY
                    isSwiping = true
                    isHoldTriggered = false
                    downTimeMs = System.currentTimeMillis()
                    // Start snappy 240ms Touch & Hold timer if enabled
                    holdHandler.removeCallbacks(holdRunnable)
                    if (allowHold) {
                        holdHandler.postDelayed(holdRunnable, 240)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isSwiping && event.pointerCount == 1 && allowSwipe) {
                        val currentY = event.rawY
                        val currentX = event.rawX
                        if (currentY < minRawY) minRawY = currentY
                        if (currentY > maxRawY) maxRawY = currentY

                        // 1. Gesture Pattern: Below Pill -> Above Pill -> Below Pill
                        val isUpThenDown = (startY - minRawY >= 8f) && (currentY - minRawY >= 4f)

                        // 2. Gesture Pattern: Above Pill -> Below Pill -> Above Pill
                        val isDownThenUp = (maxRawY - startY >= 8f) && (maxRawY - currentY >= 4f)

                        // 3. General Hook/Loop Gesture across the pill
                        val isCrossPillGesture = (maxRawY - minRawY >= 10f) && (
                            isUpThenDown || isDownThenUp ||
                            ((currentY - minRawY >= 4f) && (maxRawY - currentY >= 4f))
                        )

                        if (isCrossPillGesture && !isTaskbarShowing && !isHoldTriggered) {
                            holdHandler.removeCallbacks(holdRunnable)
                            try {
                                container.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            } catch (ignored: Exception) {}
                            showTaskbarTransient()
                            isSwiping = false
                            isHoldTriggered = true
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    holdHandler.removeCallbacks(holdRunnable)
                    if (isSwiping && !isHoldTriggered && allowSwipe) {
                        val currentY = event.rawY
                        if (currentY < minRawY) minRawY = currentY
                        if (currentY > maxRawY) maxRawY = currentY

                        val isUpThenDown = (startY - minRawY >= 8f) && (currentY - minRawY >= 4f)
                        val isDownThenUp = (maxRawY - startY >= 8f) && (maxRawY - currentY >= 4f)
                        val isCrossPillGesture = (maxRawY - minRawY >= 10f) && (isUpThenDown || isDownThenUp)

                        if (isCrossPillGesture && !isTaskbarShowing) {
                            try {
                                container.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            } catch (ignored: Exception) {}
                            showTaskbarTransient()
                        }
                    }
                    isSwiping = false
                    isHoldTriggered = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    holdHandler.removeCallbacks(holdRunnable)
                    // If finger was held for >= 150ms before SystemUI sent cancel, treat it as successful hold
                    val elapsed = System.currentTimeMillis() - downTimeMs
                    if (allowHold && isSwiping && !isHoldTriggered && elapsed >= 150) {
                        holdRunnable.run()
                    }
                    isSwiping = false
                    isHoldTriggered = false
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add gesture edge detector", e)
        }
    }

    fun applyGestureSensor(heightDp: Int, widthDp: Int, position: String, showIndicator: Boolean) {
        val container = gestureEdgeView as? FrameLayout ?: return
        val density = resources.displayMetrics.density
        val heightPx = (heightDp * density).toInt()
        val widthPx = if (position == "center") {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            (widthDp * density).toInt()
        }
        try {
            val params = (container.layoutParams as? WindowManager.LayoutParams) ?: return
            params.height = heightPx
            params.width = widthPx
            when (position) {
                "left" -> {
                    params.gravity = Gravity.BOTTOM or Gravity.START
                    params.x = (16 * density).toInt()
                }
                "right" -> {
                    params.gravity = Gravity.BOTTOM or Gravity.END
                    params.x = (16 * density).toInt()
                }
                else -> {
                    params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    params.x = 0
                }
            }
            params.flags = params.flags or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.fitInsetsTypes = 0
                params.fitInsetsSides = 0
            }

            val pillView = container.getChildAt(0)
            pillView?.visibility = if (showIndicator) View.VISIBLE else View.GONE

            windowManager.updateViewLayout(container, params)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                container.post {
                    val w = container.width
                    val h = container.height
                    if (w > 0 && h > 0) {
                        val exclW = Math.min((180 * density).toInt(), w)
                        val exclH = Math.min((40 * density).toInt(), h)
                        val exclLeft = (w - exclW) / 2
                        val exclTop = h - exclH
                        try {
                            container.systemGestureExclusionRects = listOf(Rect(exclLeft, exclTop, exclLeft + exclW, h))
                        } catch (ignored: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating gesture edge sensor", e)
        }
    }

    fun showTaskbarTransient() {
        val view = taskbarView ?: return
        isTaskbarShowing = true
        lastShowTimestamp = System.currentTimeMillis()

        // 1. Make taskbar window touchable
        val params = view.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                windowManager.updateViewLayout(view, params)
            } catch (ignored: Exception) {}
        }

        // 2. Make gesture edge sensor non-touchable so dock receives 100% of touch events
        val edgeView = gestureEdgeView
        val edgeParams = edgeView?.layoutParams as? WindowManager.LayoutParams
        if (edgeView != null && edgeParams != null) {
            edgeParams.flags = edgeParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                windowManager.updateViewLayout(edgeView, edgeParams)
            } catch (ignored: Exception) {}
        }

        view.isClickable = true
        view.visibility = View.VISIBLE
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(180)
            .setListener(null)
            .start()

        reloadDockApps()
        scheduleAutoHide()
    }

    fun hideTaskbarTransient() {
        val view = taskbarView ?: return
        if (!isTransient && isTaskbarShowing) {
            // In persistent mode, hide only when on home screen
            serviceScope.launch {
                if (isHomeScreenActive()) {
                    isTaskbarShowing = false
                    view.visibility = View.GONE
                    val params = view.layoutParams as? WindowManager.LayoutParams
                    if (params != null) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (ignored: Exception) {}
                    }
                    // Restore touchability to gesture edge detector
                    val edgeView = gestureEdgeView
                    val edgeParams = edgeView?.layoutParams as? WindowManager.LayoutParams
                    if (edgeView != null && edgeParams != null) {
                        edgeParams.flags = edgeParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        try {
                            windowManager.updateViewLayout(edgeView, edgeParams)
                        } catch (ignored: Exception) {}
                    }
                }
            }
            return
        }

        isTaskbarShowing = false
        view.isClickable = false

        // 1. Immediately make taskbar window non-touchable so touches pass through to underlying apps
        val params = view.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                windowManager.updateViewLayout(view, params)
            } catch (ignored: Exception) {}
        }

        // 2. Restore touchability to gesture edge detector immediately
        val edgeView = gestureEdgeView
        val edgeParams = edgeView?.layoutParams as? WindowManager.LayoutParams
        if (edgeView != null && edgeParams != null) {
            edgeParams.flags = edgeParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                windowManager.updateViewLayout(edgeView, edgeParams)
            } catch (ignored: Exception) {}
        }

        view.animate()
            .translationY(200f)
            .alpha(0f)
            .setDuration(160)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isTaskbarShowing) {
                        view.visibility = View.GONE
                    }
                }
            })
            .start()
    }

    private fun scheduleAutoHide() {
        autoHideJob?.cancel()
        if (!isTransient) return

        autoHideJob = serviceScope.launch {
            delay(5000)
            hideTaskbarTransient()
        }
    }

    private fun setupTaskbarInteractions(view: View) {
        val btnAppDrawer: ImageButton = view.findViewById(R.id.btnAppDrawer)
        val dividerMenu: View? = view.findViewById(R.id.dividerMenu)

        // 1. Mini App Drawer Button (Tap opens drawer, Long-press opens Taskbar Options)
        btnAppDrawer.setOnClickListener {
            scheduleAutoHide()
            toggleAppDrawer()
        }

        btnAppDrawer.setOnLongClickListener {
            scheduleAutoHide()
            showTaskbarOptionsPopup()
            true
        }

        // 2. Long-press on the menu divider or empty dock space opens Taskbar Options
        dividerMenu?.setOnLongClickListener {
            scheduleAutoHide()
            showTaskbarOptionsPopup()
            true
        }

        view.setOnLongClickListener {
            scheduleAutoHide()
            showTaskbarOptionsPopup()
            true
        }
    }

    fun reloadDockApps() {
        taskbarView?.let { view ->
            loadPinnedAndRecentApps(view)
        }
    }

    private fun loadPinnedAndRecentApps(view: View) {
        val layoutPinned: LinearLayout = view.findViewById(R.id.layoutPinnedApps)
        val layoutRecent: LinearLayout = view.findViewById(R.id.layoutRecentApps)

        layoutPinned.removeAllViews()
        layoutRecent.removeAllViews()

        serviceScope.launch {
            // Load Pinned Home Screen Tray Apps
            val pinnedApps = withContext(Dispatchers.IO) {
                DockAppsManager.getDockApps(this@TaskbarService)
            }
            val pinnedPackageSet = pinnedApps.map { it.packageName }.toSet()

            for (app in pinnedApps) {
                val iconView = createDockAppIconView(app, layoutPinned)
                layoutPinned.addView(iconView)
            }

            // Load Dynamic Recent Apps (Right of Divider)
            val recentApps = withContext(Dispatchers.IO) {
                RecentAppsManager.getRecentApps(this@TaskbarService, pinnedPackageSet, limit = 3)
            }

            for (app in recentApps) {
                val iconView = createDockAppIconView(app, layoutRecent)
                layoutRecent.addView(iconView)
            }
        }
    }

    private fun createDockAppIconView(app: AppInfo, parent: ViewGroup): View {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_TaskbarToggle)
        val iconView = LayoutInflater.from(themedContext)
            .inflate(R.layout.item_taskbar_icon, parent, false)
        val ivIcon: ImageView = iconView.findViewById(R.id.ivTaskbarAppIcon)
        ivIcon.setImageDrawable(app.icon)

        // 1. Click: Instant App Launch
        iconView.setOnClickListener {
            scheduleAutoHide()
            val launchIntent = app.launchIntent ?: packageManager.getLaunchIntentForPackage(app.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch ${app.packageName}", e)
                    Toast.makeText(this@TaskbarService, "Cannot launch app", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@TaskbarService, "App not launchable", Toast.LENGTH_SHORT).show()
            }
            if (isTransient) {
                hideTaskbarTransient()
            }
        }

        // 2. Long Click: Opens Quick Multitasking Action Menu OR directly opens in Floating Window if split is disabled
        iconView.setOnLongClickListener {
            scheduleAutoHide()
            val isSplitEnabled = prefs.getBoolean(PREFS_KEY_ENABLE_SPLIT_SCREEN, true)
            if (isSplitEnabled) {
                showMultitaskingMenu(app)
            } else {
                try {
                    iconView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                } catch (ignored: Exception) {}
                launchAppInFreeform(app)
            }
            true
        }

        return iconView
    }

    // =========================================================================
    // MULTITASKING: QUICK ACTION MENU, SPLIT-SCREEN & FREEFORM
    // =========================================================================

    @SuppressLint("InflateParams")
    private fun showMultitaskingMenu(app: AppInfo) {
        closeMultitaskingMenu()

        try {
            val themedContext = ContextThemeWrapper(this, R.style.Theme_TaskbarToggle)
            val inflater = LayoutInflater.from(themedContext)
            val popup = inflater.inflate(R.layout.dialog_app_multitasking_menu, null)
            multitaskingMenuPopup = popup

            val density = resources.displayMetrics.density
            val widthPx = (270 * density).toInt()

            val params = WindowManager.LayoutParams(
                widthPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val ivAppIcon: ImageView = popup.findViewById(R.id.ivMenuAppIcon)
            val tvAppName: TextView = popup.findViewById(R.id.tvMenuAppName)
            val btnSplitScreen: View = popup.findViewById(R.id.btnSplitScreenOption)
            val btnFreeform: View = popup.findViewById(R.id.btnFreeformOption)

            ivAppIcon.setImageDrawable(app.icon)
            tvAppName.text = app.label

            val isSplitEnabled = prefs.getBoolean(PREFS_KEY_ENABLE_SPLIT_SCREEN, true)
            btnSplitScreen.visibility = if (isSplitEnabled) View.VISIBLE else View.GONE

            popup.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    closeMultitaskingMenu()
                    true
                } else false
            }

            btnSplitScreen.setOnClickListener {
                launchAppInSplitScreen(app)
            }

            btnFreeform.setOnClickListener {
                launchAppInFreeform(app)
            }

            popup.alpha = 0f
            popup.scaleX = 0.85f
            popup.scaleY = 0.85f

            windowManager.addView(popup, params)

            popup.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(190)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                .start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show multitasking menu", e)
        }
    }

    private fun closeMultitaskingMenu() {
        val menu = multitaskingMenuPopup ?: return
        if (isMenuClosing) return
        isMenuClosing = true
        multitaskingMenuPopup = null

        menu.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(140)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    menu.visibility = View.GONE
                    try {
                        windowManager.removeViewImmediate(menu)
                    } catch (e: Exception) {
                        try {
                            windowManager.removeView(menu)
                        } catch (ignored: Exception) {}
                    } finally {
                        isMenuClosing = false
                    }
                }
            })
            .start()
    }

    private fun launchAppInSplitScreen(app: AppInfo) {
        val pm = packageManager
        val launchIntent = app.launchIntent ?: pm.getLaunchIntentForPackage(app.packageName)
        val component = launchIntent?.component ?: launchIntent?.resolveActivity(pm)

        if (ShizukuShell.hasPermission()) {
            serviceScope.launch(Dispatchers.IO) {
                val compStr = component?.flattenToShortString()
                val cmd = if (compStr != null) {
                    "am start -n $compStr --windowingMode 4 -f 0x18000000"
                } else {
                    "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${app.packageName} --windowingMode 4 -f 0x18000000"
                }
                val result = ShizukuShell.exec(cmd)
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(this@TaskbarService, "Opening ${app.label} in Split Screen", Toast.LENGTH_SHORT).show()
                    } else {
                        fallbackLaunchInSplitScreen(app, launchIntent)
                    }
                }
            }
        } else {
            fallbackLaunchInSplitScreen(app, launchIntent)
        }

        closeMultitaskingMenu()
        closeAppDrawer()
        if (isTransient) hideTaskbarTransient()
    }

    private fun fallbackLaunchInSplitScreen(app: AppInfo, launchIntent: Intent?) {
        if (launchIntent == null) return
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        launchIntent.putExtra("android.intent.extra.WINDOWING_MODE", 3)
        launchIntent.putExtra("com.samsung.android.intent.extra.SPLIT_WINDOW", true)
        launchIntent.putExtra("miui.intent.extra.SPLIT_SCREEN", true)

        val isA11yActive = TaskbarAccessibilityService.isAccessibilityEnabled(this)
        if (isA11yActive) {
            TaskbarAccessibilityService.toggleSplitScreen()
            serviceScope.launch(Dispatchers.Main) {
                delay(350)
                try {
                    val options = ActivityOptions.makeBasic()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        try {
                            val method = ActivityOptions::class.java.getMethod(
                                "setLaunchWindowingMode",
                                Int::class.javaPrimitiveType
                            )
                            method.isAccessible = true
                            method.invoke(options, 3)
                        } catch (ignored: Exception) {}
                        startActivity(launchIntent, options.toBundle())
                    } else {
                        startActivity(launchIntent)
                    }
                    Toast.makeText(this@TaskbarService, "Opening ${app.label} in Split Screen", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    try {
                        startActivity(launchIntent)
                    } catch (ignored: Exception) {}
                }
            }
        } else {
            try {
                startActivity(launchIntent)
            } catch (ignored: Exception) {}
        }
    }

    private fun launchAppInFreeform(app: AppInfo) {
        val pm = packageManager
        val launchIntent = app.launchIntent ?: pm.getLaunchIntentForPackage(app.packageName)
        val component = launchIntent?.component ?: launchIntent?.resolveActivity(pm)
        val pkg = app.packageName

        // Register this app into our active multi-app floating stack
        addActiveFloatingApp(app)

        if (ShizukuShell.hasPermission()) {
            serviceScope.launch(Dispatchers.IO) {
                val compStr = component?.flattenToShortString()
                // Reopen the EXACT existing instance using FLAG_ACTIVITY_REORDER_TO_FRONT (0x10020000) and am task focus
                val cmd = if (compStr != null) {
                    "for tid in $(cmd activity tasks | grep -B 2 -A 2 \"$pkg\" | grep -oE \"taskId=[0-9]+|#[0-9]+\" | grep -oE \"[0-9]+\"); do am task focus \$tid 2>/dev/null || cmd activity task focus \$tid 2>/dev/null; done; am start -n $compStr --windowingMode 5 -f 0x10020000"
                } else {
                    "for tid in $(cmd activity tasks | grep -B 2 -A 2 \"$pkg\" | grep -oE \"taskId=[0-9]+|#[0-9]+\" | grep -oE \"[0-9]+\"); do am task focus \$tid 2>/dev/null || cmd activity task focus \$tid 2>/dev/null; done; am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg --windowingMode 5 -f 0x10020000"
                }
                val result = ShizukuShell.exec(cmd)
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(this@TaskbarService, "Opening ${app.label} in Floating Window", Toast.LENGTH_SHORT).show()
                    } else {
                        fallbackLaunchInFreeform(app, launchIntent)
                    }
                }
            }
        } else {
            fallbackLaunchInFreeform(app, launchIntent)
        }

        // Start background monitor for all open floating apps
        startFloatingAppMonitor()

        closeMultitaskingMenu()
        closeAppDrawer()
        if (isTransient) hideTaskbarTransient()
    }

    private var floatingAppMonitorJob: Job? = null

    private fun startFloatingAppMonitor() {
        floatingAppMonitorJob?.cancel()
        floatingAppMonitorJob = serviceScope.launch(Dispatchers.IO) {
            delay(350)
            withContext(Dispatchers.Main) {
                showFloatingBubblesStack()
            }
            while (isRunning && activeFloatingApps.isNotEmpty()) {
                delay(500)
                if (isHomeScreenActive()) {
                    withContext(Dispatchers.Main) {
                        hideFloatingBubblesStack()
                    }
                    continue
                }
                withContext(Dispatchers.Main) {
                    if (floatingBubbleView == null && activeFloatingApps.isNotEmpty()) {
                        showFloatingBubblesStack()
                    }
                }
            }
        }
    }

    private fun getTopPackageName(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val time = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 30, time)
                if (stats != null && stats.isNotEmpty()) {
                    stats.maxByOrNull { it.lastTimeUsed }?.packageName
                } else null
            } else null
        } catch (ignored: Exception) {
            null
        }
    }

    private fun fallbackLaunchInFreeform(app: AppInfo, launchIntent: Intent?) {
        if (launchIntent == null) return
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        launchIntent.putExtra("android.intent.extra.WINDOWING_MODE", 5)
        launchIntent.putExtra("com.samsung.android.intent.extra.FREEFORM_WINDOW", true)
        launchIntent.putExtra("miui.intent.extra.FLOATING_WINDOW", true)
        launchIntent.putExtra("miui.intent.extra.FREEFORM_WINDOW", true)
        launchIntent.putExtra("freeform_window", true)

        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.85).toInt()
        val height = (displayMetrics.heightPixels * 0.65).toInt()
        val left = (displayMetrics.widthPixels - width) / 2
        val top = (displayMetrics.heightPixels - height) / 3
        val bounds = Rect(left, top, left + width, top + height)

        try {
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                options.setLaunchBounds(bounds)
                try {
                    val method = ActivityOptions::class.java.getMethod(
                        "setLaunchWindowingMode",
                        Int::class.javaPrimitiveType
                    )
                    method.isAccessible = true
                    method.invoke(options, 5)
                } catch (ignored: Exception) {}
                startActivity(launchIntent, options.toBundle())
            } else {
                startActivity(launchIntent)
            }
            Toast.makeText(this, "Opening ${app.label} in Floating Window", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                startActivity(launchIntent)
            } catch (ignored: Exception) {}
        }
    }

    // =========================================================================
    // MULTI-APP MINIMIZED INSTANCE FLOATING BUBBLES STACK
    // =========================================================================

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun showFloatingBubblesStack() {
        if (!isRunning || activeFloatingApps.isEmpty()) return

        serviceScope.launch(Dispatchers.Main) {
            val existing = floatingBubbleView
            if (existing != null) {
                updateFloatingBubblesStack()
                existing.visibility = View.VISIBLE
                return@launch
            }

            val themedContext = ContextThemeWrapper(this@TaskbarService, R.style.Theme_TaskbarToggle)
            val inflater = LayoutInflater.from(themedContext)
            val container = inflater.inflate(R.layout.layout_floating_bubbles_container, null)
            floatingBubbleView = container

            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val initialXPos = (screenWidth - (65 * displayMetrics.density)).toInt().coerceAtLeast(0)
            val initialYPos = (screenHeight / 3).coerceAtLeast(100)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialXPos
                y = initialYPos
            }
            bubbleParams = params

            updateFloatingBubblesStack()

            container.alpha = 0f
            container.scaleX = 0.5f
            container.scaleY = 0.5f
            try {
                windowManager.addView(container, params)
                container.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add floating bubble container", e)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun updateFloatingBubblesStack() {
        val container = floatingBubbleView ?: return
        val layoutItems: LinearLayout = container.findViewById(R.id.layoutBubblesItems) ?: return

        layoutItems.removeAllViews()
        val themedContext = ContextThemeWrapper(this, R.style.Theme_TaskbarToggle)
        val inflater = LayoutInflater.from(themedContext)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        for (floatingApp in activeFloatingApps.values) {
            val itemView = inflater.inflate(R.layout.item_floating_bubble_icon, layoutItems, false)
            val ivIcon: ImageView = itemView.findViewById(R.id.ivItemBubbleIcon)
            ivIcon.setImageDrawable(floatingApp.icon)

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false
            var lastTapTime = 0L
            var singleTapRunnable: Runnable? = null
            val handler = Handler(Looper.getMainLooper())
            val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

            itemView.setOnTouchListener { v, event ->
                val currentParams = bubbleParams ?: return@setOnTouchListener false
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = currentParams.x
                        initialY = currentParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        container.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop.toDouble()) {
                            isDragging = true
                            // Cancel pending single-tap action when dragging
                            singleTapRunnable?.let { handler.removeCallbacks(it) }
                            singleTapRunnable = null
                        }
                        if (isDragging) {
                            // Completely free movement anywhere on the screen!
                            currentParams.x = (initialX + dx).coerceIn(0, screenWidth - container.width.coerceAtLeast(50))
                            currentParams.y = (initialY + dy).coerceIn(30, screenHeight - container.height.coerceAtLeast(50) - 30)
                            try {
                                windowManager.updateViewLayout(container, currentParams)
                            } catch (ignored: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        container.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                        if (!isDragging) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < doubleTapTimeout) {
                                // DOUBLE TAP: Close / dismiss this bubble!
                                singleTapRunnable?.let { handler.removeCallbacks(it) }
                                singleTapRunnable = null
                                lastTapTime = 0L

                                try {
                                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                } catch (ignored: Exception) {}

                                Toast.makeText(this@TaskbarService, "Closed ${floatingApp.label}", Toast.LENGTH_SHORT).show()
                                removeActiveFloatingApp(floatingApp.packageName)
                            } else {
                                // SINGLE TAP CANDIDATE: Schedule opening previous instance
                                lastTapTime = now
                                singleTapRunnable?.let { handler.removeCallbacks(it) }
                                val runnable = Runnable {
                                    try {
                                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    } catch (ignored: Exception) {}
                                    launchAppInFreeform(floatingApp)
                                }
                                singleTapRunnable = runnable
                                handler.postDelayed(runnable, doubleTapTimeout)
                            }
                        }
                        // When dragged, stays freely anywhere the user released it!
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        container.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                        singleTapRunnable?.let { handler.removeCallbacks(it) }
                        singleTapRunnable = null
                        true
                    }
                    else -> false
                }
            }

            layoutItems.addView(itemView)
        }
    }

    fun hideFloatingBubblesStack() {
        floatingBubbleView?.let { container ->
            try {
                container.animate()
                    .alpha(0f)
                    .scaleX(0.5f)
                    .scaleY(0.5f)
                    .setDuration(150)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            try {
                                windowManager.removeView(container)
                            } catch (ignored: Exception) {}
                            floatingBubbleView = null
                            bubbleParams = null
                        }
                    })
                    .start()
            } catch (e: Exception) {
                try { windowManager.removeView(container) } catch (ignored: Exception) {}
                floatingBubbleView = null
                bubbleParams = null
            }
        }
    }

    // =========================================================================
    // TASKBAR OPTIONS POPUP (Transient vs Persistent Switch)
    // =========================================================================

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showTaskbarOptionsPopup() {
        if (optionsPopupView != null) {
            closeOptionsPopup()
            return
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_TaskbarToggle)
        val popup = LayoutInflater.from(themedContext).inflate(R.layout.dialog_taskbar_mode_popup, null)
        optionsPopupView = popup

        val switchAlwaysShow: MaterialSwitch = popup.findViewById(R.id.switchAlwaysShow)
        switchAlwaysShow.isChecked = !isTransient

        switchAlwaysShow.setOnCheckedChangeListener { _, isChecked ->
            isTransient = !isChecked
            prefs.edit().putBoolean(PREFS_KEY_ALWAYS_SHOW, isChecked).apply()

            if (isChecked) {
                autoHideJob?.cancel()
                taskbarView?.visibility = View.VISIBLE
                taskbarView?.translationY = 0f
                taskbarView?.alpha = 1f
                isTaskbarShowing = true
                gestureEdgeView?.visibility = View.GONE
                Toast.makeText(this, "Taskbar: Persistent Mode", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Taskbar: Transient Mode", Toast.LENGTH_SHORT).show()
                scheduleAutoHide()
            }
            closeOptionsPopup()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        popup.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                closeOptionsPopup()
                true
            } else false
        }

        windowManager.addView(popup, params)
    }

    private fun closeOptionsPopup() {
        optionsPopupView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing options popup", e)
            }
            optionsPopupView = null
        }
    }

    private fun preloadAppsList() {
        serviceScope.launch {
            val allApps = withContext(Dispatchers.IO) {
                getInstalledLaunchableApps()
            }
            cachedAppsList = allApps
        }
    }

    // =========================================================================
    // MINI APP DRAWER OVERLAY
    // =========================================================================

    @SuppressLint("InflateParams")
    private fun toggleAppDrawer() {
        if (drawerPopupView != null) {
            closeAppDrawer()
            return
        }

        try {
            // 1. Hide the dock when opening All Applications list
            hideTaskbarTransient()

            val themedContext = ContextThemeWrapper(this, R.style.Theme_TaskbarToggle)
            val inflater = LayoutInflater.from(themedContext)
            val popup = inflater.inflate(R.layout.dialog_app_drawer, null)
            drawerPopupView = popup

            val displayMetrics = resources.displayMetrics
            val drawerWidth = (displayMetrics.widthPixels * 0.90).toInt()

            val drawerParams = WindowManager.LayoutParams(
                drawerWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 100
            }

            // Dismiss both app drawer and dock when touching anywhere outside
            popup.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    closeAppDrawer()
                    hideTaskbarTransient()
                    true
                } else false
            }

            val layoutRecents: View? = popup.findViewById(R.id.layoutRecentsSection)
            val rvRecents: RecyclerView? = popup.findViewById(R.id.rvRecentsList)
            rvRecents?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

            val recentsAdapter = RecentAppsAdapter(
                this@TaskbarService,
                emptyList(),
                onAppClick = { selectedApp ->
                    val intent = selectedApp.launchIntent ?: packageManager.getLaunchIntentForPackage(selectedApp.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        startActivity(intent)
                    }
                    closeAppDrawer()
                    hideTaskbarTransient()
                },
                onAppLongClick = { selectedApp, anchorView ->
                    val isSplitEnabled = prefs.getBoolean(PREFS_KEY_ENABLE_SPLIT_SCREEN, true)
                    if (isSplitEnabled) {
                        showMultitaskingMenu(selectedApp)
                    } else {
                        try {
                            anchorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } catch (ignored: Exception) {}
                        launchAppInFreeform(selectedApp)
                    }
                }
            )
            rvRecents?.adapter = recentsAdapter

            // Load and display Recent Apps in the Drawer
            serviceScope.launch {
                val recentApps = getRecentAppsList()
                withContext(Dispatchers.Main) {
                    if (drawerPopupView != null && recentApps.isNotEmpty()) {
                        recentsAdapter.updateRecents(recentApps)
                        layoutRecents?.visibility = View.VISIBLE
                    }
                }
            }

            val rvApps: RecyclerView = popup.findViewById(R.id.rvAppsList)
            rvApps.layoutManager = GridLayoutManager(this, 4)

            // Instantly display preloaded cached apps (Zero delay, Zero jerk)
            val initialApps = cachedAppsList
            val adapter = AppDrawerAdapter(
                this@TaskbarService,
                initialApps,
                onAppClick = { selectedApp ->
                    val intent = selectedApp.launchIntent ?: packageManager.getLaunchIntentForPackage(selectedApp.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        startActivity(intent)
                    }
                    closeAppDrawer()
                    hideTaskbarTransient()
                },
                onAppLongClick = { selectedApp, anchorView ->
                    val isSplitEnabled = prefs.getBoolean(PREFS_KEY_ENABLE_SPLIT_SCREEN, true)
                    if (isSplitEnabled) {
                        showMultitaskingMenu(selectedApp)
                    } else {
                        try {
                            anchorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } catch (ignored: Exception) {}
                        launchAppInFreeform(selectedApp)
                    }
                }
            )
            rvApps.adapter = adapter

            // If cache was not ready, load and update adapter seamlessly
            if (initialApps.isEmpty()) {
                serviceScope.launch {
                    val allApps = withContext(Dispatchers.IO) {
                        getInstalledLaunchableApps()
                    }
                    cachedAppsList = allApps
                    adapter.updateApps(allApps)
                }
            }

            // Smooth Entrance Spring Animation
            popup.alpha = 0f
            popup.scaleX = 0.88f
            popup.scaleY = 0.88f
            popup.translationY = 50f

            windowManager.addView(popup, drawerParams)

            popup.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.9f))
                .start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show app drawer", e)
        }
    }

    private suspend fun getRecentAppsList(): List<AppInfo> = withContext(Dispatchers.IO) {
        val recents = mutableListOf<AppInfo>()
        val seenPackages = mutableSetOf<String>()
        val currentAppPkg = packageName
        val homePkgs = getHomePackages().toSet()

        // 1. Shizuku / Shell Recents (Real-time active running recents)
        if (ShizukuShell.hasPermission()) {
            try {
                val result = ShizukuShell.exec("cmd activity recents 20 2>/dev/null || dumpsys activity recents 2>/dev/null")
                val shellResult = result.getOrNull()
                if (result.isSuccess && shellResult != null) {
                    val lines = shellResult.stdout.lines()
                    val pkgRegex = Regex("""(?:cmp=|realActivity=|A=)([a-zA-Z0-9_.]+)/""")
                    for (line in lines) {
                        val match = pkgRegex.find(line)
                        if (match != null) {
                            val pkg = match.groupValues[1]
                            if (pkg != currentAppPkg && !homePkgs.contains(pkg) && seenPackages.add(pkg)) {
                                val app = cachedAppsList.find { it.packageName == pkg }
                                if (app != null) {
                                    recents.add(app)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get recents via Shizuku", e)
            }
        }

        // 2. Fallback: UsageStatsManager Recents
        if (recents.isEmpty()) {
            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm != null) {
                    val time = System.currentTimeMillis()
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60 * 60 * 24, time)
                    if (stats != null && stats.isNotEmpty()) {
                        val sortedStats = stats.sortedByDescending { it.lastTimeUsed }
                        for (stat in sortedStats) {
                            val pkg = stat.packageName
                            if (pkg != currentAppPkg && !homePkgs.contains(pkg) && seenPackages.add(pkg)) {
                                val app = cachedAppsList.find { it.packageName == pkg }
                                if (app != null) {
                                    recents.add(app)
                                    if (recents.size >= 8) break
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get recents via UsageStats", e)
            }
        }

        // 3. Fallback: ActivityManager Recents
        if (recents.isEmpty()) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                @Suppress("DEPRECATION")
                val tasks = am?.getRecentTasks(15, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
                if (tasks != null) {
                    for (task in tasks) {
                        val pkg = task.baseIntent?.component?.packageName
                        if (pkg != null && pkg != currentAppPkg && !homePkgs.contains(pkg) && seenPackages.add(pkg)) {
                            val app = cachedAppsList.find { it.packageName == pkg }
                            if (app != null) {
                                recents.add(app)
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        recents.take(8)
    }

    private var isDrawerClosing = false

    private fun closeAppDrawer() {
        val popup = drawerPopupView ?: return
        if (isDrawerClosing) return
        isDrawerClosing = true
        drawerPopupView = null

        popup.animate()
            .alpha(0f)
            .scaleX(0.90f)
            .scaleY(0.90f)
            .translationY(40f)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    popup.visibility = View.GONE
                    try {
                        windowManager.removeViewImmediate(popup)
                    } catch (e: Exception) {
                        try {
                            windowManager.removeView(popup)
                        } catch (ignored: Exception) {}
                    } finally {
                        isDrawerClosing = false
                    }
                }
            })
            .start()
    }

    private fun getInstalledLaunchableApps(): List<AppInfo> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        return resolveInfos.mapNotNull { resolveInfo ->
            val pkgName = resolveInfo.activityInfo.packageName
            if (pkgName == packageName) return@mapNotNull null

            val appLabel = resolveInfo.loadLabel(pm).toString()
            val appIcon: Drawable = resolveInfo.loadIcon(pm)
            val launchIntent = pm.getLaunchIntentForPackage(pkgName)

            AppInfo(appLabel, pkgName, appIcon, launchIntent)
        }.sortedBy { it.label }
    }

    private suspend fun isHomeScreenActive(): Boolean {
        val homePackages = getHomePackages()

        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val time = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
                if (stats != null && stats.isNotEmpty()) {
                    val lastApp = stats.maxByOrNull { it.lastTimeUsed }
                    if (lastApp != null && homePackages.contains(lastApp.packageName)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UsageStats failed in isHomeScreenActive", e)
        }

        return false
    }

    private fun getHomePackages(): List<String> {
        val names = mutableListOf<String>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (ri in resolveInfos) {
            names.add(ri.activityInfo.packageName)
        }
        return names
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        serviceScope.cancel()

        try {
            unregisterReceiver(homeGestureReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering homeGestureReceiver", e)
        }

        closeMultitaskingMenu()
        closeAppDrawer()
        closeOptionsPopup()
        hideFloatingBubblesStack()

        gestureEdgeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing gestureEdgeView", e)
            }
            gestureEdgeView = null
        }

        taskbarView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing taskbarView", e)
            }
            taskbarView = null
        }
    }
}
