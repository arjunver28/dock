package com.example.taskbartoggle

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

object DockAppsManager {

    private const val PREFS_NAME = "taskbar_dock_prefs"
    private const val KEY_PINNED_PACKAGES = "pinned_dock_packages"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets the saved list of package names chosen for the fixed dock.
     */
    fun getSavedDockPackages(context: Context): List<String>? {
        val jsonStr = getPrefs(context).getString(KEY_PINNED_PACKAGES, null) ?: return null
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
            if (list.isEmpty()) null else list
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves user's chosen dock apps.
     */
    fun saveDockPackages(context: Context, packageNames: List<String>) {
        val jsonArray = JSONArray(packageNames)
        getPrefs(context).edit().putString(KEY_PINNED_PACKAGES, jsonArray.toString()).apply()
    }

    /**
     * Clears custom selection and resets to default home tray auto-detection.
     */
    fun resetToDefault(context: Context) {
        getPrefs(context).edit().remove(KEY_PINNED_PACKAGES).apply()
    }

    /**
     * Loads the apps that should appear in the fixed dock:
     * 1. If user set custom apps, use those.
     * 2. Fallback to system standard fixed tray apps (Dialer, SMS, Browser, Camera, Gallery).
     */
    suspend fun getDockApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager

        // 1. Check user-configured packages
        val savedPkgs = getSavedDockPackages(context)
        if (!savedPkgs.isNullOrEmpty()) {
            val apps = savedPkgs.mapNotNull { pkg -> getAppInfoForPackage(pm, pkg) }
            if (apps.isNotEmpty()) return@withContext apps
        }

        // 2. Fallback to Default Home Screen Tray Apps (Phone, SMS, Browser, Camera, Gallery)
        return@withContext getDefaultFixedTrayApps(context)
    }

    /**
     * Resolves the primary default apps that make up the fixed home screen tray.
     */
    fun getDefaultFixedTrayApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val list = mutableListOf<AppInfo>()
        val addedPackages = mutableSetOf<String>()

        fun addIfValid(intent: Intent) {
            val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val pkg = resolveInfo?.activityInfo?.packageName ?: return
            if (pkg != context.packageName && addedPackages.add(pkg)) {
                getAppInfoForPackage(pm, pkg)?.let { list.add(it) }
            }
        }

        // 1. Default Dialer / Phone
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val defaultDialer = telecomManager?.defaultDialerPackage
        if (!defaultDialer.isNullOrEmpty() && addedPackages.add(defaultDialer)) {
            getAppInfoForPackage(pm, defaultDialer)?.let { list.add(it) }
        } else {
            addIfValid(Intent(Intent.ACTION_DIAL))
        }

        // 2. Default Messaging / SMS
        val defaultSms = Telephony.Sms.getDefaultSmsPackage(context)
        if (!defaultSms.isNullOrEmpty() && addedPackages.add(defaultSms)) {
            getAppInfoForPackage(pm, defaultSms)?.let { list.add(it) }
        } else {
            addIfValid(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        }

        // 3. Default Browser
        addIfValid(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))

        // 4. Default Camera
        addIfValid(Intent(MediaStore.ACTION_IMAGE_CAPTURE))

        // 5. Default Gallery / Photos
        val galleryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
        addIfValid(galleryIntent)

        return list
    }

    fun getAppInfoForPackage(pm: PackageManager, packageName: String): AppInfo? {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = pm.getApplicationIcon(appInfo)
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            AppInfo(label, packageName, icon, launchIntent)
        } catch (e: Exception) {
            null
        }
    }
}
