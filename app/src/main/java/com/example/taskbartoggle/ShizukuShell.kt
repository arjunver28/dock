package com.example.taskbartoggle

import android.app.Activity
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
) {
    val isSuccess: Boolean get() = exitCode == 0
}

object ShizukuShell {

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            if (isShizukuAvailable()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (isShizukuAvailable() && Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(requestCode)
            } else if (isShizukuAvailable()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (ignored: Exception) {}
    }

    fun exec(command: String): Result<ShellResult> {
        return try {
            if (!isShizukuAvailable()) {
                return Result.failure(IllegalStateException("Shizuku is not running. Please start Shizuku service."))
            }
            if (!hasPermission()) {
                return Result.failure(IllegalStateException("Shizuku permission not granted."))
            }

            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val outThread = Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stdoutBuilder.append(line).append("\n")
                    }
                }
            }

            val errThread = Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stderrBuilder.append(line).append("\n")
                    }
                }
            }

            outThread.start()
            errThread.start()

            val exitCode = process.waitFor()
            outThread.join()
            errThread.join()

            Result.success(ShellResult(stdoutBuilder.toString().trim(), stderrBuilder.toString().trim(), exitCode))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Option A: Force Android OS to enable Freeform Windowing & Multi-Window globally on phone display.
     */
    fun enableGlobalFreeform(): Result<ShellResult> {
        val cmd = "settings put global enable_freeform_support 1 && settings put global force_resizable_activities 1"
        return exec(cmd)
    }

    /**
     * Reset / Disable Global Freeform Support.
     */
    fun disableGlobalFreeform(): Result<ShellResult> {
        val cmd = "settings put global enable_freeform_support 0 && settings put global force_resizable_activities 0"
        return exec(cmd)
    }

    /**
     * Option B: Lower window manager density to trigger Android 600dp Tablet UI / Freeform mode.
     */
    fun setTabletDensity(targetDpi: Int = 280): Result<ShellResult> {
        val cmd = "wm density $targetDpi"
        return exec(cmd)
    }

    /**
     * Reset display density to default hardware scaling.
     */
    fun resetDensity(): Result<ShellResult> {
        val cmd = "wm density reset"
        return exec(cmd)
    }

    /**
     * Query current display density.
     */
    fun getDensityInfo(): Result<ShellResult> {
        return exec("wm density")
    }
}
