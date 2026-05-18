package com.example.chalride

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import com.example.chalride.data.repository.AuthRepository
import com.example.chalride.databinding.ActivityMainBinding
import kotlinx.coroutines.runBlocking
import android.content.Intent
import com.example.chalride.ui.driver.DriverNotificationManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)

        // Determine start destination BEFORE graph is set
        val startDestination = getStartDestination()
        navGraph.setStartDestination(startDestination)
        navController.graph = navGraph


        // Handle tap from RideLive notification — route to active ride screen
        if (intent?.getBooleanExtra("openRideLive", false) == true) {
            val prefs = getSharedPreferences(
                com.example.chalride.ui.rider.RideLiveService.PREFS_NAME,
                MODE_PRIVATE
            )
            val savedRideId = prefs.getString(
                com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_RIDE_ID, ""
            ) ?: ""
            if (savedRideId.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putString("rideRequestId", savedRideId)
                    putString("driverId",      prefs.getString(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_DRIVER_ID, ""))
                    putString("driverName",    prefs.getString(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_DRIVER_NAME, "Driver"))
                    putString("vehicleType",   prefs.getString(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_VEHICLE, ""))
                    putDouble("pickupLat",     Double.fromBits(prefs.getLong(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_PICKUP_LAT, 0L)))
                    putDouble("pickupLng",     Double.fromBits(prefs.getLong(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_PICKUP_LNG, 0L)))
                    putDouble("destLat",       Double.fromBits(prefs.getLong(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_DEST_LAT, 0L)))
                    putDouble("destLng",       Double.fromBits(prefs.getLong(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_DEST_LNG, 0L)))
                    putString("pickupAddress", prefs.getString(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_PICKUP_ADDR, ""))
                    putString("destAddress",   prefs.getString(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_DEST_ADDR, ""))
                    putInt("estimatedFare",    prefs.getInt(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_FARE, 0))
                }
                navController.navigate(R.id.rideLiveFragment, bundle)
            }
        }

        handleDriverNotificationTap(intent)

    }




    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)  // update the intent so getIntent() returns the new one

        if (intent.getBooleanExtra("openRideLive", false)) {
            val prefs = getSharedPreferences(
                com.example.chalride.ui.rider.RideLiveService.PREFS_NAME,
                MODE_PRIVATE
            )
            val savedRideId = prefs.getString(
                com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_RIDE_ID, ""
            ) ?: ""
            if (savedRideId.isNotEmpty()) {
                val navHostFragment = supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                val navController = navHostFragment?.navController ?: return
                val bundle = Bundle().apply {
                    putString("rideRequestId", savedRideId)
                    putString("driverId",      prefs.getString(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_DRIVER_ID, ""))
                    putString("driverName",    prefs.getString(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_DRIVER_NAME, "Driver"))
                    putString("vehicleType",   prefs.getString(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_VEHICLE, ""))
                    putDouble("pickupLat",     Double.fromBits(prefs.getLong(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_PICKUP_LAT, 0L)))
                    putDouble("pickupLng",     Double.fromBits(prefs.getLong(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_PICKUP_LNG, 0L)))
                    putDouble("destLat",       Double.fromBits(prefs.getLong(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_DEST_LAT, 0L)))
                    putDouble("destLng",       Double.fromBits(prefs.getLong(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_DEST_LNG, 0L)))
                    putString("pickupAddress", prefs.getString(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_PICKUP_ADDR, ""))
                    putString("destAddress",   prefs.getString(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_DEST_ADDR, ""))
                    putInt("estimatedFare",    prefs.getInt(com.example.chalride.ui.rider.RideLiveService.PREFS_KEY_FARE, 0))
                }
                // Only navigate if we're not already on RideLiveFragment
                if (navController.currentDestination?.id != R.id.rideLiveFragment) {
                    navController.navigate(R.id.rideLiveFragment, bundle)
                }
            }
        }

        handleDriverNotificationTap(intent)
    }


    /**
     * Handles taps on DriverNotificationManager notifications.
     * Routes the driver to the correct fragment based on EXTRA_NOTIF_TYPE.
     */
    private fun handleDriverNotificationTap(intent: Intent?) {
        val type = intent?.getStringExtra(DriverNotificationManager.EXTRA_NOTIF_TYPE) ?: return

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        val navController = navHostFragment.navController

        when (type) {

            DriverNotificationManager.TYPE_RIDE_REQUEST -> {
                // Navigate to DriverHome — the Firestore listener there
                // will re-show the sheet if the request is still pending.
                if (navController.currentDestination?.id != R.id.driverHomeFragment) {
                    navController.navigate(
                        R.id.driverHomeFragment,
                        null,
                        androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_graph, true)
                            .build()
                    )
                }
                // Clear the extra so screen rotation doesn't re-trigger
                intent.removeExtra(DriverNotificationManager.EXTRA_NOTIF_TYPE)
            }

            DriverNotificationManager.TYPE_CANCELLED -> {
                // Guard: if driver is already on DriverHome, the ride was already handled.
                // Do not navigate back to CancelledFragment over the top of DriverHome.
                val currentDest = navController.currentDestination?.id
                if (currentDest == R.id.driverHomeFragment) {
                    // Driver already handled it — just clear the extra and do nothing
                    intent.removeExtra(DriverNotificationManager.EXTRA_NOTIF_TYPE)
                    return
                }
                val bundle = Bundle().apply {
                    putString("cancelReason",  intent.getStringExtra(DriverNotificationManager.EXTRA_CANCEL_REASON) ?: "RIDER_CANCELLED")
                    putString("riderName",     intent.getStringExtra(DriverNotificationManager.EXTRA_RIDER_NAME) ?: "")
                    putString("rideRequestId", intent.getStringExtra(DriverNotificationManager.EXTRA_RIDE_REQUEST_ID) ?: "")
                }
                if (currentDest != R.id.driverRideCancelledFragment) {
                    navController.navigate(
                        R.id.driverRideCancelledFragment, bundle,
                        androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_graph, true)
                            .build()
                    )
                }
                intent.removeExtra(DriverNotificationManager.EXTRA_NOTIF_TYPE)
            }

            DriverNotificationManager.TYPE_COMPLETED -> {
                // Guard: if driver is already on DriverHome, the ride was already handled.
                val currentDest = navController.currentDestination?.id
                if (currentDest == R.id.driverHomeFragment) {
                    intent.removeExtra(DriverNotificationManager.EXTRA_NOTIF_TYPE)
                    return
                }
                val bundle = Bundle().apply {
                    putString("riderName",     intent.getStringExtra(DriverNotificationManager.EXTRA_RIDER_NAME) ?: "")
                    putInt("estimatedFare",    intent.getIntExtra(DriverNotificationManager.EXTRA_ESTIMATED_FARE, 0))
                    putString("rideRequestId", intent.getStringExtra(DriverNotificationManager.EXTRA_RIDE_REQUEST_ID) ?: "")
                }
                if (currentDest != R.id.driverRideCompletedFragment) {
                    navController.navigate(
                        R.id.driverRideCompletedFragment, bundle,
                        androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_graph, true)
                            .build()
                    )
                }
                intent.removeExtra(DriverNotificationManager.EXTRA_NOTIF_TYPE)
            }
        }
    }


    private fun getStartDestination(): Int {
        val currentUser = authRepository.currentUser
            ?: return R.id.roleSelectionFragment

        return runBlocking {
            val role = authRepository.getUserRole(currentUser.uid)

            when (role) {
                "rider" -> {
                    val profileStep = authRepository.getRiderProfileStep(currentUser.uid)
                    if (profileStep >= 2) R.id.riderHomeFragment
                    else R.id.riderPhoneVerifyFragment
                }
                "driver" -> {
                    // Fetch profileStep to know how far setup got
                    val profileStep = authRepository.getDriverProfileStep(currentUser.uid)
                    when (profileStep) {
                        0 -> R.id.driverProfileSetupFragment    // just registered, no profile yet
                        1 -> R.id.driverVehicleSetupFragment    // profile done, no vehicle yet
                        else -> R.id.driverHomeFragment          // fully set up
                    }
                }

                else -> R.id.roleSelectionFragment
            }
        }
    }
}