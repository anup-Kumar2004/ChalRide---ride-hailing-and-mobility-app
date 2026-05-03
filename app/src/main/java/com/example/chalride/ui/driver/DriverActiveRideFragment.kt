package com.example.chalride.ui.driver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
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
import com.example.chalride.databinding.FragmentDriverActiveRideBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import androidx.core.graphics.toColorInt
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DriverActiveRideFragment : Fragment() {

    private var _binding: FragmentDriverActiveRideBinding? = null
    private val binding get() = _binding!!

    // ── Arguments ─────────────────────────────────────────────────────────────
    private val rideRequestId by lazy { arguments?.getString("rideRequestId") ?: "" }
    private val riderName     by lazy { arguments?.getString("riderName")     ?: "Rider" }
    private val pickupLat     by lazy { arguments?.getDouble("pickupLat")     ?: 0.0 }
    private val pickupLng     by lazy { arguments?.getDouble("pickupLng")     ?: 0.0 }
    private val destLat       by lazy { arguments?.getDouble("destLat")       ?: 0.0 }
    private val destLng       by lazy { arguments?.getDouble("destLng")       ?: 0.0 }
    private val pickupAddress by lazy { arguments?.getString("pickupAddress") ?: "" }
    private val destAddress   by lazy { arguments?.getString("destAddress")   ?: "" }
    private val estimatedFare by lazy { arguments?.getInt("estimatedFare")    ?: 0 }
    private val vehicleType   by lazy { arguments?.getString("vehicleType")   ?: "" }
    private var isProgrammaticMapMove = false

    // ── Trip phase ────────────────────────────────────────────────────────────
    private enum class TripPhase { HEADING_TO_PICKUP, ARRIVED_AT_PICKUP, IN_PROGRESS }
    private var tripPhase = TripPhase.HEADING_TO_PICKUP

    // ── Location ──────────────────────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: GeoPoint? = null

    // ── Map state ─────────────────────────────────────────────────────────────
    private var driverMarker:  Marker?   = null
    private var pickupMarker:  Marker?   = null
    private var destMarker:    Marker?   = null
    private var routePolyline: Polyline? = null

    // Route fetch throttle — same pattern used in DestinationSearchFragment
    private var routeFetchInProgress = false
    private var lastRouteUpdateLat   = 0.0
    private var lastRouteUpdateLng   = 0.0
    private val ROUTE_UPDATE_THRESHOLD_METERS = 100

    // Map interaction guard — don't auto-zoom after user manually pans
    private var userIsInteracting  = false
    private var mapInitialized     = false
    private var hasZoomedToRoute   = false   // zoom to fit only once per phase
    private var fragmentReady      = false   // block location callbacks until map is ready

    // Firestore listener for rider cancellation
    private var rideStatusListener: com.google.firebase.firestore.ListenerRegistration? = null

    // Add these new properties at the top of the class, with the other vars:
    private var markerAnimator: android.animation.ValueAnimator? = null
    private var lastKnownBearing: Float = 0f

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverActiveRideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Block hardware back during a ride
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { }
            }
        )

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        initMap()
        bindStaticData()
        updatePhaseUI()
        startLocationUpdates()
        listenForRiderCancellation()

        binding.fabMyLocation.setOnClickListener {
            userIsInteracting = false
            currentLocation?.let {
                binding.mapView.controller.animateTo(it)
                binding.mapView.controller.setZoom(16.0)
            }
        }

        binding.btnTripAction.setOnClickListener { handleTripAction() }

        // Only start processing location updates after the full layout pass
        binding.root.post { fragmentReady = true }

        // Zoom into car after 3 seconds regardless of route state
        binding.root.postDelayed({
            if (_binding == null || userIsInteracting) return@postDelayed
            isProgrammaticMapMove = true
            currentLocation?.let { loc ->
                binding.mapView.controller.animateTo(loc)
                binding.mapView.controller.setZoom(17.0)
            }
            binding.mapView.postDelayed({ isProgrammaticMapMove = false }, 800L)
        }, 3000L)
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        rideStatusListener?.remove()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        super.onDestroyView()
        markerAnimator?.cancel()
        _binding = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Map initialisation
    // ─────────────────────────────────────────────────────────────────────────

    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )
        binding.mapView.controller.setZoom(14.0)
        binding.mapView.controller.setCenter(GeoPoint(pickupLat, pickupLng))

        // Place static pickup + destination markers immediately so the user
        // always sees where they are going even before the route loads
        placeStaticMarker(GeoPoint(pickupLat, pickupLng), isPickup = true)
        placeStaticMarker(GeoPoint(destLat, destLng),    isPickup = false)

        // Detect user manual pan/zoom → stop auto-centering
        binding.mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent): Boolean {
                if (mapInitialized && !isProgrammaticMapMove) userIsInteracting = true
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                if (mapInitialized && !isProgrammaticMapMove) userIsInteracting = true
                return false
            }
        })

        binding.mapView.post { mapInitialized = true }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static pickup / destination markers
    // ─────────────────────────────────────────────────────────────────────────

    private fun placeStaticMarker(geoPoint: GeoPoint, isPickup: Boolean) {
        val marker = Marker(binding.mapView).apply {
            position = geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null
            title = null
            try {
                val res = if (isPickup) R.drawable.ic_pickup_marker
                else R.drawable.ic_destination_marker
                val sizePx = ((if (isPickup) 28 else 24) *
                        resources.displayMetrics.density).toInt()
                icon = ContextCompat.getDrawable(requireContext(), res)?.let { d ->
                    val bmp = createBitmap(sizePx, sizePx)
                    val cvs = android.graphics.Canvas(bmp)
                    d.setBounds(0, 0, sizePx, sizePx)
                    d.draw(cvs)
                    bmp.toDrawable(resources)
                }
            } catch (_: Exception) { }
            setOnMarkerClickListener { _, _ -> true }
        }
        binding.mapView.overlays.add(marker)
        if (isPickup) pickupMarker = marker else destMarker = marker
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Driver car marker
    // ─────────────────────────────────────────────────────────────────────────

    private fun placeOrMoveDriverMarker(newGeoPoint: GeoPoint) {
        if (_binding == null) return

        if (driverMarker == null) {
            // First time — just place it
            driverMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
                title = null
                try {
                    val sizePx = (40 * resources.displayMetrics.density).toInt()
                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_driver_car)
                        ?.let { d ->
                            val bmp = createBitmap(sizePx, sizePx)
                            val cvs = android.graphics.Canvas(bmp)
                            d.setBounds(0, 0, sizePx, sizePx)
                            d.draw(cvs)
                            bmp.toDrawable(resources)
                        }
                } catch (_: Exception) { }
                position = newGeoPoint
                binding.mapView.overlays.add(this)
            }
            binding.mapView.invalidate()
            return
        }

        // Smooth interpolation from old position to new position
        val startLat = driverMarker!!.position.latitude
        val startLng = driverMarker!!.position.longitude
        val endLat   = newGeoPoint.latitude
        val endLng   = newGeoPoint.longitude

        // Calculate bearing for rotation
        val dLng = Math.toRadians(endLng - startLng)
        val lat1  = Math.toRadians(startLat)
        val lat2  = Math.toRadians(endLat)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        val bearing = ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        if (endLat != startLat || endLng != startLng) lastKnownBearing = bearing

        markerAnimator?.cancel()
        markerAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L  // smooth over 1 second — matches your location update interval
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                if (_binding == null) return@addUpdateListener
                val t = animator.animatedValue as Float
                val lat = startLat + (endLat - startLat) * t
                val lng = startLng + (endLng - startLng) * t
                driverMarker?.position = GeoPoint(lat, lng)
                driverMarker?.rotation = (360f - lastKnownBearing)
                binding.mapView.invalidate()
            }
            start()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Route drawing — same proven pattern as DestinationSearchFragment
    // Key rule: read API key on main thread, then do ALL network in IO
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawRoute(from: GeoPoint, to: GeoPoint) {
        if (routeFetchInProgress) return

        // Read API key on main thread — safe, avoids thread issues on MIUI
        val apiKey = try {
            getString(R.string.ors_api_key).trim()
        } catch (e: Exception) {
            android.util.Log.e("ROUTE", "Cannot read API key: ${e.message}")
            return
        }
        if (apiKey.isBlank()) {
            android.util.Log.e("ROUTE", "ors_api_key is blank")
            return
        }

        routeFetchInProgress = true
        android.util.Log.d("ROUTE", "Fetching route from driver to target")

        lifecycleScope.launch {
            try {
                // ALL network work stays inside Dispatchers.IO — same as DestinationSearchFragment
                val routePoints = withContext(Dispatchers.IO) {
                    val url = "https://api.openrouteservice.org/v2/directions/driving-car" +
                            "?start=${from.longitude},${from.latitude}" +
                            "&end=${to.longitude},${to.latitude}" +
                            "&radiuses=2000%7C2000"

                    val conn = URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/geo+json")
                    conn.setRequestProperty("Authorization", apiKey)
                    conn.connectTimeout = 10_000
                    conn.readTimeout    = 10_000
                    conn.connect()

                    val code = conn.responseCode
                    if (code != 200) {
                        val err = conn.errorStream?.bufferedReader()?.readText()
                        android.util.Log.e("ROUTE", "HTTP $code → $err")
                        conn.disconnect()
                        return@withContext null
                    }

                    val json   = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    conn.disconnect()

                    val features = json.getJSONArray("features")
                    if (features.length() == 0) return@withContext null

                    val coords = features.getJSONObject(0)
                        .getJSONObject("geometry")
                        .getJSONArray("coordinates")

                    ArrayList<GeoPoint>(coords.length()).also { list ->
                        for (i in 0 until coords.length()) {
                            val c = coords.getJSONArray(i)
                            list.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                        }
                    }
                }

                if (_binding == null) return@launch
                if (routePoints == null || routePoints.isEmpty()) return@launch

                // Remove old polyline
                routePolyline?.let { binding.mapView.overlays.remove(it) }

                // Draw new polyline
                routePolyline = Polyline().apply {
                    setPoints(routePoints)
                    outlinePaint.color       = "#4A80F0".toColorInt()
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.strokeCap   = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin  = android.graphics.Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }
                // Insert at index 0 so route is behind markers
                binding.mapView.overlays.add(0, routePolyline)
                binding.mapView.invalidate()

                // Zoom to fit — only on first draw per phase, same logic as DestinationSearchFragment
                if (!hasZoomedToRoute && !userIsInteracting) {
                    hasZoomedToRoute = true
                    // Show full route overview first for 2.5 seconds, then zoom into car
                    val allPoints = ArrayList<GeoPoint>().apply {
                        add(from)
                        addAll(routePoints)
                        add(to)
                    }
                    zoomToFitRouteInSafeArea(allPoints)

                    // After overview, zoom into driver for navigation mode
                    binding.mapView.postDelayed({
                        if (_binding == null || userIsInteracting) return@postDelayed
                        isProgrammaticMapMove = true
                        currentLocation?.let { loc ->
                            binding.mapView.controller.animateTo(loc)
                            binding.mapView.controller.setZoom(17.0)
                        }
                        binding.mapView.postDelayed({ isProgrammaticMapMove = false }, 800L)
                    }, 2500L)
                }

            } catch (e: Exception) {
                android.util.Log.e("ROUTE", "drawRoute exception: ${e.message}")
            } finally {
                routeFetchInProgress = false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Zoom to fit route inside the visible area (between top card & bottom sheet)
    // Adapted from DestinationSearchFragment.zoomToFitRouteInSafeArea()
    // ─────────────────────────────────────────────────────────────────────────

    private fun zoomToFitRouteInSafeArea(routePoints: ArrayList<GeoPoint>) {
        binding.mapView.post {
            val mapView = binding.mapView
            val mapH = mapView.height
            val mapW = mapView.width
            if (mapH == 0 || mapW == 0 || routePoints.isEmpty()) return@post

            val box = org.osmdroid.util.BoundingBox.fromGeoPoints(routePoints)
            val mapPos  = IntArray(2).also { mapView.getLocationOnScreen(it) }
            val cardPos = IntArray(2).also { binding.cardStatus.getLocationOnScreen(it) }
            val sheetPos = IntArray(2).also { binding.bottomSheet.getLocationOnScreen(it) }

            val extraBuffer = maxOf(80, (mapH * 0.08).toInt())
            val topOccluded = ((cardPos[1] + binding.cardStatus.height) - mapPos[1] + extraBuffer)
                .coerceIn(0, mapH / 2)
            val botOccluded = ((mapPos[1] + mapH) - sheetPos[1] + extraBuffer)
                .coerceIn(0, mapH / 2)
            val sidePadPx = 60

            val origLatSpan = (box.latNorth - box.latSouth).coerceAtLeast(0.001)
            val origLonSpan = (box.lonEast  - box.lonWest).coerceAtLeast(0.001)
            val routeLatCenter = (box.latNorth + box.latSouth) / 2.0
            val routeLonCenter = (box.lonEast  + box.lonWest)  / 2.0

            val safeH = (mapH - topOccluded - botOccluded).coerceAtLeast(100)
            val safeW = (mapW - 2 * sidePadPx).coerceAtLeast(100)

            val newLatSpan = origLatSpan * (mapH.toDouble() / safeH.toDouble())
            val newLonSpan = origLonSpan * (mapW.toDouble() / safeW.toDouble())

            val safeCenterOffsetPx = (topOccluded - botOccluded) / 2.0
            val latCenterShift = safeCenterOffsetPx / mapH.toDouble() * newLatSpan
            val adjustedLatCenter = routeLatCenter + latCenterShift

            val expandedBox = org.osmdroid.util.BoundingBox(
                adjustedLatCenter + newLatSpan / 2.0,
                routeLonCenter    + newLonSpan / 2.0,
                adjustedLatCenter - newLatSpan / 2.0,
                routeLonCenter    - newLonSpan / 2.0
            )

            // Mark as programmatic so map listener doesn't set userIsInteracting = true
            isProgrammaticMapMove = true
            mapView.zoomToBoundingBox(expandedBox, true, 0)
            mapView.postDelayed({ isProgrammaticMapMove = false }, 500L)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static data binding
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindStaticData() {
        binding.tvRiderName.text     = riderName
        binding.tvFare.text          = "₹$estimatedFare"
        binding.tvVehicleType.text   = vehicleType.replaceFirstChar { it.uppercase() }
        binding.tvPickupAddress.text = pickupAddress
        binding.tvDestAddress.text   = destAddress
        binding.tvCurrentTarget.text = pickupAddress
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trip phase UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun updatePhaseUI() {
        when (tripPhase) {
            TripPhase.HEADING_TO_PICKUP -> {
                binding.tvTripStatus.text    = "HEADING TO PICKUP"
                binding.tvCurrentTarget.text = pickupAddress
                binding.btnTripAction.text   = "Arrived at Pickup"
                binding.btnTripAction.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.brand_primary)
                    )
            }
            TripPhase.ARRIVED_AT_PICKUP -> {
                binding.tvTripStatus.text    = "WAITING FOR RIDER"
                binding.tvCurrentTarget.text = "Rider is on their way to you"
                binding.btnTripAction.text   = "Start Trip"
                binding.btnTripAction.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.success_color)
                    )
            }
            TripPhase.IN_PROGRESS -> {
                binding.tvTripStatus.text    = "TRIP IN PROGRESS"
                binding.tvCurrentTarget.text = destAddress
                binding.btnTripAction.text   = "Complete Trip"
                binding.btnTripAction.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(requireContext(), R.color.driver_color)
                    )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trip phase button handler
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleTripAction() {
        userIsInteracting = false   // ← ADD THIS LINE at the very top
        when (tripPhase) {
            TripPhase.HEADING_TO_PICKUP -> {
                tripPhase = TripPhase.ARRIVED_AT_PICKUP
                userIsInteracting = false
                updatePhaseUI()
                updateFirestoreStatus("arrived_at_pickup")
            }
            TripPhase.ARRIVED_AT_PICKUP -> {
                tripPhase = TripPhase.IN_PROGRESS
                // Reset zoom so map re-fits the new route (driver → destination)
                hasZoomedToRoute  = false
                userIsInteracting = false
                // Clear the old route line immediately
                routePolyline?.let { binding.mapView.overlays.remove(it) }
                routePolyline = null
                updatePhaseUI()
                updateFirestoreStatus("in_progress")
                // Draw route from current driver location to destination
                currentLocation?.let { loc ->
                    isProgrammaticMapMove = false  // ensure clean state for new phase
                    drawRoute(loc, GeoPoint(destLat, destLng))
                }
            }
            TripPhase.IN_PROGRESS -> {
                completeTrip()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Location updates
    // ─────────────────────────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 4000L
        ).setMinUpdateIntervalMillis(2000L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // Don't process any updates until the map is fully ready
                if (!fragmentReady || _binding == null) return

                val location = result.lastLocation ?: return
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                currentLocation = geoPoint

                // Mark zoom as done after first location fix so camera-follow activates
                if (!hasZoomedToRoute) {
                    binding.root.postDelayed({
                        if (_binding != null) hasZoomedToRoute = true
                    }, 3500L)  // slightly after the zoom-in postDelayed above
                }

                // Move driver car icon
                placeOrMoveDriverMarker(geoPoint)

                // Update distance/ETA label
                updateDistanceLabel(geoPoint)

                // Auto-arrive detection
                checkArrival(geoPoint)

                // Decide which target we're routing to
                val target = when (tripPhase) {
                    TripPhase.HEADING_TO_PICKUP -> GeoPoint(pickupLat, pickupLng)
                    TripPhase.IN_PROGRESS       -> GeoPoint(destLat, destLng)
                    else                        -> null
                } ?: return

                // Re-fetch route only after driver moves ROUTE_UPDATE_THRESHOLD_METERS
                val movedEnough = haversineDistance(
                    geoPoint.latitude, geoPoint.longitude,
                    lastRouteUpdateLat, lastRouteUpdateLng
                ) * 1000 > ROUTE_UPDATE_THRESHOLD_METERS

                if (movedEnough || (lastRouteUpdateLat == 0.0 && lastRouteUpdateLng == 0.0)) {
                    lastRouteUpdateLat = geoPoint.latitude
                    lastRouteUpdateLng = geoPoint.longitude
                    drawRoute(geoPoint, target)
                }

                // Gently follow driver on map (only if user hasn't manually panned)
                if (!userIsInteracting && hasZoomedToRoute) {
                    isProgrammaticMapMove = true
                    binding.mapView.controller.animateTo(geoPoint)
                    binding.mapView.postDelayed({ isProgrammaticMapMove = false }, 600L)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Distance / ETA label
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateDistanceLabel(driverLocation: GeoPoint) {
        val targetLat = if (tripPhase == TripPhase.IN_PROGRESS) destLat else pickupLat
        val targetLng = if (tripPhase == TripPhase.IN_PROGRESS) destLng else pickupLng
        val distKm = haversineDistance(
            driverLocation.latitude, driverLocation.longitude,
            targetLat, targetLng
        )
        val minutes = (distKm / 30.0) * 60
        binding.tvDistanceToPickup.text =
            "${String.format("%.1f", distKm)} km • ${minutes.toInt()} min"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-arrival detection
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkArrival(driverLoc: GeoPoint) {
        if (tripPhase != TripPhase.HEADING_TO_PICKUP) return
        val distanceMeters = driverLoc.distanceToAsDouble(GeoPoint(pickupLat, pickupLng))
        if (distanceMeters < 50) {
            tripPhase = TripPhase.ARRIVED_AT_PICKUP
            updatePhaseUI()
            updateFirestoreStatus("arrived_at_pickup")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Firestore helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateFirestoreStatus(status: String) {
        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .update("status", status)
    }

    private fun completeTrip() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .update(mapOf(
                "status"      to "completed",
                "completedAt" to System.currentTimeMillis()
            ))

        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(uid)
            .update(// Replace with:
                mapOf(
                    "isAvailable"  to true,
                    "isOnline"     to true,
                    "activeRideId" to null,
                    "earnings"     to com.google.firebase.firestore.FieldValue.increment(estimatedFare.toLong()),
                    "totalTrips"   to com.google.firebase.firestore.FieldValue.increment(1)
                ))

        findNavController().navigate(R.id.action_driverActiveRide_to_driverHome)
    }

    private fun listenForRiderCancellation() {
        rideStatusListener = FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || _binding == null) return@addSnapshotListener
                if (snapshot.getString("status") == "cancelled") {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@addSnapshotListener
                    FirebaseFirestore.getInstance()
                        .collection("drivers").document(uid)
                        .update(mapOf("isAvailable" to true, "activeRideId" to null))
                    android.widget.Toast.makeText(
                        requireContext(), "Rider cancelled the ride", android.widget.Toast.LENGTH_LONG
                    ).show()
                    findNavController().navigate(R.id.action_driverActiveRide_to_driverHome)
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Haversine
    // ─────────────────────────────────────────────────────────────────────────

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}