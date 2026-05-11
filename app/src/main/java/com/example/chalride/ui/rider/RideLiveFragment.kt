package com.example.chalride.ui.rider

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRideLiveBinding
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.*
import androidx.core.graphics.scale
import kotlinx.coroutines.tasks.await
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.content.pm.PackageManager
import android.os.Build
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import androidx.core.content.edit

class RideLiveFragment : Fragment() {

    private var _binding: FragmentRideLiveBinding? = null
    private val binding get() = _binding!!

    // ── Arguments ─────────────────────────────────────────────────────────────
    private val rideRequestId by lazy { arguments?.getString("rideRequestId") ?: "" }
    private val driverId      by lazy { arguments?.getString("driverId")      ?: "" }
    private val driverName    by lazy { arguments?.getString("driverName")    ?: "Driver" }
    private val vehicleType   by lazy { arguments?.getString("vehicleType")   ?: "" }
    private val pickupLat     by lazy { arguments?.getDouble("pickupLat")     ?: 0.0 }
    private val pickupLng     by lazy { arguments?.getDouble("pickupLng")     ?: 0.0 }
    private val destLat       by lazy { arguments?.getDouble("destLat")       ?: 0.0 }
    private val destLng       by lazy { arguments?.getDouble("destLng")       ?: 0.0 }
    private val pickupAddress by lazy { arguments?.getString("pickupAddress") ?: "" }
    private val destAddress   by lazy { arguments?.getString("destAddress")   ?: "" }
    private val estimatedFare by lazy { arguments?.getInt("estimatedFare")    ?: 0 }

    // ── State ─────────────────────────────────────────────────────────────────
    private var isPhase2 = false
    private var mapPhase2SetupDone = false
    private var networkBannerJob: kotlinx.coroutines.Job? = null
    private var isNetworkAvailable = true
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private var rideListener:   ListenerRegistration? = null
    private var driverListener: ListenerRegistration? = null

    private var driverMarker:  Marker?   = null
    private var pickupMarker:  Marker?   = null
    private var destMarker:    Marker?   = null
    private var routePolyline: Polyline? = null

    private var markerAnimator: android.animation.ValueAnimator? = null
    private var lastKnownOtp = ""   // saved so OTP can be restored after rotation

    // ── Snap-back: restore fit view after user interaction ────────────────────
    // After the user's last touch, we wait SNAP_BACK_DELAY_MS then call
    // restoreFitView() which re-runs zoomToFitWithPadding for the current phase.
    private val snapBackHandler = Handler(Looper.getMainLooper())
    private val SNAP_BACK_DELAY_MS = 9_000L  // 9 seconds after last touch
    private var userIsInteracting = false

    private val snapBackRunnable = Runnable {
        userIsInteracting = false
        restoreFitView()
    }

    // Bearing tracking — for rotating driver arrow
    // ── DO NOT MODIFY — bearing/rotation logic is intentionally unchanged ──────
    private var lastDriverLat = 0.0
    private var lastDriverLng = 0.0
    private var currentBearing = 0f
    // ─────────────────────────────────────────────────────────────────────────

    // Route fetch throttle
    private var lastDriverRouteFetchLat = 0.0
    private var lastDriverRouteFetchLng = 0.0
    private var driverRouteFetchInProgress = false
    private val DRIVER_ROUTE_THRESHOLD_M = 80

    // Phase 2 initial fetch guard
    private var phase2InitialRouteFetched = false
    private var lastFetchedPhase2RoutePoints: List<GeoPoint>? = null
    private var initialFitViewDone = false

    // ── Driver offline watchdog ───────────────────────────────────────────────
    // Tracks the last time we received a driver location update.
    // If the driver's isOnline goes false and doesn't recover within
    // DRIVER_OFFLINE_TIMEOUT_MS, we cancel the ride automatically.
    private var driverOfflineWatchdogJob: kotlinx.coroutines.Job? = null
    private var isDriverOnline = true  // assume online until proven otherwise
    private var stalenessPollingJob: kotlinx.coroutines.Job? = null
    private val LOCATION_STALE_THRESHOLD_MS = 5 * 60 * 1000L  // 5 minutes
    private val POLLING_INTERVAL_MS = 60_000L                  // check every 1 minute


    private var currentRideStatus = ""
    private var otpShownToUser    = false   // OTP card shown in UI at least once
    private var otpVerified       = false   // driver verified OTP, ride is in_progress

    // ── Last known driver position (for snap-back restore) ────────────────────
    private var lastKnownDriverLat = 0.0
    private var lastKnownDriverLng = 0.0

    // Measured at runtime from actual view heights — screen-size independent
    private var mapPadTop    = 0
    private var mapPadBottom = 0
    private var mapPadSide   = 40  // small fixed side margin in px, overridden after measure

    // ── Service binding ───────────────────────────────────────────────────────
    private var rideLiveService: RideLiveService? = null
    private var isServiceBound  = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? RideLiveService.LocalBinder ?: return
            rideLiveService = localBinder.getService()
            isServiceBound  = true
            android.util.Log.d("CHALRIDE_LIVE", "Service bound")

            // Register callback so service pushes status updates to this fragment
            rideLiveService?.onStatusChanged = { status, otp, cancelReason ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (_binding == null) return@launch
                    handleStatusFromService(status, otp, cancelReason)
                }
            }

            // NEW — isPhase2 is NOT set here; switchToPhase2() is the single authority
            rideLiveService?.let { svc ->
                if (svc.currentStatus.isNotEmpty()) {
                    if (svc.isPhase2) {
                        otpVerified = true
                        // intentionally NOT setting isPhase2 here
                    }
                    handleStatusFromService(svc.currentStatus, svc.currentOtp, "")
                }
                if (svc.isPhase2 && !mapPhase2SetupDone) {
                    switchToPhase2()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rideLiveService  = null
            isServiceBound   = false
            android.util.Log.d("CHALRIDE_LIVE", "Service disconnected")
        }
    }




    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRideLiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let {
            currentRideStatus = it.getString("currentRideStatus", "")
            lastKnownOtp = it.getString("lastKnownOtp", "")
            otpShownToUser    = it.getBoolean("otpShownToUser", false)
            otpVerified       = it.getBoolean("otpVerified", false)
            isPhase2          = it.getBoolean("isPhase2", false)
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { }
            }
        )

        val dm = resources.displayMetrics
        mapPadTop    = (dm.heightPixels * 0.18).toInt()
        mapPadBottom = (dm.heightPixels * 0.35).toInt()
        mapPadSide   = (dm.widthPixels  * 0.08).toInt()

        initMap()
        bindStaticData()

        bindNewViews()
        startStatusDotPulse()

        binding.mapView.post {
            if (_binding == null) return@post
            val screenH = binding.mapView.height.takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels
            val screenW = binding.mapView.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            // Use fixed % of screen — reliable on all screen sizes
            // Top: 18% covers the status pill on any screen
            // Bottom: 35% covers the bottom sheet on any screen
            // Side: 8% breathing room
            mapPadTop    = (screenH * 0.18).toInt()
            mapPadBottom = (screenH * 0.35).toInt()
            mapPadSide   = (screenW * 0.08).toInt()
            android.util.Log.d("CHALRIDE_LIVE",
                "Padding set — top:$mapPadTop bottom:$mapPadBottom side:$mapPadSide")
        }

        // Start and bind to RideLiveService
        startAndBindService()
        listenForRideUpdates()
        // Reset marker reference — the old MapView overlay list is gone after view destruction.
        // onResume() will redraw once mapView.onResume() has been called.
        driverMarker = null
        routePolyline = null
        initialFitViewDone = false

        listenForDriverLocation()
        setupCancelButton()

        binding.chipRideDetails.setOnClickListener {
            val bundle = Bundle().apply {
                putString("rideRequestId", rideRequestId)
                putString("driverId",      driverId)
                putString("driverName",    driverName)
                putString("vehicleType",   vehicleType)
                putString("pickupAddress", pickupAddress)
                putString("destAddress",   destAddress)
                putInt("estimatedFare",    estimatedFare)
                putString("currentStatus", currentRideStatus)
            }
            safeNavigate(R.id.action_rideLive_to_rideSummary, bundle)
        }

        registerNetworkCallback()
        startStalenessPolling()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        markerAnimator?.resume()
        snapBackHandler.removeCallbacks(snapBackRunnable)
        snapBackHandler.postDelayed(snapBackRunnable, SNAP_BACK_DELAY_MS)

        // If ride ended while app was backgrounded (STOPPED state blocked safeNavigate),
        // act on the terminal status now that we are RESUMED and navigation is safe.
        when (currentRideStatus) {
            "completed" -> {
                requireContext().stopService(Intent(requireContext(), RideLiveService::class.java))
                safeNavigate(R.id.action_rideLive_to_rideCompletion)
            }
            "cancelled" -> {
                safeNavigate(R.id.action_rideLive_to_rideCancelled)
            }
        }
    }


    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        // Pause marker animation — no point animating while screen is off
        markerAnimator?.pause()
        // Cancel snap-back timer — it was scheduled for 9s but user
        // may return after 30s. Reschedule on resume instead.
        snapBackHandler.removeCallbacks(snapBackRunnable)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentRideStatus", currentRideStatus)
        outState.putString("lastKnownOtp", lastKnownOtp)
        outState.putBoolean("otpShownToUser",   otpShownToUser)
        outState.putBoolean("otpVerified",      otpVerified)
        outState.putBoolean("isPhase2",         isPhase2)
    }


    override fun onDestroyView() {

        // Unregister callback and unbind — service keeps running
        rideLiveService?.onStatusChanged = null
        if (isServiceBound) {
            requireContext().unbindService(serviceConnection)
            isServiceBound = false
        }


        rideListener?.remove()
        driverListener?.remove()
        markerAnimator?.cancel()
        markerAnimator = null
        driverMarker = null
        routePolyline = null
        mapPhase2SetupDone = false
        stalenessPollingJob?.cancel()
        networkBannerJob?.cancel()       // ← ADD
        unregisterNetworkCallback()      // ← ADD
        driverOfflineWatchdogJob?.cancel()   // ADD THIS LINE
        snapBackHandler.removeCallbacks(snapBackRunnable)
        binding.viewStatusDot.animate().cancel()

        super.onDestroyView()
        _binding = null
    }

    private fun startAndBindService() {
        val intent = Intent(requireContext(), RideLiveService::class.java).apply {
            putExtra(RideLiveService.EXTRA_RIDE_REQUEST_ID, rideRequestId)
            putExtra(RideLiveService.EXTRA_DRIVER_ID,       driverId)
            putExtra(RideLiveService.EXTRA_DRIVER_NAME,     driverName)
            putExtra(RideLiveService.EXTRA_VEHICLE_TYPE,    vehicleType)
        }

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }

        if (!RideLiveService.isRunning) {
            ContextCompat.startForegroundService(requireContext(), intent)
        }
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Save ride data to SharedPreferences so RiderHomeFragment
        // can detect an active ride and navigate here on relaunch
        // We save this here because we have all the arguments
        val prefs = requireContext().getSharedPreferences(
            RideLiveService.PREFS_NAME, Context.MODE_PRIVATE
        )
        prefs.edit {
            putString(RideLiveService.PREFS_KEY_RIDE_ID,     rideRequestId)
            putString(RideLiveService.PREFS_KEY_DRIVER_ID,   driverId)
            putString(RideLiveService.PREFS_KEY_DRIVER_NAME, driverName)
            putString(RideLiveService.PREFS_KEY_VEHICLE,     vehicleType)
            putLong(RideLiveService.PREFS_KEY_PICKUP_LAT,    pickupLat.toBits())
            putLong(RideLiveService.PREFS_KEY_PICKUP_LNG,    pickupLng.toBits())
            putLong(RideLiveService.PREFS_KEY_DEST_LAT,      destLat.toBits())
            putLong(RideLiveService.PREFS_KEY_DEST_LNG,      destLng.toBits())
            putString(RideLiveService.PREFS_KEY_PICKUP_ADDR, pickupAddress)
            putString(RideLiveService.PREFS_KEY_DEST_ADDR,   destAddress)
            putInt(RideLiveService.PREFS_KEY_FARE,           estimatedFare)
        }
    }



    // ─────────────────────────────────────────────────────────────────────────
    // Map init
    // ─────────────────────────────────────────────────────────────────────────

    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )
        // Set a reasonable initial zoom; will be overridden by zoomToFitWithPadding
        // once the driver position is first received.
        binding.mapView.controller.setZoom(14.0)
        binding.mapView.controller.setCenter(GeoPoint(pickupLat, pickupLng))

        // ── Intercept touch events to track user interaction ─────────────────
        // We override dispatchTouchEvent on the MapView so we can detect when
        // the user starts/stops touching and schedule the snap-back.
        @Suppress("ClickableViewAccessibility")
        binding.mapView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    userIsInteracting = true
                    snapBackHandler.removeCallbacks(snapBackRunnable)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> {
                    snapBackHandler.removeCallbacks(snapBackRunnable)
                    snapBackHandler.postDelayed(snapBackRunnable, SNAP_BACK_DELAY_MS)
                    v.performClick()
                }
            }
            false
        }

        placePickupMarker()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Snap-back restore
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called after SNAP_BACK_DELAY_MS of user inactivity.
     * Recomputes the fit-view for the current phase and animates back to it.
     */
    private fun restoreFitView() {
        if (_binding == null) return

        if (!isPhase2) {
            // Phase 1: fit driver + pickup
            if (lastKnownDriverLat != 0.0 || lastKnownDriverLng != 0.0) {
                zoomToFitWithPadding(listOf(
                    GeoPoint(lastKnownDriverLat, lastKnownDriverLng),
                    GeoPoint(pickupLat, pickupLng)
                ))
            } else {
                // Driver position not yet known — center on pickup
                zoomToFitWithPadding(listOf(GeoPoint(pickupLat, pickupLng)))
            }
        } else {
            // Phase 2: fit driver + destination
            if (lastKnownDriverLat != 0.0 || lastKnownDriverLng != 0.0) {
                zoomToFitWithPadding(listOf(
                    GeoPoint(lastKnownDriverLat, lastKnownDriverLng),
                    GeoPoint(destLat, destLng)
                ))
            } else {
                zoomToFitWithPadding(listOf(
                    GeoPoint(pickupLat, pickupLng),
                    GeoPoint(destLat, destLng)
                ))
            }
        }
    }

    private fun startStatusDotPulse() {
        ObjectAnimator.ofPropertyValuesHolder(
            binding.viewStatusDot,
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.25f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.40f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.40f, 1f)
        ).apply {
            duration = 1600L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }.start()
    }

    private fun bindNewViews() {
        if (isPhase2) {
            updatePhaseUiForPhase2()
        } else {
            binding.tvPhaseLabel.text = "● PICKUP PHASE"
        }
    }

    private fun updateEtaAndDistance(
        etaMinutes: Int,
        distMeters: Double
    ) {

        val etaText = when {
            etaMinutes <= 0 -> "-- min"
            etaMinutes == 1 -> "1 min"
            else -> "$etaMinutes min"
        }

        val distKm = distMeters / 1000.0

        val distText = when {
            distMeters <= 0 -> "-- km"
            distMeters < 100 -> "Arriving"
            distMeters < 1000 -> "${distMeters.toInt()} m"
            else -> String.format("%.1f km", distKm)
        }

        binding.tvEta.text = etaText
        binding.tvDistance.text = distText

    }

    private fun updatePhaseUiForPhase2() {

        binding.tvPhaseLabel.text = "● TRIP IN PROGRESS"

        binding.tvPhaseLabel.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.brand_accent)
        )

        binding.tvEta.text = "-- min"
        binding.tvDistance.text = "-- km"
    }

    private fun updateContextualStatus(
        distMeters: Double,
        isPhase2: Boolean
    ) {

        if (!isDriverOnline) return

        if (isPhase2) return

        // Don't overwrite status text once driver has arrived or trip has started
        if (currentRideStatus == "arrived_at_pickup" || currentRideStatus == "in_progress") return

        val msg = when {

            distMeters > 3000 ->
                "Driver is on the way"

            distMeters > 1200 ->
                "Driver is getting close"

            distMeters > 150 ->
                "Driver is almost there!"

            else ->
                "Driver has nearly arrived"
        }

        updateStatus(msg)
    }





    // ─────────────────────────────────────────────────────────────────────────
    // Static data
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindStaticData() {
        binding.tvDriverName.text  = driverName
        binding.tvVehicleType.text = vehicleType.replaceFirstChar { it.uppercase() }
        // Restore or reset OTP card depending on state
        when {
            otpShownToUser && !otpVerified && lastKnownOtp.isNotEmpty() -> {
                // View was recreated (e.g. returned from RideSummary) —
                // make the card visible again with the saved OTP code
                binding.tvOtpCode.text     = lastKnownOtp
                binding.cardOtp.visibility = View.VISIBLE
            }
            !otpShownToUser || otpVerified -> {
                binding.tvOtpCode.text     = "----"
                binding.cardOtp.visibility = View.GONE
            }
            // else: otpShownToUser=true but lastKnownOtp is empty — leave card as-is
        }

        // Restore correct UI state based on already-known ride status
        // This handles rotation and view recreation correctly
        when (currentRideStatus) {
            "arrived_at_pickup", "in_progress" -> {
                binding.btnCancelRide.visibility = View.GONE
                binding.tvStatus.text = if (currentRideStatus == "in_progress")
                    "Enjoy your ride!"
                else
                    "Driver has arrived"
            }
            else -> {
                binding.btnCancelRide.visibility = View.VISIBLE
                binding.tvStatus.text = "Driver is on the way"
            }
        }
    }

    private fun setupCancelButton() {
        binding.btnCancelRide.setOnClickListener {
            showRiderCancelConfirmDialog()
        }
    }

    private fun showRiderCancelConfirmDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Cancel Ride?")
            .setMessage("Are you sure you want to cancel this ride?")
            .setPositiveButton("Yes, Cancel") { _, _ ->
                performRiderCancellation()
            }
            .setNegativeButton("No, Keep") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun performRiderCancellation() {
        rideListener?.remove()
        driverListener?.remove()
        driverOfflineWatchdogJob?.cancel()
        stalenessPollingJob?.cancel()

        // Stop service callbacks FIRST, then unbind, then stop
        rideLiveService?.onStatusChanged = null
        if (isServiceBound) {
            requireContext().unbindService(serviceConnection)
            isServiceBound = false
        }
        rideLiveService = null

        requireContext().getSharedPreferences(RideLiveService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit { clear() }
        requireContext().stopService(
            Intent(requireContext(), RideLiveService::class.java)
        )

        android.util.Log.d("CHALRIDE_LIVE", "Rider cancelled the ride")

        FirebaseFirestore.getInstance()
            .collection("rideRequests").document(rideRequestId)
            .update(
                mapOf(
                    "status"             to "cancelled",
                    "cancellationReason" to CancelReason.RIDER_CANCELLED.name
                )
            )

        val bundle = Bundle().apply {
            putString("cancelReason", CancelReason.RIDER_CANCELLED.name)
        }
        safeNavigate(R.id.action_rideLive_to_rideCancelled, bundle)
    }





    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2 switch
    // ─────────────────────────────────────────────────────────────────────────

    private fun switchToPhase2() {
        if (mapPhase2SetupDone) return
        isPhase2 = true
        mapPhase2SetupDone = true
        updatePhaseUiForPhase2()
        phase2InitialRouteFetched = false

        android.util.Log.d("CHALRIDE_LIVE",
            "switchToPhase2: pickup=($pickupLat,$pickupLng) dest=($destLat,$destLng)")

        if (destLat == 0.0 || destLng == 0.0) {
            android.util.Log.e("CHALRIDE_LIVE", "switchToPhase2 aborted: destLat/destLng are 0.0")
            return
        }

        // Remove only stale overlays — do NOT clear all, markers go first
        routePolyline?.let { binding.mapView.overlays.remove(it) }
        driverMarker?.let  { binding.mapView.overlays.remove(it) }
        driverMarker = null
        routePolyline = null
        lastDriverRouteFetchLat = 0.0
        lastDriverRouteFetchLng = 0.0
        lastDriverLat = 0.0
        lastDriverLng = 0.0
        initialFitViewDone = false

        // Place static markers immediately (instant, no network)
        placePickupMarker()
        placeDestMarker()
        binding.mapView.invalidate()

        // Restore from cache instantly if available, then refresh in background
        lastFetchedPhase2RoutePoints?.let { drawRoutePolyline(ArrayList(it)) }
        fetchRouteForPhase2()
    }

    private fun fetchRouteForPhase2() {
        if (phase2InitialRouteFetched) return
        phase2InitialRouteFetched = true

        val apiKey = try { getString(R.string.ors_api_key).trim() } catch (_: Exception) { "" }

        lifecycleScope.launch {
            try {
                val points = withContext(Dispatchers.IO) {
                    val url = "https://api.openrouteservice.org/v2/directions/driving-car" +
                            "?start=$pickupLng,$pickupLat" +
                            "&end=$destLng,$destLat" +
                            "&radiuses=2000%7C2000"
                    val conn = URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/geo+json")
                    conn.setRequestProperty("Authorization", apiKey)
                    conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                    conn.connect()
                    if (conn.responseCode != 200) { conn.disconnect(); return@withContext null }
                    val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    conn.disconnect()
                    val features = json.getJSONArray("features")
                    if (features.length() == 0) return@withContext null
                    val coords = features.getJSONObject(0)
                        .getJSONObject("geometry").getJSONArray("coordinates")
                    ArrayList<GeoPoint>(coords.length()).also { list ->
                        for (i in 0 until coords.length()) {
                            val c = coords.getJSONArray(i)
                            list.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                        }
                    }
                }

                if (_binding == null) return@launch
                val routePoints = points ?: arrayListOf(
                    GeoPoint(pickupLat, pickupLng), GeoPoint(destLat, destLng)
                )
                lastFetchedPhase2RoutePoints = routePoints
                drawRoutePolyline(routePoints)

            } catch (e: Exception) {
                android.util.Log.e("CHALRIDE_LIVE", "fetchRouteForPhase2 error: ${e.message}")
                if (isNetworkAvailable) showNetworkBanner("unstable")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Driver location tracking
    // ─────────────────────────────────────────────────────────────────────────

    private fun listenForDriverLocation() {
        if (driverId.isEmpty()) return

        driverListener = FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(driverId)
            .addSnapshotListener { snapshot, error ->
                if (_binding == null) return@addSnapshotListener
                if (error != null) {
                    android.util.Log.e("CHALRIDE_LIVE", "Driver listener error: ${error.message}")
                    updateStatus("⚠️ Connection lost. Trying to reconnect...")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val lat = snapshot.getDouble("lat") ?: return@addSnapshotListener
                val lng = snapshot.getDouble("lng") ?: return@addSnapshotListener
                if (lat == 0.0 && lng == 0.0) return@addSnapshotListener

                // ── Driver offline watchdog ───────────────────────────────────
                val driverOnlineNow = snapshot.getBoolean("isOnline") ?: true
                if (!driverOnlineNow && isDriverOnline) {
                    // Driver just went offline — start 5-minute countdown
                    isDriverOnline = false
                    android.util.Log.w("CHALRIDE_LIVE",
                        "Driver went offline — starting 5-minute cancellation watchdog")
                    startDriverOfflineWatchdog()
                } else if (driverOnlineNow && !isDriverOnline) {
                    // Driver came back online — cancel watchdog
                    isDriverOnline = true
                    android.util.Log.d("CHALRIDE_LIVE",
                        "Driver came back online — cancelling watchdog")
                    driverOfflineWatchdogJob?.cancel()
                    driverOfflineWatchdogJob = null
                    if (isPhase2) {
                        updateStatus("🛣️ Enjoy your ride!")
                    } else {

                        val distM = haversineMeters(
                            lat,
                            lng,
                            pickupLat,
                            pickupLng
                        )

                        updateContextualStatus(distM, false)
                    }
                }
                // ─────────────────────────────────────────────────────────────


                updateDriverMarker(lat, lng)
            }
    }

    private fun startDriverOfflineWatchdog() {
        driverOfflineWatchdogJob?.cancel()
        updateStatus("⚠️ Driver connection lost. Waiting...")

        driverOfflineWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            // Count down 5 minutes, updating UI every minute
            val totalMinutes = 5
            for (minutesLeft in totalMinutes downTo 1) {
                kotlinx.coroutines.delay(60_000L)
                if (_binding == null) return@launch
                android.util.Log.w("CHALRIDE_LIVE",
                    "Driver still offline. ${minutesLeft - 1} minute(s) left before auto-cancel.")
                if (minutesLeft > 1) {
                    updateStatus("⚠️ Driver offline. Auto-cancelling in ${minutesLeft - 1} min...")
                }
            }

            // 5 minutes elapsed — driver never came back
            if (_binding == null) return@launch
            android.util.Log.e("CHALRIDE_LIVE",
                "Driver offline for 5 minutes — auto-cancelling ride")
            autoCancelDueToDriverOffline()
        }
    }

    private fun autoCancelDueToDriverOffline() {
        rideListener?.remove()
        driverListener?.remove()
        stalenessPollingJob?.cancel()

        android.util.Log.d("CHALRIDE_LIVE",
            "Auto-cancelling ride $rideRequestId due to driver offline timeout")

        val db = FirebaseFirestore.getInstance()

        // ── Step 1: Cancel the ride document ─────────────────────────────────
        db.collection("rideRequests").document(rideRequestId)
            .update(
                mapOf(
                    "status"             to "cancelled",
                    "cancellationReason" to CancelReason.DRIVER_OFFLINE.name
                )
            )

        // ── Step 2: Clean driver document + increment warning counter ─────────
        // We do this from the rider's phone because the driver's phone is dead.
        // This prevents the driver appearing as available to new riders.
        if (driverId.isNotEmpty()) {
            val driverRef = db.collection("drivers").document(driverId)

            db.runTransaction { transaction ->
                val driverDoc = transaction.get(driverRef)

                // Read current offline cancel count — default 0 if field doesn't exist
                val currentCount = driverDoc.getLong("offlineCancelCount") ?: 0L
                val newCount = currentCount + 1
                val shouldFlag = newCount >= 6

                val updates = mutableMapOf<String, Any?>(
                    // Full OFFLINE state reset
                    "driverState"        to "OFFLINE",
                    "isOnline"           to false,
                    "isAvailable"        to false,
                    "activeRideId"       to null,
                    "tripPhase"          to null,
                    // Warning system
                    "offlineCancelCount" to newCount,
                    "isAccountFlagged"   to shouldFlag
                )

                transaction.update(driverRef, updates)
            }.addOnSuccessListener {
                android.util.Log.d("CHALRIDE_LIVE",
                    "Driver $driverId cleaned up and warning count incremented")
            }.addOnFailureListener { e ->
                android.util.Log.e("CHALRIDE_LIVE",
                    "Driver cleanup failed: ${e.message}")
            }
        }

        // ── Step 3: Clean up local state and navigate ─────────────────────────
        requireContext().getSharedPreferences(RideLiveService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit { clear() }

        rideLiveService?.onStatusChanged = null
        if (isServiceBound) {
            requireContext().unbindService(serviceConnection)
            isServiceBound = false
        }
        rideLiveService = null
        requireContext().stopService(Intent(requireContext(), RideLiveService::class.java))

        val bundle = Bundle().apply {
            putString("cancelReason", CancelReason.DRIVER_OFFLINE.name)
        }
        safeNavigate(R.id.action_rideLive_to_rideCancelled, bundle)
    }

    private fun updateDriverMarker(lat: Double, lng: Double) {
        val newPoint = GeoPoint(lat, lng)

        // ── Compute bearing from previous → current position ──────────────────
        // ── DO NOT MODIFY — bearing/rotation logic is intentionally unchanged ──
        val bearing = if (lastDriverLat != 0.0 || lastDriverLng != 0.0) {
            computeBearing(lastDriverLat, lastDriverLng, lat, lng)
        } else {
            currentBearing  // keep last known on first fix
        }

        // Only update bearing if driver actually moved (avoids jitter on same-point updates)
        val movedAtAll = haversineMeters(lastDriverLat, lastDriverLng, lat, lng) > 2.0
        if (movedAtAll) {
            currentBearing = bearing
        }
        lastDriverLat = lat
        lastDriverLng = lng
        // ─────────────────────────────────────────────────────────────────────

        // Track latest driver position so snap-back can use it
        lastKnownDriverLat = lat
        lastKnownDriverLng = lng

        val targetLat = if (isPhase2) destLat else pickupLat
        val targetLng = if (isPhase2) destLng else pickupLng

        val distM = haversineMeters(
            lat,
            lng,
            targetLat,
            targetLng
        )

        val etaMins = (
                (distM / 1000.0) / 30.0 * 60
                ).roundToInt().coerceAtLeast(1)

        updateEtaAndDistance(etaMins, distM)

        updateContextualStatus(distM, isPhase2)



        if (driverMarker == null) {
            // First fix — create driver marker
            driverMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null; title = null
                position = newPoint
                try {
                    val sizePx = (32 * resources.displayMetrics.density).toInt()
                    val bmp = android.graphics.BitmapFactory.decodeResource(
                        resources, R.drawable.ic_driver_car
                    )
                    val scaled = bmp.scale(sizePx, sizePx)
                    icon = scaled.toDrawable(resources)
                } catch (_: Exception) { }
                // Apply initial bearing rotation (unchanged logic)
                rotation = -(currentBearing - 90f)
                binding.mapView.overlays.add(this)
            }
            binding.mapView.invalidate()

            // On first driver fix, fit the view once. Never again from this path.
            if (!userIsInteracting && !initialFitViewDone) {
                initialFitViewDone = true
                val targetLat = if (isPhase2) destLat else pickupLat
                val targetLng = if (isPhase2) destLng else pickupLng
                zoomToFitWithPadding(listOf(newPoint, GeoPoint(targetLat, targetLng)))
            }
            fetchAndUpdateDriverRoute(lat, lng)
            return
        }

        // ── Smooth animate to new position ────────────────────────────────────
        // ── DO NOT MODIFY — bearing/rotation logic is intentionally unchanged ──
        val startLat = driverMarker!!.position.latitude
        val startLng = driverMarker!!.position.longitude
        val startBearing = driverMarker!!.rotation
        // Shortest rotation path (avoid spinning the long way around)
        val targetBearing = -(currentBearing - 90f)
        val bearingDelta = shortestRotation(startBearing, targetBearing)

        markerAnimator?.cancel()
        markerAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                if (_binding == null) return@addUpdateListener
                val t = anim.animatedValue as Float
                driverMarker?.position = GeoPoint(
                    startLat + (lat - startLat) * t,
                    startLng + (lng - startLng) * t
                )
                // Smoothly rotate arrow to match travel direction (unchanged logic)
                driverMarker?.rotation = startBearing + bearingDelta * t
                binding.mapView.invalidate()
            }
            start()
        }
        // ─────────────────────────────────────────────────────────────────────

        // ── REMOVED: camera-following zoom logic that was here ────────────────
        // The map no longer follows or zooms when the driver moves.
        // Camera position is restored by snap-back after user interaction,
        // and set once on first driver fix and on phase transitions.
        // ─────────────────────────────────────────────────────────────────────

        fetchAndUpdateDriverRoute(lat, lng)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Route fetch — live driver position updates
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchAndUpdateDriverRoute(driverLat: Double, driverLng: Double) {
        if (isPhase2 && !phase2InitialRouteFetched) return
        if (driverRouteFetchInProgress) return

        val distMoved = haversineMeters(
            driverLat, driverLng,
            lastDriverRouteFetchLat, lastDriverRouteFetchLng
        )
        if (distMoved < DRIVER_ROUTE_THRESHOLD_M && lastDriverRouteFetchLat != 0.0) return

        driverRouteFetchInProgress = true
        lastDriverRouteFetchLat = driverLat
        lastDriverRouteFetchLng = driverLng

        val toLat = if (isPhase2) destLat  else pickupLat
        val toLng = if (isPhase2) destLng  else pickupLng

        val apiKey = try { getString(R.string.ors_api_key).trim() }
        catch (_: Exception) { driverRouteFetchInProgress = false; return }

        lifecycleScope.launch {
            try {
                val points = withContext(Dispatchers.IO) {
                    val url = "https://api.openrouteservice.org/v2/directions/driving-car" +
                            "?start=$driverLng,$driverLat" +
                            "&end=$toLng,$toLat" +
                            "&radiuses=2000%7C2000"
                    val conn = URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/geo+json")
                    conn.setRequestProperty("Authorization", apiKey)
                    conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                    conn.connect()
                    if (conn.responseCode != 200) { conn.disconnect(); return@withContext null }
                    val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    conn.disconnect()
                    val features = json.getJSONArray("features")
                    if (features.length() == 0) return@withContext null
                    val coords = features.getJSONObject(0)
                        .getJSONObject("geometry").getJSONArray("coordinates")
                    ArrayList<GeoPoint>(coords.length()).also { list ->
                        for (i in 0 until coords.length()) {
                            val c = coords.getJSONArray(i)
                            list.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                        }
                    }
                }

                if (_binding == null) return@launch
                val routePoints = points ?: return@launch
                drawRoutePolyline(routePoints)

            } catch (_: Exception) {
                if (isNetworkAvailable) showNetworkBanner("unstable")
            } finally {
                driverRouteFetchInProgress = false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Map helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawRoutePolyline(routePoints: ArrayList<GeoPoint>) {
        routePolyline?.let { binding.mapView.overlays.remove(it) }
        routePolyline = Polyline().apply {
            setPoints(routePoints)
            outlinePaint.color       = "#4A80F0".toColorInt()
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap   = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin  = android.graphics.Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
        }

        binding.mapView.overlays.add(0, routePolyline)
        binding.mapView.invalidate()
    }

    private fun startStalenessPolling() {
        stalenessPollingJob?.cancel()
        if (driverId.isEmpty() || rideRequestId.isEmpty()) return
        stalenessPollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(POLLING_INTERVAL_MS)
                if (_binding == null) return@launch

                // Skip if watchdog already running or ride is over
                if (driverOfflineWatchdogJob?.isActive == true) continue
                if (!isDriverOnline) continue

                // Read driver doc directly — not a listener
                try {
                    val doc = FirebaseFirestore.getInstance()
                        .collection("drivers").document(driverId)
                        .get().await()

                    val lastUpdated = doc.getLong("lastUpdated") ?: continue
                    val staleness = System.currentTimeMillis() - lastUpdated

                    android.util.Log.d("CHALRIDE_LIVE",
                        "Staleness check: ${staleness / 1000}s since last location update")

                    if (staleness > LOCATION_STALE_THRESHOLD_MS) {
                        android.util.Log.w("CHALRIDE_LIVE",
                            "lastUpdated is ${staleness/60000} min old — auto-cancelling immediately")
                        isDriverOnline = false
                        // Driver has ALREADY been gone 5 minutes — cancel immediately,
                        // don't start another 5-minute watchdog on top of this
                        autoCancelDueToDriverOffline()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CHALRIDE_LIVE", "Staleness poll failed: ${e.message}")
                }
            }
        }
    }


    private fun zoomToFitWithPadding(points: List<GeoPoint>) {
        if (points.isEmpty()) return

        val bbox = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
        val centerLat = (bbox.latNorth + bbox.latSouth) / 2.0
        val centerLng = (bbox.lonEast  + bbox.lonWest)  / 2.0

        // Minimum span ~800m so single/close points still zoom out enough
        val minSpan = 0.008
        val north = maxOf(bbox.latNorth, centerLat + minSpan / 2)
        val south = minOf(bbox.latSouth, centerLat - minSpan / 2)
        val east  = maxOf(bbox.lonEast,  centerLng + minSpan / 2)
        val west  = minOf(bbox.lonWest,  centerLng - minSpan / 2)

        binding.mapView.post {
            if (_binding == null) return@post
            if (userIsInteracting) return@post

            val mapW = binding.mapView.width.takeIf  { it > 0 } ?: return@post
            val mapH = binding.mapView.height.takeIf { it > 0 } ?: return@post

            // Use screen-percentage padding if measured values are still 0
            val padTop    = if (mapPadTop    > 0) mapPadTop    else (mapH * 0.18).toInt()
            val padBottom = if (mapPadBottom > 0) mapPadBottom else (mapH * 0.35).toInt()
            val padSide   = if (mapPadSide   > 0) mapPadSide   else (mapW * 0.08).toInt()

            val usableW = (mapW - padSide  * 2).coerceAtLeast(mapW / 2)
            val usableH = (mapH - padTop - padBottom).coerceAtLeast(mapH / 3)

            val latSpan = north - south
            val lonSpan = east  - west

            // Expand the bounding box so content fits in the USABLE area,
            // not the full map canvas. Asymmetric vertical (more bottom than top).
            val latScale = mapH.toDouble() / usableH.toDouble()
            val lonScale = mapW.toDouble() / usableW.toDouble()

            val latExpand = latSpan * (latScale - 1.0)
            val lonExpand = lonSpan * (lonScale - 1.0)

            val topShare    = padTop.toDouble()    / (padTop + padBottom).toDouble()
            val bottomShare = padBottom.toDouble() / (padTop + padBottom).toDouble()

            val paddedBox = org.osmdroid.util.BoundingBox(
                north + latExpand * topShare,
                east  + lonExpand * 0.5,
                south - latExpand * bottomShare,
                west  - lonExpand * 0.5
            )

            binding.mapView.zoomToBoundingBox(paddedBox, false)  // false = no animation

            // Hard zoom cap
            if (binding.mapView.zoomLevelDouble > 16.5) {
                binding.mapView.controller.setZoom(16.5)
            }
            // Extra zoom-out safety margin — ensures nothing clips
            binding.mapView.controller.setZoom(binding.mapView.zoomLevelDouble - 0.5)
            binding.mapView.invalidate()
        }
    }

    private fun placePickupMarker() {
        pickupMarker?.let { binding.mapView.overlays.remove(it) }
        val gp = GeoPoint(pickupLat, pickupLng)
        pickupMarker = Marker(binding.mapView).apply {
            position = gp
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null; title = null
            try {
                val sizePx = (24 * resources.displayMetrics.density).toInt()
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_pickup_marker)
                    ?.let { d ->
                        val bmp = createBitmap(sizePx, sizePx)
                        val cvs = android.graphics.Canvas(bmp)
                        d.setBounds(0, 0, sizePx, sizePx); d.draw(cvs)
                        bmp.toDrawable(resources)
                    }
            } catch (_: Exception) { }
            setOnMarkerClickListener { _, _ -> true }
        }
        binding.mapView.overlays.add(pickupMarker)
    }

    private fun placeDestMarker() {
        if (destLat == 0.0 && destLng == 0.0) return
        destMarker?.let { binding.mapView.overlays.remove(it) }
        val gp = GeoPoint(destLat, destLng)
        destMarker = Marker(binding.mapView).apply {
            position = gp
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null; title = null
            try {
                val sizePx = (24 * resources.displayMetrics.density).toInt()
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_destination_marker)
                    ?.let { d ->
                        val bmp = createBitmap(sizePx, sizePx)
                        val cvs = android.graphics.Canvas(bmp)
                        d.setBounds(0, 0, sizePx, sizePx); d.draw(cvs)
                        bmp.toDrawable(resources)
                    }
            } catch (_: Exception) { }
            setOnMarkerClickListener { _, _ -> true }
        }
        binding.mapView.overlays.add(destMarker)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firestore ride status listener
    // ─────────────────────────────────────────────────────────────────────────

    private fun listenForRideUpdates() {
        if (rideRequestId.isEmpty()) return

        rideListener = FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .addSnapshotListener { snapshot, error ->
                if (_binding == null) return@addSnapshotListener
                if (error != null) {
                    android.util.Log.e("CHALRIDE_LIVE", "Ride listener error: ${error.message}")
                    updateStatus("⚠️ Connection lost. Trying to reconnect...")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val status = snapshot.getString("status") ?: return@addSnapshotListener

                android.util.Log.d("CHALRIDE_LIVE",
                    "Snapshot: status=$status otp=${snapshot.getString("riderOtp")}")

                val otp = snapshot.getString("riderOtp") ?: ""
                val statusNow = snapshot.getString("status") ?: ""
                if (otp.isNotEmpty() && !otpVerified && statusNow != "in_progress") showOtp(otp)

                if (status == currentRideStatus) return@addSnapshotListener
                currentRideStatus = status

                when (status) {
                    "accepted" -> {
                        updateStatus("Driver is on the way")
                        binding.btnCancelRide.visibility = View.VISIBLE  // allow cancel in phase 1
                    }
                    "arrived_at_pickup" -> {
                        binding.btnCancelRide.visibility = View.GONE
                        updateStatus("Driver has arrived")
                    }
                    "in_progress" -> {
                        otpVerified    = true
                        otpShownToUser = false
                        binding.btnCancelRide.visibility = View.GONE
                        updateStatus("Enjoy your ride!")
                        binding.cardOtp.visibility = View.GONE
                        switchToPhase2()
                    }
                    "completed" -> {
                        otpVerified    = true
                        otpShownToUser = false
                        driverOfflineWatchdogJob?.cancel()
                        stalenessPollingJob?.cancel()
                        updateStatus("You have reached your destination!")
                        val bundle = Bundle().apply {
                            putString("rideRequestId", rideRequestId)
                            putString("driverName",    driverName)
                            putString("vehicleType",   vehicleType)
                            putString("pickupAddress", pickupAddress)
                            putString("destAddress",   destAddress)
                            putInt("estimatedFare",    estimatedFare)
                            putDouble("pickupLat",     pickupLat)
                            putDouble("pickupLng",     pickupLng)
                            putDouble("destLat",       destLat)
                            putDouble("destLng",       destLng)
                        }
                        requireContext().stopService(Intent(requireContext(), RideLiveService::class.java))
                        safeNavigate(R.id.action_rideLive_to_rideCompletion, bundle)
                    }
                    "cancelled" -> {
                        otpVerified    = true
                        otpShownToUser = false
                        driverOfflineWatchdogJob?.cancel()
                        stalenessPollingJob?.cancel()
                        rideListener?.remove()
                        driverListener?.remove()
                        val reason = snapshot.getString("cancellationReason")
                            ?: CancelReason.RIDER_CANCELLED.name
                        val bundle = Bundle().apply {
                            putString("cancelReason", reason)
                        }
                        safeNavigate(R.id.action_rideLive_to_rideCancelled, bundle)
                    }
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Safe navigation — only navigates if the fragment is still
     * in a RESUMED or STARTED state. Prevents IllegalStateException
     * when Firestore callbacks fire while the app is backgrounded.
     */
    private fun safeNavigate(actionId: Int, bundle: Bundle? = null) {
        if (!isAdded || _binding == null) return
        val lifecycle = viewLifecycleOwner.lifecycle
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        try {
            if (bundle != null) findNavController().navigate(actionId, bundle)
            else findNavController().navigate(actionId)
        } catch (e: Exception) {
            android.util.Log.e("CHALRIDE_LIVE", "safeNavigate failed: ${e.message}")
        }
    }

    /**
     * Handles ride status updates coming from the service.
     * This is called both on bind (to restore state) and live during the ride.
     * The fragment's own Firestore listener is kept for real-time UI updates
     * when the fragment is in foreground — the service listener handles background.
     */
    private fun handleStatusFromService(status: String, otp: String, cancelReason: String) {
        if (_binding == null) return
        if (status == currentRideStatus) return
        currentRideStatus = status

        // Show OTP if it arrived while we were in background
        if (otp.isNotEmpty() && !otpVerified) showOtp(otp)

        when (status) {
            "accepted" -> {
                binding.btnCancelRide.visibility = View.VISIBLE
                updateStatus("Driver is on the way")
            }
            "arrived_at_pickup" -> {
                binding.btnCancelRide.visibility = View.GONE
                updateStatus("Driver has arrived")
                // User is now looking at the app — the ongoing notification stays but silences itself
            }
            "in_progress" -> {
                otpVerified    = true
                otpShownToUser = false
                binding.btnCancelRide.visibility = View.GONE
                binding.cardOtp.visibility = View.GONE
                updateStatus("Enjoy your ride!")
                if (!isPhase2) switchToPhase2()
            }
            "completed" -> {
                updateStatus("You have reached your destination!")
                val bundle = Bundle().apply {
                    putString("rideRequestId", rideRequestId)
                    putString("driverName",    driverName)
                    putString("vehicleType",   vehicleType)
                    putString("pickupAddress", pickupAddress)
                    putString("destAddress",   destAddress)
                    putInt("estimatedFare",    estimatedFare)
                    putDouble("pickupLat",     pickupLat)
                    putDouble("pickupLng",     pickupLng)
                    putDouble("destLat",       destLat)
                    putDouble("destLng",       destLng)
                }
                requireContext().stopService(Intent(requireContext(), RideLiveService::class.java))
                safeNavigate(R.id.action_rideLive_to_rideCompletion, bundle)
            }
            "cancelled" -> {
                val bundle = Bundle().apply {
                    putString("cancelReason", cancelReason.ifEmpty { CancelReason.DRIVER_OFFLINE.name })
                }
                safeNavigate(R.id.action_rideLive_to_rideCancelled, bundle)
            }
        }
    }



// ─────────────────────────────────────────────────────────────────────────
// Network status banner
// ─────────────────────────────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (_binding == null) return@launch
                    if (!isNetworkAvailable) {
                        isNetworkAvailable = true
                        showNetworkBanner("online")
                    }
                }
            }
            override fun onLost(network: android.net.Network) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (_binding == null) return@launch
                    isNetworkAvailable = false
                    showNetworkBanner("offline")
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(networkCallback!!)
        } else {
            val req = android.net.NetworkRequest.Builder().build()
            cm.registerNetworkCallback(req, networkCallback!!)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
    }

    private fun showNetworkBanner(state: String) {
        if (_binding == null) return
        networkBannerJob?.cancel()

        val card = binding.cardNetworkBanner
        val dot  = binding.viewNetworkDot
        val text = binding.tvNetworkBannerText
        val density = resources.displayMetrics.density

        when (state) {
            "offline" -> {
                card.setCardBackgroundColor(android.graphics.Color.parseColor("#E61A0808"))
                card.strokeColor = android.graphics.Color.parseColor("#40FF4757")
                dot.backgroundTintList  = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#FF4757"))
                text.setTextColor(android.graphics.Color.parseColor("#FF4757"))
                text.text = "No internet connection"
            }
            "unstable" -> {
                card.setCardBackgroundColor(android.graphics.Color.parseColor("#E61A1000"))
                card.strokeColor = android.graphics.Color.parseColor("#40FFC107")
                dot.backgroundTintList  = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#FFC107"))
                text.setTextColor(android.graphics.Color.parseColor("#FFC107"))
                text.text = "Network unstable"
                // Auto-hide unstable after 6 s — next successful fetch will also hide it
                networkBannerJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(6_000)
                    hideNetworkBanner()
                }
            }
            "online" -> {
                card.setCardBackgroundColor(android.graphics.Color.parseColor("#E6001A18"))
                card.strokeColor = android.graphics.Color.parseColor("#404ECDC4")
                dot.backgroundTintList  = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#4ECDC4"))
                text.setTextColor(android.graphics.Color.parseColor("#4ECDC4"))
                text.text = "Back online"
                // Auto-hide after 3 s
                networkBannerJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(3_000)
                    hideNetworkBanner()
                }
            }
        }

        // Slide in from above if not already visible
        if (card.visibility != View.VISIBLE) {
            card.translationY = -(56f * density)
            card.alpha = 0f
            card.visibility = View.VISIBLE
            card.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                .start()
        }
    }

    private fun hideNetworkBanner() {
        val card = _binding?.cardNetworkBanner ?: return
        val density = resources.displayMetrics.density
        card.animate()
            .translationY(-(56f * density))
            .alpha(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.8f))
            .withEndAction { _binding?.cardNetworkBanner?.visibility = View.GONE }
            .start()
    }


    private fun updateStatus(message: String) {
        binding.tvStatus.text = message
    }

    private fun showOtp(otp: String) {
        if (otpShownToUser || otpVerified) return
        otpShownToUser = true
        lastKnownOtp = otp
        binding.tvOtpCode.text           = otp
        binding.cardOtp.visibility       = View.VISIBLE
        binding.cardOtp.scaleX = 0.85f; binding.cardOtp.scaleY = 0.85f; binding.cardOtp.alpha = 0f
        binding.cardOtp.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).setDuration(400)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Math helpers
    // ── DO NOT MODIFY — bearing/rotation logic is intentionally unchanged ─────
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compass bearing from point A → point B in degrees (0 = North, 90 = East).
     * Same formula used in DriverNavigationFragment.
     */
    private fun computeBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLng  = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val y = sin(dLng) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    /**
     * Returns the shortest rotation delta between two angles (handles wrap-around).
     * e.g. from 350° to 10° returns +20 (not -340).
     */
    private fun shortestRotation(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f)  delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        if (lat1 == 0.0 && lng1 == 0.0) return Double.MAX_VALUE
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}