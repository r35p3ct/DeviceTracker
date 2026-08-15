package com.example.devicetracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class Collector(private val ctx: Context) {

    @Volatile
    private var lastLocation: Location? = null

    @Volatile
    private var wifiResults: List<android.net.wifi.ScanResult> = emptyList()

    private val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val telephony = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.provider == LocationManager.GPS_PROVIDER ||
                lastLocation == null ||
                location.time > (lastLocation?.time ?: 0)
            ) {
                lastLocation = location
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun start() {
        val fine = hasFine()
        try {
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, listener)
            }
        } catch (_: SecurityException) {}
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, listener)
            }
        } catch (_: SecurityException) {}
    }

    fun stop() {
        try {
            locationManager.removeUpdates(listener)
        } catch (_: SecurityException) {}
    }

    fun scanWifi() {
        if (!hasFine()) return
        try {
            wifiManager.startScan()
            wifiResults = wifiManager.scanResults
        } catch (_: Exception) {}
    }

    fun telemetryJson(): JSONObject {
        val j = JSONObject()
        j.put("ts", System.currentTimeMillis() / 1000)

        val loc = lastLocation
        if (loc != null) {
            j.put(
                "loc", JSONObject()
                    .put("lat", loc.latitude)
                    .put("lng", loc.longitude)
                    .put("acc", loc.accuracy)
                    .put("provider", loc.provider ?: "unknown")
                    .put("speed", loc.speed)
            )
        }
        j.put("mock", loc?.isFromMockProvider ?: false)

        val cells = collectCells()
        if (cells.length() > 0) j.put("cell", cells)

        val wifi = collectWifi()
        if (wifi.length() > 0) j.put("wifi", wifi)

        j.put("battery", batteryLevel())
        return j
    }

    fun speed(): Float = lastLocation?.speed ?: 0f

    fun batteryLevel(): Int {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            -1
        }
    }

    private fun collectCells(): JSONArray {
        val arr = JSONArray()
        if (!hasFine()) return arr
        val cells = try {
            telephony?.allCellInfo
        } catch (_: SecurityException) {
            null
        } ?: return arr

        for (c in cells) {
            val o = JSONObject()
            when (c) {
                is CellInfoLte -> {
                    val id = c.cellIdentity
                    o.put("radio", "LTE")
                    o.put("mcc", id.mcc); o.put("mnc", id.mnc)
                    o.put("tac", id.tac); o.put("cid", id.ci)
                    putSignal(o, c.cellSignalStrength.dbm)
                }
                is CellInfoWcdma -> {
                    val id = c.cellIdentity
                    o.put("radio", "WCDMA")
                    o.put("mcc", id.mcc); o.put("mnc", id.mnc)
                    o.put("lac", id.lac); o.put("cid", id.cid)
                    putSignal(o, c.cellSignalStrength.dbm)
                }
                is CellInfoGsm -> {
                    val id = c.cellIdentity
                    o.put("radio", "GSM")
                    o.put("mcc", id.mcc); o.put("mnc", id.mnc)
                    o.put("lac", id.lac); o.put("cid", id.cid)
                    putSignal(o, c.cellSignalStrength.dbm)
                }
                is CellInfoNr -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        o.put("radio", "NR")
                        putSignal(o, c.cellSignalStrength.dbm)
                    }
                }
            }
            o.put("serving", c.isRegistered)
            if (o.length() > 1) arr.put(o)
        }
        return arr
    }

    private fun putSignal(o: JSONObject, dbm: Int) {
        if (dbm != Int.MAX_VALUE && dbm != Int.MIN_VALUE) o.put("rsrp", dbm)
    }

    private fun collectWifi(): JSONArray {
        val arr = JSONArray()
        if (!hasFine()) return arr
        for (sr in wifiResults) {
            if (sr.BSSID.isNullOrBlank()) continue
            arr.put(
                JSONObject()
                    .put("bssid", sr.BSSID)
                    .put("rssi", sr.level)
            )
        }
        return arr
    }

    private fun hasFine(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
