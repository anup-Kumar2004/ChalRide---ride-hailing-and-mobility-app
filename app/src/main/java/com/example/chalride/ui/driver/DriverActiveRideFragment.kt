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

class DriverActiveRideFragment : Fragment() {

    private var _binding: FragmentDriverActiveRideBinding? = null
    private val binding get() = _binding!!

    // Args
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

    // Trip phase
    private enum class TripPhase { HEADING_TO_PICKUP, ARRIVED_AT_PICKUP, IN_PROGRESS }
    private var tripPhase = TripPhase.HEADING_TO_PICKUP

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var driverMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var currentLocation: GeoPoint? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverActiveRideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { }  // block back during ride
            }
        )

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        initMap()
        bindStaticData()
        startLocationUpdates()
        updatePhaseUI()

        binding.fabMyLocation.setOnClickListener {
            currentLocation?.let {
                binding.mapView.controller.animateTo(it)
                binding.mapView.controller.setZoom(16.0)
            }
        }

        binding.btnTripAction.setOnClickListener {
            handleTripAction()
        }
    }

    // ── Static data ───────────────────────────────────────────────────────────

    private fun bindStaticData() {
        binding.tvRiderName.text    = riderName
        binding.tvFare.text         = "₹$estimatedFare"
        binding.tvVehicleType.text  = vehicleType.replaceFirstChar { it.uppercase() }
        binding.tvPickupAddress.text = pickupAddress
        binding.tvDestAddress.text  = destAddress
        binding.tvCurrentTarget.text = pickupAddress
    }

    // ── Trip phase logic ──────────────────────────────────────────────────────

    private fun handleTripAction() {
        when (tripPhase) {
            TripPhase.HEADING_TO_PICKUP -> {
                // Driver arrived at pickup — waiting for rider
                tripPhase = TripPhase.ARRIVED_AT_PICKUP
                updatePhaseUI()
                updateFirestoreStatus("arrived_at_pickup")
            }
            TripPhase.ARRIVED_AT_PICKUP -> {
                // Rider boarded — start trip
                tripPhase = TripPhase.IN_PROGRESS
                updatePhaseUI()
                updateFirestoreStatus("in_progress")
                // Draw route to destination
                currentLocation?.let { loc ->
                    drawRoute(loc, GeoPoint(destLat, destLng))
                }
            }
            TripPhase.IN_PROGRESS -> {
                // Trip complete
                completeTrip()
            }
        }
    }

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
                // Draw route to pickup
                currentLocation?.let { loc ->
                    drawRoute(loc, GeoPoint(pickupLat, pickupLng))
                }
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

    // ── Map ───────────────────────────────────────────────────────────────────

    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )
        binding.mapView.controller.setZoom(15.0)

        // Place pickup and destination markers immediately
        placeStaticMarker(GeoPoint(pickupLat, pickupLng), isPickup = true)
        placeStaticMarker(GeoPoint(destLat, destLng), isPickup = false)

        // Center offset for bottom sheet
        binding.root.post {
            val topCardBottom = binding.cardStatus.bottom
            val bottomSheetTop = binding.bottomSheet.top
            val screenCenter = binding.root.height / 2
            val visibleMapCenter = topCardBottom + (bottomSheetTop - topCardBottom) / 2
            val neededOffset = visibleMapCenter - screenCenter
            binding.mapView.setMapCenterOffset(0, neededOffset)
        }
    }

    private fun placeStaticMarker(geoPoint: GeoPoint, isPickup: Boolean) {
        val marker = Marker(binding.mapView).apply {
            position = geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null
            title = null
            try {
                val res = if (isPickup) R.drawable.ic_pickup_marker
                else R.drawable.ic_destination_marker
                icon = ContextCompat.getDrawable(requireContext(), res)?.let { drawable ->
                    val sizePx = (20 * resources.displayMetrics.density).toInt()
                    val bmp = createBitmap(sizePx, sizePx)
                    val cvs = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, sizePx, sizePx)
                    drawable.draw(cvs)
                    bmp.toDrawable(resources)
                }
            } catch (_: Exception) { }
            setOnMarkerClickListener { _, _ -> true }
        }
        binding.mapView.overlays.add(marker)
    }

    private fun placeDriverMarker(geoPoint: GeoPoint) {
        driverMarker?.let { binding.mapView.overlays.remove(it) }
        driverMarker = Marker(binding.mapView).apply {
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
        binding.mapView.overlays.add(driverMarker)
        binding.mapView.invalidate()
    }

    private fun drawRoute(from: GeoPoint, to: GeoPoint) {
        lifecycleScope.launch {
            try {
                val points = withContext(Dispatchers.IO) {
                    val apiKey = getString(R.string.ors_api_key)
                    val url = "https://api.openrouteservice.org/v2/directions/driving-car?" +
                            "start=${from.longitude},${from.latitude}" +
                            "&end=${to.longitude},${to.latitude}"
                    val conn = URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/geo+json")
                    conn.setRequestProperty("Authorization", apiKey)
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.connect()
                    if (conn.responseCode != 200) return@withContext null
                    val json = org.json.JSONObject(
                        conn.inputStream.bufferedReader().readText()
                    )
                    val coords = json.getJSONArray("features")
                        .getJSONObject(0)
                        .getJSONObject("geometry")
                        .getJSONArray("coordinates")
                    ArrayList<GeoPoint>().also { list ->
                        for (i in 0 until coords.length()) {
                            val c = coords.getJSONArray(i)
                            list.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                        }
                    }
                } ?: arrayListOf(from, to)

                // Remove old route
                routePolyline?.let { binding.mapView.overlays.remove(it) }

                routePolyline = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = "#6C63FF".toColorInt()
                    outlinePaint.strokeWidth = 7f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.isAntiAlias = true
                }
                binding.mapView.overlays.add(0, routePolyline)
                binding.mapView.invalidate()

                // Zoom to fit route
                val box = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
                binding.mapView.post {
                    binding.mapView.zoomToBoundingBox(box.increaseByScale(1.3f), true)
                }

            } catch (_: Exception) { }
        }
    }

    // ── Location updates ──────────────────────────────────────────────────────

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
                val location = result.lastLocation ?: return
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                currentLocation = geoPoint

                placeDriverMarker(geoPoint)
                updateDistanceLabel(geoPoint)
                writeDriverLocationToFirestore(location.latitude, location.longitude)

                // If first fix and heading to pickup — draw route and center map
                if (tripPhase == TripPhase.HEADING_TO_PICKUP) {
                    binding.mapView.controller.animateTo(geoPoint)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    private fun updateDistanceLabel(driverLocation: GeoPoint) {
        val targetLat = if (tripPhase == TripPhase.IN_PROGRESS) destLat else pickupLat
        val targetLng = if (tripPhase == TripPhase.IN_PROGRESS) destLng else pickupLng

        val dist = haversineDistance(
            driverLocation.latitude, driverLocation.longitude,
            targetLat, targetLng
        )

        binding.tvDistanceToPickup.text = if (dist < 1.0)
            "${(dist * 1000).toInt()} m"
        else
            String.format("%.1f km", dist)
    }

    private fun writeDriverLocationToFirestore(lat: Double, lng: Double) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("drivers").document(uid)
            .update(mapOf(
                "lat" to lat,
                "lng" to lng,
                "lastUpdated" to System.currentTimeMillis()
            ))
    }

    // ── Firestore status updates ──────────────────────────────────────────────

    private fun updateFirestoreStatus(status: String) {
        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .update("status", status)
    }

    private fun completeTrip() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Update ride request
        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .update(mapOf(
                "status"      to "completed",
                "completedAt" to System.currentTimeMillis()
            ))

        // Update driver — back to available, increment earnings + trips
        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(uid)
            .update(mapOf(
                "isAvailable" to true,
                "earnings"    to com.google.firebase.firestore.FieldValue.increment(
                    estimatedFare.toLong()
                ),
                "totalTrips"  to com.google.firebase.firestore.FieldValue.increment(1)
            ))

        // Navigate back to driver home
        findNavController().navigate(R.id.action_driverActiveRide_to_driverHome)
    }

    // ── Haversine ─────────────────────────────────────────────────────────────

    private fun haversineDistance(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) *
                Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        super.onDestroyView()
        _binding = null
    }
}