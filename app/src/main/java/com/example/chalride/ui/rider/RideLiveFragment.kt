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
    private var initialFitViewDone = false

    private var currentRideStatus = ""
    private var otpDisplayed      = false

    // ── Last known driver position (for snap-back restore) ────────────────────
    private var lastKnownDriverLat = 0.0
    private var lastKnownDriverLng = 0.0

    // Measured at runtime from actual view heights — screen-size independent
    private var mapPadTop    = 0
    private var mapPadBottom = 0
    private var mapPadSide   = 40  // small fixed side margin in px, overridden after measure

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

        listenForRideUpdates()
        listenForDriverLocation()
    }

    override fun onResume()  { super.onResume();  binding.mapView.onResume() }
    override fun onPause()   { super.onPause();   binding.mapView.onPause() }

    override fun onDestroyView() {
        rideListener?.remove()
        driverListener?.remove()
        markerAnimator?.cancel()
        snapBackHandler.removeCallbacks(snapBackRunnable)
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

        initialFitViewDone = false

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

            } catch (e: Exception) {
                android.util.Log.e("CHALRIDE_LIVE", "fetchRouteForPhase2 error: ${e.message}")
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
     * Zoom to fit all given points with pixel-level padding that accounts for:
     *  - The status pill / OTP card floating at the top of the screen
     *  - The bottom sheet info card
     *  - Side breathing room
     *
     * How it works:
     *  1. Build the tight bounding box around the points.
     *  2. Ensure a minimum lat/lon span so we never over-zoom on a single point.
     *  3. Convert the desired dp padding into lat/lng degree offsets using the
     *     ratio of the bounding box span to the visible map dimension.
     *  4. Expand the bounding box by those degree offsets and call zoomToBoundingBox.
     *
     * This guarantees no marker or route segment is ever hidden behind UI chrome.
     */
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