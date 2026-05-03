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
import kotlin.math.pow

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

    private var nearbyDrivers: List<Map<String, Any>> = emptyList()
    private val availableVehicleTypes = mutableSetOf<String>()

    private var selectedVehicleType = ""
    private var selectedFare = 0

    private var allRoutePoints: ArrayList<GeoPoint> = arrayListOf()
    private var travelingDot: Marker? = null
    private var dotAnimationRunning = false

    // ── Vehicle card data — keeps all card meta in one place ──────────────
    private data class VehicleCardMeta(
        val type: String,
        val card: () -> com.google.android.material.card.MaterialCardView,
        val scrim: () -> View,
        val fareView: () -> android.widget.TextView,
        val unavailableView: () -> android.widget.TextView,
        val nameView: () -> android.widget.TextView,
        val subtitleView: () -> android.widget.TextView,
        val emojiView: () -> android.widget.TextView
    )

    private val vehicleCards by lazy {
        listOf(
            VehicleCardMeta(
                type = "bike",
                card = { binding.cardBike },
                scrim = { binding.scrimBike },
                fareView = { binding.tvBikeFare },
                unavailableView = { binding.tvBikeUnavailable },
                nameView = { binding.tvBikeName },
                subtitleView = { binding.tvBikeSubtitle },
                emojiView = { binding.tvBikeEmoji }
            ),
            VehicleCardMeta(
                type = "auto",
                card = { binding.cardAuto },
                scrim = { binding.scrimAuto },
                fareView = { binding.tvAutoFare },
                unavailableView = { binding.tvAutoUnavailable },
                nameView = { binding.tvAutoName },
                subtitleView = { binding.tvAutoSubtitle },
                emojiView = { binding.tvAutoEmoji }
            ),
            VehicleCardMeta(
                type = "sedan",
                card = { binding.cardSedan },
                scrim = { binding.scrimSedan },
                fareView = { binding.tvSedanFare },
                unavailableView = { binding.tvSedanUnavailable },
                nameView = { binding.tvSedanName },
                subtitleView = { binding.tvSedanSubtitle },
                emojiView = { binding.tvSedanEmoji }
            ),
            VehicleCardMeta(
                type = "suv",
                card = { binding.cardSuv },
                scrim = { binding.scrimSuv },
                fareView = { binding.tvSuvFare },
                unavailableView = { binding.tvSuvUnavailable },
                nameView = { binding.tvSuvName },
                subtitleView = { binding.tvSuvSubtitle },
                emojiView = { binding.tvSuvEmoji }
            )
        )
    }

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

        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        bottomSheetBehavior.apply {
            state = BottomSheetBehavior.STATE_COLLAPSED
            isDraggable = true
            skipCollapsed = false
            isFitToContents = false
        }

        binding.bottomSheet.post {
            val screenHeight = resources.displayMetrics.heightPixels
            bottomSheetBehavior.peekHeight = (screenHeight * 0.24).toInt()
            bottomSheetBehavior.expandedOffset = (screenHeight * 0.2).toInt()
        }

        binding.bottomSheet.setOnTouchListener { _, _ -> false }

        // Show "Searching…" in badge immediately, then update after Firestore returns
        binding.tvDriverCountBadge.text = "Searching nearby..."

        initMap()
        fetchNearbyDriversAndFilterVehicles()
    }

    // ── Route summary ─────────────────────────────────────────────────────

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

    // ── Vehicle card setup ────────────────────────────────────────────────

    /**
     * Registers click listeners on every card.
     * Unavailable cards ignore taps (handled via the scrim View being on top
     * and the card itself having clickable=false when unavailable).
     */
    private fun setupVehicleCards() {
        vehicleCards.forEach { meta ->
            meta.card().setOnClickListener {
                if (meta.type !in availableVehicleTypes) return@setOnClickListener
                selectVehicle(meta.type)
            }
        }
    }

    /**
     * Called once Firestore returns nearby drivers.
     * Renders every card in its correct available / unavailable state.
     */
    private fun renderVehicleCards() {
        val availableCount = availableVehicleTypes.size

        // Update driver count badge
        binding.tvDriverCountBadge.text = when {
            availableCount == 0 -> "No drivers nearby"
            availableCount == 1 -> "1 vehicle available"
            else                -> "$availableCount vehicle types available"
        }

        vehicleCards.forEach { meta ->
            val isAvailable = meta.type in availableVehicleTypes
            applyCardState(meta, isAvailable)
        }

        if (availableVehicleTypes.isEmpty()) {
            binding.btnFindRide.isEnabled = false
            binding.btnFindRide.text = "No drivers nearby"
        } else {
            binding.btnFindRide.isEnabled = true
            binding.btnFindRide.text = "Find a Ride"
        }
    }

    /**
     * Makes a card visually available (normal) or unavailable (dimmed + badge).
     */
    private fun applyCardState(meta: VehicleCardMeta, isAvailable: Boolean) {
        val card       = meta.card()
        val scrim      = meta.scrim()
        val fareView   = meta.fareView()
        val badge      = meta.unavailableView()
        val nameView   = meta.nameView()
        val subtitleView = meta.subtitleView()

        if (isAvailable) {
            // ── Available state ──────────────────────────────────────────
            card.alpha = 1f
            card.isClickable = true
            card.isFocusable = true
            scrim.visibility = View.GONE
            badge.visibility = View.GONE
            fareView.visibility = View.VISIBLE
            nameView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            subtitleView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

        } else {
            // ── Unavailable state ────────────────────────────────────────
            card.alpha = 0.75f          // dim the card itself
            card.isClickable = false
            card.isFocusable = false
            scrim.visibility = View.VISIBLE   // dark overlay on top
            badge.visibility = View.VISIBLE   // "Not available" pill
            fareView.visibility = View.GONE   // hide fare — replaced by badge
            fareView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            nameView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            subtitleView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))

            // If this was the selected type, deselect it
            if (selectedVehicleType == meta.type) {
                selectedVehicleType = ""
                selectedFare = 0
            }
        }
    }

    /**
     * Highlights the tapped card; resets all others to their available-unselected look.
     */
    private fun selectVehicle(type: String) {
        selectedVehicleType = type
        selectedFare = when (type) {
            "bike"  -> binding.tvBikeFare.text.toString().replace("₹", "").toIntOrNull() ?: 0
            "auto"  -> binding.tvAutoFare.text.toString().replace("₹", "").toIntOrNull() ?: 0
            "sedan" -> binding.tvSedanFare.text.toString().replace("₹", "").toIntOrNull() ?: 0
            "suv"   -> binding.tvSuvFare.text.toString().replace("₹", "").toIntOrNull() ?: 0
            else    -> 0
        }

        vehicleCards.forEach { meta ->
            if (meta.type !in availableVehicleTypes) return@forEach

            val card     = meta.card()
            val fareView = meta.fareView()
            val nameView = meta.nameView()

            if (meta.type == type) {
                // ── SELECTED state ───────────────────────────────────────
                card.setCardBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.bg_surface)
                )
                card.cardElevation = 8f * resources.displayMetrics.density
                card.strokeColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)
                card.strokeWidth = (1.5f * resources.displayMetrics.density).toInt()
                nameView.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.brand_primary)
                )
                fareView.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.brand_primary)
                )
                // Scale up slightly for a "picked" feel
                card.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .start()

            } else {
                // ── UNSELECTED available state ───────────────────────────
                card.setCardBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.input_bg)
                )
                card.cardElevation = 0f
                card.strokeColor = ContextCompat.getColor(requireContext(), R.color.divider_color)
                card.strokeWidth = (1f * resources.displayMetrics.density).toInt()
                nameView.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_primary)
                )
                fareView.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.brand_primary)
                )
                card.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
        }
    }

    // ── Map ───────────────────────────────────────────────────────────────

    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapRoutePreview.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapRoutePreview.setMultiTouchControls(false)
        binding.mapRoutePreview.isClickable = false
        binding.mapRoutePreview.isFocusable = false
        binding.mapRoutePreview.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )
        binding.mapRoutePreview.setOnTouchListener { _, _ -> true }

        val pickup = GeoPoint(pickupLat, pickupLng)
        val dest   = GeoPoint(destLat, destLng)
        val midLat = (pickupLat + destLat) / 2.0
        val midLng = (pickupLng + destLng) / 2.0
        binding.mapRoutePreview.controller.setCenter(GeoPoint(midLat, midLng))
        binding.mapRoutePreview.controller.setZoom(5.0)

        fetchRouteAndDisplay(pickup, dest)
    }

    private fun placeMarker(geoPoint: GeoPoint, isPickup: Boolean) {
        val marker = Marker(binding.mapRoutePreview).apply {
            position = geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            infoWindow = null
            title = null
            try {
                val drawableRes = if (isPickup) R.drawable.ic_pickup_marker else R.drawable.ic_destination_marker
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

    private fun fetchRouteAndDisplay(pickup: GeoPoint, destination: GeoPoint) {
        lifecycleScope.launch {
            try {
                val routePoints = withContext(Dispatchers.IO) {
                    val apiKey = getString(R.string.ors_api_key)
                    val url = "https://api.openrouteservice.org/v2/directions/driving-car" +
                            "?start=${pickup.longitude},${pickup.latitude}" +
                            "&end=${destination.longitude},${destination.latitude}" +
                            "&radiuses=1000%7C1000"

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

                // Shadow layer
                val shadowLine = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = "#441A1560".toColorInt()
                    outlinePaint.strokeWidth = 13f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }
                binding.mapRoutePreview.overlays.add(0, shadowLine)

                // Main route line
                val mainLine = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = "#6C63FF".toColorInt()
                    outlinePaint.strokeWidth = 7f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }
                binding.mapRoutePreview.overlays.add(mainLine)

                placeMarker(pickup, isPickup = true)
                placeMarker(destination, isPickup = false)

                val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
                binding.mapRoutePreview.post {
                    val latSpan = boundingBox.latNorth - boundingBox.latSouth
                    val lonSpan = boundingBox.lonEast - boundingBox.lonWest
                    val paddedBox = org.osmdroid.util.BoundingBox(
                        boundingBox.latNorth + latSpan * 0.15,
                        boundingBox.lonEast + lonSpan * 0.15,
                        boundingBox.latSouth - latSpan * 0.45,
                        boundingBox.lonWest - lonSpan * 0.15
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
                    outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 12f), 0f)
                    outlinePaint.isAntiAlias = true
                }
                binding.mapRoutePreview.overlays.add(0, line)
                placeMarker(pickup, isPickup = true)
                placeMarker(destination, isPickup = false)
                val box = org.osmdroid.util.BoundingBox.fromGeoPoints(fallback)
                binding.mapRoutePreview.post {
                    val latSpan = box.latNorth - box.latSouth
                    val lonSpan = box.lonEast - box.lonWest
                    val paddedBox = org.osmdroid.util.BoundingBox(
                        box.latNorth + latSpan * 0.15,
                        box.lonEast + lonSpan * 0.15,
                        box.latSouth - latSpan * 0.45,
                        box.lonWest - lonSpan * 0.15
                    )
                    binding.mapRoutePreview.zoomToBoundingBox(paddedBox, true)
                    binding.mapRoutePreview.invalidate()
                }
                delay(500)
                startGlowingDotAnimation(fallback)
            }
        }
    }

    private fun startGlowingDotAnimation(points: ArrayList<GeoPoint>) {
        if (points.size < 2 || _binding == null) return
        dotAnimationRunning = true

        val cumDist = FloatArray(points.size)
        cumDist[0] = 0f
        for (i in 1 until points.size) {
            val dx = (points[i].longitude - points[i - 1].longitude).toFloat()
            val dy = (points[i].latitude  - points[i - 1].latitude).toFloat()
            cumDist[i] = cumDist[i - 1] + Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        val totalDist = cumDist.last()
        val density = resources.displayMetrics.density

        fun makeOrbBitmap(outerRadiusDp: Float, midRadiusDp: Float, coreRadiusDp: Float): android.graphics.Bitmap {
            val sizePx = (outerRadiusDp * 2 * density).toInt()
            val bmp = createBitmap(sizePx, sizePx)
            val cvs = android.graphics.Canvas(bmp)
            val cx = sizePx / 2f; val cy = sizePx / 2f
            val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    cx, cy, outerRadiusDp * density,
                    intArrayOf("#556C63FF".toColorInt(), "#226C63FF".toColorInt(), android.graphics.Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            cvs.drawCircle(cx, cy, outerRadiusDp * density, glowPaint)
            cvs.drawCircle(cx, cy, midRadiusDp * density, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = "#886C63FF".toColorInt() })
            cvs.drawCircle(cx, cy, coreRadiusDp * density, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                setShadowLayer(3f * density, 0f, 0f, "#FF6C63FF".toColorInt())
            })
            return bmp
        }

        val orbBitmap = makeOrbBitmap(12f, 6f, 3.5f)
        travelingDot = Marker(binding.mapRoutePreview).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null; title = null
            icon = orbBitmap.toDrawable(resources)
            position = points[0]
        }
        binding.mapRoutePreview.overlays.add(travelingDot)

        val loopDurationMs = 5000L; val frameMs = 16L
        lifecycleScope.launch {
            var elapsed = 0L
            while (dotAnimationRunning && _binding != null) {
                val linearT = (elapsed % loopDurationMs).toFloat() / loopDurationMs.toFloat()
                val t = linearT * linearT * (3f - 2f * linearT)
                val targetDist = t * totalDist
                var lo = 0; var hi = points.size - 1
                while (lo < hi - 1) { val mid = (lo + hi) / 2; if (cumDist[mid] <= targetDist) lo = mid else hi = mid }
                val segLen = cumDist[hi] - cumDist[lo]
                val segT = if (segLen > 0f) (targetDist - cumDist[lo]) / segLen else 0f
                val lat = points[lo].latitude  + segT * (points[hi].latitude  - points[lo].latitude)
                val lng = points[lo].longitude + segT * (points[hi].longitude - points[lo].longitude)
                travelingDot?.position = GeoPoint(lat, lng)
                binding.mapRoutePreview.invalidate()
                delay(frameMs); elapsed += frameMs
            }
        }
    }

    // ── Nearby driver fetch ───────────────────────────────────────────────

    private fun fetchNearbyDriversAndFilterVehicles() {
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)

        // Use precision 5 (≈ 4.9 km x 4.9 km cells) — matches what the service writes.
        // Precision 4 cells are ~156km wide which is too coarse for city-level matching.
        val centerHash = encodeGeohash(pickupLat, pickupLng, precision = 5)

        // Get all 9 cells: center + 8 neighbors
        val cellsToQuery = geohashNeighbors(centerHash) + centerHash

        android.util.Log.d("NearbyDrivers", "Querying ${cellsToQuery.size} geohash cells around $centerHash")

        val allDriverDocs = mutableListOf<Map<String, Any>>()
        var completedQueries = 0
        val totalQueries = cellsToQuery.size

        fun onAllQueriesComplete() {
            // Deduplicate by driverId (a driver near a cell boundary may appear in 2 cells)
            val seen = mutableSetOf<String>()
            val deduplicated = allDriverDocs.filter { driver ->
                val id = driver["driverId"] as? String ?: return@filter false
                seen.add(id)
            }

            // Filter: must have recent location update AND be within 10km of pickup
            val nearby = deduplicated.filter { driver ->
                val lat = driver["lat"] as? Double ?: return@filter false
                val lng = driver["lng"] as? Double ?: return@filter false
                val lastUpdated = driver["lastUpdated"] as? Long ?: 0L
                if (lastUpdated < fiveMinutesAgo) return@filter false
                val distanceKm = haversineDistance(pickupLat, pickupLng, lat, lng)
                android.util.Log.d("NearbyDrivers", "Driver ${driver["driverId"]}: ${String.format("%.2f", distanceKm)}km away")
                distanceKm <= 20.0
            }

            android.util.Log.d("NearbyDrivers", "Found ${nearby.size} drivers within 10km")

            nearbyDrivers = nearby
            availableVehicleTypes.clear()
            nearby.forEach { driver ->
                val type = driver["vehicleType"] as? String ?: return@forEach
                availableVehicleTypes.add(type)
            }

            renderVehicleCards()
        }

        // Run one Firestore query per geohash cell
        cellsToQuery.forEach { cell ->
            val cellEnd = cell + "\uf8ff"

            FirebaseFirestore.getInstance()
                .collection("drivers")
                .whereEqualTo("isOnline", true)
                .whereEqualTo("isAvailable", true)
                .whereGreaterThanOrEqualTo("geohash", cell)
                .whereLessThanOrEqualTo("geohash", cellEnd)
                .get()
                .addOnSuccessListener { snapshot ->
                    snapshot.documents.forEach { doc ->
                        val data = doc.data ?: return@forEach
                        allDriverDocs.add(data + ("driverId" to doc.id))
                    }
                    completedQueries++
                    if (completedQueries == totalQueries) onAllQueriesComplete()
                }
                .addOnFailureListener {
                    completedQueries++
                    if (completedQueries == totalQueries) onAllQueriesComplete()
                }
        }
    }


    // ── Click listeners ───────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnFindRide.setOnClickListener { findRide() }
    }

    // ── Find Ride ─────────────────────────────────────────────────────────

    private fun findRide() {
        if (selectedVehicleType.isEmpty()) {
            Toast.makeText(requireContext(), "Please select a vehicle type", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedVehicleType !in availableVehicleTypes) {
            Toast.makeText(requireContext(), "This vehicle type is no longer available", Toast.LENGTH_SHORT).show()
            return
        }

        val driversOfType = nearbyDrivers
            .filter { it["vehicleType"] == selectedVehicleType }
            .sortedBy {
                val lat = it["lat"] as? Double ?: 0.0
                val lng = it["lng"] as? Double ?: 0.0
                haversineDistance(pickupLat, pickupLng, lat, lng)
            }

        if (driversOfType.isEmpty()) {
            Toast.makeText(requireContext(), "No ${selectedVehicleType} available right now", Toast.LENGTH_SHORT).show()
            return
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        binding.btnFindRide.isEnabled = false
        binding.btnFindRide.text = "Searching..."

        FirebaseFirestore.getInstance().collection("riders").document(uid).get()
            .addOnSuccessListener { doc ->
                val rideData = hashMapOf(
                    "riderId"         to uid,
                    "riderName"       to (doc.getString("name") ?: "Rider"),
                    "pickupLat"       to pickupLat,
                    "pickupLng"       to pickupLng,
                    "pickupAddress"   to pickupAddress,
                    "destLat"         to destLat,
                    "destLng"         to destLng,
                    "destAddress"     to destAddress,
                    "vehicleType"     to selectedVehicleType,
                    "estimatedFare"   to selectedFare,
                    "status"          to "pending",
                    "createdAt"       to System.currentTimeMillis(),
                    "driverId"        to "",
                    "driverName"      to "",
                    "rejectedDrivers" to emptyList<String>()
                )

                FirebaseFirestore.getInstance().collection("rideRequests").add(rideData)
                    .addOnSuccessListener { docRef ->
                        val bundle = Bundle().apply {
                            putString("rideRequestId", docRef.id)
                            putString("vehicleType", selectedVehicleType)
                            putDouble("pickupLat", pickupLat)
                            putDouble("pickupLng", pickupLng)
                        }
                        findNavController().navigate(R.id.action_rideConfirm_to_rideSearching, bundle)
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

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).pow(2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2).pow(2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /**
     * Returns the 8 neighboring geohash cells around the given cell.
     * Together with the center cell, this gives full coverage for drivers
     * near cell boundaries — the same approach used by Uber/Lyft.
     */
    private fun geohashNeighbors(hash: String): List<String> {
        return listOf(
            geohashNeighbor(hash, 1, 0),   // N
            geohashNeighbor(hash, 1, 1),   // NE
            geohashNeighbor(hash, 0, 1),   // E
            geohashNeighbor(hash, -1, 1),  // SE
            geohashNeighbor(hash, -1, 0),  // S
            geohashNeighbor(hash, -1, -1), // SW
            geohashNeighbor(hash, 0, -1),  // W
            geohashNeighbor(hash, 1, -1)   // NW
        )
    }

    /**
     * Decodes a geohash to lat/lng bounds, shifts by (latDir, lngDir) cells,
     * then re-encodes at the same precision. Simple and dependency-free.
     */
    private fun geohashNeighbor(hash: String, latDir: Int, lngDir: Int): String {
        val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        var minLat = -90.0; var maxLat = 90.0
        var minLng = -180.0; var maxLng = 180.0

        // Decode bounding box of the hash
        for (i in hash.indices) {
            val idx = base32.indexOf(hash[i])
            for (b in 4 downTo 0) {
                val bitN = (idx shr b) and 1
                if ((i * 5 + (4 - b)) % 2 == 0) {
                    val mid = (minLng + maxLng) / 2
                    if (bitN == 1) minLng = mid else maxLng = mid
                } else {
                    val mid = (minLat + maxLat) / 2
                    if (bitN == 1) minLat = mid else maxLat = mid
                }
            }
        }

        val latCenter = (minLat + maxLat) / 2
        val lngCenter = (minLng + maxLng) / 2
        val latHeight = maxLat - minLat
        val lngWidth  = maxLng - minLng

        val neighborLat = (latCenter + latDir * latHeight).coerceIn(-90.0, 90.0)
        val neighborLng = (lngCenter + lngDir * lngWidth).let {
            when {
                it > 180  -> it - 360
                it < -180 -> it + 360
                else      -> it
            }
        }

        return encodeGeohash(neighborLat, neighborLng, hash.length)
    }


    private fun encodeGeohash(lat: Double, lng: Double, precision: Int = 6): String {
        val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        var minLat = -90.0; var maxLat = 90.0
        var minLng = -180.0; var maxLng = 180.0
        val hash = StringBuilder()
        var bits = 0; var bitsTotal = 0; var hashValue = 0
        while (hash.length < precision) {
            if (bitsTotal % 2 == 0) {
                val mid = (minLng + maxLng) / 2
                if (lng >= mid) { hashValue = hashValue * 2 + 1; minLng = mid }
                else { hashValue *= 2; maxLng = mid }
            } else {
                val mid = (minLat + maxLat) / 2
                if (lat >= mid) { hashValue = hashValue * 2 + 1; minLat = mid }
                else { hashValue *= 2; maxLat = mid }
            }
            bits++; bitsTotal++
            if (bits == 5) { hash.append(base32[hashValue]); bits = 0; hashValue = 0 }
        }
        return hash.toString()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

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