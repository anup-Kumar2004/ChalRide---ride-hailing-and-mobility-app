package com.example.chalride.ui.rider

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.chalride.databinding.FragmentDestinationSearchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.net.URL
import com.example.chalride.R
import org.json.JSONArray
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable

class DestinationSearchFragment : Fragment() {

    private var _binding: FragmentDestinationSearchBinding? = null
    private val binding get() = _binding!!

    private var searchJob: Job? = null
    private var destinationMarker: Marker? = null
    private var selectedGeoPoint: GeoPoint? = null
    private var selectedAddress: String = ""
    private val searchResults = mutableListOf<Pair<String, GeoPoint>>()

    private val pickupAddress by lazy { arguments?.getString("pickupAddress") ?: "Current Location" }
    private val pickupLat by lazy { arguments?.getDouble("pickupLat") ?: 20.5937 }
    private val pickupLng by lazy { arguments?.getDouble("pickupLng") ?: 78.9629 }

    private var pickupMarker: Marker? = null

    private var routePolyline: org.osmdroid.views.overlay.Polyline? = null
    private var routeDistanceKm: Double = 0.0
    private var routeDurationMin: Double = 0.0

    private var isSelectingFromList = false
    private var isRouteConfirmed = false  // true once a route is successfully drawn
    private var changeDestHideRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDestinationSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvPickupInSearch.text = pickupAddress

        initMap()
        setupSearch()
        setupClickListeners()
    }

    /**
     * When user accidentally taps the map while route is locked,
     * briefly pulse the "Change Destination" button to guide them.
     * Professional pattern used by Uber/Ola.
     */


    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapFullView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapFullView.setMultiTouchControls(true)

        val startPoint = GeoPoint(pickupLat, pickupLng)
        binding.mapFullView.controller.setZoom(14.0)
        binding.mapFullView.controller.setCenter(startPoint)
        placePickupMarker(startPoint)

        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (isRouteConfirmed) {
                    // Route is locked — flash the change button to guide the user
                    animateChangeDestinationHint()
                    return true  // consume the tap, do nothing else
                }
                placeDestinationMarker(p)
                reverseGeocode(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        })

        binding.mapFullView.overlays.add(0, mapEventsOverlay)

        binding.mapFullView.addMapListener(object : org.osmdroid.events.MapListener {

            override fun onScroll(event: org.osmdroid.events.ScrollEvent): Boolean {
                updatePulsePosition()
                return false
            }

            override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                updatePulsePosition()
                return false
            }
        })
    }

    private fun animateChangeDestinationHint() {
        if (binding.btnChangeDestination.isVisible) return

        binding.tvRouteInfo.visibility = View.INVISIBLE

        binding.btnChangeDestination.visibility = View.VISIBLE
        binding.btnChangeDestination.scaleX = 0.7f
        binding.btnChangeDestination.scaleY = 0.7f
        binding.btnChangeDestination.alpha = 0f

        binding.btnChangeDestination.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(200)
            .withEndAction {
                // Store runnable so we can cancel it if route is cleared
                changeDestHideRunnable = Runnable {
                    binding.btnChangeDestination.animate()
                        .alpha(0f)
                        .translationY(20f)
                        .setDuration(300)
                        .withEndAction {
                            binding.btnChangeDestination.visibility = View.GONE
                            binding.btnChangeDestination.translationY = 0f
                            // Only restore tvRouteInfo if route is still active
                            if (isRouteConfirmed) {
                                binding.tvRouteInfo.visibility = View.VISIBLE
                            }
                        }
                        .start()
                }
                binding.btnChangeDestination.postDelayed(changeDestHideRunnable!!, 3000)
            }
            .start()
    }

    private fun cancelChangeDestinationHint() {
        changeDestHideRunnable?.let { binding.btnChangeDestination.removeCallbacks(it) }
        changeDestHideRunnable = null
        binding.btnChangeDestination.animate().cancel()
        binding.btnChangeDestination.visibility = View.GONE
        binding.btnChangeDestination.alpha = 1f
        binding.btnChangeDestination.translationY = 0f
    }

    private fun fetchAndDrawRoute(pickup: GeoPoint, destination: GeoPoint) {
        // Remove old route if exists
        routePolyline?.let { binding.mapFullView.overlays.remove(it) }
        routePolyline = null

        // Show loading on confirm button
        binding.btnConfirmLocation.text = "Calculating route..."
        binding.btnConfirmLocation.isEnabled = false

        lifecycleScope.launch {
            try {
                val apiKey = getString(R.string.ors_api_key)
                val result = withContext(Dispatchers.IO) {
                    val url = "https://api.openrouteservice.org/v2/directions/driving-car?" +
                            "radiuses=1000;1000&" +
                            "start=${pickup.longitude},${pickup.latitude}" +
                            "&end=${destination.longitude},${destination.latitude}"

                    android.util.Log.d("RouteDebug", "Fetching route: $url")

                    val httpConn = URL(url).openConnection() as java.net.HttpURLConnection
                    httpConn.requestMethod = "GET"

                    // ✅ FIXED HEADERS
                    httpConn.setRequestProperty("Accept", "application/geo+json")
                    httpConn.setRequestProperty("Authorization", apiKey)

                    httpConn.connectTimeout = 10000
                    httpConn.readTimeout = 10000
                    httpConn.connect()

                    val responseCode = httpConn.responseCode
                    android.util.Log.d("RouteDebug", "Response code: $responseCode")

                    if (responseCode != 200) {
                        val errorBody = httpConn.errorStream?.bufferedReader()?.readText() ?: "No error body"
                        android.util.Log.e("RouteDebug", "Error response: $errorBody")
                        throw Exception("HTTP $responseCode: $errorBody")
                    }

                    val response = httpConn.inputStream.bufferedReader().readText()
                    android.util.Log.d("RouteDebug", "Route response received, length: ${response.length}")
                    org.json.JSONObject(response)
                }

                // Parse route geometry
                val features = result.getJSONArray("features")
                if (features.length() == 0) {
                    binding.btnConfirmLocation.text = "Confirm Destination"
                    binding.btnConfirmLocation.isEnabled = true
                    return@launch
                }

                val feature = features.getJSONObject(0)
                val geometry = feature.getJSONObject("geometry")
                val coordinates = geometry.getJSONArray("coordinates")

                // Parse summary
                val summary = feature.getJSONObject("properties")
                    .getJSONArray("segments")
                    .getJSONObject(0)
                routeDistanceKm = summary.getDouble("distance") / 1000.0
                routeDurationMin = summary.getDouble("duration") / 60.0

                // Build list of GeoPoints for polyline
                val routePoints = ArrayList<GeoPoint>()
                for (i in 0 until coordinates.length()) {
                    val coord = coordinates.getJSONArray(i)
                    routePoints.add(GeoPoint(coord.getDouble(1), coord.getDouble(0)))
                }

                // Draw polyline on map
                routePolyline = org.osmdroid.views.overlay.Polyline().apply {
                    setPoints(routePoints)
                    outlinePaint.color = android.graphics.Color.parseColor("#6C63FF")
                    outlinePaint.strokeWidth = 8f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }

                binding.mapFullView.overlays.add(routePolyline)

                // Make sure markers stay on top of route
                pickupMarker?.let {
                    binding.mapFullView.overlays.remove(it)
                    binding.mapFullView.overlays.add(it)
                }
                destinationMarker?.let {
                    binding.mapFullView.overlays.remove(it)
                    binding.mapFullView.overlays.add(it)
                }

                binding.mapFullView.invalidate()

                // Update UI FIRST so element heights are measurable before zooming
                val distanceText = if (routeDistanceKm < 1.0) {
                    "${(routeDistanceKm * 1000).toInt()} m"
                } else {
                    String.format("%.1f km", routeDistanceKm)
                }
                val etaText = if (routeDurationMin < 60) {
                    "${routeDurationMin.toInt()} min"
                } else {
                    String.format("%.0fh %02.0fm", routeDurationMin / 60, routeDurationMin % 60)
                }

                binding.tvRouteInfo.visibility = View.VISIBLE
                binding.tvRouteInfo.text = "$distanceText · ⏱ $etaText"
                binding.btnConfirmLocation.text = "Confirm Destination"
                binding.btnConfirmLocation.isEnabled = true

                hideWarning()   // ✅ ADD EXACTLY HERE

                // Zoom AFTER UI is updated so we can accurately measure occluded heights
                zoomToFitRouteInSafeArea(routePoints)

                // Lock map against accidental taps now that route is drawn
                isRouteConfirmed = true


            } catch (e: Exception) {
                android.util.Log.e("RouteDebug", "Route fetch failed: ${e.message}", e)

                binding.btnConfirmLocation.text = "Confirm Destination"
                binding.btnConfirmLocation.isEnabled = true
                isRouteConfirmed = false
                binding.btnChangeDestination.visibility = View.GONE

                val message = e.message ?: ""

                when {

                    // ✅ ROUTING errors (show warning)
                    message.contains("coordinate 0") -> {
                        showWarning("Pickup location isn’t accessible, please go back and change your pickup spot.")

                        findNavController().previousBackStackEntry?.savedStateHandle
                            ?.set("pickupUnroutable", true)
                    }

                    message.contains("coordinate 1") -> {
                        showWarning("Destination isn’t reachable, please select a different destination spot.")
                    }

                    message.contains("Could not find routable point") -> {
                        showWarning("We couldn’t find a route between these locations, try changing pickup or destination spot.")
                    }

                    // ⚠️ NETWORK / SERVER ERRORS → DO NOT break UX
                    message.contains("timeout", true) ||
                            message.contains("Network issue. Please try again.", true) -> {

                        // 🔥 Just reset button, DO NOT show warning
                        binding.btnConfirmLocation.text = "Confirm Destination"
                        binding.btnConfirmLocation.isEnabled = true

                        // Optional: log only
                        android.util.Log.e("RouteDebug", "Temporary network issue: $message")
                    }

                    else -> {
                        // fallback: do nothing (like your old working code)
                        binding.tvRouteInfo.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * Zooms the map so the full route is visible inside the "safe area" —
     * the visible map rectangle that is NOT covered by the top address card
     * or the bottom route-info / confirm-button bar.
     *
     * Strategy:
     *  1. Measure how many pixels each UI overlay actually covers at runtime.
     *  2. Expand the route BoundingBox proportionally so that when OSMDroid
     *     zooms the expanded box to fill the whole screen, the original route
     *     lands only within the safe area.
     *  3. Shift the expanded box's lat center to account for the asymmetry
     *     between top and bottom occlusion heights.
     */
    private fun zoomToFitRouteInSafeArea(routePoints: ArrayList<GeoPoint>) {
        binding.mapFullView.post {
            val mapView = binding.mapFullView
            val mapH = mapView.height
            val mapW = mapView.width
            if (mapH == 0 || mapW == 0 || routePoints.isEmpty()) return@post

            val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(routePoints)

            // ── Step 1: Measure actual on-screen occlusion by UI elements ─────────

            val mapScreenPos = IntArray(2)
            mapView.getLocationOnScreen(mapScreenPos)

            val searchScreenPos = IntArray(2)
            binding.etDestinationSearch.getLocationOnScreen(searchScreenPos)

            val btnScreenPos = IntArray(2)
            binding.btnConfirmLocation.getLocationOnScreen(btnScreenPos)
            val mapBottomOnScreen = mapScreenPos[1] + mapH

            // Buffer: whichever is larger — 80px flat, or 8% of map height
            val extraBuffer = maxOf(80, (mapH * 0.08).toInt())

            val topOccluded = ((searchScreenPos[1] + binding.etDestinationSearch.height)
                    - mapScreenPos[1] + extraBuffer)
                .coerceIn(0, mapH / 2)

            val botOccluded = (mapBottomOnScreen - btnScreenPos[1] + extraBuffer)
                .coerceIn(0, mapH / 2)

            // ── Step 2: Compute route geometry first ──────────────────────────────

            val origLatSpan = (boundingBox.latNorth - boundingBox.latSouth).coerceAtLeast(0.001)
            val origLonSpan = (boundingBox.lonEast - boundingBox.lonWest).coerceAtLeast(0.001)
            val routeLatCenter = (boundingBox.latNorth + boundingBox.latSouth) / 2.0
            val routeLonCenter = (boundingBox.lonEast + boundingBox.lonWest) / 2.0

            // Very vertical routes have near-zero lon span — give them more
            // horizontal padding so the expanded box stays well-proportioned
            val routeAspect = origLonSpan / origLatSpan
            val sidePadPx = if (routeAspect < 0.3) 120 else 60

            // ── Step 3: Compute safe-area dimensions ──────────────────────────────

            val safeH = (mapH - topOccluded - botOccluded).coerceAtLeast(100)
            val safeW = (mapW - 2 * sidePadPx).coerceAtLeast(100)

            // ── Step 4: Scale the bounding box to fill the full screen while ──────
            //           keeping the route inside the safe area only             ──────

            val newLatSpan = origLatSpan * (mapH.toDouble() / safeH.toDouble())
            val newLonSpan = origLonSpan * (mapW.toDouble() / safeW.toDouble())

            // ── Step 5: Shift lat center so route is centered in the safe zone ────

            val safeCenterOffsetPx = (topOccluded - botOccluded) / 2.0
            val latCenterShift = safeCenterOffsetPx / mapH.toDouble() * newLatSpan
            val adjustedLatCenter = routeLatCenter + latCenterShift

            // ── Step 6: Build expanded box and zoom ───────────────────────────────

            val expandedBox = org.osmdroid.util.BoundingBox(
                adjustedLatCenter + newLatSpan / 2.0,
                routeLonCenter    + newLonSpan / 2.0,
                adjustedLatCenter - newLatSpan / 2.0,
                routeLonCenter    - newLonSpan / 2.0
            )

            mapView.zoomToBoundingBox(expandedBox, true, 0)
        }
    }

    private fun showWarning(message: String) {
        binding.cardWarning.visibility = View.VISIBLE
        binding.tvWarningMessage.text = message

        // Optional smooth animation
        binding.cardWarning.alpha = 0f
        binding.cardWarning.translationY = 20f
        binding.cardWarning.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .start()

        binding.btnConfirmLocation.visibility = View.GONE
        binding.fabGps.visibility = View.GONE
    }

    private fun hideWarning() {
        binding.cardWarning.visibility = View.GONE
        binding.btnConfirmLocation.visibility = View.VISIBLE
        binding.fabGps.visibility = View.VISIBLE
    }


    private fun placePickupMarker(geoPoint: GeoPoint) {
        pickupMarker?.let { binding.mapFullView.overlays.remove(it) }

        pickupMarker = Marker(binding.mapFullView).apply {
            position = geoPoint
            title = null
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            try {
                icon = ContextCompat.getDrawable(
                    requireContext(), R.drawable.ic_pickup_marker
                )?.let { drawable ->
                    val sizePx = (20 * resources.displayMetrics.density).toInt()
                    val bitmap = createBitmap(sizePx, sizePx)
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, sizePx, sizePx)
                    drawable.draw(canvas)
                    bitmap.toDrawable(resources)
                }
            } catch (_: Exception) { }
            infoWindow = null
            setOnMarkerClickListener { _, _ -> true }
        }

        binding.mapFullView.overlays.add(pickupMarker)
        binding.mapFullView.invalidate()

        // Your original working pulse logic — do NOT use post{}
        updatePulsePosition()
        updatePulseOnNextFrame()

        binding.pickupPulseView.animate().cancel()
        binding.pickupPulseView.clearAnimation()
        binding.pickupPulseView.visibility = View.VISIBLE
        binding.pickupPulseView.bringToFront()
        startPulse(binding.pickupPulseView)
    }


    private fun setupSearch() {
        binding.etDestinationSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if ((s?.length ?: 0) < 3) hideDestinationDropdown()
            }

            override fun afterTextChanged(s: Editable?) {
                if (isSelectingFromList) {
                    hideDestinationDropdown()   // 🔥 force close
                    return
                }


                searchJob?.cancel()
                val query = s.toString().trim()

                if (query.length >= 3) {
                    searchJob = lifecycleScope.launch {
                        delay(400)
                        searchLocation(query)
                    }
                } else {
                    hideDestinationDropdown()
                }
            }
        })

        // ADD THIS INSTEAD:
        binding.etDestinationSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.etDestinationSearch.isCursorVisible = true
        }

        binding.etDestinationSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etDestinationSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch { searchLocation(query) }
                }
                true
            } else false
        }


    }

    private suspend fun searchLocation(query: String) {
        try {
            val results = withContext(Dispatchers.IO) {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5&countrycodes=in"
                val connection = URL(url).openConnection()
                connection.setRequestProperty("User-Agent", requireContext().packageName)
                connection.connect()
                val response = connection.getInputStream().bufferedReader().readText()
                val jsonArray = JSONArray(response)

                (0 until jsonArray.length()).map {
                    val obj = jsonArray.getJSONObject(it)
                    val name = obj.getString("display_name").split(",").take(3).joinToString(", ")
                    Pair(name, GeoPoint(obj.getDouble("lat"), obj.getDouble("lon")))
                }
            }

            searchResults.clear()
            searchResults.addAll(results)

            if (results.isNotEmpty()) {
                hideNoResultsState()
                showDestinationDropdown(results.map { it.first })
            } else {
                hideDestinationDropdown()
                showNoResultsState(query)  // ← show empty state
            }

        } catch (_: Exception) {
            hideDestinationDropdown()
            showNoResultsState(query)  // ← also show on network error
        }
    }

    private fun showDestinationDropdown(items: List<String>) {
        val adapter = object : ArrayAdapter<String>(
            requireContext(), R.layout.item_search_result, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(
                    R.layout.item_search_result, parent, false
                )
                val parts = items[position].split(",", limit = 2)
                view.findViewById<TextView>(R.id.tvResultPrimary).text = parts[0].trim()
                view.findViewById<TextView>(R.id.tvResultSecondary).text =
                    if (parts.size > 1) parts[1].trim() else ""
                return view
            }
        }
        binding.dividerDropdown.visibility = View.VISIBLE
        binding.lvDestinationResults.adapter = adapter
        binding.lvDestinationResults.visibility = View.VISIBLE
    }

    private fun hideDestinationDropdown() {
        binding.dividerDropdown.visibility = View.GONE
        binding.lvDestinationResults.visibility = View.GONE
        binding.lvDestinationResults.adapter = null
        searchResults.clear()
        hideNoResultsState()
    }

    private fun placeDestinationMarker(geoPoint: GeoPoint) {
        hideWarning()

        selectedGeoPoint = geoPoint
        destinationMarker?.let { binding.mapFullView.overlays.remove(it) }

        destinationMarker = Marker(binding.mapFullView).apply {
            position = geoPoint
            title = null
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            try {
                icon = ContextCompat.getDrawable(
                    requireContext(), R.drawable.ic_destination_marker
                )?.let { drawable ->
                    val sizePx = (20 * resources.displayMetrics.density).toInt()
                    val bitmap = createBitmap(sizePx, sizePx)
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, sizePx, sizePx)
                    drawable.draw(canvas)
                    bitmap.toDrawable(resources)
                }
            } catch (e: Exception) { }
            infoWindow = null
            setOnMarkerClickListener { _, _ -> true }
        }

        binding.mapFullView.overlays.add(destinationMarker)
        binding.mapFullView.invalidate()

        // Your original working pulse logic — do NOT use post{}
        updatePulsePosition()
        updatePulseOnNextFrame()

        binding.pulseView.animate().cancel()
        binding.pulseView.clearAnimation()
        binding.pulseView.visibility = View.VISIBLE
        binding.pulseView.bringToFront()
        startPulse(binding.pulseView)

    }


    private fun updatePulseOnNextFrame() {
        binding.mapFullView.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    binding.mapFullView.viewTreeObserver.removeOnPreDrawListener(this)
                    updatePulsePosition()
                    return true
                }
            }
        )
    }

    private fun updatePulsePosition() {

        // 🔴 Destination (RED)
        destinationMarker?.position?.let { geoPoint ->
            val p = binding.mapFullView.projection.toPixels(geoPoint, null)

            binding.pulseView.x = p.x.toFloat() - binding.pulseView.width / 2f
            binding.pulseView.y = p.y.toFloat() - binding.pulseView.height / 2f
        }

        // 🟢 Pickup (GREEN)
        pickupMarker?.position?.let { geoPoint ->
            val p = binding.mapFullView.projection.toPixels(geoPoint, null)

            binding.pickupPulseView.x = p.x.toFloat() - binding.pickupPulseView.width / 2f
            binding.pickupPulseView.y = p.y.toFloat() - binding.pickupPulseView.height / 2f
        }
    }

    private fun startPulse(view: View) {
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 0.8f

        view.animate()
            .scaleX(1.8f)
            .scaleY(1.8f)
            .alpha(0f)
            .setDuration(1000)
            .withEndAction {
                if (view.isVisible) {
                    startPulse(view)
                }
            }
            .start()
    }

    private fun showNoResultsState(query: String) {
        binding.lvDestinationResults.visibility = View.GONE
        binding.dividerDropdown.visibility = View.VISIBLE
        binding.cardNoResults.visibility = View.VISIBLE
        binding.tvNoResultsQuery.text = "Try a different search for \"$query\""
    }

    private fun hideNoResultsState() {
        binding.cardNoResults.visibility = View.GONE
    }

    private fun reverseGeocode(geoPoint: GeoPoint) {
        lifecycleScope.launch {
            try {
                val address = withContext(Dispatchers.IO) {
                    val url = "https://nominatim.openstreetmap.org/reverse?lat=${geoPoint.latitude}&lon=${geoPoint.longitude}&format=json"
                    val connection = URL(url).openConnection()
                    connection.setRequestProperty("User-Agent", requireContext().packageName)
                    connection.connect()
                    val response = connection.getInputStream().bufferedReader().readText()
                    val json = org.json.JSONObject(response)
                    json.optString("display_name", "Selected Location")
                        .split(",").take(3).joinToString(", ")
                }

                selectedAddress = address

                isSelectingFromList = true   // 🔥 block TextWatcher
                binding.etDestinationSearch.isCursorVisible = false
                binding.etDestinationSearch.setText(address)
                binding.etDestinationSearch.setSelection(0)   // 🔥 IMPORTANT
                isSelectingFromList = false

                hideDestinationDropdown()    // 🔥 ensure dropdown is closed
                hideKeyboard()               // optional but clean UX

            } catch (_: Exception) {
                selectedAddress = "Selected Location"
            }

            // ← ADD THE NEW CODE HERE, outside the try-catch but inside launch{}
            selectedGeoPoint?.let { dest ->
                val pickup = GeoPoint(pickupLat, pickupLng)
                fetchAndDrawRoute(pickup, dest)
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnClearSearch.setOnClickListener {
            cancelChangeDestinationHint()  // handles chip + timer cancellation
            isRouteConfirmed = false

            binding.etDestinationSearch.setText("")
            // ADD THIS LINE right after setText(""):
            binding.etDestinationSearch.isCursorVisible = true

            selectedGeoPoint = null
            selectedAddress = ""
            hideDestinationDropdown()

            destinationMarker?.let {
                binding.mapFullView.overlays.remove(it)
                binding.mapFullView.invalidate()
            }
            destinationMarker = null

            binding.pulseView.animate().cancel()
            binding.pulseView.clearAnimation()
            binding.pulseView.visibility = View.GONE

            routePolyline?.let {
                binding.mapFullView.overlays.remove(it)
                binding.mapFullView.invalidate()
            }
            routePolyline = null

            binding.tvRouteInfo.visibility = View.GONE


            hideWarning()   // ✅ THIS IS THE ONLY LINE YOU NEEDED
        }

        binding.lvDestinationResults.setOnItemClickListener { _, _, position, _ ->
            if (position >= searchResults.size) return@setOnItemClickListener

            val (label, geoPoint) = searchResults[position]

            selectedAddress = label
            selectedGeoPoint = geoPoint

            hideDestinationDropdown()

            isSelectingFromList = true
            binding.etDestinationSearch.setText(label)
            isSelectingFromList = false

            binding.etDestinationSearch.setSelection(0)   // 🔥 IMPORTANT CHANGE

            placeDestinationMarker(geoPoint)
            binding.mapFullView.controller.animateTo(geoPoint)
            binding.mapFullView.controller.setZoom(15.0)

            val pickup = GeoPoint(pickupLat, pickupLng)
            fetchAndDrawRoute(pickup, geoPoint)

            hideKeyboard()

            binding.etDestinationSearch.isCursorVisible = false
        }

        binding.fabGps.setOnClickListener {
            val pickup = GeoPoint(pickupLat, pickupLng)
            binding.mapFullView.controller.animateTo(pickup)
            binding.mapFullView.controller.setZoom(15.0)
        }

        binding.btnConfirmLocation.setOnClickListener {
            val dest = selectedGeoPoint ?: return@setOnClickListener
            val address = selectedAddress.ifEmpty { "Selected Location" }

            val bundle = Bundle().apply {
                putDouble("pickupLat", pickupLat)
                putDouble("pickupLng", pickupLng)
                putString("pickupAddress", pickupAddress)
                putDouble("destLat", dest.latitude)
                putDouble("destLng", dest.longitude)
                putString("destAddress", address)
                putDouble("routeDistanceKm", routeDistanceKm)
                putDouble("routeDurationMin", routeDurationMin)
            }

            findNavController().navigate(
                R.id.action_destination_search_to_ride_confirm,
                bundle
            )
        }

        binding.btnChangeDestination.setOnClickListener {
            // Cancel any pending hide animation
            cancelChangeDestinationHint()  // ← replaces the 4 manual lines
            isRouteConfirmed = false

            // Remove current route and reset state
            routePolyline?.let { binding.mapFullView.overlays.remove(it) }
            routePolyline = null
            binding.tvRouteInfo.visibility = View.GONE

            // Remove destination marker and reset
            destinationMarker?.let { binding.mapFullView.overlays.remove(it) }
            destinationMarker = null
            binding.pulseView.animate().cancel()
            binding.pulseView.clearAnimation()
            binding.pulseView.visibility = View.GONE

            selectedGeoPoint = null
            selectedAddress = ""
            binding.mapFullView.invalidate()

            val pickup = GeoPoint(pickupLat, pickupLng)
            binding.mapFullView.controller.animateTo(pickup)
            binding.mapFullView.controller.setZoom(14.0)

            binding.etDestinationSearch.setText("")
            binding.etDestinationSearch.requestFocus()
            showKeyboard()

            // ADD THIS LINE right after showKeyboard():
            binding.etDestinationSearch.isCursorVisible = true
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etDestinationSearch.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etDestinationSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onResume() {
        super.onResume()
        binding.mapFullView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapFullView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}