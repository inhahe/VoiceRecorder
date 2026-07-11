package com.lithic.voicerecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat

/**
 * Central helper for the two permission families the app needs:
 *  - microphone (RECORD_AUDIO) and, on API 33+, POST_NOTIFICATIONS — normal runtime permissions
 *  - storage — either "All files access" (MANAGE_EXTERNAL_STORAGE, API 30+) or the legacy
 *    WRITE_EXTERNAL_STORAGE runtime permission (API 26-29).
 */
object PermissionUtil {

    fun hasMicPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    /** Runtime permissions to request via ActivityResultContracts.RequestMultiplePermissions. */
    fun runtimePermissionsToRequest(ctx: Context): Array<String> {
        val list = mutableListOf<String>()
        if (!hasMicPermission(ctx)) list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(ctx)) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !hasLegacyStoragePermission(ctx)) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return list.toTypedArray()
    }

    private fun hasLegacyStoragePermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the app can write to arbitrary user-chosen folders. */
    fun hasStoragePermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            hasLegacyStoragePermission(ctx)
        }

    /** On API 30+, "All files access" is granted through a dedicated Settings screen, not a dialog. */
    fun needsAllFilesAccessScreen(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    fun allFilesAccessIntent(ctx: Context): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    /**
     * The app's "App info" screen. For sideloaded apps this is where the ⋮ menu's
     * "Allow restricted settings" lives, which must be unlocked before the "All files access"
     * switch becomes tappable.
     */
    fun appDetailsIntent(ctx: Context): Intent =
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
        }

    fun allPermissionsGranted(ctx: Context): Boolean =
        hasMicPermission(ctx) && hasStoragePermission(ctx)
}
