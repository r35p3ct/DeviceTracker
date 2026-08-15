package com.example.devicetracker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object VendorSettings {

    /** Известные Intents для экранов автозапуска / фоновой активности разных оболочек. */
    private val autostartIntents: List<Intent>
        get() = listOf(
            // Oppo / ColorOS / Realme
            Intent().setComponent(ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )),
            Intent().setComponent(ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )),
            // Xiaomi / MIUI / HyperOS
            Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )),
            // Huawei / Honor (EMUI / Magic UI)
            Intent().setComponent(ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )),
            // Samsung
            Intent().setComponent(ComponentName(
                "com.samsung.android.sm",
                "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity"
            )),
            // OnePlus
            Intent().setComponent(ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )),
            // Vivo / iQOO
            Intent().setComponent(ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )),
            Intent().setComponent(ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ))
        )

    private fun Context.packageExists(pkg: String): Boolean =
        try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }

    /** Пытается открыть известный экран автозапуска. Возвращает true, если удалось. */
    fun openAutostartSettings(context: Context): Boolean {
        for (base in autostartIntents) {
            val intent = base.cloneFilter() as Intent
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val cn = intent.component ?: continue
            if (!context.packageExists(cn.packageName)) continue
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                try {
                    context.startActivity(intent)
                    return true
                } catch (_: Exception) {}
            }
        }
        return false
    }

    /** Открывает системные настройки приложения (background location, батарея и т.д.). */
    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    /** Проверяет, что background location уже выдано (Android 10+). */
    fun hasBackgroundLocation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
