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
import com.example.chalride.databinding.FragmentDriverNavigationBinding
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
import kotlin.math.*

/**
 * DriverNavigationFragment — Full-screen turn-by-turn navigation.
 *
 * Features:
 *  • Map rotates to match the driver's bearing (heading-up navigation).
 *  • Driver arrow icon animates smoothly between GPS fixes.
 *  • Route is re-fetched every ROUTE_UPDATE_THRESHOLD_METERS of movement.
 *  • "ARRIVED" detection within 80m → prompts action.
 *  • If heading to pickup → navigate to DriverArrivedPickupFragment on arrival.
 *  • If heading to destination → complete ride.
 *  • Back button returns to DriverActiveRideFragment.
 */
class DriverNavigationFragment : Fragment() {

    private var _binding: FragmentDriverNavigationBinding? = null
    private val binding get() = _binding!!

    // ── Arguments ─────────────────────────────────────────────────────────────
    private val rideRequestId by lazy { arguments?.getString("rideRequestId") ?: "" }
    private val riderName     by lazy { arguments?.getString("riderName")     ?: "Rider" }
    private val riderPhone    by lazy { arguments?.getString("riderPhone")    ?: "" }
    private val targetLat     by lazy { arguments?.getDouble("targetLat")     ?: 0.0 }
    private val targetLng     by lazy { arguments?.getDouble("targetLng")     ?: 0.0 }
    private val targetAddress by lazy { arguments?.getString("targetAddress") ?: "" }
    private val pickupLat     by lazy { arguments?.getDouble("pickupLat")     ?: 0.0 }
    private val pickupLng     by lazy { arguments?.getDouble("pickupLng")     ?: 0.0 }
    private val destLat       by lazy { arguments?.getDouble("destLat")       ?: 0.0 }
    private val destLng       by lazy { arguments?.getDouble("destLng")       ?: 0.0 }
    private val pickupAddress by lazy { arguments?.getString("pickupAddress") ?: "" }
    private val destAddress   by lazy { arguments?.getString("destAddress")   ?: "" }
    private val estimatedFare by lazy { arguments?.getInt("estimatedFare")    ?: 0 }
    private val vehicleType   by lazy { arguments?.getString("vehicleType")   ?: "" }
    private val tripPhaseStr  by lazy { arguments?.getString("tripPhase")     ?: "HEADING_TO_PICKUP" }

    private val isHeadingToPickup get() = tripPhaseStr == "HEADING_TO_PICKUP"

    // ── Location ──────────────────────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: GeoPoint? = null
    private var currentBearing: Float = 0f

    // ── Map state ─────────────────────────────────────────────────────────────
    private var driverMarker:  Marker?   = null
    private var targetMarker:  Marker?   = null
    private var routePolyline: Polyline? = null

    private var routeFetchInProgress   = false
    private var lastRouteUpdateLat     = 0.0
    private var lastRouteUpdateLng     = 0.0
    private val ROUTE_UPDATE_THRESHOLD = 80   // meters

    // Prevent map listener from fighting location auto-follow
    private var isProgrammaticMapMove = false
    private var mapInitialized        = false
    private var hasFirstFix           = false
    private var arrivedDetected       = false

    private var markerAnimator: android.animation.ValueAnimator? = null

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverNavigationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { findNavController().popBackStack() }
            }
        )

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        initMap()

        binding.tvDestLabel.text = targetAddress
        binding.tvPhaseTag.text  = if (isHeadingToPickup) "TO PICKUP" else "TO DESTINATION"

        binding.btnBack.setOnClickListener {
            // Stop location updates to save battery while on overview screen
            if (::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
            findNavController().popBackStack()
        }
        binding.btnRecenter.setOnClickListener {
            currentLocation?.let {
                isProgrammaticMapMove = true
                rotateAndCenterMap(it, currentBearing)
                binding.mapView.postDelayed({ isProgrammaticMapMove = false }, 600)
            }
        }

        startLocationUpdates()

        // Map scroll listener — nothing (we want full auto-follow in nav mode)
        binding.mapView.post { mapInitialized = true }
    }

    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause()  { super.onPause();  binding.mapView.onPause() }

    override fun onDestroyView() {
        markerAnimator?.cancel()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Map
    // ─────────────────────────────────────────────────────────────────────────

    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )
        binding.mapView.controller.setZoom(18.0)
        binding.mapView.controller.setCenter(GeoPoint(targetLat, targetLng))
        // Disable all user zoom gestures during navigation — map stays locked to driver
        binding.mapView.setMultiTouchControls(false)

        // Place the target marker (destination/pickup)
        placeTargetMarker()
    }

    private fun placeTargetMarker() {
        targetMarker?.let { binding.mapView.overlays.remove(it) }
        val gp = GeoPoint(targetLat, targetLng)
        targetMarker = Marker(binding.mapView).apply {
            position = gp
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null; title = null
            try {
                val res    = if (isHeadingToPickup) R.drawable.ic_pickup_marker else R.drawable.ic_destination_marker
                val sizePx = (28 * resources.displayMetrics.density).toInt()
                icon = ContextCompat.getDrawable(requireContext(), res)?.let { d ->
                    val bmp = createBitmap(sizePx, sizePx); val cvs = android.graphics.Canvas(bmp)
                    d.setBounds(0, 0, sizePx, sizePx); d.draw(cvs); bmp.toDrawable(resources)
                }
            } catch (_: Exception) { }
            setOnMarkerClickListener { _, _ -> true }
        }
        binding.mapView.overlays.add(targetMarker)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Driver arrow marker — smooth animated, bearing-rotated
    // ─────────────────────────────────────────────────────────────────────────

    private fun placeOrAnimateDriverMarker(newGeoPoint: GeoPoint) {
        if (_binding == null) return

        if (driverMarker == null) {
            driverMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null; title = null
                rotation = 0f
                try {
                    val sizePx = (44 * resources.displayMetrics.density).toInt()
                    icon = ContextCompat.getDrawable(requireContext(), R.drawable.white_bg_driver_blue_arrow_icon)
                        ?.let { d ->
                            val bmp = createBitmap(sizePx, sizePx)
                            val cvs = android.graphics.Canvas(bmp)
                            d.setBounds(0, 0, sizePx, sizePx); d.draw(cvs)
                            bmp.toDrawable(resources)
                        }
                } catch (_: Exception) { }
                position = newGeoPoint
                binding.mapView.overlays.add(this)
            }
            binding.mapView.invalidate()
            return
        }

        val startLat = driverMarker!!.position.latitude
        val startLng = driverMarker!!.position.longitude

        markerAnimator?.cancel()
        markerAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                if (_binding == null) return@addUpdateListener
                val t = anim.animatedValue as Float
                val lat = startLat + (newGeoPoint.latitude  - startLat) * t
                val lng = startLng + (newGeoPoint.longitude - startLng) * t
                driverMarker?.rotation = 0f
                driverMarker?.position = GeoPoint(lat, lng)
                binding.mapView.invalidate()
            }
            start()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Map rotation + center — the "heading up" effect
    // ─────────────────────────────────────────────────────────────────────────

    private fun rotateAndCenterMap(location: GeoPoint, bearing: Float) {
        binding.mapView.mapOrientation = -bearing
        binding.mapView.controller.setZoom(18.0)
        binding.mapView.controller.setCenter(location)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Route drawing
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawRoute(from: GeoPoint) {
        if (routeFetchInProgress) return
        val apiKey = try { getString(R.string.ors_api_key).trim() } catch (_: Exception) { return }

        routeFetchInProgress = true
        val target = GeoPoint(targetLat, targetLng)

        lifecycleScope.launch {
            try {
                val points = withContext(Dispatchers.IO) {
                    val url = "https://api.openrouteservice.org/v2/directions/driving-car" +
                            "?start=${from.longitude},${from.latitude}" +
                            "&end=${target.longitude},${target.latitude}" +
                            "&radiuses=2000%7C2000"
                    val conn = URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/geo+json")
                    conn.setRequestProperty("Authorization", apiKey)
                    conn.connectTimeout = 10_000; conn.readTimeout = 10_000; conn.connect()
                    if (conn.responseCode != 200) { conn.disconnect(); return@withContext null }
                    val json     = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    conn.disconnect()
                    val features = json.getJSONArray("features")
                    if (features.length() == 0) return@withContext null
                    val summary = features.getJSONObject(0)
                        .getJSONObject("properties")
                        .getJSONArray("segments").getJSONObject(0)
                    val distKm = summary.getDouble("distance") / 1000.0
                    val durMin = summary.getDouble("duration") / 60.0
                    withContext(Dispatchers.Main) {
                        if (_binding != null) updateDistanceEta(distKm, durMin)
                    }
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

                routePolyline?.let { binding.mapView.overlays.remove(it) }
                val routePoints = points ?: arrayListOf(from, target)

                routePolyline = Polyline().apply {
                    setPoints(routePoints)
                    outlinePaint.color       = "#4A80F0".toColorInt()
                    outlinePaint.strokeWidth = 12f
                    outlinePaint.strokeCap   = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin  = android.graphics.Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }

                // Insert route below markers
                val markerIdx = binding.mapView.overlays.indexOf(targetMarker)
                val insertAt  = if (markerIdx >= 0) markerIdx else 0
                binding.mapView.overlays.add(insertAt, routePolyline)
                binding.mapView.invalidate()

            } catch (_: Exception) {
            } finally {
                routeFetchInProgress = false
            }
        }
    }

    private fun updateDistanceEta(distKm: Double, durMin: Double) {
        val dist = if (distKm < 1.0) "${(distKm * 1000).toInt()} m"
        else String.format("%.1f km", distKm)
        val eta  = if (durMin < 60) "${durMin.toInt()} min"
        else String.format("%.0fh %02.0fm", durMin / 60, durMin % 60)
        binding.tvDistEta.text = "$dist · $eta"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Arrival detection
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkArrival(location: GeoPoint) {
        if (arrivedDetected) return
        val distMeters = location.distanceToAsDouble(GeoPoint(targetLat, targetLng))
        if (distMeters < 80.0) {
            arrivedDetected = true
            showArrivalPrompt()
        } else {
            // Show how far we are
            binding.tvArrivedBar.visibility = View.GONE
        }
    }

    private fun showArrivalPrompt() {
        binding.tvArrivedBar.visibility = View.VISIBLE
        binding.tvArrivedBar.text = if (isHeadingToPickup) "You have arrived at pickup!" else "You have reached the destination!"
        binding.btnArrivedAction.visibility = View.VISIBLE
        binding.btnArrivedAction.text = if (isHeadingToPickup) "Arrived at Pickup →" else "Complete Trip →"
        binding.btnArrivedAction.setOnClickListener {
            if (isHeadingToPickup) {
                navigateToArrivedScreen()
            } else {
                completeTrip()
            }
        }

        // Pop-in animation
        binding.btnArrivedAction.scaleX = 0.8f; binding.btnArrivedAction.scaleY = 0.8f; binding.btnArrivedAction.alpha = 0f
        binding.btnArrivedAction.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
    }

    private fun navigateToArrivedScreen() {
        // Only update status here — OTP is generated in DriverArrivedPickupFragment
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseFirestore.getInstance()
            .collection("rideRequests").document(rideRequestId)
            .update("status", "arrived_at_pickup")

        // Update driver document phase
        FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)
            .update("tripPhase", "ARRIVED_AT_PICKUP")

        val bundle = Bundle().apply {
            putString("rideRequestId", rideRequestId)
            putString("riderName",     riderName)
            putString("riderPhone",    riderPhone)
            putDouble("pickupLat",     pickupLat)
            putDouble("pickupLng",     pickupLng)
            putDouble("destLat",       destLat)
            putDouble("destLng",       destLng)
            putString("pickupAddress", pickupAddress)
            putString("destAddress",   destAddress)
            putInt("estimatedFare",    estimatedFare)
            putString("vehicleType",   vehicleType)
        }
        findNavController().navigate(R.id.action_driverNavigation_to_driverArrivedPickup, bundle)
    }

    private fun completeTrip() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("rideRequests").document(rideRequestId)
            .update(mapOf("status" to "completed", "completedAt" to System.currentTimeMillis()))
        FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)
            .update(mapOf(
                "isAvailable"  to true,
                "isOnline"     to true,
                "activeRideId" to null,
                "tripPhase"    to "HEADING_TO_PICKUP", // reset for next ride
                "earnings"     to com.google.firebase.firestore.FieldValue.increment(estimatedFare.toLong()),
                "totalTrips"   to com.google.firebase.firestore.FieldValue.increment(1L)
            ))
        findNavController().navigate(R.id.action_driverNavigation_to_driverHome)
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
            Priority.PRIORITY_HIGH_ACCURACY, 2000L
        ).setMinUpdateIntervalMillis(1000L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (_binding == null) return
                val loc = result.lastLocation ?: return
                val geoPoint = GeoPoint(loc.latitude, loc.longitude)
                // Note: currentLocation still holds PREVIOUS position here — used for bearing calc below

                // Bearing — use device bearing if available, else compute from last known
                // Always compute bearing from movement — more reliable than loc.bearing
                // especially on emulator and low-speed scenarios
                val prevLat = currentLocation?.latitude ?: geoPoint.latitude
                val prevLng = currentLocation?.longitude ?: geoPoint.longitude
                val computedBearing = computeBearing(prevLat, prevLng, geoPoint.latitude, geoPoint.longitude)

                currentBearing = when {
                    loc.hasBearing() && loc.speed > 1.0f -> loc.bearing  // real device, moving fast
                    computedBearing != 0f -> computedBearing               // computed from movement delta
                    else -> currentBearing                                  // keep last known bearing
                }

                currentLocation = geoPoint  // NOW update current location

                // Animate the driver arrow
                placeOrAnimateDriverMarker(geoPoint)

                // Rotate map to heading-up
                isProgrammaticMapMove = true
                rotateAndCenterMap(geoPoint, currentBearing)
                binding.mapView.postDelayed({ isProgrammaticMapMove = false }, 700)

                // Zoom in on first fix
                if (!hasFirstFix) {
                    hasFirstFix = true
                    binding.mapView.controller.setZoom(17.5)
                }

                // Update Firestore driver location
                updateDriverLocationFirestore(loc.latitude, loc.longitude)

                // Check arrival
                checkArrival(geoPoint)

                // Re-fetch route if moved enough
                val movedEnough = haversineDistance(
                    geoPoint.latitude, geoPoint.longitude,
                    lastRouteUpdateLat, lastRouteUpdateLng
                ) * 1000 > ROUTE_UPDATE_THRESHOLD

                if (movedEnough || lastRouteUpdateLat == 0.0) {
                    lastRouteUpdateLat = geoPoint.latitude
                    lastRouteUpdateLng = geoPoint.longitude
                    drawRoute(geoPoint)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    private fun updateDriverLocationFirestore(lat: Double, lng: Double) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("drivers").document(uid)
            .update(mapOf(
                "lat"         to lat,
                "lng"         to lng,
                "lastUpdated" to System.currentTimeMillis()
            ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Math helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun computeBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1); val rLat2 = Math.toRadians(lat2)
        val y = sin(dLng) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}