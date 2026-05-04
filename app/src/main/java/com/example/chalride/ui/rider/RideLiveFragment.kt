package com.example.chalride.ui.rider

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

    private var rideListener:   ListenerRegistration? = null
    private var driverListener: ListenerRegistration? = null

    private var driverMarker:  Marker?   = null
    private var pickupMarker:  Marker?   = null
    private var destMarker:    Marker?   = null
    private var routePolyline: Polyline? = null

    private var markerAnimator: android.animation.ValueAnimator? = null

    // Bearing tracking — for rotating driver arrow
    private var lastDriverLat = 0.0
    private var lastDriverLng = 0.0
    private var currentBearing = 0f

    // Route fetch throttle
    private var lastDriverRouteFetchLat = 0.0
    private var lastDriverRouteFetchLng = 0.0
    private var driverRouteFetchInProgress = false
    private val DRIVER_ROUTE_THRESHOLD_M = 80

    // Phase 2 initial fetch guard
    private var phase2InitialRouteFetched = false

    private var currentRideStatus = ""
    private var otpDisplayed      = false

    // ── Padding constants (dp) ────────────────────────────────────────────────
    // Extra space so the status pill at top and info card at bottom
    // never clip any part of the route or markers.
    private val PAD_TOP_DP    = 100f   // clears "Driver is heading to you" pill
    private val PAD_BOTTOM_DP = 220f   // clears bottom info card
    private val PAD_SIDE_DP   = 48f    // side breathing room

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

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { }
            }
        )

        initMap()
        bindStaticData()
        listenForRideUpdates()
        listenForDriverLocation()
    }

    override fun onResume()  { super.onResume();  binding.mapView.onResume() }
    override fun onPause()   { super.onPause();   binding.mapView.onPause() }

    override fun onDestroyView() {
        rideListener?.remove()
        driverListener?.remove()
        markerAnimator?.cancel()
        super.onDestroyView()
        _binding = null
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
        binding.mapView.controller.setZoom(14.0)
        binding.mapView.controller.setCenter(GeoPoint(pickupLat, pickupLng))
        placePickupMarker()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static data
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindStaticData() {
        binding.tvDriverName.text  = driverName
        binding.tvVehicleType.text = vehicleType.replaceFirstChar { it.uppercase() }
        binding.tvOtpCode.text     = "----"
        binding.tvStatus.text      = "Driver is heading to you"
        binding.cardOtp.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2 switch
    // ─────────────────────────────────────────────────────────────────────────

    private fun switchToPhase2() {
        if (isPhase2) return
        isPhase2 = true
        phase2InitialRouteFetched = false

        android.util.Log.d("CHALRIDE_LIVE",
            "switchToPhase2: pickup=($pickupLat,$pickupLng) dest=($destLat,$destLng)")

        if (destLat == 0.0 || destLng == 0.0) {
            android.util.Log.e("CHALRIDE_LIVE", "switchToPhase2 aborted: destLat/destLng are 0.0")
            return
        }

        binding.mapView.overlays.clear()
        binding.mapView.invalidate()

        driverMarker = null
        routePolyline = null
        lastDriverRouteFetchLat = 0.0
        lastDriverRouteFetchLng = 0.0
        // Reset bearing so arrow doesn't jump
        lastDriverLat = 0.0
        lastDriverLng = 0.0

        placePickupMarker()
        placeDestMarker()
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
                drawRoutePolyline(routePoints)
                zoomToFitWithPadding(
                    listOf(GeoPoint(pickupLat, pickupLng), GeoPoint(destLat, destLng))
                )

            } catch (e: Exception) {
                android.util.Log.e("CHALRIDE_LIVE", "fetchRouteForPhase2 error: ${e.message}")
                if (_binding != null) {
                    zoomToFitWithPadding(
                        listOf(GeoPoint(pickupLat, pickupLng), GeoPoint(destLat, destLng))
                    )
                }
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
                if (error != null || snapshot == null || _binding == null) return@addSnapshotListener
                val lat = snapshot.getDouble("lat") ?: return@addSnapshotListener
                val lng = snapshot.getDouble("lng") ?: return@addSnapshotListener
                if (lat == 0.0 && lng == 0.0) return@addSnapshotListener
                updateDriverMarker(lat, lng)
            }
    }

    private fun updateDriverMarker(lat: Double, lng: Double) {
        val newPoint = GeoPoint(lat, lng)

        // ── Compute bearing from previous → current position ──────────────────
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

        if (driverMarker == null) {
            // First fix — create driver marker
            driverMarker = Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null; title = null
                position = newPoint
                // FIX 1: Smaller icon — 30dp instead of 44dp
                try {
                    val sizePx = (24 * resources.displayMetrics.density).toInt()
                    icon = ContextCompat.getDrawable(
                        requireContext(), R.drawable.white_bg_driver_blue_arrow_icon
                    )?.let { d ->
                        val bmp = createBitmap(sizePx, sizePx)
                        val cvs = android.graphics.Canvas(bmp)
                        d.setBounds(0, 0, sizePx, sizePx); d.draw(cvs)
                        bmp.toDrawable(resources)
                    }
                } catch (_: Exception) { }
                // FIX 3: Apply initial bearing rotation
                rotation = -currentBearing
                binding.mapView.overlays.add(this)
            }
            binding.mapView.invalidate()

            // FIX 2: Zoom to fit driver + target with proper padding
            val targetLat = if (isPhase2) destLat else pickupLat
            val targetLng = if (isPhase2) destLng else pickupLng
            zoomToFitWithPadding(listOf(newPoint, GeoPoint(targetLat, targetLng)))

            fetchAndUpdateDriverRoute(lat, lng)
            return
        }

        // ── Smooth animate to new position ────────────────────────────────────
        val startLat = driverMarker!!.position.latitude
        val startLng = driverMarker!!.position.longitude
        val startBearing = driverMarker!!.rotation
        // Shortest rotation path (avoid spinning the long way around)
        val targetBearing = -currentBearing
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
                // FIX 3: Smoothly rotate arrow to match travel direction
                driverMarker?.rotation = startBearing + bearingDelta * t
                binding.mapView.invalidate()
            }
            start()
        }

        // FIX 2: Keep both driver + target visible during movement
        val targetLat = if (isPhase2) destLat else pickupLat
        val targetLng = if (isPhase2) destLng else pickupLng
        val distToTarget = haversineMeters(lat, lng, targetLat, targetLng)

        if (distToTarget < 250.0) {
            // Driver is very close to target — tight zoom, center between them
            binding.mapView.controller.setZoom(17.0)
            binding.mapView.controller.setCenter(
                GeoPoint((lat + targetLat) / 2.0, (lng + targetLng) / 2.0)
            )
        } else {
            // Keep both in view with padding
            zoomToFitWithPadding(listOf(newPoint, GeoPoint(targetLat, targetLng)))
        }

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
        // Insert below markers (index 0 or 1 keeps route under icons)
        val insertAt = minOf(1, binding.mapView.overlays.size)
        binding.mapView.overlays.add(insertAt, routePolyline)
        binding.mapView.invalidate()
    }

    /**
     * FIX 2 — Zoom to fit all given points with pixel-level padding that
     * accounts for the status pill at top and the info card at bottom.
     *
     * How it works:
     *  1. Build the tight bounding box around the points.
     *  2. Convert the desired pixel padding to lat/lng degrees using the
     *     map's current meters-per-pixel at the centre of the box.
     *  3. Expand the box by those degrees and call zoomToBoundingBox.
     *
     * This guarantees no marker or route is ever hidden behind the UI chrome.
     */
    private fun zoomToFitWithPadding(points: List<GeoPoint>) {
        if (points.isEmpty()) return

        val bbox = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
        val latSpan = (bbox.latNorth - bbox.latSouth).coerceAtLeast(0.003)
        val lonSpan = (bbox.lonEast  - bbox.lonWest ).coerceAtLeast(0.003)

        val density = resources.displayMetrics.density

        // Convert pixel padding → approximate degree padding.
        // We use the lat span vs screen height ratio as a rough scale factor.
        val mapH = binding.mapView.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val mapW = binding.mapView.width.takeIf  { it > 0 }
            ?: resources.displayMetrics.widthPixels

        val latPerPx = latSpan / mapH.toDouble()
        val lonPerPx = lonSpan / mapW.toDouble()

        val topPadDeg    = PAD_TOP_DP    * density * latPerPx
        val bottomPadDeg = PAD_BOTTOM_DP * density * latPerPx
        val sidePadDeg   = PAD_SIDE_DP   * density * lonPerPx

        val paddedBox = org.osmdroid.util.BoundingBox(
            bbox.latNorth + topPadDeg,
            bbox.lonEast  + sidePadDeg,
            bbox.latSouth - bottomPadDeg,
            bbox.lonWest  - sidePadDeg
        )

        binding.mapView.post {
            if (_binding == null) return@post
            binding.mapView.zoomToBoundingBox(paddedBox, true)
            // Cap zoom — never go so close tiles blur
            if (binding.mapView.zoomLevelDouble > 17.0) {
                binding.mapView.controller.setZoom(17.0)
            }
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
                if (error != null || snapshot == null || _binding == null) return@addSnapshotListener

                val status = snapshot.getString("status") ?: return@addSnapshotListener

                android.util.Log.d("CHALRIDE_LIVE",
                    "Snapshot: status=$status otp=${snapshot.getString("riderOtp")}")

                val otp = snapshot.getString("riderOtp") ?: ""
                if (otp.isNotEmpty() && !otpDisplayed) showOtp(otp)

                if (status == currentRideStatus) return@addSnapshotListener
                currentRideStatus = status

                when (status) {
                    "accepted"          -> updateStatus("Driver is heading to you")
                    "arrived_at_pickup" -> updateStatus(
                        "Driver has arrived! Share the OTP to start your ride"
                    )
                    "in_progress" -> {
                        updateStatus("Trip in progress 🚗")
                        binding.cardOtp.visibility = View.GONE
                        switchToPhase2()
                    }
                    "completed" -> {
                        updateStatus("You have reached your destination! 🎉")
                        android.widget.Toast.makeText(
                            requireContext(), "Trip completed!", android.widget.Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigate(R.id.action_rideLive_to_riderHome)
                    }
                    "cancelled" -> {
                        android.widget.Toast.makeText(
                            requireContext(), "Ride was cancelled", android.widget.Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigate(R.id.action_rideLive_to_riderHome)
                    }
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateStatus(message: String) {
        binding.tvStatus.text = message
    }

    private fun showOtp(otp: String) {
        if (otpDisplayed) return
        otpDisplayed = true
        binding.tvOtpCode.text           = otp
        binding.cardOtp.visibility       = View.VISIBLE
        binding.layoutOtpHint.visibility = View.GONE
        binding.cardOtp.scaleX = 0.85f; binding.cardOtp.scaleY = 0.85f; binding.cardOtp.alpha = 0f
        binding.cardOtp.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).setDuration(400)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Math helpers
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

