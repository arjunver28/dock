package com.example.taskbartoggle

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RecentAppsManager {

    /**
     * Retrieves the top 2-4 recent running apps (excluding the current launcher/dock app and pinned apps)
     * using standard Android UsageStatsManager.
     */
    suspend fun getRecentApps(
        context: Context,
        excludedPackageNames: Set<String>,
        limit: Int = 3
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val recentPackages = mutableListOf<String>()

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager != null) {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (1000 * 60 * 60 * 6) // Past 6 hours
            val usageList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                startTime,
                endTime
            )
            val sorted = usageList?.sortedByDescending { it.lastTimeUsed } ?: emptyList()
            for (usage in sorted) {
                val pkg = usage.packageName
                if (pkg != context.packageName &&
                    !excludedPackageNames.contains(pkg) &&
                    !recentPackages.contains(pkg) &&
                    pm.getLaunchIntentForPackage(pkg) != null
                ) {
                    recentPackages.add(pkg)
                    if (recentPackages.size >= limit) break
                }
            }
        }

        // Map packages to AppInfo models
        recentPackages.mapNotNull { pkg ->
            DockAppsManager.getAppInfoForPackage(pm, pkg)
        }
    }
}
