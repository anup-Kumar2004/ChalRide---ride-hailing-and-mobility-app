package com.example.chalride.ui.rider

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRiderHomeBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.location.SettingsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.net.URL
import java.util.Locale
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.core.content.edit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class RiderHomeFragment : Fragment() {

    private var _binding: FragmentRiderHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var settingsClient: SettingsClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var pickupMarker: Marker? = null
    private var fetchingMessageJob: Job? = null
    private var isLocationBeingFetched = false

    // ── Confirmed pickup state ──────────────────────────────────────────────
    // These are only updated when a location is CONFIRMED (GPS fix, search
    // selection, or map tap). They are NEVER cleared or overwritten unless
    // the user explicitly confirms a new location.
    private var confirmedPickupLocation: GeoPoint? = null
    private var confirmedPickupLabel: String = ""   // empty = no pickup confirmed yet

    // ── GPS tracking ────────────────────────────────────────────────────────
    private var gpsHasBeenFetched = false  // true after first GPS fix arrives
    private var locationUpdatesStarted = false

    // ── Search mode ─────────────────────────────────────────────────────────
    private var isInSearchMode = false
    private var isProgrammaticTextChange = false
    private var searchJob: Job? = null
    private val searchResults = mutableListOf<Pair<String, GeoPoint>>()
    // Stores the user's current confirmed location for biasing search results
    // toward nearby places — same pattern Google Maps uses.
    private var searchBiasLat: Double = 28.4595   // default: center of Haryana
    private var searchBiasLon: Double = 77.0266

    // ── Ride booking state ──────────────────────────────────────────────
    private var userIsInteracting = false
    private var isSpeedDialOpen = false


    // ── Permission launchers ────────────────────────────────────────────────
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            checkLocationSettings()
        } else {
            stopFetchingState()
            showPermissionDeniedUX()
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startLocationUpdates()
        else {
            stopFetchingState()
            showPermissionDeniedUX()   // shows the full-screen overlay
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRiderHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        userIsInteracting = false  // Reset on every view creation

        // Restore pickup state after process death or rotation
        if (confirmedPickupLabel.isEmpty() && savedInstanceState != null) {
            val savedLabel = savedInstanceState.getString("confirmedPickupLabel", "")
            val savedLat   = savedInstanceState.getDouble("confirmedPickupLat", 0.0)
            val savedLng   = savedInstanceState.getDouble("confirmedPickupLng", 0.0)
            if (savedLabel.isNotEmpty() && savedLat != 0.0) {
                confirmedPickupLabel   = savedLabel
                confirmedPickupLocation = GeoPoint(savedLat, savedLng)
                currentLocation        = confirmedPickupLocation
            }
        }

        // Back button: if in search mode → exit search mode. Otherwise do nothing.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {

                private var doubleBackPressed = false

                override fun handleOnBackPressed() {

                    if (isInSearchMode) {
                        exitSearchMode(restoreLabel = true)
                        return
                    }

                    if (doubleBackPressed) {
                        requireActivity().finish()
                        return
                    }

                    doubleBackPressed = true

                    Toast.makeText(
                        requireContext(),
                        "Press back again to exit",
                        Toast.LENGTH_SHORT
                    ).show()

                    view.postDelayed({
                        doubleBackPressed = false
                    }, 2000)
                }
            }
        )

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        settingsClient = LocationServices.getSettingsClient(requireActivity())

        initMap()
        buildLocationRequest()
        setupLocationCallback()
        setupPickupSearchBar()
        setupClickListeners()

        binding.root.post {
            val sheetVisibleHeight = binding.root.height - binding.bottomSheet.top
            positionFabsAboveSheet(sheetVisibleHeight)
        }

        // Observe destination result
        findNavController().currentBackStackEntry?.savedStateHandle?.apply {
            // ADD THIS — observe pickup unroutable signal from DestinationSearchFragment
            getLiveData<Boolean>("pickupUnroutable").observe(viewLifecycleOwner) { unroutable ->
                if (unroutable == true) {
                    showPickupWarning()
                    // Clear the flag so it doesn't re-trigger on next navigation
                    set("pickupUnroutable", false)
                }
            }
        }

        // Start location only if we don't already have a confirmed pickup
        // (handles returning from destination fragment)
        if (confirmedPickupLabel.isEmpty()) {
            isLocationBeingFetched = true
            setPickupSearchEnabled(false)
            // Set the initial fetching text + color BEFORE showing the progress bar
            // so the EditText is correctly styled from frame one (no flash of white text)
            isProgrammaticTextChange = true
            binding.etPickupSearch.setText("Fetching location...")
            binding.etPickupSearch.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
            isProgrammaticTextChange = false
            setLocationProgress(true)
            startFetchingMessages()       // starts the cycling (first message at 0ms delay)
            checkAndRequestPermission()   // requests permission — if denied, overlay is shown
        } else {
            // We already have a pickup — just restore the UI, no GPS needed
            restoreConfirmedPickupUI()
        }

        // Check if there is an active ride in progress from a previous session
        checkAndRejoinActiveRide()


    }

    // ── Confirmed pickup ────────────────────────────────────────────────────

    /**
     * The ONLY function that updates the confirmed pickup.
     * Passing null geoPoint = error state (no location).
     */
    private fun setConfirmedPickup(geoPoint: GeoPoint?, label: String) {

        // ADD THIS LINE at the very top:
        isLocationBeingFetched = false
        // ADD THIS LINE:
        fetchingMessageJob?.cancel()
        setPickupSearchEnabled(true)

        hidePickupWarning()   // ← ADD THIS LINE — clears warning on any new pickup selection
        confirmedPickupLocation = geoPoint
        confirmedPickupLabel = label
        geoPoint?.let {
            currentLocation = it
            // Keep search bias updated so future searches rank nearby places higher
            searchBiasLat = it.latitude
            searchBiasLon = it.longitude
        }

        // Update UI — suppress TextWatcher
        isProgrammaticTextChange = true
        binding.etPickupSearch.setText(label)
        binding.etPickupSearch.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        )
        binding.etPickupSearch.clearFocus()
        isProgrammaticTextChange = false

        setLocationProgress(false)

        if (geoPoint != null && !userIsInteracting) {
            placePickupMarker(geoPoint)
            binding.mapView.controller.animateTo(geoPoint)
            binding.mapView.controller.setZoom(15.0)
        }

    }

    // currentLocation alias for fare calculation etc.
    private var currentLocation: GeoPoint? = null

    private fun restoreConfirmedPickupUI() {
        isProgrammaticTextChange = true
        binding.etPickupSearch.setText(confirmedPickupLabel)
        binding.etPickupSearch.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        )
        binding.etPickupSearch.clearFocus()
        isProgrammaticTextChange = false
        setLocationProgress(false)

        confirmedPickupLocation?.let { loc ->
            // Use post() to defer map operations until after the MapView has completed its first layout pass.
            // Without this, animateTo() is called before the view has dimensions and does nothing.
            binding.mapView.post {
                placePickupMarker(loc)
                binding.mapView.controller.animateTo(loc)
                binding.mapView.controller.setZoom(15.0)
            }
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

        binding.mapView.addMapListener(object : org.osmdroid.events.MapListener {

            override fun onScroll(event: org.osmdroid.events.ScrollEvent): Boolean {
                userIsInteracting = true
                updatePulsePosition()
                return false
            }

            override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                userIsInteracting = true
                updatePulsePosition()
                return false
            }
        })


        val mapTapOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                // ADD THIS BLOCK — block map taps while GPS is still working
                if (isLocationBeingFetched) return true

                if (isInSearchMode) {
                    exitSearchMode(restoreLabel = true)
                    return true
                }
                setPickupFromMapTap(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        binding.mapView.overlays.add(0, mapTapOverlay)
    }

    private fun updatePulsePosition() {
        val geoPoint = pickupMarker?.position ?: return

        val screenPoint = binding.mapView.projection.toPixels(geoPoint, null)

        binding.pulseView.x = screenPoint.x.toFloat() - binding.pulseView.width / 2f
        binding.pulseView.y = screenPoint.y.toFloat() - binding.pulseView.height / 2f
    }

    private fun setPickupFromMapTap(geoPoint: GeoPoint) {
        placePickupMarker(geoPoint)
        setPickupSearchEnabled(false)

        isProgrammaticTextChange = true
        binding.etPickupSearch.setText("Fetching location...")
        binding.etPickupSearch.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.text_secondary)
        )
        isProgrammaticTextChange = false
        setLocationProgress(true)

        lifecycleScope.launch {
            val address = reverseGeocodeAddress(geoPoint.latitude, geoPoint.longitude)
            setConfirmedPickup(geoPoint, address)
        }
    }

    private fun placePickupMarker(geoPoint: GeoPoint) {
        pickupMarker?.let { binding.mapView.overlays.remove(it) }

        pickupMarker = Marker(binding.mapView).apply {
            title = "Pickup"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            position = geoPoint
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
            } catch (_: Exception) {
                // fallback to default marker if icon fails
            }
            infoWindow = null
            setOnMarkerClickListener { _, _ -> true }
        }
        binding.mapView.overlays.add(pickupMarker)
        binding.mapView.invalidate()

        // Show and start pulse on rider home map
        binding.pulseView.animate().cancel()
        binding.pulseView.clearAnimation()
        binding.pulseView.visibility = View.VISIBLE
        binding.pulseView.bringToFront()

        // Position pulse over marker
        binding.mapView.post {
            val projection = binding.mapView.projection
            val point = projection.toPixels(geoPoint, null)
            binding.pulseView.x = point.x.toFloat() - binding.pulseView.width / 2f
            binding.pulseView.y = point.y.toFloat() - binding.pulseView.height / 2f
            startPulse(binding.pulseView)
        }
    }

    // ── GPS location ────────────────────────────────────────────────────────

    private fun buildLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 4000L
        ).setMinUpdateIntervalMillis(2000L).build()
    }

    private fun checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            checkLocationSettings()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
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
                    } catch (_: IntentSender.SendIntentException) {}
                } else {
                    stopFetchingState()
                }
            }
    }

    private fun stopFetchingState() {
        isLocationBeingFetched = false
        fetchingMessageJob?.cancel()
        setLocationProgress(false)
        setPickupSearchEnabled(true)
        // Restore hint text without triggering search mode
        if (!isInSearchMode) {
            isProgrammaticTextChange = true
            binding.etPickupSearch.setText("")
            binding.etPickupSearch.hint = "Search pickup location..."
            isProgrammaticTextChange = false
        }
    }

    private fun showPermissionDeniedUX() {
        // Hide all normal-state UI elements
        binding.cardPickupSearch.visibility = View.GONE
        binding.bottomSheet.visibility = View.GONE
        binding.fabMyLocation.visibility = View.GONE
        binding.fabSpeedDial.visibility = View.GONE

        // Show the full-screen overlay
        binding.layoutPermissionDenied.visibility = View.VISIBLE

        // "Open App Settings" button
        binding.btnOpenSettings.setOnClickListener {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", requireContext().packageName, null)
            )
            startActivity(intent)
        }

        // "I've allowed it — Continue" button
        // Re-check permission; if now granted, hide overlay and restart the flow
        binding.btnRetryPermission.setOnClickListener {
            if (hasLocationPermission()) {
                hidePermissionDeniedUX()
                // Restart the full location fetch flow
                gpsHasBeenFetched = false
                locationUpdatesStarted = false
                isLocationBeingFetched = true
                setPickupSearchEnabled(false)
                isProgrammaticTextChange = true
                binding.etPickupSearch.setText("Fetching location...")
                binding.etPickupSearch.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_secondary)
                )
                isProgrammaticTextChange = false
                setLocationProgress(true)
                startFetchingMessages()
                checkLocationSettings()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Permission still not granted. Please allow location in Settings.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun hidePermissionDeniedUX() {
        binding.layoutPermissionDenied.visibility = View.GONE
        binding.cardPickupSearch.visibility = View.VISIBLE
        binding.bottomSheet.visibility = View.VISIBLE
        binding.fabMyLocation.visibility = View.VISIBLE
        binding.fabSpeedDial.visibility = View.VISIBLE
    }

    private fun setPickupSearchEnabled(enabled: Boolean) {
        binding.etPickupSearch.isEnabled = enabled
        binding.etPickupSearch.isFocusable = enabled
        binding.etPickupSearch.isFocusableInTouchMode = enabled
        binding.etPickupSearch.alpha = if (enabled) 1.0f else 0.6f
    }

    private fun setLocationProgress(visible: Boolean) {
        binding.progressLocation.visibility = if (visible) View.VISIBLE else View.GONE
        binding.ivSearchIcon.visibility     = if (visible) View.GONE   else View.VISIBLE
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val geoPoint = GeoPoint(location.latitude, location.longitude)

                if (!gpsHasBeenFetched) {
                    gpsHasBeenFetched = true
                    fusedLocationClient.removeLocationUpdates(locationCallback)

                    // ── Step 1: Immediately show map + marker + stop the
                    //    fetching messages. User sees the map respond instantly.
                    isLocationBeingFetched = false
                    fetchingMessageJob?.cancel()
                    setPickupSearchEnabled(false)   // keep locked while geocoding

                    binding.mapView.controller.animateTo(geoPoint)
                    binding.mapView.controller.setZoom(15.0)
                    placePickupMarker(geoPoint)

                    isProgrammaticTextChange = true
                    binding.etPickupSearch.setText("Fetching location...")
                    binding.etPickupSearch.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_secondary)
                    )
                    isProgrammaticTextChange = false
                    setLocationProgress(true)

                    // ── Step 2: Geocode in background — field unlocks when done
                    lifecycleScope.launch {
                        val address = reverseGeocodeAddress(location.latitude, location.longitude)
                        setConfirmedPickup(geoPoint, address)
                        // setConfirmedPickup already calls setPickupSearchEnabled(true)
                    }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (locationUpdatesStarted) return
        // Fragment may have been detached by checkAndRejoinActiveRide() navigating
        // away before this async callback fires — guard against that here.
        if (!isAdded || context == null) return
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        locationUpdatesStarted = true
        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    // ── Geocoding ────────────────────────────────────────────────────────────

    private suspend fun reverseGeocodeAddress(lat: Double, lon: Double): String {
        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                val results = geocoder.getFromLocation(lat, lon, 1)
                if (!results.isNullOrEmpty()) {
                    val addr = results[0]
                    buildString {
                        addr.subLocality?.let { append("$it, ") }
                        addr.locality?.let { append(it) }
                        if (isEmpty()) append(addr.getAddressLine(0) ?: "Current Location")
                    }
                } else {
                    val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json"
                    val conn = URL(url).openConnection()
                    conn.setRequestProperty("User-Agent", requireContext().packageName)
                    conn.connect()
                    val json = org.json.JSONObject(
                        conn.getInputStream().bufferedReader().readText()
                    )
                    json.optString("display_name", "Current Location")
                        .split(",").take(2).joinToString(", ")
                }
            } catch (_: Exception) {
                "Current Location"
            }
        }
    }

    // ── Search bar ──────────────────────────────────────────────────────────

    private fun setupPickupSearchBar() {
        // Tapping EditText → enter search mode
        binding.etPickupSearch.setOnClickListener {
            hidePickupWarning()   // ✅ ADD THIS
            if (!isInSearchMode) enterSearchMode()
        }

        binding.etPickupSearch.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            if (isLocationBeingFetched) {
                binding.etPickupSearch.clearFocus()   // ← reject focus during fetch
                return@setOnFocusChangeListener
            }
            hidePickupWarning()
            if (!isInSearchMode) enterSearchMode()
        }

        binding.etPickupSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isProgrammaticTextChange) return
                if (!isInSearchMode) return

                hidePickupWarning()   // ✅ ADD THIS

                val text = s.toString().trim()
                if (text.length >= 3) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch {
                        delay(300)
                        searchPickupLocation(text)
                    }
                } else {
                    // Cancel any in-flight search job BEFORE hiding the dropdown.
                    // Without this, a job launched at 3+ chars completes after the
                    // text is cleared and calls showPickupDropdown() — overwriting the hide.
                    searchJob?.cancel()
                    searchJob = null
                    hidePickupDropdown()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.etPickupSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etPickupSearch.text.toString().trim()
                if (query.length >= 3) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch { searchPickupLocation(query) }
                }
                true
            } else false
        }

        // Dropdown item selected → confirm this as pickup
        binding.lvPickupResults.setOnItemClickListener { _, _, position, _ ->
            if (position >= searchResults.size) return@setOnItemClickListener
            val (rawLabel, geoPoint) = searchResults[position]

            // Strip the || separator — confirmed label should just be the primary name
            val sepIdx = rawLabel.indexOf("||")
            val cleanLabel = if (sepIdx >= 0) rawLabel.substring(0, sepIdx).trim() else rawLabel.trim()

            hidePickupDropdown()
            hideKeyboard()
            isInSearchMode = false
            userIsInteracting = false

            binding.mapView.controller.animateTo(geoPoint)
            binding.mapView.controller.setZoom(16.0)
            setConfirmedPickup(geoPoint, cleanLabel)
        }
    }

    private fun enterSearchMode() {
        isInSearchMode = true
        isProgrammaticTextChange = true
        binding.etPickupSearch.setText("")
        binding.etPickupSearch.hint = "Search pickup location..."
        isProgrammaticTextChange = false
        setLocationProgress(false)
        binding.etPickupSearch.requestFocus()
        showKeyboard(binding.etPickupSearch)
    }

    /**
     * Exit search mode.
     * restoreLabel=true → put back the last confirmed label (user pressed Back)
     * restoreLabel=false → used after confirming a new selection
     */
    private fun exitSearchMode(restoreLabel: Boolean) {
        isInSearchMode = false
        searchJob?.cancel()
        hidePickupDropdown()
        hideKeyboard()
        binding.etPickupSearch.clearFocus()
        setLocationProgress(false)

        if (restoreLabel) {
            // Restore exactly what was confirmed before search mode started
            isProgrammaticTextChange = true
            binding.etPickupSearch.setText(confirmedPickupLabel)
            isProgrammaticTextChange = false
        }
    }

    private suspend fun searchPickupLocation(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 3) return

        showSearchLoadingState()

        val results = withContext(Dispatchers.IO) {
            try {
                val allResults = mutableListOf<Pair<String, GeoPoint>>()

                // ── Strategy 1: Search the full query as typed ────────────────────
                // Works when the user has typed complete words: "bml munjal" → hits
                allResults += nominatimSearch(trimmed)

                // ── Strategy 2: Search only the FIRST word of the query ───────────
                // "bml mun" → we search just "bml" with a broader name-only search.
                // This finds "BML Munjal" even when "mun" is still incomplete,
                // because "bml" alone matches the beginning of "BML Munjal".
                val words = trimmed.split("\\s+".toRegex()).filter { it.length >= 2 }
                if (words.size >= 2) {
                    // Search the first word alone — broad match
                    val firstWordResults = nominatimSearch(words.first())
                    // Only keep results whose name actually contains ALL the typed words
                    // as prefixes, so "bml" results are filtered to only those where
                    // the name also starts with "mun..." etc.
                    val filtered = firstWordResults.filter { (label, _) ->
                        val labelLower = label.lowercase()
                        words.all { word ->
                            labelLower.contains(word.lowercase())
                        }
                    }
                    allResults += filtered
                }

                // ── Strategy 3: Search each word independently and intersect ──────
                // For queries like "bml munjal manesar" — search "bml munjal" and
                // "manesar" separately, keep results that appear in both.
                if (words.size >= 2) {
                    val firstTwoWords = words.take(2).joinToString(" ")
                    if (firstTwoWords != trimmed) {   // avoid duplicate of Strategy 1
                        allResults += nominatimSearch(firstTwoWords)
                    }
                }

                // ── Deduplicate by coordinate (within ~100m) ──────────────────────
                val seen = mutableSetOf<String>()
                val deduped = allResults.filter { (_, pt) ->
                    val key = "%.2f,%.2f".format(pt.latitude, pt.longitude)
                    seen.add(key)
                }

                // ── Proximity sort ────────────────────────────────────────────────
                deduped.sortedBy { (_, pt) ->
                    distanceMeters(searchBiasLat, searchBiasLon, pt.latitude, pt.longitude)
                }.take(5)   // cap at 5 results shown

            } catch (_: Exception) {
                emptyList()
            }
        }

        searchResults.clear()
        searchResults.addAll(results)

        if (results.isNotEmpty()) {
            hideNoResultsState()
            showPickupDropdown(results.map { it.first })
        } else {
            hidePickupDropdown()
            showNoResultsState(trimmed)
        }
    }

    /**
     * Single Nominatim HTTP call. Returns cleaned (primary||secondary) labeled results.
     * No wildcards — works correctly with the public Nominatim API.
     */
    private fun nominatimSearch(query: String): List<Pair<String, GeoPoint>> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        val viewboxDelta = 1.5
        val minLat = searchBiasLat - viewboxDelta
        val maxLat = searchBiasLat + viewboxDelta
        val minLon = searchBiasLon - viewboxDelta
        val maxLon = searchBiasLon + viewboxDelta

        val url = "https://nominatim.openstreetmap.org/search" +
                "?q=$encoded" +
                "&format=json" +
                "&limit=8" +
                "&countrycodes=in" +
                "&addressdetails=1" +
                "&namedetails=1" +
                "&dedupe=1" +
                "&viewbox=$minLon,$maxLat,$maxLon,$minLat"

        val conn = URL(url).openConnection()
        conn.setRequestProperty("User-Agent", "ChalRide/1.0")
        conn.setRequestProperty("Accept-Language", "en")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.connect()

        val json = JSONArray(conn.getInputStream().bufferedReader().readText())

        return (0 until json.length()).mapNotNull { i ->
            try {
                val obj = json.getJSONObject(i)
                val lat = obj.getDouble("lat")
                val lon = obj.getDouble("lon")
                val label = buildSearchResultLabel(obj)
                Pair(label, GeoPoint(lat, lon))
            } catch (_: Exception) { null }
        }
    }

    private fun buildSearchResultLabel(obj: org.json.JSONObject): String {
        // ── Primary name ──────────────────────────────────────────────────────────
        val nameDetails = obj.optJSONObject("namedetails")
        val primaryName = nameDetails?.optString("name")?.takeIf { it.isNotBlank() }
            ?: obj.getString("display_name").split(",").first().trim()

        // ── Secondary: road + city ────────────────────────────────────────────────
        val addr = obj.optJSONObject("address")

        // Try every Nominatim address field from most specific to least specific.
        // Different place types populate different fields — a city like Pune comes
        // back as addr.city, a district like Mahendragarh as addr.state_district,
        // a village as addr.village. We try all of them so secondary is never blank.
        val road         = addr?.optString("road")?.takeIf         { it.isNotBlank() }
        val suburb       = addr?.optString("suburb")?.takeIf       { it.isNotBlank() }
        val neighbourhood= addr?.optString("neighbourhood")?.takeIf{ it.isNotBlank() }
        val city         = addr?.optString("city")?.takeIf         { it.isNotBlank() }
        val town         = addr?.optString("town")?.takeIf         { it.isNotBlank() }
        val village      = addr?.optString("village")?.takeIf      { it.isNotBlank() }
        val county       = addr?.optString("county")?.takeIf       { it.isNotBlank() }
        val district     = addr?.optString("state_district")?.takeIf{ it.isNotBlank() }
        val state        = addr?.optString("state")?.takeIf        { it.isNotBlank() }

        // Pick the best "street-level" part
        val streetPart = road ?: suburb ?: neighbourhood

        // Pick the best "city-level" part — cascade from most to least specific
        val cityPart = city ?: town ?: village ?: county ?: district ?: state

        val secondaryParts = listOfNotNull(streetPart, cityPart)
            .distinct()
            .filter { part ->
                // Don't repeat the primary name in the secondary line
                !primaryName.equals(part, ignoreCase = true)
            }

        val secondary = if (secondaryParts.isNotEmpty()) {
            secondaryParts.joinToString(", ")
        } else {
            // Last resort: pull parts 2-3 from raw display_name
            obj.getString("display_name").split(",").drop(1).take(2)
                .joinToString(", ").trim()
        }

        // Store as "Primary||Secondary" — the || separator is used in showPickupDropdown()
        return "$primaryName||$secondary"
    }

    /**
     * Haversine distance in meters between two lat/lon points.
     * Used to rank results by proximity to the user.
     */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return R * 2 * asin(sqrt(a))
    }

    private fun showPickupDropdown(items: List<String>) {
        val adapter = object : ArrayAdapter<String>(
            requireContext(), R.layout.item_search_result, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(
                    R.layout.item_search_result, parent, false
                )
                // New separator is || which cannot appear in a place name,
                // unlike comma which splits "BML Munjal, Haryana" incorrectly.
                val raw = items[position]
                val sepIdx = raw.indexOf("||")
                val primary   = if (sepIdx >= 0) raw.substring(0, sepIdx).trim() else raw.trim()
                val secondary = if (sepIdx >= 0) raw.substring(sepIdx + 2).trim() else ""

                view.findViewById<TextView>(R.id.tvResultPrimary).text = primary
                view.findViewById<TextView>(R.id.tvResultSecondary).text = secondary
                return view
            }
        }
        binding.dividerPickupDropdown.visibility = View.VISIBLE
        binding.lvPickupResults.adapter = adapter
        binding.lvPickupResults.visibility = View.VISIBLE
    }

    private fun hidePickupDropdown() {
        binding.dividerPickupDropdown.visibility = View.GONE
        binding.lvPickupResults.visibility = View.GONE
        hideNoResultsState()
        searchResults.clear()
    }

    /**
     * Shows a subtle "Searching..." message inside the dropdown area while
     * the network request is in flight. Replaces stale results or no-results
     * state so the user knows something is happening.
     */
    private fun showSearchLoadingState() {
        // Hide stale results and no-results state while new results load
        binding.lvPickupResults.visibility = View.GONE
        binding.cardNoResults.visibility = View.GONE
        binding.dividerPickupDropdown.visibility = View.VISIBLE
        // Reuse tvNoResultsQuery to show loading text — cheap, no extra view needed
        binding.cardNoResults.visibility = View.VISIBLE
        binding.tvNoResultsQuery.text = "Searching..."
    }

    private fun showKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etPickupSearch.windowToken, 0)
    }

    private fun showPickupWarning() {
        binding.dividerPickupWarning.visibility = View.VISIBLE
        binding.layoutPickupWarning.visibility = View.VISIBLE
    }

    private fun hidePickupWarning() {
        binding.dividerPickupWarning.visibility = View.GONE
        binding.layoutPickupWarning.visibility = View.GONE
    }

    private fun showNoResultsState(query: String) {
        binding.lvPickupResults.visibility = View.GONE
        binding.dividerPickupDropdown.visibility = View.VISIBLE
        binding.cardNoResults.visibility = View.VISIBLE
        binding.tvNoResultsQuery.text = "Try a different search for \"$query\""
    }

    private fun hideNoResultsState() {
        binding.cardNoResults.visibility = View.GONE
    }


    private fun startPulse(view: View) {
        view.visibility = View.VISIBLE
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

    private fun startFetchingMessages() {
        fetchingMessageJob?.cancel()
        fetchingMessageJob = lifecycleScope.launch {
            // Delays are RELATIVE (time to wait before showing THIS message),
            // not absolute. So: show immediately, then after 4s, then 2s, then 4s.
            val messages = listOf(
                0L    to "Fetching location...",
                4000L to "Just a sec, hold on tight...",
                2000L to "Almost there, bear with us...",
                4000L to "Taking longer than usual..."
            )
            for ((delayMs, message) in messages) {
                if (delayMs > 0) delay(delayMs)
                if (!isLocationBeingFetched) break
                isProgrammaticTextChange = true
                binding.etPickupSearch.setText(message)
                // Always use text_secondary color for fetching messages — consistent
                binding.etPickupSearch.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_secondary)
                )
                isProgrammaticTextChange = false
            }
        }
    }


    private fun positionFabsAboveSheet(sheetVisibleHeight: Int) {
        if (_binding == null) return
        val density = resources.displayMetrics.density

        // fabSpeedDial (normal FAB = 56dp) sits 16dp above sheet
        val mainMargin = sheetVisibleHeight + (16 * density).toInt()

        val tripDetailsMargin = sheetVisibleHeight + (88 * density).toInt()

        val profileMargin = sheetVisibleHeight + (154 * density).toInt()

        val myLocationMarginClosed = sheetVisibleHeight + (88 * density).toInt()
        val myLocationMarginOpen   = sheetVisibleHeight + (225 * density).toInt()

        fun setBottomMargin(view: View, margin: Int) {
            val params = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            params.bottomMargin = margin
            view.layoutParams = params
        }

        setBottomMargin(binding.fabSpeedDial,  mainMargin)
        setBottomMargin(binding.fabTripDetails, tripDetailsMargin)
        setBottomMargin(binding.fabProfile,    profileMargin)
        setBottomMargin(
            binding.fabMyLocation,
            if (isSpeedDialOpen) myLocationMarginOpen else myLocationMarginClosed
        )
    }

    private fun openSpeedDial() {
        isSpeedDialOpen = true
        binding.fabScrim.visibility = View.VISIBLE
        binding.fabScrim.animate().alpha(1f).setDuration(200).start()

        listOf(binding.fabTripDetails, binding.fabProfile).forEachIndexed { index, fab ->
            fab.visibility = View.VISIBLE
            fab.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay((index * 50).toLong())
                .setDuration(200)
                .start()
        }
        binding.fabSpeedDial.animate().rotation(45f).setDuration(200).start()
        val sheetVisibleHeight = binding.root.height - binding.bottomSheet.top
        positionFabsAboveSheet(sheetVisibleHeight)

    }

    private fun closeSpeedDial() {
        isSpeedDialOpen = false
        binding.fabScrim.animate().alpha(0f).setDuration(200)
            .withEndAction { binding.fabScrim.visibility = View.GONE }.start()

        listOf(binding.fabProfile, binding.fabTripDetails).forEachIndexed { index, fab ->
            fab.animate()
                .scaleX(0f).scaleY(0f).alpha(0f)
                .setStartDelay((index * 50).toLong())
                .setDuration(150)
                .withEndAction { fab.visibility = View.INVISIBLE }
                .start()
        }
        binding.fabSpeedDial.animate().rotation(0f).setDuration(200).start()
        val sheetVisibleHeight = binding.root.height - binding.bottomSheet.top
        positionFabsAboveSheet(sheetVisibleHeight)
    }


    // ── Click listeners ──────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.fabMyLocation.setOnClickListener {
            // Close speed dial if open
            if (isSpeedDialOpen) closeSpeedDial()

            // Reset to fresh GPS fetch — same flow as app launch
            gpsHasBeenFetched = false
            locationUpdatesStarted = false
            userIsInteracting = false
            isLocationBeingFetched = true

            setPickupSearchEnabled(false)
            setLocationProgress(true)
            isProgrammaticTextChange = true
            binding.etPickupSearch.setText("Fetching location...")
            binding.etPickupSearch.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
            isProgrammaticTextChange = false

            startFetchingMessages()
            checkAndRequestPermission()
        }

        binding.fabSpeedDial.setOnClickListener {
            if (isSpeedDialOpen) closeSpeedDial() else openSpeedDial()
        }

        binding.fabScrim.setOnClickListener {
            closeSpeedDial()
        }

        binding.fabProfile.setOnClickListener {
            closeSpeedDial()
            findNavController().navigate(R.id.action_riderHome_to_riderProfile)
        }

        binding.fabTripDetails.setOnClickListener {
            closeSpeedDial()
            findNavController().navigate(R.id.action_riderHome_to_riderTripDetails)
        }

        binding.cardDestination.setOnClickListener {
            // Guard: must have a confirmed pickup label
            if (confirmedPickupLabel.isEmpty() || confirmedPickupLocation == null) {
                Toast.makeText(
                    requireContext(),
                    "Pickup location not set yet",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            // Guard: must not be in search mode with empty text
            if (isInSearchMode) {
                exitSearchMode(restoreLabel = true)
                Toast.makeText(
                    requireContext(),
                    "Pickup set to: $confirmedPickupLabel",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            hidePickupDropdown()
            openDestinationSearch()
        }

    }




    /**
     * Checks SharedPreferences for an active ride left over from a previous
     * session (process death, app relaunch). If found and the ride is still
     * active in Firestore, navigates directly to RideLiveFragment.
     */
    private fun checkAndRejoinActiveRide() {
        val prefs = requireContext().getSharedPreferences(
            RideLiveService.PREFS_NAME, Context.MODE_PRIVATE
        )
        val savedRideId = prefs.getString(RideLiveService.PREFS_KEY_RIDE_ID, "") ?: ""
        if (savedRideId.isEmpty()) return  // no active ride saved

        android.util.Log.d("RiderHome", "Found saved rideId=$savedRideId — verifying with Firestore")

        // Verify the ride is still actually active before navigating
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(savedRideId)
            .get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val status = doc.getString("status") ?: ""
                android.util.Log.d("RiderHome", "Saved ride status=$status")

                // Only rejoin if ride is in an active state
                if (status in listOf("accepted", "arrived_at_pickup", "in_progress")) {
                    val bundle = Bundle().apply {
                        putString("rideRequestId", savedRideId)
                        putString("driverId",      prefs.getString(RideLiveService.PREFS_KEY_DRIVER_ID, ""))
                        putString("driverName",    prefs.getString(RideLiveService.PREFS_KEY_DRIVER_NAME, "Driver"))
                        putString("vehicleType",   prefs.getString(RideLiveService.PREFS_KEY_VEHICLE, ""))

                        putDouble("pickupLat",     Double.fromBits(prefs.getLong(RideLiveService.PREFS_KEY_PICKUP_LAT, 0L)))
                        putDouble("pickupLng",     Double.fromBits(prefs.getLong(RideLiveService.PREFS_KEY_PICKUP_LNG, 0L)))
                        putDouble("destLat",       Double.fromBits(prefs.getLong(RideLiveService.PREFS_KEY_DEST_LAT, 0L)))
                        putDouble("destLng",       Double.fromBits(prefs.getLong(RideLiveService.PREFS_KEY_DEST_LNG, 0L)))


                        putString("pickupAddress", prefs.getString(RideLiveService.PREFS_KEY_PICKUP_ADDR, ""))
                        putString("destAddress",   prefs.getString(RideLiveService.PREFS_KEY_DEST_ADDR, ""))
                        putInt("estimatedFare",    prefs.getInt(RideLiveService.PREFS_KEY_FARE, 0))
                    }
                    findNavController().navigate(R.id.action_rider_home_to_ride_live, bundle)
                } else {
                    // Ride ended while app was closed — clear stale prefs
                    prefs.edit { clear() }
                    android.util.Log.d("RiderHome", "Saved ride is no longer active ($status) — cleared prefs")
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.e("RiderHome", "Failed to verify saved ride: ${e.message}")
                // Don't clear prefs on network failure — try again next launch
            }
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private fun openDestinationSearch() {
        val loc = confirmedPickupLocation ?: return
        val bundle = Bundle().apply {
            putString("pickupAddress", confirmedPickupLabel)
            putDouble("pickupLat", loc.latitude)
            putDouble("pickupLng", loc.longitude)
        }
        findNavController().navigate(R.id.action_rider_home_to_destination_search, bundle)
    }


    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        // Restart pulse animation if marker exists — it stops when app is backgrounded
        if (confirmedPickupLocation != null) {
            binding.pulseView.animate().cancel()
            binding.pulseView.clearAnimation()
            if (binding.pulseView.isVisible) {
                startPulse(binding.pulseView)
            }
        }
        // If GPS was still fetching when user backgrounded AND the permission
        // dialog is NOT currently showing, restart the location flow on resume.
        // We guard with locationUpdatesStarted to avoid relaunching the system
        // permission dialog (which is already open on first-ever launch).
        if (isLocationBeingFetched && !locationUpdatesStarted && hasLocationPermission()) {
            startFetchingMessages()
            checkLocationSettings()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        // Cancel the fetching message coroutine — no point updating UI while paused
        fetchingMessageJob?.cancel()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationUpdatesStarted = false
        }
        // Exit search mode cleanly so keyboard doesn't linger when user comes back
        if (isInSearchMode) {
            exitSearchMode(restoreLabel = true)
        }
    }

    override fun onDestroyView() {
        fetchingMessageJob?.cancel()
        searchJob?.cancel()
        super.onDestroyView()
        if (::locationCallback.isInitialized) fusedLocationClient.removeLocationUpdates(locationCallback)
        pickupMarker = null
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save confirmed pickup so it survives process death and rotation
        outState.putString("confirmedPickupLabel", confirmedPickupLabel)
        confirmedPickupLocation?.let {
            outState.putDouble("confirmedPickupLat", it.latitude)
            outState.putDouble("confirmedPickupLng", it.longitude)
        }
    }
}