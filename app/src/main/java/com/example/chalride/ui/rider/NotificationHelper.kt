package com.example.chalride.ui.rider

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.chalride.MainActivity
import com.example.chalride.R

object NotificationHelper {

    const val CHANNEL_RIDE_STATUS   = "ride_status_channel"
    const val CHANNEL_OTP_ALERT     = "otp_alert_channel"
    const val NOTIF_ID_RIDE_ONGOING = 1001
    const val NOTIF_ID_OTP          = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        // ── Channel 1: Ongoing ride status (silent, persistent) ──────────────
        val rideChannel = NotificationChannel(
            CHANNEL_RIDE_STATUS,
            "Ride Status",
            NotificationManager.IMPORTANCE_LOW          // silent — no sound
        ).apply {
            description = "Shows ongoing ride status while your trip is active"
            setShowBadge(false)
        }
        manager.createNotificationChannel(rideChannel)

        // ── Channel 2: OTP alert (high importance — sound + heads-up) ────────
        val otpSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val otpChannel = NotificationChannel(
            CHANNEL_OTP_ALERT,
            "Driver Arrived Alert",
            NotificationManager.IMPORTANCE_HIGH         // heads-up + sound
        ).apply {
            description = "Alerts you with sound when your driver arrives"
            setSound(otpSound, audioAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setShowBadge(true)
        }
        manager.createNotificationChannel(otpChannel)
    }

    /**
     * Builds the persistent foreground notification shown while ride is active.
     * This is the notification the user sees when they press Home.
     */
    fun buildRideOngoingNotification(
        context: Context,
        title: String,
        message: String
    ): android.app.Notification {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openRideLive", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_RIDE_STATUS)
            .setSmallIcon(R.drawable.ic_pickup_marker)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)                           // user cannot swipe away
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Shows a high-priority OTP alert notification with sound.
     * Called when driver status changes to arrived_at_pickup.
     */
    fun showOtpNotification(
        context: Context,
        driverName: String,
        otp: String
    ) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openRideLive", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 1, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_OTP_ALERT)
            .setSmallIcon(R.drawable.ic_pickup_marker)
            .setContentTitle("$driverName has arrived!")
            .setContentText("Share OTP $otp to start your ride")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your driver $driverName is waiting at the pickup point.\nShare OTP: $otp to begin your trip."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.notify(NOTIF_ID_OTP, notification)
    }

    /**
     * Shows a destination reached notification when trip completes in background.
     */
    fun showDestinationReachedNotification(context: Context) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 2, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_OTP_ALERT)
            .setSmallIcon(R.drawable.ic_pickup_marker)
            .setContentTitle("You've reached your destination! 🎉")
            .setContentText("Your trip is complete. Thank you for riding with ChalRide!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.notify(NOTIF_ID_OTP, notification)
    }

    fun cancelOtpNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.cancel(NOTIF_ID_OTP)
    }
}