package com.example.chalride.ui.rider

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRideConfirmBinding
import com.google.firebase.auth.FirebaseAuth
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
import java.net.URL
import androidx.core.graphics.toColorInt
import com.google.android.material.bottomsheet.BottomSheetBehavior

class RideConfirmFragment : Fragment() {

    private var _binding: FragmentRideConfirmBinding? = null
    private val binding get() = _binding!!

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>


    private val pickupLat        by lazy { arguments?.getDouble("pickupLat")        ?: 0.0 }
    private val pickupLng        by lazy { arguments?.getDouble("pickupLng")        ?: 0.0 }
    private val pickupAddress    by lazy { arguments?.getString("pickupAddress")    ?: "" }
    private val destLat          by lazy { arguments?.getDouble("destLat")          ?: 0.0 }
    private val destLng          by lazy { arguments?.getDouble("destLng")          ?: 0.0 }
    private val destAddress      by lazy { arguments?.getString("destAddress")      ?: "" }
    private val routeDistanceKm  by lazy { arguments?.getDouble("routeDistanceKm") ?: 0.0 }
    private val routeDurationMin by lazy { arguments?.getDouble("routeDurationMin") ?: 0.0 }

    private var selectedVehicleType = ""
    private var selectedFare = 0

    private var allRoutePoints: ArrayList<GeoPoint> = arrayListOf()

    // The glowing dot marker that travels along the route
    private var travelingDot: Marker? = null
    private var dotAnimationRunning = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRideConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { findNavController().popBackStack() }
            }
        )

        setupRideSummary()
        setupVehicleCards()
        setupClickListeners()

        // 1. INIT FIRST
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)

        // 2. CONFIGURE
        bottomSheetBehavior.apply {
            state = BottomSheetBehavior.STATE_COLLAPSED
            isDraggable = true
            skipCollapsed = false
            isFitToContents = false
        }

        binding.bottomSheet.post {

            val screenHeight = resources.displayMetrics.heightPixels

            // Collapsed = 24%
            bottomSheetBehavior.peekHeight = (screenHeight * 0.24).toInt()

            // 🔥 Limit max expansion (70%)
            bottomSheetBehavior.expandedOffset = (screenHeight * 0.3).toInt()
        }

        binding.bottomSheet.setOnTouchListener { _, _ -> false }

        // ✅ INIT MAP AFTER sheet is ready
        initMap()
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private fun setupRideSummary() {
        binding.tvPickupAddress.text = pickupAddress
        binding.tvDestAddress.text = destAddress

        val distanceText = if (routeDistanceKm < 1.0)
            "${(routeDistanceKm * 1000).toInt()} m"
        else
            String.format("%.1f km", routeDistanceKm)

        val etaText = if (routeDurationMin < 60)
            "${routeDurationMin.toInt()} min"
        else
            String.format("%.0fh %02.0fm", routeDurationMin / 60, routeDurationMin % 60)

        binding.tvRouteDistance.text = distanceText
        binding.tvRouteEta.text = etaText
        calculateFares(routeDistanceKm)
    }

    private fun calculateFares(distanceKm: Double) {
        val base = 20.0
        binding.tvBikeFare.text  = "₹${(base + distanceKm * 8.0).toInt()}"
        binding.tvAutoFare.text  = "₹${(base + distanceKm * 12.0).toInt()}"
        binding.tvSedanFare.text = "₹${(base + distanceKm * 16.0).toInt()}"
        binding.tvSuvFare.text   = "₹${(base + distanceKm * 22.0).toInt()}"
    }

    // ── Map ───────────────────────────────────────────────────────────────────

    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapRoutePreview.setTileSource(TileSourceFactory.MAPNIK)

        // Fully lock the map — no pan, no zoom, no touch at all
        binding.mapRoutePreview.setMultiTouchControls(false)
        binding.mapRoutePreview.isClickable = false
        binding.mapRoutePreview.isFocusable = false
        binding.mapRoutePreview.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )
        // Intercept all touch events so nothing passes through to the map
        binding.mapRoutePreview.setOnTouchListener { _, _ -> true }

        val pickup = GeoPoint(pickupLat, pickupLng)
        val dest   = GeoPoint(destLat, destLng)

        val midLat = (pickupLat + destLat) / 2.0
        val midLng = (pickupLng + destLng) / 2.0
        binding.mapRoutePreview.controller.setCenter(GeoPoint(midLat, midLng))
        binding.mapRoutePreview.controller.setZoom(5.0)

        // Fetch route then draw everything instantly + start dot animation
        fetchRouteAndDisplay(pickup, dest)
    }

    private fun placeMarker(geoPoint: GeoPoint, isPickup: Boolean) {
        val marker = Marker(binding.mapRoutePreview).apply {
            position = geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null
            title = null
            try {
                val drawableRes = if (isPickup) R.drawable.ic_pickup_marker
                else          R.drawable.ic_destination_marker
                icon = ContextCompat.getDrawable(requireContext(), drawableRes)?.let { drawable ->
                    val sizePx = (18 * resources.displayMetrics.density).toInt()
                    val bitmap = createBitmap(sizePx, sizePx)
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, sizePx, sizePx)
                    drawable.draw(canvas)
                    bitmap.toDrawable(resources)
                }
            } catch (_: Exception) { }
            setOnMarkerClickListener { _, _ -> true }
        }
        binding.mapRoutePreview.overlays.add(marker)
    }

    /**
     * Fetches route, draws full polyline INSTANTLY, then starts premium animation.
     * The route is drawn with a subtle shadow layer beneath the main line for depth.
     */
    private fun fetchRouteAndDisplay(pickup: GeoPoint, destination: GeoPoint) {
        lifecycleScope.launch {
            try {
                val routePoints = withContext(Dispatchers.IO) {
                    val apiKey = getString(R.string.ors_api_key)
                    val url = "https://api.openrouteservice.org/v2/directions/driving-car?" +
                            "radiuses=1000;1000&" +
                            "start=${pickup.longitude},${pickup.latitude}" +
                            "&end=${destination.longitude},${destination.latitude}"

                    val conn = URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/geo+json")
                    conn.setRequestProperty("Authorization", apiKey)
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.connect()

                    if (conn.responseCode != 200) return@withContext null

                    val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    val features = json.getJSONArray("features")
                    if (features.length() == 0) return@withContext null

                    val coords = features.getJSONObject(0)
                        .getJSONObject("geometry")
                        .getJSONArray("coordinates")

                    ArrayList<GeoPoint>().also { list ->
                        for (i in 0 until coords.length()) {
                            val c = coords.getJSONArray(i)
                            list.add(GeoPoint(c.getDouble(1), c.getDouble(0)))
                        }
                    }
                }

                val points = routePoints ?: arrayListOf(pickup, destination)
                allRoutePoints = points

                // ── Shadow layer (slightly wider, dark purple) ─────────────────
                val shadowLine = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = "#441A1560".toColorInt()
                    outlinePaint.strokeWidth = 13f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }
                binding.mapRoutePreview.overlays.add(0, shadowLine)

                // ── Main route line ────────────────────────────────────────────
                val mainLine = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = "#6C63FF".toColorInt()
                    outlinePaint.strokeWidth = 7f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }
                binding.mapRoutePreview.overlays.add(mainLine)

                // ── Place markers on top ───────────────────────────────────────
                placeMarker(pickup, isPickup = true)
                placeMarker(destination, isPickup = false)

                // ── Zoom to fit with generous padding ─────────────────────────
                val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
                binding.mapRoutePreview.post {

                    val latSpan = boundingBox.latNorth - boundingBox.latSouth
                    val lonSpan = boundingBox.lonEast - boundingBox.lonWest

                    // 🔥 Asymmetric padding
                    val topPadding = latSpan * 0.15      // LESS top padding
                    val bottomPadding = latSpan * 0.45   // MORE bottom padding
                    val sidePadding = lonSpan * 0.15

                    val paddedBox = org.osmdroid.util.BoundingBox(
                        boundingBox.latNorth + topPadding,
                        boundingBox.lonEast + sidePadding,
                        boundingBox.latSouth - bottomPadding,
                        boundingBox.lonWest - sidePadding
                    )

                    binding.mapRoutePreview.zoomToBoundingBox(paddedBox, true)
                    binding.mapRoutePreview.invalidate()
                }

                delay(500)
                startGlowingDotAnimation(points)

            } catch (_: Exception) {
                val fallback = arrayListOf(pickup, destination)
                allRoutePoints = fallback

                val line = Polyline().apply {
                    setPoints(fallback)
                    outlinePaint.color = "#6C63FF".toColorInt()
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.pathEffect = android.graphics.DashPathEffect(
                        floatArrayOf(20f, 12f), 0f
                    )
                    outlinePaint.isAntiAlias = true
                }
                binding.mapRoutePreview.overlays.add(0, line)
                placeMarker(pickup, isPickup = true)
                placeMarker(destination, isPickup = false)

                val box = org.osmdroid.util.BoundingBox.fromGeoPoints(fallback)
                binding.mapRoutePreview.post {

                    val latSpan = box.latNorth - box.latSouth
                    val lonSpan = box.lonEast - box.lonWest

                    val topPadding = latSpan * 0.15
                    val bottomPadding = latSpan * 0.45
                    val sidePadding = lonSpan * 0.15

                    val paddedBox = org.osmdroid.util.BoundingBox(
                        box.latNorth + topPadding,
                        box.lonEast + sidePadding,
                        box.latSouth - bottomPadding,
                        box.lonWest - sidePadding
                    )

                    binding.mapRoutePreview.zoomToBoundingBox(paddedBox, true)
                    binding.mapRoutePreview.invalidate()
                }
                delay(500)
                startGlowingDotAnimation(fallback)
            }
        }
    }

    /**
     * Premium three-layer orb animation:
     *   Layer 1 — large soft outer glow (brand purple, very transparent)
     *   Layer 2 — mid halo (brand purple, semi-transparent)
     *   Layer 3 — bright white core
     *
     * Movement uses ease-in-out (sine curve) so it accelerates and decelerates
     * smoothly instead of moving at robotic constant speed.
     * Full loop completes in 5 seconds. Loops continuously.
     */
    private fun startGlowingDotAnimation(points: ArrayList<GeoPoint>) {
        if (points.size < 2 || _binding == null) return
        dotAnimationRunning = true

        // Build cumulative distance for position interpolation
        val cumDist = FloatArray(points.size)
        cumDist[0] = 0f
        for (i in 1 until points.size) {
            val dx = (points[i].longitude - points[i - 1].longitude).toFloat()
            val dy = (points[i].latitude  - points[i - 1].latitude).toFloat()
            cumDist[i] = cumDist[i - 1] + Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        val totalDist = cumDist.last()

        val density = resources.displayMetrics.density

        // ── Outer glow marker (large, very transparent brand purple) ──────────
        fun makeOrbBitmap(outerRadiusDp: Float, midRadiusDp: Float, coreRadiusDp: Float): android.graphics.Bitmap {
            val sizePx = (outerRadiusDp * 2 * density).toInt()
            val bmp = createBitmap(sizePx, sizePx)
            val cvs = android.graphics.Canvas(bmp)
            val cx = sizePx / 2f
            val cy = sizePx / 2f

            // Outer soft glow
            val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    cx, cy, outerRadiusDp * density,
                    intArrayOf(
                        "#556C63FF".toColorInt(),
                        "#226C63FF".toColorInt(),
                        android.graphics.Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            cvs.drawCircle(cx, cy, outerRadiusDp * density, glowPaint)

            // Mid halo
            val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = "#886C63FF".toColorInt()
            }
            cvs.drawCircle(cx, cy, midRadiusDp * density, haloPaint)

            // Bright white core
            val corePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                setShadowLayer(3f * density, 0f, 0f, "#FF6C63FF".toColorInt())
            }
            cvs.drawCircle(cx, cy, coreRadiusDp * density, corePaint)

            return bmp
        }

        val orbBitmap = makeOrbBitmap(outerRadiusDp = 12f, midRadiusDp = 6f, coreRadiusDp = 3.5f)

        travelingDot = Marker(binding.mapRoutePreview).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null
            title = null
            icon = orbBitmap.toDrawable(resources)
            position = points[0]
        }
        binding.mapRoutePreview.overlays.add(travelingDot)

        val loopDurationMs = 5000L
        val frameMs = 16L

        lifecycleScope.launch {
            var elapsed = 0L
            while (dotAnimationRunning && _binding != null) {
                // Linear 0..1 within loop
                val linearT = (elapsed % loopDurationMs).toFloat() / loopDurationMs.toFloat()

                // Ease-in-out (smoothstep) so movement feels premium, not robotic
                val t = linearT * linearT * (3f - 2f * linearT)

                val targetDist = t * totalDist

                // Binary search for segment
                var lo = 0; var hi = points.size - 1
                while (lo < hi - 1) {
                    val mid = (lo + hi) / 2
                    if (cumDist[mid] <= targetDist) lo = mid else hi = mid
                }

                val segLen = cumDist[hi] - cumDist[lo]
                val segT = if (segLen > 0f) (targetDist - cumDist[lo]) / segLen else 0f

                val lat = points[lo].latitude  + segT * (points[hi].latitude  - points[lo].latitude)
                val lng = points[lo].longitude + segT * (points[hi].longitude - points[lo].longitude)

                travelingDot?.position = GeoPoint(lat, lng)
                binding.mapRoutePreview.invalidate()

                delay(frameMs)
                elapsed += frameMs
            }
        }
    }

    // ── Vehicle cards ─────────────────────────────────────────────────────────

    private fun setupVehicleCards() {
        listOf(
            binding.cardBike  to "bike",
            binding.cardAuto  to "auto",
            binding.cardSedan to "sedan",
            binding.cardSuv   to "suv"
        ).forEach { (card, type) ->
            card.setOnClickListener {
                selectedVehicleType = type
                selectedFare = when (type) {
                    "bike"  -> binding.tvBikeFare.text.toString().replace("₹","").toIntOrNull() ?: 0
                    "auto"  -> binding.tvAutoFare.text.toString().replace("₹","").toIntOrNull() ?: 0
                    "sedan" -> binding.tvSedanFare.text.toString().replace("₹","").toIntOrNull() ?: 0
                    "suv"   -> binding.tvSuvFare.text.toString().replace("₹","").toIntOrNull() ?: 0
                    else    -> 0
                }
                highlightSelectedVehicle(card)
            }
        }
    }

    private fun highlightSelectedVehicle(selectedCard: CardView) {
        listOf(binding.cardBike, binding.cardAuto, binding.cardSedan, binding.cardSuv).forEach {
            it.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.input_bg))
        }
        selectedCard.setCardBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.bg_surface)
        )
    }

    // ── Click listeners ───────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnFindRide.setOnClickListener { findRide() }
    }

    // ── Find Ride ─────────────────────────────────────────────────────────────

    private fun findRide() {
        if (selectedVehicleType.isEmpty()) {
            Toast.makeText(requireContext(), "Please select a vehicle type", Toast.LENGTH_SHORT).show()
            return
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        binding.btnFindRide.isEnabled = false
        binding.btnFindRide.text = "Searching..."

        FirebaseFirestore.getInstance().collection("riders").document(uid).get()
            .addOnSuccessListener { doc ->
                val rideData = hashMapOf(
                    "riderId"       to uid,
                    "riderName"     to (doc.getString("name") ?: "Rider"),
                    "pickupLat"     to pickupLat,
                    "pickupLng"     to pickupLng,
                    "pickupAddress" to pickupAddress,
                    "destLat"       to destLat,
                    "destLng"       to destLng,
                    "destAddress"   to destAddress,
                    "vehicleType"   to selectedVehicleType,
                    "estimatedFare" to selectedFare,
                    "status"        to "pending",
                    "createdAt"     to System.currentTimeMillis()
                )
                FirebaseFirestore.getInstance().collection("rideRequests").add(rideData)
                    .addOnSuccessListener {
                        binding.btnFindRide.text = "Searching for drivers..."
                        Toast.makeText(
                            requireContext(),
                            "Looking for nearby $selectedVehicleType drivers...",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .addOnFailureListener { e ->
                        binding.btnFindRide.isEnabled = true
                        binding.btnFindRide.text = "Find a Ride"
                        Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener {
                binding.btnFindRide.isEnabled = true
                binding.btnFindRide.text = "Find a Ride"
            }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        binding.mapRoutePreview.onResume()
    }

    override fun onPause() {
        super.onPause()
        dotAnimationRunning = false
        binding.mapRoutePreview.onPause()
    }

    override fun onDestroyView() {
        dotAnimationRunning = false
        super.onDestroyView()
        _binding = null
    }
}