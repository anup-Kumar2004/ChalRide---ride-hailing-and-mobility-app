package com.example.chalride.ui.driver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import androidx.core.app.NotificationCompat
import com.example.chalride.MainActivity
import com.example.chalride.R

/**
 * DriverNotificationManager
 *
 * Single notification (ID = 2001) that updates itself in-place throughout
 * the entire driver session. Two channels control sound behaviour:
 *
 *   CHANNEL_SILENT  (IMPORTANCE_LOW)  — status updates, no sound, no heads-up
 *   CHANNEL_ALERT   (IMPORTANCE_HIGH) — events needing attention, sound + heads-up
 *
 * Lifecycle:
 *   Go Online          → silent  "You're Online"
 *   Ride Request       → alert   "New Ride Request"
 *   Reject / Timeout   → silent  "You're Online"        (back to waiting)
 *   Accept Ride        → silent  "Trip Ongoing..."
 *   Ride Cancelled     → alert   "Ride Cancelled"       (auto-reverts after 45s)
 *   Trip Completed     → alert   "Trip Completed"       (auto-reverts after 60s)
 *   Go Offline         → notification dismissed
 */
object DriverNotificationManager {

    const val NOTIF_ID = 2001

    private const val CHANNEL_SILENT = "driver_status_silent"
    private const val CHANNEL_ALERT  = "driver_event_alert"

    // Intent extras
    const val EXTRA_NOTIF_TYPE      = "driver_notif_type"
    const val EXTRA_RIDE_REQUEST_ID = "notif_ride_request_id"
    const val EXTRA_RIDER_NAME      = "notif_rider_name"
    const val EXTRA_ESTIMATED_FARE  = "notif_estimated_fare"
    const val EXTRA_CANCEL_REASON   = "notif_cancel_reason"

    // Notification type values
    const val TYPE_RIDE_REQUEST = "NEW_RIDE_REQUEST"
    const val TYPE_CANCELLED    = "RIDE_CANCELLED"
    const val TYPE_COMPLETED    = "TRIP_COMPLETED"

    // Handler for auto-revert timer (cancellation / completion → back to You're Online)
    private val revertHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var revertRunnable: Runnable? = null

    // ── Channel creation ──────────────────────────────────────────────────────

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val alertAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val defaultSound = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI

        // Silent channel — for persistent status updates
        val silentChannel = NotificationChannel(
            CHANNEL_SILENT,
            "Driver Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent status while driver is online"
            setSound(null, null)
            enableVibration(false)
        }

        // Alert channel — for events needing immediate attention
        val alertChannel = NotificationChannel(
            CHANNEL_ALERT,
            "Ride Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "New ride requests, cancellations, completions"
            setSound(defaultSound, alertAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300)
        }

        nm.createNotificationChannel(silentChannel)
        nm.createNotificationChannel(alertChannel)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called when driver taps GO ONLINE.
     * Silent — only visible in tray, no sound, no heads-up.
     * This notification is also what DriverLocationService uses for startForeground().
     */
    fun buildOnlineNotification(context: Context): android.app.Notification {
        cancelRevertTimer()
        val pendingIntent = buildHomePendingIntent(context, requestCode = 0)
        return NotificationCompat.Builder(context, CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_driver_marker)
            .setContentTitle("You're Online")
            .setContentText("Waiting for ride requests nearby...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    /**
     * Post / update the notification to "You're Online".
     * Call this when driver goes online OR after a ride request expires/is rejected.
     */
    fun notifyOnline(context: Context) {
        cancelRevertTimer()
        notify(context, buildOnlineNotification(context))
    }

    /**
     * New ride request arrived — alert with sound and heads-up.
     */
    fun notifyNewRideRequest(
        context: Context,
        rideRequestId: String,
        estimatedFare: Int,
        pickupAddress: String,
        destAddress: String
    ) {
        cancelRevertTimer()

        val tapIntent = buildTapIntent(context, TYPE_RIDE_REQUEST).apply {
            putExtra(EXTRA_RIDE_REQUEST_ID, rideRequestId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 1, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_driver_marker)
            .setContentTitle("New Ride Request")
            .setContentText("₹$estimatedFare · $pickupAddress → $destAddress")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .build()

        notify(context, notification)
    }

    /**
     * Driver accepted a ride — silent "Trip Ongoing..." update.
     */
    fun notifyTripOngoing(context: Context) {
        cancelRevertTimer()
        val pendingIntent = buildHomePendingIntent(context, requestCode = 2)

        val notification = NotificationCompat.Builder(context, CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_driver_marker)
            .setContentTitle("Ride Accepted")
            .setContentText("Trip Ongoing...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

        notify(context, notification)
    }

    /**
     * Ride was cancelled — alert with sound.
     * Auto-reverts to "You're Online" after 45 seconds.
     */
    fun notifyRideCancelled(
        context: Context,
        cancelReason: com.example.chalride.ui.rider.CancelReason,
        riderName: String,
        rideRequestId: String = ""
    ) {
        cancelRevertTimer()

        val tapIntent = buildTapIntent(context, TYPE_CANCELLED).apply {
            putExtra(EXTRA_RIDER_NAME,      riderName)
            putExtra(EXTRA_RIDE_REQUEST_ID, rideRequestId)
            putExtra(EXTRA_CANCEL_REASON,   cancelReason.name)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 3, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, body) = when (cancelReason) {
            com.example.chalride.ui.rider.CancelReason.RIDER_NO_SHOW ->
                "Rider No-Show" to "Rider didn't arrive in time. You're free for new rides."
            com.example.chalride.ui.rider.CancelReason.RIDER_CANCELLED ->
                "Ride Cancelled" to "${riderName.ifEmpty { "Rider" }} cancelled the trip."
            else ->
                "Ride Cancelled" to "This ride was cancelled. You're available for new rides."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_driver_marker)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .build()

        notify(context, notification)

        // Auto-revert to "You're Online" after 45 seconds
        scheduleRevert(context, delayMs = 45_000L)
    }

    /**
     * Trip completed — alert with sound.
     * Auto-reverts to "You're Online" after 60 seconds.
     */
    fun notifyTripCompleted(
        context: Context,
        riderName: String,
        estimatedFare: Int,
        rideRequestId: String = ""
    ) {
        cancelRevertTimer()

        val tapIntent = buildTapIntent(context, TYPE_COMPLETED).apply {
            putExtra(EXTRA_RIDER_NAME,      riderName)
            putExtra(EXTRA_ESTIMATED_FARE,  estimatedFare)
            putExtra(EXTRA_RIDE_REQUEST_ID, rideRequestId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 4, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_driver_marker)
            .setContentTitle("Trip Completed")
            .setContentText("You earned ₹$estimatedFare. Great ride!")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .build()

        notify(context, notification)

        // Auto-revert to "You're Online" after 60 seconds
        scheduleRevert(context, delayMs = 60_000L)
    }

    /**
     * Driver went offline — dismiss the notification entirely.
     */
    fun dismiss(context: Context) {
        cancelRevertTimer()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun scheduleRevert(context: Context, delayMs: Long) {
        revertRunnable = Runnable { notifyOnline(context) }
        revertHandler.postDelayed(revertRunnable!!, delayMs)
    }

    private fun cancelRevertTimer() {
        revertRunnable?.let { revertHandler.removeCallbacks(it) }
        revertRunnable = null
    }

    private fun buildTapIntent(context: Context, type: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIF_TYPE, type)
        }

    private fun buildHomePendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notify(context: Context, notification: android.app.Notification) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notification)
    }
}