package com.example.chalride.ui.driver

import android.os.Bundle
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
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import androidx.core.graphics.toColorInt
import java.net.URL

/**
 * DriverActiveRideFragment — Overview screen.
 *
 * Shows:
 *  • A locked, non-interactive mini-map with the route drawn between
 *    pickup and destination (same pattern as RideConfirmFragment).
 *  • Ride details card (fare, rider name, pickup/destination addresses).
 *  • A "▲ Navigate" button that opens the turn-by-turn DriverNavigationFragment.
 *
 * Trip phases managed here:
 *   HEADING_TO_PICKUP  → navigate button opens nav to pickup
 *   ARRIVED_AT_PICKUP  → arrived screen is shown (handled by DriverArrivedPickupFragment)
 *   IN_PROGRESS        → navigate button opens nav to destination
 *
 * This fragment does NOT do GPS tracking itself — it just stores ride context
 * and lets DriverNavigationFragment do all the driving work.
 */
class DriverActiveRideFragment : Fragment() {

    private var _binding: FragmentDriverActiveRideBinding? = null
    private val binding get() = _binding!!

    // ── Arguments ─────────────────────────────────────────────────────────────
    val rideRequestId by lazy { arguments?.getString("rideRequestId") ?: "" }
    val riderName     by lazy { arguments?.getString("riderName")     ?: "Rider" }
    val riderPhone    by lazy { arguments?.getString("riderPhone")    ?: "" }
    val pickupLat     by lazy { arguments?.getDouble("pickupLat")     ?: 0.0 }
    val pickupLng     by lazy { arguments?.getDouble("pickupLng")     ?: 0.0 }
    val destLat       by lazy { arguments?.getDouble("destLat")       ?: 0.0 }
    val destLng       by lazy { arguments?.getDouble("destLng")       ?: 0.0 }
    val pickupAddress by lazy { arguments?.getString("pickupAddress") ?: "" }
    val destAddress   by lazy { arguments?.getString("destAddress")   ?: "" }
    val estimatedFare by lazy { arguments?.getInt("estimatedFare")    ?: 0 }
    val vehicleType   by lazy { arguments?.getString("vehicleType")   ?: "" }

    // Trip phase — kept as companion so navigation fragments can read it
    var tripPhase = TripPhase.HEADING_TO_PICKUP

    // Map overlays
    private var routePolyline: Polyline? = null
    private var rideStatusListener: com.google.firebase.firestore.ListenerRegistration? = null

    private var cachedRoutePoints: ArrayList<GeoPoint>? = null
    private var cachedRoutePhase: TripPhase? = null
    private var savedStartLat: Double = 0.0
    private var savedStartLng: Double = 0.0

    private var mapPadTop    = 0
    private var mapPadBottom = 0
    private var mapPadSide   = 0


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

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { /* block during ride */ }
            }
        )

        initMap()

        binding.mapView.post {
            if (_binding == null) return@post
            val screenH = binding.mapView.height.takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels
            val screenW = binding.mapView.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            // Top card ~16% of screen, bottom sheet ~40%, nav button side ~10%
            mapPadTop    = (screenH * 0.18).toInt()
            mapPadBottom = (screenH * 0.40).toInt()
            mapPadSide   = (screenW * 0.08).toInt()
        }



        bindRideDetails()
        setupClickListeners()
        listenForRiderCancellation()

        val restoredPhase = arguments?.getString("tripPhase")
        when (restoredPhase) {
            "IN_PROGRESS"       -> tripPhase = TripPhase.IN_PROGRESS
            "ARRIVED_AT_PICKUP" -> tripPhase = TripPhase.ARRIVED_AT_PICKUP
            null -> restorePhaseFromFirestore() // App crash recovery — fetch from Firestore
        }
        updatePhaseUI()
        if (restoredPhase != null) {
            binding.mapView.post { fetchLastLocationAndDraw() }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        val restoredPhase = arguments?.getString("tripPhase")
        if (restoredPhase == "IN_PROGRESS" && tripPhase != TripPhase.IN_PROGRESS) {
            tripPhase = TripPhase.IN_PROGRESS
            updatePhaseUI()
        }
        // Always redraw on resume — handles returning from DriverNavigationFragment
        // cachedRoutePoints will prevent an ORS re-fetch if phase hasn't changed
        binding.mapView.overlays.clear()
        binding.mapView.post { fetchLastLocationAndDraw() }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        rideStatusListener?.remove()
        super.onDestroyView()
        _binding = null
    }

    private fun fetchLastLocationAndDraw() {
        android.util.Log.d("CHALRIDE_NAV", "fetchLastLocationAndDraw() called, tripPhase=$tripPhase")
        android.util.Log.d("CHALRIDE_NAV", "pickupLat=$pickupLat, pickupLng=$pickupLng")
        android.util.Log.d("CHALRIDE_NAV", "destLat=$destLat, destLng=$destLng")

        val fusedClient = com.google.android.gms.location.LocationServices
            .getFusedLocationProviderClient(requireContext())
        try {
            fusedClient.lastLocation.addOnSuccessListener { location ->
                if (_binding == null) return@addOnSuccessListener
                if (location != null) {
                    android.util.Log.d("CHALRIDE_NAV",
                        "Driver location fetched: lat=${location.latitude}, lng=${location.longitude}")

                    // Save starting location ONLY once for phase 1 — never overwrite after first save
                    if (savedStartLat == 0.0 && savedStartLng == 0.0
                        && tripPhase == TripPhase.HEADING_TO_PICKUP) {
                        savedStartLat = location.latitude
                        savedStartLng = location.longitude
                        android.util.Log.d("CHALRIDE_NAV",
                            "Saved driver start location: $savedStartLat, $savedStartLng")
                    }

                    drawOverviewRoute(location.latitude, location.longitude)
                } else {
                    android.util.Log.w("CHALRIDE_NAV",
                        "lastLocation returned NULL — using pickup as fallback from-point")
                    drawOverviewRoute()
                }
            }.addOnFailureListener { e ->
                android.util.Log.e("CHALRIDE_NAV", "Failed to get location: ${e.message}")
                drawOverviewRoute()
            }
        } catch (e: SecurityException) {
            android.util.Log.e("CHALRIDE_NAV", "Location permission denied: ${e.message}")
            drawOverviewRoute()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Map — fully locked, no touch, no zoom controls (same as RideConfirmFragment)
    // ─────────────────────────────────────────────────────────────────────────

    private fun initMap() {

        Configuration.getInstance().userAgentValue = requireContext().packageName

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)

        // Allow gestures
        binding.mapView.setMultiTouchControls(true)

        // Hide OSMDroid zoom buttons
        binding.mapView.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )

        binding.mapView.setBuiltInZoomControls(false)

        binding.mapView.isTilesScaledToDpi = true

        val midLat = (pickupLat + destLat) / 2.0
        val midLng = (pickupLng + destLng) / 2.0

        binding.mapView.controller.setCenter(GeoPoint(midLat, midLng))
        binding.mapView.controller.setZoom(12.0)
    }

    private fun drawOverviewRoute(driverLat: Double = 0.0, driverLng: Double = 0.0) {
        binding.btnNavigate.visibility = View.GONE
        routePolyline?.let { binding.mapView.overlays.remove(it) }
        routePolyline = null

        // Cache check
        if (cachedRoutePoints != null && cachedRoutePhase == tripPhase) {
            android.util.Log.d("CHALRIDE_NAV", "Using cached route for phase=$tripPhase")
            redrawCachedRoute()
            return
        }

        val from: GeoPoint
        val to: GeoPoint
        if (tripPhase == TripPhase.IN_PROGRESS) {
            from = GeoPoint(pickupLat, pickupLng)
            to   = GeoPoint(destLat,   destLng)
            android.util.Log.d("CHALRIDE_NAV",
                "Phase IN_PROGRESS: from=($pickupLat,$pickupLng) to=($destLat,$destLng)")
        } else {
            // Always use the SAVED starting location for phase 1 preview
            // This never changes even as driver moves toward pickup
            val useLat = if (savedStartLat != 0.0) savedStartLat else driverLat
            val useLng = if (savedStartLng != 0.0) savedStartLng else driverLng
            val hasLoc = useLat != 0.0 && useLng != 0.0
            from = if (hasLoc) GeoPoint(useLat, useLng)
            else GeoPoint(pickupLat, pickupLng)
            to   = GeoPoint(pickupLat, pickupLng)
            android.util.Log.d("CHALRIDE_NAV",
                "Phase HEADING_TO_PICKUP: using saved start loc=$hasLoc " +
                        "from=(${from.latitude},${from.longitude}) to=(${to.latitude},${to.longitude})")
        }

        // Guard: if from == to (both are 0,0 fallback to same point), skip ORS
        if (from.latitude == to.latitude && from.longitude == to.longitude) {
            android.util.Log.e("CHALRIDE_NAV",
                "from and to are identical — cannot draw route. Check arguments.")
            return
        }

        placeMarker(from, isPickup = true)
        placeMarker(to,   isPickup = false)

        val apiKey = try {
            getString(R.string.ors_api_key).trim()
        } catch (e: Exception) {
            android.util.Log.e("CHALRIDE_NAV", "ORS API key missing: ${e.message}")
            ""
        }

        android.util.Log.d("CHALRIDE_NAV",
            "ORS API key present: ${apiKey.isNotEmpty()}, length=${apiKey.length}")

        lifecycleScope.launch {
            try {
                val points = withContext(Dispatchers.IO) {
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
                    if (conn.responseCode != 200) { conn.disconnect(); return@withContext null }
                    val json     = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
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
                val routePoints = points ?: arrayListOf(from, to)

                cachedRoutePoints = routePoints
                cachedRoutePhase = tripPhase

                // Shadow layer
                val shadow = Polyline().apply {
                    setPoints(routePoints)
                    outlinePaint.color       = "#441A1560".toColorInt()
                    outlinePaint.strokeWidth = 13f
                    outlinePaint.strokeCap   = android.graphics.Paint.Cap.ROUND
                    outlinePaint.isAntiAlias = true
                }
                // Main line
                routePolyline = Polyline().apply {
                    setPoints(routePoints)
                    outlinePaint.color       = "#6C63FF".toColorInt()
                    outlinePaint.strokeWidth = 7f
                    outlinePaint.strokeCap   = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin  = android.graphics.Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }

                binding.mapView.overlays.add(0, shadow)
                binding.mapView.overlays.add(1, routePolyline)
                binding.mapView.invalidate()

                val allPoints = arrayListOf(from, to)

                zoomToFitWithPadding(allPoints)

                // Show Navigate button with a pop-in animation after route is drawn
                delay(600)
                if (_binding == null) return@launch
                showNavigateButton()

            } catch (e: Exception) {
                android.util.Log.e("CHALRIDE_NAV", "drawOverviewRoute outer exception: ${e.message}", e)
                // Do NOT show dotted line — just log. User stays on screen, can retry.
            }
        }
    }

    private fun restorePhaseFromFirestore() {
        FirebaseFirestore.getInstance()
            .collection("rideRequests").document(rideRequestId)
            .get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val savedPhase = doc.getString("tripPhase") ?: "HEADING_TO_PICKUP"
                tripPhase = when (savedPhase) {
                    "IN_PROGRESS"       -> TripPhase.IN_PROGRESS
                    "ARRIVED_AT_PICKUP" -> TripPhase.ARRIVED_AT_PICKUP
                    else                -> TripPhase.HEADING_TO_PICKUP
                }
                updatePhaseUI()
                binding.mapView.post { fetchLastLocationAndDraw() }
            }
    }

    private fun redrawCachedRoute() {
        val points = cachedRoutePoints ?: return

        // Re-place markers first — they were cleared in onResume
        val from: GeoPoint
        val to: GeoPoint
        if (tripPhase == TripPhase.IN_PROGRESS) {
            from = GeoPoint(pickupLat, pickupLng)
            to   = GeoPoint(destLat, destLng)
        } else {
            val useLat = if (savedStartLat != 0.0) savedStartLat else pickupLat
            val useLng = if (savedStartLng != 0.0) savedStartLng else pickupLng
            from = GeoPoint(useLat, useLng)
            to   = GeoPoint(pickupLat, pickupLng)
        }
        placeMarker(from, isPickup = true)
        placeMarker(to, isPickup = false)

        // Re-draw route lines
        val shadow = Polyline().apply {
            setPoints(points)
            outlinePaint.color = "#441A1560".toColorInt()
            outlinePaint.strokeWidth = 13f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.isAntiAlias = true
        }
        routePolyline = Polyline().apply {
            setPoints(points)
            outlinePaint.color = "#6C63FF".toColorInt()
            outlinePaint.strokeWidth = 7f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.isAntiAlias = true
        }
        binding.mapView.overlays.add(0, shadow)
        binding.mapView.overlays.add(1, routePolyline)

        val allPoints = arrayListOf(from, to)

        zoomToFitWithPadding(allPoints)

        lifecycleScope.launch {
            delay(400)
            if (_binding != null) showNavigateButton()
        }
    }

    private fun placeMarker(geoPoint: GeoPoint, isPickup: Boolean) {
        val marker = Marker(binding.mapView).apply {
            position = geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null
            title = null
            try {
                // isPickup=true means this is the FROM point (driver's start location)
                // isPickup=false means this is the TO point (pickup in phase1, dest in phase2)
                val res = when {
                    isPickup -> R.drawable.white_bg_driver_blue_arrow_icon      // always driver icon for FROM
                    tripPhase == TripPhase.IN_PROGRESS -> R.drawable.ic_destination_marker  // red dest
                    else -> R.drawable.ic_pickup_marker                // green pickup for phase1 TO
                }
                val sizePx = (30 * resources.displayMetrics.density).toInt()
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
    }

    private fun zoomToFitWithPadding(allPoints: List<GeoPoint>) {
        if (allPoints.isEmpty()) return

        val bbox = org.osmdroid.util.BoundingBox.fromGeoPoints(allPoints)
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

            val mapW = binding.mapView.width.takeIf  { it > 0 } ?: return@post
            val mapH = binding.mapView.height.takeIf { it > 0 } ?: return@post

            val padTop    = if (mapPadTop    > 0) mapPadTop    else (mapH * 0.18).toInt()
            val padBottom = if (mapPadBottom > 0) mapPadBottom else (mapH * 0.40).toInt()
            val padSide   = if (mapPadSide   > 0) mapPadSide   else (mapW * 0.08).toInt()

            val usableW = (mapW - padSide  * 2).coerceAtLeast(mapW / 2)
            val usableH = (mapH - padTop - padBottom).coerceAtLeast(mapH / 3)

            val latSpan = north - south
            val lonSpan = east  - west

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

            binding.mapView.zoomToBoundingBox(paddedBox, false)

            if (binding.mapView.zoomLevelDouble > 16.5) {
                binding.mapView.controller.setZoom(16.5)
            }
            binding.mapView.controller.setZoom(binding.mapView.zoomLevelDouble - 0.1)
            binding.mapView.invalidate()
        }
    }



    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updatePhaseUI() {
        when (tripPhase) {
            TripPhase.HEADING_TO_PICKUP -> {
                binding.tvPhaseLabel.text    = "HEADING TO PICKUP"
                binding.tvPhaseLabel.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.brand_primary)
                )
                binding.tvCurrentTarget.text = pickupAddress
                binding.btnNavigate.text = "Navigate to Pickup"
            }
            TripPhase.ARRIVED_AT_PICKUP -> {
                // Navigate immediately to arrived screen
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
                findNavController().navigate(
                    R.id.action_driverActiveRide_to_driverArrivedPickup, bundle
                )
            }
            TripPhase.IN_PROGRESS -> {
                binding.tvPhaseLabel.text    = "TRIP IN PROGRESS"
                binding.tvPhaseLabel.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.success_color)
                )
                binding.tvCurrentTarget.text = destAddress
                binding.btnNavigate.text = "Navigate to Destination"
            }
        }
    }

    private fun showNavigateButton() {
        binding.btnNavigate.visibility = View.VISIBLE
        binding.btnNavigate.alpha = 1f
        binding.btnNavigate.scaleX = 1f
        binding.btnNavigate.scaleY = 1f
    }


    private fun bindRideDetails() {
        binding.tvRiderName.text     = riderName
        binding.tvRiderAvatar.text   = riderName.firstOrNull()?.uppercaseChar()?.toString() ?: "R"  // NEW
        binding.tvFare.text          = "₹$estimatedFare"
        binding.tvVehicleType.text   = vehicleType.replaceFirstChar { it.uppercase() }
        binding.tvPickupAddress.text = pickupAddress
        binding.tvDestAddress.text   = destAddress
    }


    // ─────────────────────────────────────────────────────────────────────────
    // Click listeners
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnNavigate.setOnClickListener {
            // Open the turn-by-turn navigation fragment
            val targetLat: Double
            val targetLng: Double
            val targetAddress: String
            val phase: String

            if (tripPhase == TripPhase.IN_PROGRESS) {
                targetLat     = destLat
                targetLng     = destLng
                targetAddress = destAddress
                phase         = "IN_PROGRESS"
            } else {
                targetLat     = pickupLat
                targetLng     = pickupLng
                targetAddress = pickupAddress
                phase         = "HEADING_TO_PICKUP"
            }

            val bundle = Bundle().apply {
                putString("rideRequestId", rideRequestId)
                putString("riderName",     riderName)
                putString("riderPhone",    riderPhone)
                putDouble("targetLat",     targetLat)
                putDouble("targetLng",     targetLng)
                putString("targetAddress", targetAddress)
                putDouble("pickupLat",     pickupLat)
                putDouble("pickupLng",     pickupLng)
                putDouble("destLat",       destLat)
                putDouble("destLng",       destLng)
                putString("pickupAddress", pickupAddress)
                putString("destAddress",   destAddress)
                putInt("estimatedFare",    estimatedFare)
                putString("vehicleType",   vehicleType)
                putString("tripPhase",     phase)
            }
            findNavController().navigate(R.id.action_driverActiveRide_to_driverNavigation, bundle)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rider cancellation listener
    // ─────────────────────────────────────────────────────────────────────────

    private fun listenForRiderCancellation() {
        rideStatusListener = FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || _binding == null) return@addSnapshotListener
                if (snapshot.getString("status") == "cancelled") {
                    android.util.Log.d("DriverActiveRide", "Rider cancelled — navigating to DriverRideCancelled")
                    // State transition happens inside DriverRideCancelledFragment.onViewCreated()
                    // so we don't need to write Firestore here
                    rideStatusListener?.remove()
                    findNavController().navigate(R.id.action_driverActiveRide_to_driverRideCancelled)
                }
            }
    }
}