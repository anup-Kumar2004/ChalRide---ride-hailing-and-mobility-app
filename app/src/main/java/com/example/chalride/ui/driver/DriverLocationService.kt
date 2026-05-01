package com.example.chalride.ui.driver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.chalride.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Foreground service that runs while the driver is online.
 * Updates the driver's lat/lng/geohash in Firestore every ~8 seconds
 * even when the app is minimized or screen is off.
 *
 * Register in AndroidManifest.xml:
 *   <service
 *       android:name=".ui.driver.DriverLocationService"
 *       android:foregroundServiceType="location"
 *       android:exported="false"/>
 *
 * Also add these permissions to AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
 *   <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
 */
class DriverLocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val db = FirebaseFirestore.getInstance()

    companion object {
        private const val CHANNEL_ID   = "driver_location_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart if killed by system
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        // Mark driver as offline in Firestore when service stops
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        db.collection("drivers").document(uid)
            .update(mapOf("isAvailable" to false, "isOnline" to false))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App was swiped away — force driver offline
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            FirebaseFirestore.getInstance()
                .collection("drivers")
                .document(uid)
                .update(
                    mapOf(
                        "isAvailable" to false,
                        "isOnline"    to false
                    )
                )
        }
        stopSelf()
    }

    // ── Location ─────────────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 8000L
        ).setMinUpdateIntervalMillis(5000L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                writeToFirestore(location.latitude, location.longitude)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    // ── Firestore ─────────────────────────────────────────────────────────────

    private fun writeToFirestore(lat: Double, lng: Double) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val geohash = encodeGeohash(lat, lng, precision = 6)

        db.collection("drivers").document(uid)
            .update(
                mapOf(
                    "lat"         to lat,
                    "lng"         to lng,
                    "geohash"     to geohash,
                    "isAvailable" to true,
                    "isOnline"    to true,
                    "lastUpdated" to System.currentTimeMillis()
                )
            )
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Driver Location",
                NotificationManager.IMPORTANCE_LOW   // silent, no sound
            ).apply {
                description = "Used to track your location while you are online"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChalRide — You are online")
            .setContentText("Receiving ride requests nearby")
            .setSmallIcon(R.drawable.ic_pickup_marker)   // use your app icon here
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)          // cannot be swiped away
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )
            .build()
    }

    // ── Geohash encoder ───────────────────────────────────────────────────────

    private fun encodeGeohash(lat: Double, lng: Double, precision: Int = 6): String {
        val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        var minLat = -90.0;  var maxLat = 90.0
        var minLng = -180.0; var maxLng = 180.0
        val hash = StringBuilder()
        var bits = 0; var bitsTotal = 0; var hashValue = 0

        while (hash.length < precision) {
            if (bitsTotal % 2 == 0) {
                val mid = (minLng + maxLng) / 2
                if (lng >= mid) { hashValue = hashValue * 2 + 1; minLng = mid }
                else            { hashValue *= 2;                 maxLng = mid }
            } else {
                val mid = (minLat + maxLat) / 2
                if (lat >= mid) { hashValue = hashValue * 2 + 1; minLat = mid }
                else            { hashValue *= 2;                 maxLat = mid }
            }
            bits++; bitsTotal++
            if (bits == 5) {
                hash.append(base32[hashValue])
                bits = 0; hashValue = 0
            }
        }
        return hash.toString()
    }
}