package com.example.chalride.ui.rider

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
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

class RiderHomeFragment : Fragment() {

    private var _binding: FragmentRiderHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var settingsClient: SettingsClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var pickupMarker: Marker? = null

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
    private var selectedVehicleType = ""
    private var selectedFare = 0
    private var destLat = 0.0
    private var destLng = 0.0
    private var destAddress = ""

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
            getLiveData<Double>("destLat").observe(viewLifecycleOwner) { lat ->
                val lng = get<Double>("destLng") ?: return@observe
                val address = get<String>("destAddress") ?: "Destination"
                destLat = lat; destLng = lng; destAddress = address
                onDestinationSelected(lat, lng, address)
            }
        }

        // Start location only if we don't already have a confirmed pickup
        // (handles returning from destination fragment)
        if (confirmedPickupLabel.isEmpty()) {
            binding.progressLocation.visibility = View.VISIBLE
            checkAndRequestPermission()
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
        binding.mapView.controller.setZoom(4.0)

        binding.mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent): Boolean {
                userIsInteracting = true; return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                userIsInteracting = true; return false
            }
        })

        val mapTapOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
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
        // Always remove old marker first — eliminates stale-reference bug across view recreations
        pickupMarker?.let { binding.mapView.overlays.remove(it) }
        pickupMarker = Marker(binding.mapView).apply {
            title = "Pickup"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            position = geoPoint
        }
        binding.mapView.overlays.add(pickupMarker)
        binding.mapView.invalidate()
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
            if (!isInSearchMode) enterSearchMode()
        }
        binding.etPickupSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isInSearchMode) enterSearchMode()
        }

        binding.etPickupSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isProgrammaticTextChange) return
                if (!isInSearchMode) return
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
            if (results.isNotEmpty()) showPickupDropdown(results.map { it.first })
            else hidePickupDropdown()
        } catch (e: Exception) {
            hidePickupDropdown()
        }
    }

    private fun showPickupDropdown(items: List<String>) {
        val adapter = object : ArrayAdapter<String>(
            requireContext(), android.R.layout.simple_list_item_1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setPadding(32, 22, 32, 22)
                    setBackgroundColor(Color.TRANSPARENT)
                }
                return view
            }
        }
        binding.lvPickupResults.adapter = adapter
        binding.lvPickupResults.visibility = View.VISIBLE
    }

    private fun hidePickupDropdown() {
        binding.lvPickupResults.visibility = View.GONE
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

    // ── Destination ──────────────────────────────────────────────────────────

    private fun onDestinationSelected(lat: Double, lng: Double, address: String) {
        binding.tvDestinationHint.text = address
        binding.tvDestinationHint.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        )
        binding.tvChooseRide.visibility = View.VISIBLE
        binding.layoutVehicles.visibility = View.VISIBLE
        binding.btnFindRide.visibility = View.VISIBLE

        confirmedPickupLocation?.let { pickup ->
            calculateFares(pickup.distanceToAsDouble(GeoPoint(lat, lng)) / 1000.0)
        }
        selectedVehicleType = ""; selectedFare = 0
        highlightSelectedVehicle(null)
    }

    private fun calculateFares(distanceKm: Double) {
        val base = 20.0
        binding.tvBikeFare.text = "₹${(base + distanceKm * 8.0).toInt()}"
        binding.tvAutoFare.text = "₹${(base + distanceKm * 12.0).toInt()}"
        binding.tvSedanFare.text = "₹${(base + distanceKm * 16.0).toInt()}"
        binding.tvSuvFare.text = "₹${(base + distanceKm * 22.0).toInt()}"
    }

    private fun highlightSelectedVehicle(selectedCard: CardView?) {
        listOf(binding.cardBike, binding.cardAuto, binding.cardSedan, binding.cardSuv).forEach {
            it.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.input_bg))
        }
        selectedCard?.setCardBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.bg_surface)
        )
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

            if (isInSearchMode) exitSearchMode(restoreLabel = false)

            binding.progressLocation.visibility = View.VISIBLE
            isProgrammaticTextChange = true
            binding.etPickupSearch.setText("Fetching location...")
            isProgrammaticTextChange = false

            checkAndRequestPermission()
        }

        binding.cardDestination.setOnClickListener {
            // Guard: must have a confirmed pickup label
            if (confirmedPickupLabel.isEmpty() || confirmedPickupLocation == null) {
                Toast.makeText(
                    requireContext(),
                    "Please wait — pickup location not set yet",
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

        binding.cardBike.setOnClickListener {
            selectedVehicleType = "bike"
            selectedFare = binding.tvBikeFare.text.toString().replace("₹", "").toIntOrNull() ?: 0
            highlightSelectedVehicle(binding.cardBike)
        }
        binding.cardAuto.setOnClickListener {
            selectedVehicleType = "auto"
            selectedFare = binding.tvAutoFare.text.toString().replace("₹", "").toIntOrNull() ?: 0
            highlightSelectedVehicle(binding.cardAuto)
        }
        binding.cardSedan.setOnClickListener {
            selectedVehicleType = "sedan"
            selectedFare = binding.tvSedanFare.text.toString().replace("₹", "").toIntOrNull() ?: 0
            highlightSelectedVehicle(binding.cardSedan)
        }
        binding.cardSuv.setOnClickListener {
            selectedVehicleType = "suv"
            selectedFare = binding.tvSuvFare.text.toString().replace("₹", "").toIntOrNull() ?: 0
            highlightSelectedVehicle(binding.cardSuv)
        }

        binding.btnFindRide.setOnClickListener { findRide() }
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

    // ── Find Ride ────────────────────────────────────────────────────────────

    private fun findRide() {
        if (selectedVehicleType.isEmpty()) {
            Toast.makeText(requireContext(), "Please select a vehicle type", Toast.LENGTH_SHORT).show()
            return
        }
        if (destLat == 0.0 && destLng == 0.0) {
            Toast.makeText(requireContext(), "Please select a destination", Toast.LENGTH_SHORT).show()
            return
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val pickup = confirmedPickupLocation ?: run {
            Toast.makeText(requireContext(), "Pickup location not set", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnFindRide.isEnabled = false
        binding.btnFindRide.text = "Searching..."

        FirebaseFirestore.getInstance().collection("riders").document(uid).get()
            .addOnSuccessListener { doc ->
                val rideData = hashMapOf(
                    "riderId" to uid,
                    "riderName" to (doc.getString("name") ?: "Rider"),
                    "pickupLat" to pickup.latitude,
                    "pickupLng" to pickup.longitude,
                    "pickupAddress" to confirmedPickupLabel,
                    "destLat" to destLat,
                    "destLng" to destLng,
                    "destAddress" to destAddress,
                    "vehicleType" to selectedVehicleType,
                    "estimatedFare" to selectedFare,
                    "status" to "pending",
                    "createdAt" to System.currentTimeMillis()
                )
                FirebaseFirestore.getInstance().collection("rideRequests").add(rideData)
                    .addOnSuccessListener {
                        binding.btnFindRide.text = "Searching for drivers..."
                        Toast.makeText(requireContext(),
                            "Looking for nearby $selectedVehicleType drivers...",
                            Toast.LENGTH_LONG).show()
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

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() { super.onResume(); binding.mapView.onResume() }

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