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
import androidx.activity.OnBackPressedCallback
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

class DriverHomeFragment : Fragment() {

    private var rideRequestListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var currentRideRequestId: String? = null
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

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { /* Home screen — block back */ }
            }
        )

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

        // Reset after map init causes false interaction events
        binding.mapView.post {
            userIsInteracting = false
        }

    }

    private fun restoreOnlineStateIfNeeded() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val wasOnline = doc.getBoolean("isOnline") ?: false
                if (wasOnline && !isOnline) {
                    isOnline = true
                    updateOnlineUI()
                    startOnlineTimer()
                    listenForRideRequests()
                }
            }
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
        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val earnings = doc.getLong("earnings") ?: 0L
                val trips = doc.getLong("totalTrips") ?: 0L
                binding.tvEarnings.text = "₹$earnings"
                binding.tvTripsCount.text = trips.toString()
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


    private fun setDriverAvailability(available: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("drivers").document(uid)
            .update(
                mapOf(
                    "isAvailable" to available,
                    "isOnline"    to available
                )
            )
    }

    // ── Online/Offline toggle ────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.fabMyLocation.setOnClickListener {
            userIsInteracting = false
            currentLocation?.let { loc ->
                binding.mapView.controller.animateTo(loc)
                binding.mapView.controller.setZoom(17.0)
            } ?: checkLocationSettings()
        }

        binding.btnToggleOnline.setOnClickListener {
            isOnline = !isOnline
            updateOnlineUI()

            if (isOnline) {
                // Start foreground service for background location
                startDriverLocationService()
                setDriverAvailability(true)
                startOnlineTimer()
            } else {
                stopDriverLocationService()
                setDriverAvailability(false)
                timerJob?.cancel()
            }
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
            listenForRideRequests()
            //avatar ring changes to green color
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
        val vehicleType = FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)

        // First fetch this driver's vehicleType
        vehicleType.get().addOnSuccessListener { doc ->
            val myVehicleType = doc.getString("vehicleType") ?: return@addOnSuccessListener

            rideRequestListener = FirebaseFirestore.getInstance()
                .collection("rideRequests")
                .whereEqualTo("status", "pending")
                .whereEqualTo("vehicleType", myVehicleType)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    if (_binding == null) return@addSnapshotListener

                    for (change in snapshot.documentChanges) {
                        if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val doc2 = change.document

                            // Skip if already handled or if this driver is in rejectedDrivers
                            val rejectedDrivers = doc2.get("rejectedDrivers") as? List<*> ?: emptyList<String>()
                            if (uid in rejectedDrivers) continue
                            if (doc2.id == currentRideRequestId) continue

                            currentRideRequestId = doc2.id

                            val pickupLat = doc2.getDouble("pickupLat") ?: 0.0
                            val pickupLng = doc2.getDouble("pickupLng") ?: 0.0
                            val driverLat = currentLocation?.latitude ?: 0.0
                            val driverLng = currentLocation?.longitude ?: 0.0

                            val distanceKm = haversineDistance(driverLat, driverLng, pickupLat, pickupLng)

                            showRideRequestSheet(
                                rideRequestId = doc2.id,
                                riderName     = doc2.getString("riderName") ?: "Rider",
                                pickupAddress = doc2.getString("pickupAddress") ?: "",
                                destAddress   = doc2.getString("destAddress") ?: "",
                                vehicleType   = myVehicleType,
                                estimatedFare = (doc2.getLong("estimatedFare") ?: 0).toInt(),
                                distanceKm    = distanceKm
                            )
                            break
                        }
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
                rejectRide(rideRequestId)
                currentRideRequestId = null
            }

            onTimeout = {
                rejectRide(rideRequestId)
                currentRideRequestId = null
            }
        }

        sheet.show(parentFragmentManager, RideRequestSheet.TAG)
    }

    private fun acceptRide(rideRequestId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val driverName = doc.getString("name") ?: "Driver"

                FirebaseFirestore.getInstance()
                    .collection("rideRequests").document(rideRequestId)
                    .get()
                    .addOnSuccessListener { rideDoc ->

                        // Update ride status
                        FirebaseFirestore.getInstance()
                            .collection("rideRequests").document(rideRequestId)
                            .update(mapOf(
                                "status"     to "accepted",
                                "driverId"   to uid,
                                "driverName" to driverName,
                                "assignedAt" to System.currentTimeMillis()
                            ))

                        // Mark driver unavailable AND save activeRideId for crash recovery
                        FirebaseFirestore.getInstance()
                            .collection("drivers").document(uid)
                            .update(mapOf(
                                "isAvailable"  to false,
                                "activeRideId" to rideRequestId   // ← KEY: crash recovery anchor
                            ))

                        val bundle = Bundle().apply {
                            putString("rideRequestId", rideRequestId)
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

                        findNavController().navigate(
                            R.id.action_driverHome_to_driverActiveRide,
                            bundle
                        )
                    }
            }
    }

    private fun rejectRide(rideRequestId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Add this driver to rejectedDrivers list so request goes to next driver
        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .update(
                "rejectedDrivers",
                com.google.firebase.firestore.FieldValue.arrayUnion(uid)
            )
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

    // ── Geohash encoder ─────────────────────────────────────────────────────

    /**
     * Lightweight geohash encoder — no external library needed.
     * Precision 6 = ~1.2km x 0.6km cell, good enough for 3km radius queries.
     */
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