package com.example.chalride.ui.driver

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.chalride.R
import com.example.chalride.databinding.FragmentDriverHomeBinding
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

class DriverHomeFragment : Fragment() {

    private var _binding: FragmentDriverHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var settingsClient: SettingsClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var isOnline = false
    private var onlineStartTimeMs = 0L
    private var timerJob: Job? = null

    private var currentMarker: Marker? = null
    private var currentLocation: GeoPoint? = null
    private var userIsInteracting = false
    private var firstLocationFix = true

    // ── Permission launchers ────────────────────────────────────────────────

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) checkLocationSettings()
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startLocationUpdates()
    }

    // ───────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { /* Home screen — block back */ }
            }
        )

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        settingsClient = LocationServices.getSettingsClient(requireActivity())

        setupDriverInfo()
        initMap()
        buildLocationRequest()
        setupLocationCallback()
        checkAndRequestPermission()
        setupClickListeners()
    }

    // ── Driver info from Firestore ──────────────────────────────────────────

    private fun setupDriverInfo() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("drivers").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val name = doc.getString("name") ?: "Driver"
                binding.tvDriverName.text = name.split(" ").first()
                binding.tvProfileInitial.text = name.first().uppercase()

                val rating = doc.getDouble("rating")
                binding.tvRating.text = if (rating != null) String.format("%.1f", rating) else "—"

                val trips = doc.getLong("totalTrips")?.toInt() ?: 0
                binding.tvTripsCount.text = trips.toString()
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
    }

    private fun placeDriverMarker(geoPoint: GeoPoint) {
        currentMarker?.let { binding.mapView.overlays.remove(it) }

        currentMarker = Marker(binding.mapView).apply {
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
        binding.mapView.overlays.add(currentMarker)
        binding.mapView.invalidate()

        // Show pulse
        binding.pulseView.visibility = View.VISIBLE
        binding.pulseView.bringToFront()
        binding.mapView.post { updatePulsePosition() }
        startPulse(binding.pulseView)
    }

    private fun updatePulsePosition() {
        val geoPoint = currentMarker?.position ?: return
        val pt = binding.mapView.projection.toPixels(geoPoint, null)
        binding.pulseView.x = pt.x.toFloat() - binding.pulseView.width / 2f
        binding.pulseView.y = pt.y.toFloat() - binding.pulseView.height / 2f
    }

    private fun startPulse(view: View) {
        view.animate().cancel()
        view.scaleX = 1f; view.scaleY = 1f; view.alpha = 0.7f
        view.animate()
            .scaleX(2f).scaleY(2f).alpha(0f)
            .setDuration(1200)
            .withEndAction { if (view.isVisible) startPulse(view) }
            .start()
    }

    // ── Location ────────────────────────────────────────────────────────────

    private fun buildLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).setMinUpdateIntervalMillis(3000L).build()
    }

    private fun checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) checkLocationSettings()
        else locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
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
                    } catch (_: IntentSender.SendIntentException) { }
                }
            }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                currentLocation = geoPoint

                if (firstLocationFix && !userIsInteracting) {
                    firstLocationFix = false
                    binding.mapView.controller.animateTo(geoPoint)
                    binding.mapView.controller.setZoom(17.0)
                }

                placeDriverMarker(geoPoint)

                // Write location to Firestore if online (foreground service also does
                // this, but this covers when app is in foreground)
                if (isOnline) writeLocationToFirestore(location.latitude, location.longitude)
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && !userIsInteracting) {
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                currentLocation = geoPoint
                binding.mapView.controller.animateTo(geoPoint)
                binding.mapView.controller.setZoom(17.0)
                placeDriverMarker(geoPoint)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    // ── Firestore writes ─────────────────────────────────────────────────────

    /**
     * Writes the driver's current location + geohash to Firestore.
     * Called every location update when driver is online.
     *
     * Firestore document structure (drivers/{uid}):
     *   lat, lng, geohash, isAvailable, isOnline, lastUpdated
     */
    private fun writeLocationToFirestore(lat: Double, lng: Double) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val geohash = encodeGeohash(lat, lng, precision = 6)

        FirebaseFirestore.getInstance().collection("drivers").document(uid)
            .update(
                mapOf(
                    "lat"         to lat,
                    "lng"         to lng,
                    "geohash"     to geohash,
                    "lastUpdated" to System.currentTimeMillis()
                )
            )
    }

    private fun setDriverAvailability(available: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("drivers").document(uid)
            .update(
                mapOf(
                    "isAvailable" to available,
                    "isOnline"    to available
                )
            )
    }

    // ── Online/Offline toggle ────────────────────────────────────────────────

    private fun setupClickListeners() {
        binding.fabMyLocation.setOnClickListener {
            userIsInteracting = false
            currentLocation?.let { loc ->
                binding.mapView.controller.animateTo(loc)
                binding.mapView.controller.setZoom(17.0)
            } ?: checkLocationSettings()
        }

        binding.btnToggleOnline.setOnClickListener {
            isOnline = !isOnline
            updateOnlineUI()

            if (isOnline) {
                // Start foreground service for background location
                startDriverLocationService()
                setDriverAvailability(true)
                startOnlineTimer()
            } else {
                stopDriverLocationService()
                setDriverAvailability(false)
                timerJob?.cancel()
            }
        }
    }

    private fun updateOnlineUI() {
        if (isOnline) {
            // Button
            binding.btnToggleOnline.text = "GO OFFLINE"
            binding.btnToggleOnline.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.error_color)
            )

            // Top bar status
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_online)
            binding.tvStatus.text = "Online"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.success_color)
            )

            // Status ring
            binding.statusRingOuter.setBackgroundResource(R.drawable.bg_status_ring_online)
            binding.ivStatusIcon.alpha = 1f
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.success_color)
            )

            // Status text
            binding.tvStatusMessage.text = "You are online"
            binding.tvStatusMessage.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.success_color)
            )
            binding.tvStatusSubMessage.text = "Waiting for ride requests nearby..."

            // Pulse on driver marker while online
            binding.pulseView.visibility = View.VISIBLE

        } else {
            // Button
            binding.btnToggleOnline.text = "GO ONLINE"
            binding.btnToggleOnline.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.success_color)
            )

            // Top bar status
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_offline)
            binding.tvStatus.text = "Offline"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_hint)
            )

            // Status ring
            binding.statusRingOuter.setBackgroundResource(R.drawable.bg_status_ring_offline)
            binding.ivStatusIcon.alpha = 0.5f
            binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.text_hint)
            )

            // Status text
            binding.tvStatusMessage.text = "You are currently offline"
            binding.tvStatusMessage.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_primary)
            )
            binding.tvStatusSubMessage.text = "Go online to start receiving ride requests"

            binding.pulseView.animate().cancel()
            binding.pulseView.visibility = View.GONE
        }
    }

    // ── Online timer ─────────────────────────────────────────────────────────

    private fun startOnlineTimer() {
        onlineStartTimeMs = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsedMs = System.currentTimeMillis() - onlineStartTimeMs
                val totalMin = elapsedMs / 60_000
                val hours = totalMin / 60
                val mins = totalMin % 60
                binding.tvHoursOnline.text = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                delay(30_000) // update every 30 seconds
            }
        }
    }

    // ── Foreground service ───────────────────────────────────────────────────

    private fun startDriverLocationService() {
        val intent = Intent(requireContext(), DriverLocationService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun stopDriverLocationService() {
        val intent = Intent(requireContext(), DriverLocationService::class.java)
        requireContext().stopService(intent)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        // NOTE: Do NOT remove location updates here — foreground service handles it
        // Only remove if driver is offline
        if (!isOnline && ::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timerJob?.cancel()
        if (::locationCallback.isInitialized && !isOnline) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        _binding = null
    }

    // ── Geohash encoder ─────────────────────────────────────────────────────

    /**
     * Lightweight geohash encoder — no external library needed.
     * Precision 6 = ~1.2km x 0.6km cell, good enough for 3km radius queries.
     */
    private fun encodeGeohash(lat: Double, lng: Double, precision: Int = 6): String {
        val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        var minLat = -90.0;  var maxLat = 90.0
        var minLng = -180.0; var maxLng = 180.0
        val hash = StringBuilder()
        var bits = 0; var bitsTotal = 0; var hashValue = 0

        while (hash.length < precision) {
            if (bitsTotal % 2 == 0) {
                val mid = (minLng + maxLng) / 2
                if (lng >= mid) { hashValue = hashValue * 2 + 1; minLng = mid }
                else            { hashValue *= 2;                 maxLng = mid }
            } else {
                val mid = (minLat + maxLat) / 2
                if (lat >= mid) { hashValue = hashValue * 2 + 1; minLat = mid }
                else            { hashValue *= 2;                 maxLat = mid }
            }
            bits++; bitsTotal++
            if (bits == 5) {
                hash.append(base32[hashValue])
                bits = 0; hashValue = 0
            }
        }
        return hash.toString()
    }
}