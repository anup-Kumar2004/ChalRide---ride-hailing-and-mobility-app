package com.example.chalride.ui.driver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.chalride.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore

class DriverLocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // True only after THIS service instance has successfully written isOnline=true to Firestore.
    // Guards against the presenceRef listener firing with a stale RTDB value on startup.
    private var serviceHasWrittenOnline = false

    companion object {
        const val CHANNEL_ID      = "driver_location_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupFirebasePresence()
        startLocationUpdates()
    }

    // ── Firebase Realtime Database presence ───────────────────────────────────
    //
    // DESIGN:
    // RTDB only tracks `isOnline` (is the app running).
    // It does NOT touch `isAvailable` on reconnect — the driver might have
    // been mid-ride when they crashed. Setting isAvailable=true here would
    // incorrectly open them up for new rides while still on one.
    //
    // CRASH path:  onDisconnect fires → RTDB isOnline=false
    //              → listener mirrors isOnline=false to Firestore only
    //              → isAvailable in Firestore stays as ride-logic last set it
    //              → driver reopens app → DriverHomeFragment checks activeRideId → resumes
    //
    // NORMAL STOP: onDestroy() sets isOnline=false AND isAvailable=false in Firestore

    private fun setupFirebasePresence() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val rtdb = FirebaseDatabase.getInstance()

        val connectedRef = rtdb.getReference(".info/connected")
        val presenceRef  = rtdb.getReference("driverPresence/$uid")

        // ── Listener 1: React to RTDB connection state ────────────────────────
        connectedRef.addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                android.util.Log.d("DriverPresence", "RTDB connected=$connected")
                if (!connected) return

                // Register what RTDB should write on crash/kill
                presenceRef.onDisconnect().setValue(mapOf(
                    "isOnline" to false,
                    "lastSeen" to com.google.firebase.database.ServerValue.TIMESTAMP
                ))

                // Write online to RTDB now
                presenceRef.setValue(mapOf(
                    "isOnline" to true,
                    "lastSeen" to com.google.firebase.database.ServerValue.TIMESTAMP
                ))

                // Mirror isOnline=true to Firestore and set the guard flag
                FirebaseFirestore.getInstance()
                    .collection("drivers").document(uid)
                    .update("isOnline", true)
                    .addOnSuccessListener {
                        serviceHasWrittenOnline = true
                        android.util.Log.d("DriverPresence", "✅ connectedRef → isOnline=true written to Firestore. Guard flag SET.")
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.e("DriverPresence", "❌ connectedRef → failed to write isOnline=true: ${e.message}")
                    }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                android.util.Log.e("DriverPresence", "connectedRef cancelled: ${error.message}")
            }
        })

        // ── Listener 2: Watch for crash/disconnect ────────────────────────────
        // IMPORTANT: This listener fires immediately on attach with the current RTDB value.
        // If RTDB still holds isOnline=false from the previous Go Offline,
        // we must NOT write isOnline=false to Firestore — that would clobber
        // the isOnline=true we just wrote via transitionDriverState().
        // The guard flag `serviceHasWrittenOnline` prevents this.
        presenceRef.addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val isOnlineInRtdb = snapshot.child("isOnline").getValue(Boolean::class.java)
                android.util.Log.d("DriverPresence", "presenceRef fired → isOnline=$isOnlineInRtdb | guardFlag=$serviceHasWrittenOnline")

                if (isOnlineInRtdb == null) {
                    android.util.Log.d("DriverPresence", "presenceRef: isOnline is null — node doesn't exist yet, ignoring")
                    return
                }

                if (!isOnlineInRtdb && serviceHasWrittenOnline) {
                    // REAL crash/disconnect during this service's lifetime
                    android.util.Log.w("DriverPresence",
                        "⚠️ Real crash/disconnect detected — checking for active ride before Firestore update")

                    // CRITICAL: Check if driver has an active ride before deciding what to write.
                    // - No active ride → full OFFLINE (clear everything)
                    // - Active ride    → only isOnline=false (preserve ride data for resumption)
                    FirebaseFirestore.getInstance()
                        .collection("drivers").document(uid)
                        .get()
                        .addOnSuccessListener { doc ->
                            val activeRideId = doc.getString("activeRideId")
                            if (activeRideId.isNullOrEmpty()) {
                                // Idle crash — safe to go fully OFFLINE
                                android.util.Log.d("DriverPresence",
                                    "Crash: no active ride → writing full OFFLINE map")
                                FirebaseFirestore.getInstance()
                                    .collection("drivers").document(uid)
                                    .update(DriverState.OFFLINE.toFirestoreMap())
                                    .addOnSuccessListener {
                                        android.util.Log.d("DriverPresence",
                                            "✅ Idle crash: OFFLINE map written to Firestore")
                                    }
                            } else {
                                // Mid-ride crash — only mark offline, preserve ride data
                                android.util.Log.d("DriverPresence",
                                    "Crash: active ride=$activeRideId → writing isOnline=false only")
                                FirebaseFirestore.getInstance()
                                    .collection("drivers").document(uid)
                                    .update("isOnline", false)
                                    .addOnSuccessListener {
                                        android.util.Log.d("DriverPresence",
                                            "✅ Mid-ride crash: isOnline=false written. Ride data preserved.")
                                    }
                            }
                        }
                        .addOnFailureListener { e ->
                            android.util.Log.e("DriverPresence",
                                "❌ Crash: Firestore read failed: ${e.message}")
                        }
                }
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                android.util.Log.e("DriverPresence", "presenceRef cancelled: ${error.message}")
            }
        })
    }

    private fun updateNotificationMidRide() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChalRide — Ride In Progress")
            .setContentText("You have an active ride. Tap to return to the app.")
            .setSmallIcon(R.drawable.ic_driver_marker)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        android.util.Log.d("DriverService", "Mid-ride notification shown — service kept alive")
    }




    // ── Location updates ──────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).setMinUpdateIntervalMillis(3000L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

                // Always update geohash alongside lat/lng so RideConfirmFragment
                // queries work correctly. Without this, geohash stays "" from
                // profile setup and the range query returns zero results.
                val geohash = encodeGeohash(location.latitude, location.longitude, precision = 5)

                FirebaseFirestore.getInstance()
                    .collection("drivers").document(uid)
                    .update(mapOf(
                        "lat"         to location.latitude,
                        "lng"         to location.longitude,
                        "geohash"     to geohash,
                        "lastUpdated" to System.currentTimeMillis()
                    ))
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.e("DriverService", "Location permission missing: ${e.message}")
        }
    }

    // ── Normal shutdown — driver tapped Go Offline ────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Normal stop — cancel the onDisconnect handler (we're stopping intentionally)
        FirebaseDatabase.getInstance()
            .getReference("driverPresence/$uid")
            .onDisconnect().cancel()

        FirebaseDatabase.getInstance()
            .getReference("driverPresence/$uid")
            .setValue(mapOf(
                "isOnline" to false,
                "lastSeen" to com.google.firebase.database.ServerValue.TIMESTAMP
            ))

        // Reset guard flag so next service instance starts clean
        serviceHasWrittenOnline = false

        android.util.Log.d("DriverService", "🛑 onDestroy: writing OFFLINE to Firestore, guard flag cleared")

        // Intentional stop — transition to OFFLINE clears all state fields atomically
        FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)
            .update(DriverState.OFFLINE.toFirestoreMap())
            .addOnSuccessListener {
                android.util.Log.d("DriverService", "✅ OFFLINE map written to Firestore successfully")
            }
            .addOnFailureListener { e ->
                android.util.Log.e("DriverService", "❌ OFFLINE write failed: ${e.message}")
            }
    }

    /**
     * Called when the user swipes the app away from Recents.
     * Android guarantees this is called before onDestroy() for foreground services
     * (though timing can vary slightly).
     *
     * Decision logic:
     *  - No active ride → stop the service → onDestroy() writes OFFLINE to Firestore
     *  - Active ride    → keep service alive, update notification to "Ride in progress"
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("DriverService", "onTaskRemoved called")

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            android.util.Log.d("DriverService", "onTaskRemoved: no uid, stopping service")
            stopSelf()
            return
        }

        FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val activeRideId = doc.getString("activeRideId")
                if (activeRideId.isNullOrEmpty()) {
                    // Idle driver swiped away → go offline cleanly
                    android.util.Log.d("DriverService",
                        "onTaskRemoved: no active ride → stopping service (will write OFFLINE)")
                    stopSelf()
                } else {
                    // Mid-ride swipe → keep service running for resumption
                    android.util.Log.d("DriverService",
                        "onTaskRemoved: active ride=$activeRideId → keeping service alive")
                    updateNotificationMidRide()
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.e("DriverService",
                    "onTaskRemoved: Firestore check failed, stopping service: ${e.message}")
                stopSelf()
            }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Driver Location", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps your location active while you're online" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("ChalRide — You're Online")
        .setContentText("Waiting for ride requests nearby...")
        .setSmallIcon(R.drawable.ic_driver_marker)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Geohash encoder ───────────────────────────────────────────────────────

    private fun encodeGeohash(lat: Double, lng: Double, precision: Int = 5): String {
        val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        var minLat = -90.0; var maxLat = 90.0
        var minLng = -180.0; var maxLng = 180.0
        val hash = StringBuilder()
        var bits = 0; var bitsTotal = 0; var hashValue = 0
        while (hash.length < precision) {
            if (bitsTotal % 2 == 0) {
                val mid = (minLng + maxLng) / 2
                if (lng >= mid) { hashValue = hashValue * 2 + 1; minLng = mid }
                else { hashValue *= 2; maxLng = mid }
            } else {
                val mid = (minLat + maxLat) / 2
                if (lat >= mid) { hashValue = hashValue * 2 + 1; minLat = mid }
                else { hashValue *= 2; maxLat = mid }
            }
            bits++; bitsTotal++
            if (bits == 5) { hash.append(base32[hashValue]); bits = 0; hashValue = 0 }
        }
        return hash.toString()
    }
}