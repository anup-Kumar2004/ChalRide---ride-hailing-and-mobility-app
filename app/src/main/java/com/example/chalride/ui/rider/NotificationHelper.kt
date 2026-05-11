package com.example.chalride.ui.rider

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.example.chalride.MainActivity
import com.example.chalride.R

object NotificationHelper {

    const val CHANNEL_RIDE          = "ride_channel"
    const val NOTIF_ID_RIDE         = 1001          // single ID for the whole ride

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // One HIGH-importance channel for the whole ride.
        // setOnlyAlertOnce(true) on silent updates keeps it quiet during tracking.
        val rideChannel = NotificationChannel(
            CHANNEL_RIDE,
            "Ride Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Ride status alerts and ongoing trip tracking"
            setSound(sound, audioAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setShowBadge(true)
        }
        manager.createNotificationChannel(rideChannel)
    }


    fun buildRideOngoingNotification(
        context: Context,
        title: String,
        message: String,
        alertUser: Boolean = false   // true only for important events
    ): android.app.Notification {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openRideLive", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_RIDE)
            .setSmallIcon(R.drawable.ic_pickup_marker)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(!alertUser)   // silent unless this is an important event
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }


    fun cancelRideNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.cancel(NOTIF_ID_RIDE)
    }
}