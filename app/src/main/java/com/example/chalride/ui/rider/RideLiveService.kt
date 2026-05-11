package com.example.chalride.ui.rider

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import androidx.core.content.edit

class RideLiveService : Service() {

    // ── Binder so RideLiveFragment can get a reference to this service ────────
    inner class LocalBinder : Binder() {
        fun getService(): RideLiveService = this@RideLiveService
    }
    private val binder = LocalBinder()

    // ── Ride info set when service is started ─────────────────────────────────
    var rideRequestId = ""
    var driverId      = ""
    var driverName    = "Driver"
    var vehicleType   = ""

    // ── Current ride state — fragment reads these on bind ─────────────────────
    var currentStatus   = ""          // latest Firestore ride status
    var currentOtp      = ""          // OTP received from Firestore
    var isPhase2        = false       // true once trip is in_progress
    var otpNotifShown   = false       // guard: only show OTP notif once

    // ── Listener ──────────────────────────────────────────────────────────────
    private var rideListener: ListenerRegistration? = null

    // ── Callback so bound fragment gets live updates ──────────────────────────
    var onStatusChanged: ((status: String, otp: String, cancelReason: String) -> Unit)? = null

    companion object {
        const val EXTRA_RIDE_REQUEST_ID = "rideRequestId"
        const val EXTRA_DRIVER_ID       = "driverId"
        const val EXTRA_DRIVER_NAME     = "driverName"
        const val EXTRA_VEHICLE_TYPE    = "vehicleType"
        const val PREFS_NAME            = "chalride_active_ride"
        const val PREFS_KEY_RIDE_ID     = "activeRideRequestId"
        const val PREFS_KEY_DRIVER_ID   = "activeDriverId"
        const val PREFS_KEY_DRIVER_NAME = "activeDriverName"
        const val PREFS_KEY_VEHICLE     = "activeVehicleType"
        const val PREFS_KEY_PICKUP_LAT  = "activePickupLat"
        const val PREFS_KEY_PICKUP_LNG  = "activePickupLng"
        const val PREFS_KEY_DEST_LAT    = "activeDestLat"
        const val PREFS_KEY_DEST_LNG    = "activeDestLng"
        const val PREFS_KEY_PICKUP_ADDR = "activePickupAddress"
        const val PREFS_KEY_DEST_ADDR   = "activeDestAddress"
        const val PREFS_KEY_FARE        = "activeEstimatedFare"
        const val TAG                   = "RideLiveService"
        var isRunning = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        NotificationHelper.createChannels(this)
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // On START_STICKY restart after process kill, intent is null.
        // Recover ride info from SharedPreferences so the service knows which ride to listen to.
        if (intent != null) {
            rideRequestId = intent.getStringExtra(EXTRA_RIDE_REQUEST_ID) ?: ""
            driverId      = intent.getStringExtra(EXTRA_DRIVER_ID)       ?: ""
            driverName    = intent.getStringExtra(EXTRA_DRIVER_NAME)     ?: "Driver"
            vehicleType   = intent.getStringExtra(EXTRA_VEHICLE_TYPE)    ?: ""
        } else {
            // Sticky restart — recover from prefs
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            rideRequestId = prefs.getString(PREFS_KEY_RIDE_ID,     "") ?: ""
            driverId      = prefs.getString(PREFS_KEY_DRIVER_ID,   "") ?: ""
            driverName    = prefs.getString(PREFS_KEY_DRIVER_NAME, "Driver") ?: "Driver"
            vehicleType   = prefs.getString(PREFS_KEY_VEHICLE,     "") ?: ""
            Log.d(TAG, "Sticky restart — recovered rideId=$rideRequestId from prefs")
        }

        Log.d(TAG, "onStartCommand: rideId=$rideRequestId driver=$driverName")

        // Start as foreground immediately with a persistent notification
        val notification = NotificationHelper.buildRideOngoingNotification(
            this,
            "Your ride is active",
            "$driverName is heading to your pickup"
        )
        startForeground(NotificationHelper.NOTIF_ID_RIDE, notification)

        // Start listening to the ride document
        startRideListener()

        // Return STICKY so Android restarts this service if it gets killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App was swiped away — service keeps running, do nothing extra
        Log.d(TAG, "Task removed — service continues in background")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        rideListener?.remove()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }
    // ─────────────────────────────────────────────────────────────────────────
    // Firestore listener
    // ─────────────────────────────────────────────────────────────────────────

    private fun startRideListener() {
        if (rideRequestId.isEmpty()) return
        rideListener?.remove()

        rideListener = FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Ride listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val status = snapshot.getString("status") ?: return@addSnapshotListener
                val otp    = snapshot.getString("riderOtp") ?: ""

                Log.d(TAG, "Status update: $status  otp=$otp")

                currentStatus = status
                if (otp.isNotEmpty()) currentOtp = otp

                // Update the persistent notification text based on status
                updateOngoingNotification(status, otp)

                // Notify bound fragment if it's attached
                val cancelReason = snapshot.getString("cancellationReason") ?: ""
                onStatusChanged?.invoke(status, otp, cancelReason)
                when (status) {
                    "in_progress" -> isPhase2 = true

                    "completed" -> {
                        updateOngoingNotification("completed", "")
                        clearActiveRidePrefs()
                        stopSelf()
                    }

                    "cancelled" -> {
                        clearActiveRidePrefs()
                        stopSelf()
                    }
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification management
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateOngoingNotification(status: String, otp: String) {
        val alertUser: Boolean
        val title: String
        val message: String

        when (status) {
            "accepted" -> {
                alertUser = false
                title = "Ride confirmed"
                message = "$driverName is heading to your pickup"
            }
            "arrived_at_pickup" -> {
                if (!otpNotifShown && otp.isNotEmpty()) {
                    otpNotifShown = true
                }
                alertUser = !otpNotifShown  // alert only the first time
                title = "$driverName has arrived!"
                message = "OTP: $otp — Share this to start your ride"
            }
            "in_progress" -> {
                alertUser = true            // ping once when trip starts
                title = "Trip in progress"
                message = "You're on your way to the destination"
            }
            "completed" -> {
                alertUser = true
                title = "You've reached your destination! 🎉"
                message = "Your trip is complete. Thank you for riding with ChalRide!"
            }
            else -> {
                alertUser = false
                title = "Your ride is active"
                message = "$driverName is heading to your pickup"
            }
        }

        val notification = NotificationHelper.buildRideOngoingNotification(
            this, title, message, alertUser
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NotificationHelper.NOTIF_ID_RIDE, notification)
    }

    fun clearActiveRidePrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { clear() }
        Log.d(TAG, "Active ride prefs cleared")
    }
}