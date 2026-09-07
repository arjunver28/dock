package com.example.taskbartoggle

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.taskbartoggle.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_PERMISSION_REQ_CODE = 4001
    }

    private lateinit var binding: ActivityMainBinding

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasOverlayPermission()) {
            Toast.makeText(this, "Overlay permission granted! Starting taskbar...", Toast.LENGTH_SHORT).show()
            TaskbarService.start(this)
            updateOverlayStateUI()
        } else {
            Toast.makeText(this, "Overlay permission is required for the taskbar dock.", Toast.LENGTH_SHORT).show()
            updateOverlayStateUI()
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQ_CODE) {
            updateShizukuUI()
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateShizukuUI()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        updateShizukuUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Shizuku listener registration
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        } catch (ignored: Exception) {}

        setupListeners()
        setupShizukuControls()
        setupGestureSensorControls()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStateUI()
        updateShizukuUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } catch (ignored: Exception) {}
    }

    private fun setupListeners() {
        // Overlay Taskbar Switch
        binding.switchOverlayTaskbar.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasOverlayPermission()) {
                    TaskbarService.start(this)
                    binding.tvServiceStatus.text = "Active & Running"
                    binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.primary))
                } else {
                    binding.switchOverlayTaskbar.isChecked = false
                    requestOverlayPermission()
                }
            } else {
                TaskbarService.stop(this)
                binding.tvServiceStatus.text = "Disabled"
                binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        // Customize Dock Apps Button
        binding.btnCustomizeDockApps.setOnClickListener {
            showCustomizeDockAppsDialog()
        }

        // Grant Overlay Permission Button
        binding.btnGrantOverlayPermission.setOnClickListener {
            requestOverlayPermission()
        }

        // Open Developer Options Button
        binding.btnOpenDeveloperOptions.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Enable 'Enable freeform windows' & 'Force activities to be resizable'",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (ignored: Exception) {}
            }
        }

        // Enable Accessibility Service Button
        binding.btnEnableAccessibility.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Tap 'Installed Apps' / 'Downloaded Apps' -> Enable 'Floating Taskbar'",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (ignored: Exception) {}
            }
        }

        // Request Ignore Battery Optimization Button
        binding.btnRequestBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
    }

    private fun setupShizukuControls() {
        // Request Shizuku Permission Button
        binding.btnRequestShizukuPermission.setOnClickListener {
            ShizukuShell.requestPermission(SHIZUKU_PERMISSION_REQ_CODE)
        }

        // Option A: Enable Global Freeform Mode (Keep Phone DPI)
        binding.btnEnableGlobalFreeform.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = ShizukuShell.enableGlobalFreeform()
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(
                            this@MainActivity,
                            "✅ Global Freeform & Force Resizing Enabled! (Reboot or relaunch apps to take effect)",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Option A: Reset / Disable Global Freeform Mode
        binding.btnDisableGlobalFreeform.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = ShizukuShell.disableGlobalFreeform()
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(
                            this@MainActivity,
                            "🔄 Global Freeform Reset / Disabled.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Option B: Enable Tablet Density (280 DPI Trigger)
        binding.btnEnableTabletDensity.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = ShizukuShell.setTabletDensity(280)
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(
                            this@MainActivity,
                            "📐 Switched to Tablet UI Mode (280 DPI). Native Freeform & Taskbar Active!",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Option B: Reset Display Density
        binding.btnResetDensity.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = ShizukuShell.resetDensity()
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        Toast.makeText(
                            this@MainActivity,
                            "📱 Reset to Phone Default DPI.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Split Screen Menu Option Switch
        val prefs = getSharedPreferences("taskbar_prefs", Context.MODE_PRIVATE)
        val isSplitEnabled = prefs.getBoolean(TaskbarService.PREFS_KEY_ENABLE_SPLIT_SCREEN, true)
        binding.switchEnableSplitScreen.isChecked = isSplitEnabled

        binding.switchEnableSplitScreen.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(TaskbarService.PREFS_KEY_ENABLE_SPLIT_SCREEN, isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "Split Screen option enabled in menu" else "Split Screen option hidden (Floating Window only)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateShizukuUI() {
        val isAvailable = ShizukuShell.isShizukuAvailable()
        val hasPermission = ShizukuShell.hasPermission()

        if (isAvailable && hasPermission) {
            binding.tvShizukuStatus.text = "Active & Granted"
            binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.primary))
            binding.btnRequestShizukuPermission.visibility = View.GONE
            binding.btnEnableGlobalFreeform.isEnabled = true
            binding.btnDisableGlobalFreeform.isEnabled = true
            binding.btnEnableTabletDensity.isEnabled = true
            binding.btnResetDensity.isEnabled = true
        } else if (isAvailable && !hasPermission) {
            binding.tvShizukuStatus.text = "Running (Permission Required)"
            binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
            binding.btnRequestShizukuPermission.visibility = View.VISIBLE
            binding.btnEnableGlobalFreeform.isEnabled = false
            binding.btnDisableGlobalFreeform.isEnabled = false
            binding.btnEnableTabletDensity.isEnabled = false
            binding.btnResetDensity.isEnabled = false
        } else {
            binding.tvShizukuStatus.text = "Not Running (Start Shizuku App)"
            binding.tvShizukuStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnRequestShizukuPermission.visibility = View.GONE
            binding.btnEnableGlobalFreeform.isEnabled = false
            binding.btnDisableGlobalFreeform.isEnabled = false
            binding.btnEnableTabletDensity.isEnabled = false
            binding.btnResetDensity.isEnabled = false
        }
    }

    private fun setupGestureSensorControls() {
        val prefs = getSharedPreferences("taskbar_prefs", Context.MODE_PRIVATE)
        val currentHeightDp = prefs.getInt(
            TaskbarService.PREFS_KEY_GESTURE_HEIGHT,
            TaskbarService.DEFAULT_GESTURE_HEIGHT_DP
        )
        val currentWidthDp = prefs.getInt(
            TaskbarService.PREFS_KEY_GESTURE_WIDTH,
            TaskbarService.DEFAULT_GESTURE_WIDTH_DP
        )
        val currentPos = prefs.getString(
            TaskbarService.PREFS_KEY_GESTURE_POSITION,
            TaskbarService.DEFAULT_GESTURE_POSITION
        ) ?: TaskbarService.DEFAULT_GESTURE_POSITION
        val currentIndicator = prefs.getBoolean(
            TaskbarService.PREFS_KEY_GESTURE_INDICATOR,
            false
        )
        val currentGestureMode = prefs.getString(
            TaskbarService.PREFS_KEY_GESTURE_MODE,
            TaskbarService.DEFAULT_GESTURE_MODE
        ) ?: TaskbarService.DEFAULT_GESTURE_MODE

        // Set initial UI values
        binding.sliderGestureHeight.value = currentHeightDp.toFloat().coerceIn(10f, 200f)
        binding.tvGestureHeightValue.text = "$currentHeightDp dp"

        binding.sliderGestureWidth.value = currentWidthDp.toFloat().coerceIn(60f, 400f)
        binding.tvGestureWidthValue.text = "$currentWidthDp dp"

        when (currentPos) {
            "left" -> binding.toggleGroupPosition.check(R.id.btnPosLeft)
            "right" -> binding.toggleGroupPosition.check(R.id.btnPosRight)
            else -> binding.toggleGroupPosition.check(R.id.btnPosCenter)
        }

        binding.switchShowIndicator.isChecked = currentIndicator

        when (currentGestureMode) {
            "hold_only" -> binding.toggleGroupGestureMode.check(R.id.btnModeHold)
            "swipe_only" -> binding.toggleGroupGestureMode.check(R.id.btnModeSwipe)
            else -> binding.toggleGroupGestureMode.check(R.id.btnModeBoth)
        }

        fun updateSensor() {
            val height = binding.sliderGestureHeight.value.toInt()
            val width = binding.sliderGestureWidth.value.toInt()
            val pos = when (binding.toggleGroupPosition.checkedButtonId) {
                R.id.btnPosLeft -> "left"
                R.id.btnPosRight -> "right"
                else -> "center"
            }
            val indicator = binding.switchShowIndicator.isChecked
            val gestureMode = when (binding.toggleGroupGestureMode.checkedButtonId) {
                R.id.btnModeHold -> "hold_only"
                R.id.btnModeSwipe -> "swipe_only"
                else -> "both"
            }
            TaskbarService.updateGestureSensor(this, height, width, pos, indicator, gestureMode)
        }

        binding.sliderGestureHeight.addOnChangeListener { _, value, fromUser ->
            val heightDp = value.toInt()
            binding.tvGestureHeightValue.text = "$heightDp dp"
            if (fromUser) updateSensor()
        }

        binding.sliderGestureWidth.addOnChangeListener { _, value, fromUser ->
            val widthDp = value.toInt()
            binding.tvGestureWidthValue.text = "$widthDp dp"
            if (fromUser) updateSensor()
        }

        binding.toggleGroupPosition.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) updateSensor()
        }

        binding.switchShowIndicator.setOnCheckedChangeListener { _, _ ->
            updateSensor()
        }

        binding.toggleGroupGestureMode.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) updateSensor()
        }
    }

    private fun showCustomizeDockAppsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_dock_apps, null)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        val rvApps: androidx.recyclerview.widget.RecyclerView = dialogView.findViewById(R.id.rvSelectApps)
        val etSearch: android.widget.EditText = dialogView.findViewById(R.id.etSearchApp)
        val btnReset: com.google.android.material.button.MaterialButton = dialogView.findViewById(R.id.btnResetToDefault)
        val btnSave: com.google.android.material.button.MaterialButton = dialogView.findViewById(R.id.btnSaveDockApps)

        rvApps.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        lifecycleScope.launch {
            val allInstalledApps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                pm.queryIntentActivities(intent, 0).mapNotNull { resolveInfo ->
                    val pkg = resolveInfo.activityInfo.packageName
                    if (pkg == packageName) return@mapNotNull null
                    val label = resolveInfo.loadLabel(pm).toString()
                    val icon = resolveInfo.loadIcon(pm)
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    AppInfo(label, pkg, icon, launchIntent)
                }.sortedBy { it.label }
            }

            val currentDockPkgs = DockAppsManager.getSavedDockPackages(this@MainActivity)?.toSet()
                ?: DockAppsManager.getDefaultFixedTrayApps(this@MainActivity).map { it.packageName }.toSet()

            val adapter = SelectDockAppsAdapter(this@MainActivity, allInstalledApps, currentDockPkgs)
            rvApps.adapter = adapter

            etSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    adapter.filter(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            btnReset.setOnClickListener {
                DockAppsManager.resetToDefault(this@MainActivity)
                TaskbarService.reload()
                Toast.makeText(this@MainActivity, "Reset to default home dock apps", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }

            btnSave.setOnClickListener {
                val selected = adapter.getSelectedPackages()
                if (selected.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please select at least 1 app", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                DockAppsManager.saveDockPackages(this@MainActivity, selected)
                TaskbarService.reload()
                Toast.makeText(this@MainActivity, "Dock apps updated! (${selected.size} apps)", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            Toast.makeText(this, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateOverlayStateUI() {
        val hasOverlay = hasOverlayPermission()
        val isRunning = TaskbarService.isServiceRunning(this) && hasOverlay

        binding.btnGrantOverlayPermission.visibility = if (hasOverlay) View.GONE else View.VISIBLE

        // Prevent recursive listener trigger while syncing state
        binding.switchOverlayTaskbar.setOnCheckedChangeListener(null)
        binding.switchOverlayTaskbar.isChecked = isRunning
        setupListeners()

        if (isRunning) {
            binding.tvServiceStatus.text = "Active & Running"
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.primary))
        } else {
            binding.tvServiceStatus.text = if (hasOverlay) "Disabled" else "Permission Required"
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        val isA11yEnabled = TaskbarAccessibilityService.isAccessibilityEnabled(this)
        if (isA11yEnabled) {
            binding.tvAccessibilityStatus.text = "Active (0 Touch Interference)"
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.primary))
            binding.btnEnableAccessibility.text = "⚡ Gesture Service Active"
            binding.btnEnableAccessibility.isEnabled = false
        } else {
            binding.tvAccessibilityStatus.text = "Not Enabled"
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnEnableAccessibility.text = "⚡ Enable Native Gesture Service"
            binding.btnEnableAccessibility.isEnabled = true
        }

        updateBatteryOptUI()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isIgnoringBatteryOptimizations(packageName) ?: true
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (ignored: Exception) {}
        }
    }

    private fun updateBatteryOptUI() {
        val isIgnoring = isIgnoringBatteryOptimizations()
        if (isIgnoring) {
            binding.tvBatteryOptStatus.text = "Unrestricted / Protected"
            binding.tvBatteryOptStatus.setTextColor(ContextCompat.getColor(this, R.color.primary))
            binding.btnRequestBatteryOpt.text = "✅ Battery Optimization Ignored"
            binding.btnRequestBatteryOpt.isEnabled = false
        } else {
            binding.tvBatteryOptStatus.text = "Optimized (App may be killed by OS)"
            binding.tvBatteryOptStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnRequestBatteryOpt.text = "🔋 Allow Unrestricted Background Activity"
            binding.btnRequestBatteryOpt.isEnabled = true
        }
    }
}
