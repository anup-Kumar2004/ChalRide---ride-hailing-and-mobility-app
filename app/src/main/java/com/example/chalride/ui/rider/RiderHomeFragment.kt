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
import androidx.cardview.widget.CardView
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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

    // ── Ride booking state ──────────────────────────────────────────────────
    private var userIsInteracting = false



    // ── Permission launchers ────────────────────────────────────────────────
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            checkLocationSettings()
        } else {
            setConfirmedPickup(null, "Location permission denied")
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startLocationUpdates()
        else setConfirmedPickup(null, "Enable location to use ChalRide")
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

        // Back button: if in search mode → exit search mode. Otherwise do nothing.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isInSearchMode) {
                        exitSearchMode(restoreLabel = true)
                    }
                    // Home screen — do not navigate back
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
            binding.progressLocation.visibility = View.VISIBLE
            isLocationBeingFetched = true
            checkAndRequestPermission()
            // ADD THIS LINE:
            startFetchingMessages()
        } else {
            // We already have a pickup — just restore the UI, no GPS needed
            restoreConfirmedPickupUI()
        }
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

        hidePickupWarning()   // ← ADD THIS LINE — clears warning on any new pickup selection
        confirmedPickupLocation = geoPoint
        confirmedPickupLabel = label
        geoPoint?.let { currentLocation = it }

        // Update UI — suppress TextWatcher
        isProgrammaticTextChange = true
        binding.etPickupSearch.setText(label)
        binding.etPickupSearch.clearFocus()
        isProgrammaticTextChange = false

        binding.progressLocation.visibility = View.GONE
        // No clear button — removed

        // Place marker and animate map
        if (geoPoint != null && !userIsInteracting) {
            placePickupMarker(geoPoint)
            binding.mapView.controller.animateTo(geoPoint)
            binding.mapView.controller.setZoom(17.0)
        }
    }

    // currentLocation alias for fare calculation etc.
    private var currentLocation: GeoPoint? = null

    private fun restoreConfirmedPickupUI() {
        isProgrammaticTextChange = true
        binding.etPickupSearch.setText(confirmedPickupLabel)
        binding.etPickupSearch.clearFocus()
        isProgrammaticTextChange = false
        binding.progressLocation.visibility = View.GONE

        confirmedPickupLocation?.let { loc ->
            // Use post() to defer map operations until after the MapView has completed its first layout pass.
            // Without this, animateTo() is called before the view has dimensions and does nothing.
            binding.mapView.post {
                placePickupMarker(loc)
                binding.mapView.controller.animateTo(loc)
                binding.mapView.controller.setZoom(17.0)
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
                updatePulsePosition()   // 🔥 ADD THIS
                return false
            }

            override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                userIsInteracting = true
                updatePulsePosition()   // 🔥 ADD THIS
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

        isProgrammaticTextChange = true
        binding.etPickupSearch.setText("Finding address...")
        isProgrammaticTextChange = false
        binding.progressLocation.visibility = View.VISIBLE

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
                    setConfirmedPickup(null, "Enable location to use ChalRide")
                }
            }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val geoPoint = GeoPoint(location.latitude, location.longitude)

                // Only use GPS fix to SET the pickup if we don't have one yet
                if (!gpsHasBeenFetched) {
                    gpsHasBeenFetched = true

                    // Animate map to user location immediately
                    binding.mapView.controller.animateTo(geoPoint)
                    binding.mapView.controller.setZoom(17.0)
                    placePickupMarker(geoPoint)

                    // Reverse geocode and set as confirmed pickup
                    lifecycleScope.launch {
                        val address = reverseGeocodeAddress(location.latitude, location.longitude)
                        setConfirmedPickup(geoPoint, address)
                    }

                    // Stop updates — we only needed one fix for pickup
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                }
                // After first fix, GPS no longer drives anything automatically
            }
        }
    }

    private fun startLocationUpdates() {
        if (locationUpdatesStarted) return
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
            } catch (e: Exception) {
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
            if (hasFocus) {
                hidePickupWarning()   // ✅ ADD THIS
                if (!isInSearchMode) enterSearchMode()
            }
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
                        delay(400)
                        searchPickupLocation(text)
                    }
                } else {
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
            val (label, geoPoint) = searchResults[position]

            hidePickupDropdown()
            hideKeyboard()
            isInSearchMode = false
            userIsInteracting = false

            // Animate map first, then confirm
            binding.mapView.controller.animateTo(geoPoint)
            binding.mapView.controller.setZoom(16.0)
            setConfirmedPickup(geoPoint, label)
        }
    }

    private fun enterSearchMode() {
        isInSearchMode = true
        isProgrammaticTextChange = true
        binding.etPickupSearch.setText("")
        binding.etPickupSearch.hint = "Search pickup location..."
        isProgrammaticTextChange = false
        binding.progressLocation.visibility = View.GONE
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
        binding.progressLocation.visibility = View.GONE

        if (restoreLabel) {
            // Restore exactly what was confirmed before search mode started
            isProgrammaticTextChange = true
            binding.etPickupSearch.setText(confirmedPickupLabel)
            isProgrammaticTextChange = false
        }
    }

    private suspend fun searchPickupLocation(query: String) {
        try {
            val results = withContext(Dispatchers.IO) {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=5&countrycodes=in"
                val conn = URL(url).openConnection()
                conn.setRequestProperty("User-Agent", requireContext().packageName)
                conn.connect()
                val json = JSONArray(conn.getInputStream().bufferedReader().readText())
                (0 until json.length()).map { i ->
                    val obj = json.getJSONObject(i)
                    val name = obj.getString("display_name").split(",").take(3).joinToString(", ")
                    Pair(name, GeoPoint(obj.getDouble("lat"), obj.getDouble("lon")))
                }
            }
            searchResults.clear()
            searchResults.addAll(results)
            if (results.isNotEmpty()) {
                hideNoResultsState()           // ADD THIS LINE
                showPickupDropdown(results.map { it.first })
            } else {
                hidePickupDropdown()
                showNoResultsState(query)      // ADD THIS LINE
            }
        } catch (_: Exception) {
            hidePickupDropdown()
            showNoResultsState(query)          // CHANGE from just hidePickupDropdown()
        }
    }

    private fun showPickupDropdown(items: List<String>) {
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
            val messages = listOf(
                0L    to "Fetching location...",
                6000L to "Just a sec, hold on tight...",
                9000L to "Almost there, bear with us...",
                12000L to "Taking longer than usual..."
            )
            for ((delayMs, message) in messages) {
                delay(delayMs)
                // Only update if we're still fetching (not yet confirmed)
                if (isLocationBeingFetched) {
                    isProgrammaticTextChange = true
                    binding.etPickupSearch.setText(message)
                    isProgrammaticTextChange = false
                } else {
                    break
                }
            }
        }
    }



    // ── Click listeners ──────────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.ivHamburger.setOnClickListener {
            Toast.makeText(requireContext(), "Navigation menu coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.fabMyLocation.setOnClickListener {
            // Re-fetch fresh GPS — reset state
            gpsHasBeenFetched = false
            locationUpdatesStarted = false
            userIsInteracting = false
            // ADD THIS LINE:
            isLocationBeingFetched = true

            if (isInSearchMode) exitSearchMode(restoreLabel = false)

            binding.progressLocation.visibility = View.VISIBLE
            isProgrammaticTextChange = true
            binding.etPickupSearch.setText("Fetching location...")
            isProgrammaticTextChange = false

            checkAndRequestPermission()
            // ADD THIS LINE:
            startFetchingMessages()
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
        super.onResume(); binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationUpdatesStarted = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::locationCallback.isInitialized) fusedLocationClient.removeLocationUpdates(locationCallback)
        pickupMarker = null
        _binding = null
    }
}