package com.jarvis.assistant.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import com.jarvis.assistant.core.model.DeviceInfoField
import com.jarvis.assistant.core.model.ExecutionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(field: DeviceInfoField): ExecutionResult.Success {
        val text = when (field) {
            DeviceInfoField.BATTERY -> battery()
            DeviceInfoField.STORAGE -> storage()
            DeviceInfoField.MEMORY -> memory()
            DeviceInfoField.NETWORK -> network()
            DeviceInfoField.MODEL -> model()
            DeviceInfoField.SUMMARY -> "${battery()}, ${storage()}, ${network()}"
        }
        return ExecutionResult.Success(text)
    }

    fun battery(): String {
        val intent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return "Battery level is unavailable on this device."
        val percent = level * 100 / scale
        val charging = (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1) in listOf(
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL,
        )
        return "Battery is $percent%" + if (charging) " and charging." else "."
    }

    fun storage(): String {
        val stat = StatFs(context.filesDir.absolutePath)
        val free = stat.availableBytes
        val total = stat.totalBytes
        return "Storage has ${formatGb(free)} free of ${formatGb(total)}."
    }

    fun memory(): String {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        return "About ${formatGb(info.availMem)} of RAM is available."
    }

    fun network(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return "There is no active network connection."
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return "Network state is unavailable."
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "a VPN"
            else -> "another connection"
        }
        val validated = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            "with internet access"
        } else {
            "without verified internet access"
        }
        return "Connected over $transport, $validated."
    }

    fun model(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})."

    private fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format("%.1f GB", gb)
    }
}
