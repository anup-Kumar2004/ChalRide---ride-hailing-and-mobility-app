package com.example.chalride.ui.driver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.chalride.R
import com.example.chalride.databinding.FragmentDriverHomeBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.location.SettingsClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import com.bumptech.glide.Glide
import androidx.lifecycle.ViewModelProvider
import com.example.chalride.ui.auth.AuthViewModel
import androidx.navigation.fragment.findNavController
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import com.example.chalride.utils.BackPressHandler

class DriverHomeFragment : Fragment() {

    private var rideRequestListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var currentRideRequestId: String? = null
    private var isSpeedDialOpen = false
    private lateinit var bottomSheetBehavior: com.google.android.material.bottomsheet.BottomSheetBehavior<androidx.core.widget.NestedScrollView>
    private var peekHeight = 0
    private var _binding: FragmentDriverHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var settingsClient: SettingsClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var isOnline = false
    private var onlineStartTimeMs = 0L
    private var timerJob: Job? = null

    private var currentMarker: Marker? = null
    private var currentLocation: GeoPoint? = null
    private var userIsInteracting = false
    private var firstLocationFix = true
    private var mapInitialized = false

    // ── Permission launchers ────────────────────────────────────────────────

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) checkLocationSettings()
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startLocationUpdates()
    }

    // ───────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        BackPressHandler.enableDoubleBackToExit(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        settingsClient = LocationServices.getSettingsClient(requireActivity())

        initMap()
        buildLocationRequest()
        setupLocationCallback()
        checkAndRequestPermission()
        setupClickListeners()
        loadDriverProfileIfNeeded()
        loadLiveStatsFromFirestore()
        restoreOnlineStateIfNeeded()
        checkForActiveRideOnLaunch()
        checkDriverAccountStatus()


        // Reset after map init causes false interaction events
        binding.mapView.post {
            userIsInteracting = false
        }

        setupBottomSheet()

    }

    private fun restoreOnlineStateIfNeeded() {

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->

                if (_binding == null) return@addOnSuccessListener

                val firestoreOnline =
                    doc.getBoolean("isOnline") ?: false

                val driverState =
                    DriverState.fromString(
                        doc.getString("driverState")
                    )

                android.util.Log.d(
                    "DriverHome",
                    "restoreOnlineStateIfNeeded() → " +
                            "isOnline=$firestoreOnline | " +
                            "driverState=$driverState"
                )

                if (firestoreOnline) {

                    // Restore memory state
                    isOnline = true

                    // Restore UI
                    updateOnlineUI()

                    // Restart timer
                    startOnlineTimer()

                    // Reattach ride listener safely
                    if (rideRequestListener == null) {
                        listenForRideRequests()
                    }

                } else {

                    isOnline = false

                    updateOnlineUI()
                }
            }
            .addOnFailureListener { e ->

                android.util.Log.e(
                    "DriverHome",
                    "Failed to restore online state: ${e.message}"
                )
            }
    }

    /**
     * Checks offlineCancelCount and isAccountFlagged on every app open.
     * Shows appropriate warning dialog based on how many times this driver
     * has caused a ride cancellation by going offline.
     *
     * Count thresholds:
     *   1–3  → informational warning (stage 1)
     *   4–5  → firm warning (stage 2)
     *   6+   → account flagged, Go Online disabled
     */
    private fun checkDriverAccountStatus() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val isFlagged   = doc.getBoolean("isAccountFlagged") ?: false
                val cancelCount = doc.getLong("offlineCancelCount")  ?: 0L

                when {
                    isFlagged || cancelCount >= 6 -> showFlaggedDialog()
                    cancelCount in 4..5           -> showStage2WarningDialog(cancelCount)
                    cancelCount in 1..3           -> showStage1WarningDialog(cancelCount)
                    // cancelCount == 0 → no dialog, clean driver
                }
            }
    }


    private fun showStage1WarningDialog(count: Long) {
        DriverWarningDialog.newInstance(DriverWarningDialog.Stage.STAGE_1, count)
            .show(parentFragmentManager, "warning_stage1")
    }

    private fun showStage2WarningDialog(count: Long) {
        DriverWarningDialog.newInstance(DriverWarningDialog.Stage.STAGE_2, count)
            .show(parentFragmentManager, "warning_stage2")
    }

    private fun showFlaggedDialog() {
        if (_binding != null) {
            binding.btnToggleOnline.isEnabled = false
            binding.btnToggleOnline.alpha     = 0.4f
            binding.btnToggleOnline.text      = "ACCOUNT SUSPENDED"
        }
        DriverWarningDialog.newInstance(DriverWarningDialog.Stage.SUSPENDED)
            .show(parentFragmentManager, "warning_suspended")
    }

    // ── Driver info from Firestore ──────────────────────────────────────────
    private fun loadDriverProfileIfNeeded() {

        val viewModel = ViewModelProvider(requireActivity())[AuthViewModel::class.java]

        // ✅ 1. USE CACHE (no Firestore call)
        viewModel.cachedDriverProfile?.let { profile ->
            bindDriverUI(profile)
            return
        }

        // ✅ 2. FIRST TIME → FETCH FROM FIRESTORE
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->

                val name = doc.getString("name") ?: "Driver"
                val imageUrl = doc.getString("photoUrl")

                val profile = AuthViewModel.DriverProfile(name, imageUrl)

                // ✅ SAVE CACHE
                viewModel.cachedDriverProfile = profile

                bindDriverUI(profile)
            }
    }

    private fun loadLiveStatsFromFirestore() {

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()

        // Start of today
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)

        val startOfToday = calendar.timeInMillis

        db.collection("rideRequests")
            .whereEqualTo("driverId", uid)
            .whereEqualTo("status", "completed")
            .get()
            .addOnSuccessListener { snapshot ->

                var todayEarnings = 0L
                var todayTrips = 0

                var totalRating = 0.0
                var ratedTripsCount = 0

                for (doc in snapshot.documents) {

                    // ─────────────────────────────────────
                    // TODAY EARNINGS + TODAY TRIPS
                    // ─────────────────────────────────────

                    val completedAt = doc.getLong("completedAt") ?: 0L

                    if (completedAt >= startOfToday) {

                        val fare = doc.getLong("estimatedFare") ?: 0L

                        todayEarnings += fare
                        todayTrips++
                    }

                    // ─────────────────────────────────────
                    // DRIVER RATING
                    // ─────────────────────────────────────

                    val rating = doc.getDouble("riderFeedback.rating")
                        ?: doc.getLong("riderFeedback.rating")?.toDouble()

                    if (rating != null) {
                        totalRating += rating
                        ratedTripsCount++
                    }
                }

                // ─────────────────────────────────────
                // UPDATE UI
                // ─────────────────────────────────────

                binding.tvEarnings.text = "₹$todayEarnings"

                binding.tvTripsCount.text = todayTrips.toString()

                if (ratedTripsCount > 0) {

                    val averageRating = totalRating / ratedTripsCount

                    binding.tvRating.text =
                        String.format("%.1f", averageRating)

                } else {

                    binding.tvRating.text = "—"
                }
            }
    }


    private fun bindDriverUI(profile: AuthViewModel.DriverProfile) {

        binding.tvDriverName.text = profile.name

        val imageUrl = profile.imageUrl

        if (!imageUrl.isNullOrEmpty()) {

            // ✅ SHOW IMAGE
            binding.ivProfile.visibility = View.VISIBLE
            binding.tvProfileInitial.visibility = View.GONE

            Glide.with(requireContext())
                .load(imageUrl)
                .into(binding.ivProfile)   // ← remove .placeholder(...)

        } else {

            // ✅ SHOW INITIAL
            binding.ivProfile.visibility = View.GONE
            binding.tvProfileInitial.visibility = View.VISIBLE

            val initial = profile.name.firstOrNull()?.uppercase() ?: "D"
            binding.tvProfileInitial.text = initial
        }
    }


    // ── Map ─────────────────────────────────────────────────────────────────

    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )
        binding.mapView.controller.setZoom(5.0)
        binding.mapView.controller.setCenter(GeoPoint(20.5937, 78.9629))

        // Calculate exact offset after layout — works for any screen size
        binding.root.post {
            val topCardBottom = binding.cardTopBar.bottom
            val bottomSheetTop = binding.bottomSheet.top
            val screenCenter = binding.root.height / 2

            val visibleMapCenter = topCardBottom + (bottomSheetTop - topCardBottom) / 2
            val neededOffset = visibleMapCenter - screenCenter

            binding.mapView.setMapCenterOffset(0, neededOffset)
        }

        binding.mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent): Boolean {
                if (mapInitialized) userIsInteracting = true
                updatePulsePosition()
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                if (mapInitialized) userIsInteracting = true
                updatePulsePosition()
                return false
            }
        })

        // Mark init complete so listener starts tracking real interactions
        binding.mapView.post { mapInitialized = true }
    }

    private fun placeDriverMarker(geoPoint: GeoPoint) {
        if (_binding == null) return
        currentMarker?.let { binding.mapView.overlays.remove(it) }

        currentMarker = Marker(binding.mapView).apply {
            position = geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null
            title = null
            try {
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_driver_marker)
                    ?.let { drawable ->
                        val sizePx = (28 * resources.displayMetrics.density).toInt()
                        val bmp = createBitmap(sizePx, sizePx)
                        val cvs = android.graphics.Canvas(bmp)
                        drawable.setBounds(0, 0, sizePx, sizePx)
                        drawable.draw(cvs)
                        bmp.toDrawable(resources)
                    }
            } catch (_: Exception) { }
            setOnMarkerClickListener { _, _ -> true }
        }
        binding.mapView.overlays.add(currentMarker)
        binding.mapView.invalidate()

        // Show pulse
        binding.pulseView.visibility = View.VISIBLE
        binding.pulseView.bringToFront()
        binding.mapView.post { updatePulsePosition() }
        startPulse(binding.pulseView)
    }

    private fun updatePulsePosition() {
        val geoPoint = currentMarker?.position ?: return
        val pt = binding.mapView.projection.toPixels(geoPoint, null)
        binding.pulseView.x = pt.x.toFloat() - binding.pulseView.width / 2f
        binding.pulseView.y = pt.y.toFloat() - binding.pulseView.height / 2f
    }


    private fun startPulse(view: View) {
        view.animate().cancel()
        view.scaleX = 1f; view.scaleY = 1f; view.alpha = 0.7f
        view.animate()
            .scaleX(2f).scaleY(2f).alpha(0f)
            .setDuration(1200)
            .withEndAction { if (view.isVisible) startPulse(view) }
            .start()
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(binding.bottomSheet)
        bottomSheetBehavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
        bottomSheetBehavior.isHideable = false
        bottomSheetBehavior.skipCollapsed = false
        bottomSheetBehavior.isDraggable = true
        bottomSheetBehavior.isFitToContents = false  // CRITICAL — false prevents full screen expansion

        binding.expandedContent.visibility = View.GONE
        binding.expandedContent.alpha = 0f

        binding.btnToggleOnline.post {
            val density = resources.displayMetrics.density
            val screenHeight = resources.displayMetrics.heightPixels

            // Peek height: measured from sheet top to bottom of GO ONLINE button + padding
            val sheetLoc = IntArray(2)
            binding.bottomSheet.getLocationOnScreen(sheetLoc)
            val btnLoc = IntArray(2)
            binding.btnToggleOnline.getLocationOnScreen(btnLoc)
            peekHeight = (btnLoc[1] - sheetLoc[1]) + binding.btnToggleOnline.height + (24 * density).toInt()
            bottomSheetBehavior.peekHeight = peekHeight

            // expandedOffset: distance from top of screen where sheet stops — same pattern as RideConfirmFragment
            // Set to leave enough room for the map and top card to remain visible
            bottomSheetBehavior.expandedOffset = (screenHeight * 0.5).toInt()

            positionFabsAboveSheet(peekHeight)
            binding.bottomSheet.postDelayed({ animateSheetHint() }, 5000)
        }

        bottomSheetBehavior.addBottomSheetCallback(object :
            com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                if (_binding == null) return
                val sheetTop = bottomSheet.top
                val screenHeight = binding.root.height
                positionFabsAboveSheet(screenHeight - sheetTop)
                binding.expandedContent.visibility = View.VISIBLE
                binding.expandedContent.alpha = slideOffset.coerceIn(0f, 1f)
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (_binding == null) return
                when (newState) {
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED -> {
                        binding.expandedContent.visibility = View.GONE
                        binding.expandedContent.alpha = 0f
                        positionFabsAboveSheet(peekHeight)
                    }
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED -> {
                        binding.expandedContent.visibility = View.VISIBLE
                        binding.expandedContent.alpha = 1f
                    }
                    else -> {}
                }
            }
        })
    }

    private fun positionFabsAboveSheet(sheetVisibleHeight: Int) {
        if (_binding == null) return
        val density = resources.displayMetrics.density
        val mainMargin  = sheetVisibleHeight + (15 * density).toInt()
        val earnMargin  = sheetVisibleHeight + (85 * density).toInt()
        val profMargin  = sheetVisibleHeight + (155 * density).toInt()

        fun setBottomMargin(view: View, margin: Int) {
            val params = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            params.bottomMargin = margin
            view.layoutParams = params
        }

        setBottomMargin(binding.fabSpeedDial, mainMargin)
        setBottomMargin(binding.fabEarnings,  earnMargin)
        setBottomMargin(binding.fabProfile,   profMargin)
    }

    private fun animateSheetHint() {
        if (_binding == null) return
        val handle = binding.dragHandle
        // Pulse the drag handle: scale up and glow white briefly, repeat twice
        val animator = android.animation.AnimatorSet()
        fun pulse() = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(handle, "scaleX", 1f, 2.2f, 1f),
                android.animation.ObjectAnimator.ofFloat(handle, "scaleY", 1f, 2.2f, 1f),
                android.animation.ObjectAnimator.ofFloat(handle, "alpha", 0.5f, 1f, 0.5f)
            )
            duration = 600
        }
        animator.playSequentially(pulse(), pulse())
        animator.start()

        // Also translate the sheet up slightly and back to hint it's draggable
        binding.bottomSheet.animate()
            .translationY(-28f).setDuration(350).withEndAction {
                binding.bottomSheet.animate()
                    .translationY(0f).setDuration(350).start()
            }.start()
    }


    private fun openSpeedDial() {
        isSpeedDialOpen = true
        binding.fabScrim.visibility = View.VISIBLE
        binding.fabScrim.animate().alpha(1f).setDuration(200).start()

        listOf(binding.fabEarnings, binding.fabProfile).forEachIndexed { index, fab ->
            fab.visibility = View.VISIBLE
            fab.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay((index * 50).toLong())
                .setDuration(200)
                .start()
        }
        binding.fabSpeedDial.animate().rotation(45f).setDuration(200).start()
    }

    private fun closeSpeedDial() {
        isSpeedDialOpen = false
        binding.fabScrim.animate().alpha(0f).setDuration(200)
            .withEndAction { binding.fabScrim.visibility = View.GONE }.start()

        listOf(binding.fabProfile, binding.fabEarnings).forEachIndexed { index, fab ->
            fab.animate()
                .scaleX(0f).scaleY(0f).alpha(0f)
                .setStartDelay((index * 50).toLong())
                .setDuration(150)
                .withEndAction { fab.visibility = View.INVISIBLE }
                .start()
        }
        binding.fabSpeedDial.animate().rotation(0f).setDuration(200).start()
    }


    // ── Location ────────────────────────────────────────────────────────────

    private fun buildLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).setMinUpdateIntervalMillis(3000L).build()
    }

    private fun checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) checkLocationSettings()
        else locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun checkLocationSettings() {
        val req = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest).setAlwaysShow(true).build()
        settingsClient.checkLocationSettings(req)
            .addOnSuccessListener { startLocationUpdates() }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        locationSettingsLauncher.launch(
                            IntentSenderRequest.Builder(exception.resolution).build()
                        )
                    } catch (_: IntentSender.SendIntentException) { }
                }
            }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                currentLocation = geoPoint

                android.util.Log.d("ChalRide", "📍 Location received: ${location.latitude}, ${location.longitude} | firstLocationFix=$firstLocationFix")

                if (firstLocationFix && !userIsInteracting) {
                    firstLocationFix = false
                    android.util.Log.d("ChalRide", "🎬 Starting cinematic zoom...")
                    placeDriverMarker(geoPoint)
                    startCinematicZoom(geoPoint)
                } else {
                    android.util.Log.d("ChalRide", "📍 Subsequent location update, skipping cinematic zoom")
                    placeDriverMarker(geoPoint)
                }


            }
        }
    }

    private fun startCinematicZoom(geoPoint: GeoPoint) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Step 1: Hold India view
            delay(500)
            if (_binding == null) return@launch

            // Step 2: Pan to location
            binding.mapView.controller.animateTo(geoPoint)
            delay(500)
            if (_binding == null) return@launch

            // Step 3: Zoom in — fewer steps, longer delay = smoother
            val startZoom = 5.0
            val endZoom = 14.0
            val steps = 2
            val stepDelay = 1000L

            for (i in 1..steps) {
                if (_binding == null) return@launch
                val zoom = startZoom + (endZoom - startZoom) * (i.toDouble() / steps)
                binding.mapView.controller.setZoom(zoom)
                binding.mapView.controller.setCenter(geoPoint)
                delay(stepDelay)
            }
        }
    }

    private fun startLocationUpdates() {
        userIsInteracting = false

        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        // ✅ Use last known location instantly — no waiting for GPS fix
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && firstLocationFix && !userIsInteracting) {
                firstLocationFix = false
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                currentLocation = geoPoint
                placeDriverMarker(geoPoint)
                startCinematicZoom(geoPoint)
            }
        }

        // Continue requesting fresh updates in background
        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    private fun transitionDriverState(state: DriverState, activeRideId: String? = null) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)
            .update(state.toFirestoreMap(activeRideId))
    }

    // ── Online/Offline toggle ────────────────────────────────────────────────

    private fun setupClickListeners() {

        binding.btnToggleOnline.setOnClickListener {
            // Guard — flagged drivers cannot go online even if they somehow tap the button
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                FirebaseFirestore.getInstance()
                    .collection("drivers").document(uid)
                    .get()
                    .addOnSuccessListener { doc ->
                        val isFlagged = doc.getBoolean("isAccountFlagged") ?: false
                        val count     = doc.getLong("offlineCancelCount")  ?: 0L
                        if (isFlagged || count >= 6) {
                            showFlaggedDialog()
                            return@addOnSuccessListener
                        }
                        // Not flagged — proceed normally
                        isOnline = !isOnline
                        updateOnlineUI()
                        if (isOnline) {
                            startDriverLocationService()
                            transitionDriverState(DriverState.ONLINE_AVAILABLE)
                            startOnlineTimer()
                            listenForRideRequests()
                        } else {
                            stopDriverLocationService()
                            transitionDriverState(DriverState.OFFLINE)
                            timerJob?.cancel()
                            rideRequestListener?.remove()
                            rideRequestListener = null
                            currentRideRequestId = null
                        }
                    }
                return@setOnClickListener
            }
            // Fallback if uid is null — should never happen
            isOnline = !isOnline
            updateOnlineUI()
        }



        // ── Speed Dial ──────────────────────────────────────────────────────
        binding.fabSpeedDial.setOnClickListener {
            if (isSpeedDialOpen) closeSpeedDial() else openSpeedDial()
        }

        binding.fabScrim.setOnClickListener {
            closeSpeedDial()
        }

        binding.fabEarnings.setOnClickListener {
            closeSpeedDial()
            findNavController().navigate(R.id.action_driverHome_to_driverEarnings)
        }

        binding.fabProfile.setOnClickListener {
            closeSpeedDial()
            findNavController().navigate(R.id.action_driverHome_to_driverProfile)
        }


    }

    private fun checkForActiveRideOnLaunch() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val activeRideId = doc.getString("activeRideId")

                if (!activeRideId.isNullOrEmpty()) {
                    // Driver crashed mid-ride — fetch the ride and resume
                    android.util.Log.d("ChalRide", "🔄 Active ride found: $activeRideId — resuming")

                    FirebaseFirestore.getInstance()
                        .collection("rideRequests")
                        .document(activeRideId)
                        .get()
                        .addOnSuccessListener { rideDoc ->

                            // Only resume if the ride is still in progress (not completed/cancelled)
                            val status = rideDoc.getString("status") ?: ""
                            if (status !in listOf("completed", "cancelled")) {

                                val bundle = Bundle().apply {
                                    putString("rideRequestId", activeRideId)
                                    putString("riderName",     rideDoc.getString("riderName")     ?: "Rider")
                                    putDouble("pickupLat",     rideDoc.getDouble("pickupLat")     ?: 0.0)
                                    putDouble("pickupLng",     rideDoc.getDouble("pickupLng")     ?: 0.0)
                                    putDouble("destLat",       rideDoc.getDouble("destLat")       ?: 0.0)
                                    putDouble("destLng",       rideDoc.getDouble("destLng")       ?: 0.0)
                                    putString("pickupAddress", rideDoc.getString("pickupAddress") ?: "")
                                    putString("destAddress",   rideDoc.getString("destAddress")   ?: "")
                                    putInt("estimatedFare",    (rideDoc.getLong("estimatedFare")  ?: 0).toInt())
                                    putString("vehicleType",   rideDoc.getString("vehicleType")   ?: "")
                                }

                                // Also restart the location service since app crashed
                                startDriverLocationService()

                                findNavController().navigate(
                                    R.id.action_driverHome_to_driverActiveRide,
                                    bundle
                                )
                            } else {
                                // Ride ended while app was crashed — clean up stale activeRideId
                                FirebaseFirestore.getInstance()
                                    .collection("drivers").document(uid)
                                    .update("activeRideId", null)
                            }
                        }
                }
            }
    }




    private fun updateOnlineUI() {
        if (isOnline) {
            binding.avatarRing.setBackgroundResource(R.drawable.bg_driver_avatar_online)

            // Button
            binding.btnToggleOnline.text = "GO OFFLINE"
            binding.btnToggleOnline.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.error_color)
            )

            // Top bar status
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_online)
            binding.tvStatus.text = "Online"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.success_color)
            )

            // Status ring
            binding.statusRingOuter.setBackgroundResource(R.drawable.bg_status_ring_online)
            binding.ivStatusIcon.alpha = 1f
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.success_color)
            )

            // Status text
            binding.tvStatusMessage.text = "You are online"
            binding.tvStatusMessage.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.success_color)
            )
            binding.tvStatusSubMessage.text = "Waiting for ride requests nearby..."

            // Pulse on driver marker while online
            binding.pulseView.visibility = View.VISIBLE

        } else {
            rideRequestListener?.remove()
            rideRequestListener = null
            currentRideRequestId = null

            //avatar ring changes to red color
            binding.avatarRing.setBackgroundResource(R.drawable.bg_driver_avatar)

            // Button
            binding.btnToggleOnline.text = "GO ONLINE"
            binding.btnToggleOnline.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.success_color)
            )

            // Top bar status
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_offline)
            binding.tvStatus.text = "Offline"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_hint)
            )

            // Status ring
            binding.statusRingOuter.setBackgroundResource(R.drawable.bg_status_ring_offline)
            binding.ivStatusIcon.alpha = 0.5f
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.text_hint)
            )

            // Status text
            binding.tvStatusMessage.text = "You are currently offline"
            binding.tvStatusMessage.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_primary)
            )
            binding.tvStatusSubMessage.text = "Go online to start receiving ride requests"

            binding.pulseView.animate().cancel()
            binding.pulseView.visibility = View.GONE
        }
    }

    // ── Online timer ─────────────────────────────────────────────────────────

    private fun startOnlineTimer() {
        onlineStartTimeMs = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsedMs = System.currentTimeMillis() - onlineStartTimeMs
                val totalMin = elapsedMs / 60_000
                val hours = totalMin / 60
                val mins = totalMin % 60
                binding.tvHoursOnline.text = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                delay(30_000) // update every 30 seconds
            }
        }
    }

    // ── Foreground service ───────────────────────────────────────────────────

    private fun startDriverLocationService() {
        val intent = Intent(requireContext(), DriverLocationService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun stopDriverLocationService() {
        val intent = Intent(requireContext(), DriverLocationService::class.java)
        requireContext().stopService(intent)
    }

    private fun listenForRideRequests() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        rideRequestListener = FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .whereEqualTo("targetDriverId", uid)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                if (_binding == null) return@addSnapshotListener

                for (change in snapshot.documentChanges) {
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED ||
                        change.type == com.google.firebase.firestore.DocumentChange.Type.MODIFIED
                    ) {
                        val doc = change.document
                        if (doc.id == currentRideRequestId) continue

                        currentRideRequestId = doc.id

                        val pickupLat  = doc.getDouble("pickupLat")  ?: 0.0
                        val pickupLng  = doc.getDouble("pickupLng")  ?: 0.0
                        val driverLat  = currentLocation?.latitude   ?: 0.0
                        val driverLng  = currentLocation?.longitude  ?: 0.0
                        val distanceKm = haversineDistance(driverLat, driverLng, pickupLat, pickupLng)

                        showRideRequestSheet(
                            rideRequestId = doc.id,
                            riderName     = doc.getString("riderName")  ?: "Rider",
                            pickupAddress = doc.getString("pickupAddress") ?: "",
                            destAddress   = doc.getString("destAddress")   ?: "",
                            vehicleType   = doc.getString("vehicleType")   ?: "",
                            estimatedFare = (doc.getLong("estimatedFare")  ?: 0).toInt(),
                            distanceKm    = distanceKm
                        )
                        break
                    }
                }
            }
    }

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun showRideRequestSheet(
        rideRequestId: String,
        riderName: String,
        pickupAddress: String,
        destAddress: String,
        vehicleType: String,
        estimatedFare: Int,
        distanceKm: Double
    ) {
        val sheet = RideRequestSheet().apply {
            this.rideRequestId = rideRequestId
            this.riderName     = riderName
            this.pickupAddress = pickupAddress
            this.destAddress   = destAddress
            this.vehicleType   = vehicleType
            this.estimatedFare = estimatedFare
            this.distanceKm    = distanceKm

            onAccepted = {
                acceptRide(rideRequestId)
            }

            onRejected = {
                markRideAsRejected(rideRequestId)
                currentRideRequestId = null
            }

            onTimeout = {
                markRideAsRejected(rideRequestId)
                currentRideRequestId = null
            }
        }

        sheet.show(parentFragmentManager, RideRequestSheet.TAG)
    }

    private fun acceptRide(rideRequestId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db  = FirebaseFirestore.getInstance()

        // Step 1: Fetch driver name (read-only, not part of the transaction)
        db.collection("drivers").document(uid).get()
            .addOnSuccessListener { driverDoc ->
                val driverName = driverDoc.getString("name") ?: "Driver"
                val rideRef    = db.collection("rideRequests").document(rideRequestId)

                // Step 2: Atomic transaction — only succeed if the ride is still
                // pending AND still targeting THIS driver.
                // If two drivers or two riders race here, only one will win.
                db.runTransaction { transaction ->
                    val rideSnap      = transaction.get(rideRef)
                    val currentStatus = rideSnap.getString("status")       ?: ""
                    val currentTarget = rideSnap.getString("targetDriverId") ?: ""

                    if (currentStatus != "pending" || currentTarget != uid) {
                        // Ride was cancelled, already accepted by someone else,
                        // or re-targeted to a different driver — abort.
                        throw Exception("ride_no_longer_available")
                    }

                    transaction.update(
                        rideRef, mapOf(
                            "status"     to "accepted",
                            "driverId"   to uid,
                            "driverName" to driverName,
                            "assignedAt" to System.currentTimeMillis()
                        )
                    )
                }
                    .addOnSuccessListener {
                        // Transaction won — now fetch full ride details for navigation
                        rideRef.get().addOnSuccessListener { rideDoc ->

                            // Update driver state only after the ride is confirmed accepted
                            db.collection("drivers").document(uid)
                                .update(DriverState.ON_TRIP_TO_PICKUP.toFirestoreMap(rideRequestId))
                                .addOnFailureListener {
                                    // If this fails, retry once — driver must be marked unavailable
                                    db.collection("drivers").document(uid)
                                        .update(DriverState.ON_TRIP_TO_PICKUP.toFirestoreMap(rideRequestId))
                                }

                            val bundle = Bundle().apply {
                                putString("rideRequestId", rideRequestId)
                                putString("riderName",     rideDoc.getString("riderName")     ?: "Rider")
                                putDouble("pickupLat",      rideDoc.getDouble("pickupLat")     ?: 0.0)
                                putDouble("pickupLng",      rideDoc.getDouble("pickupLng")     ?: 0.0)
                                putDouble("destLat",        rideDoc.getDouble("destLat")       ?: 0.0)
                                putDouble("destLng",        rideDoc.getDouble("destLng")       ?: 0.0)
                                putString("pickupAddress", rideDoc.getString("pickupAddress") ?: "")
                                putString("destAddress",   rideDoc.getString("destAddress")   ?: "")
                                putInt("estimatedFare",    (rideDoc.getLong("estimatedFare")  ?: 0).toInt())
                                putString("vehicleType",   rideDoc.getString("vehicleType")   ?: "")
                                putString("riderPhone",    rideDoc.getString("riderPhone")    ?: "")
                            }

                            if (_binding != null) {
                                findNavController().navigate(
                                    R.id.action_driverHome_to_driverActiveRide, bundle
                                )
                            }
                        }
                    }
                    .addOnFailureListener {
                        // Transaction lost — ride was taken by another driver or cancelled
                        currentRideRequestId = null
                        if (_binding != null) {
                            Toast.makeText(
                                requireContext(),
                                "Ride is no longer available",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
            }
    }


    private fun markRideAsRejected(rideRequestId: String) {
        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .update("status", "rejected")
    }


    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        // NOTE: Do NOT remove location updates here — foreground service handles it
        // Only remove if driver is offline
        if (!isOnline && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timerJob?.cancel()
        if (::locationCallback.isInitialized && !isOnline) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        _binding = null

        rideRequestListener?.remove()
    }
    
}