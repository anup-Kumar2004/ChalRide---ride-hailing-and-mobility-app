package com.example.chalride.ui.driver

import android.Manifest
import android.app.Activity
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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
    private var onlineStartTime = 0L
    private var currentMarker: Marker? = null
    private var currentLocation: GeoPoint? = null
    private var userIsInteracting = false  // track if user is manually panning/zooming

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            checkLocationSettings()
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startLocationUpdates()
        }
    }

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
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {}
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

    private fun buildLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 4000L
        ).setMinUpdateIntervalMillis(2000L).build()
    }

    private fun setupDriverInfo() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val name = doc.getString("name") ?: "Driver"
                binding.tvDriverName.text = name.split(" ").first()
                binding.tvProfileInitial.text = name.first().uppercase()
            }
    }

    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(5.0)
        binding.mapView.controller.setCenter(GeoPoint(20.5937, 78.9629))

        // Detect when user manually interacts with map
        binding.mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent): Boolean {
                userIsInteracting = true
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                userIsInteracting = true
                return false
            }
        })
    }

    private fun checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
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

    // This shows the "Turn on Location Accuracy" dialog
    private fun checkLocationSettings() {
        val locationSettingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        settingsClient.checkLocationSettings(locationSettingsRequest)
            .addOnSuccessListener {
                // Location is already on — start updates
                startLocationUpdates()
            }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        // Show the dialog asking user to turn on location
                        locationSettingsLauncher.launch(
                            IntentSenderRequest.Builder(exception.resolution).build()
                        )
                    } catch (sendEx: IntentSender.SendIntentException) {
                        // Ignore
                    }
                }
            }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                currentLocation = geoPoint

                // Only animate map if user is NOT manually interacting
                if (!userIsInteracting) {
                    binding.mapView.controller.animateTo(geoPoint)
                    binding.mapView.controller.setZoom(17.0)
                }

                // Always update marker
                if (currentMarker == null) {
                    currentMarker = Marker(binding.mapView)
                    currentMarker!!.title = "Your location"
                    binding.mapView.overlays.add(currentMarker)
                }
                currentMarker!!.position = geoPoint
                binding.mapView.invalidate()

                // Update Firestore if online
                if (isOnline) {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
                    FirebaseFirestore.getInstance()
                        .collection("drivers")
                        .document(uid)
                        .update(
                            mapOf(
                                "currentLat" to location.latitude,
                                "currentLng" to location.longitude
                            )
                        )
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        // Get last known location for instant response
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && !userIsInteracting) {
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                currentLocation = geoPoint
                binding.mapView.controller.animateTo(geoPoint)
                binding.mapView.controller.setZoom(17.0)

                if (currentMarker == null) {
                    currentMarker = Marker(binding.mapView)
                    currentMarker!!.title = "Your location"
                    binding.mapView.overlays.add(currentMarker)
                }
                currentMarker!!.position = geoPoint
                binding.mapView.invalidate()
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun setupClickListeners() {
        // FAB resets interaction flag and animates to current location
        binding.fabMyLocation.setOnClickListener {
            userIsInteracting = false
            currentLocation?.let { location ->
                binding.mapView.controller.animateTo(location)
                binding.mapView.controller.setZoom(17.0)
            } ?: checkLocationSettings()
        }

        binding.btnToggleOnline.setOnClickListener {
            isOnline = !isOnline
            updateOnlineStatus()
        }
    }

    private fun updateOnlineStatus() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        if (isOnline) {
            onlineStartTime = System.currentTimeMillis()
            binding.btnToggleOnline.text = "GO OFFLINE"
            binding.btnToggleOnline.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.error_color)
                )
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_online)
            binding.tvStatus.text = "Online"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.success_color)
            )
            binding.tvStatusMessage.text = "You are online"
            binding.tvStatusSubMessage.text = "Waiting for ride requests nearby..."
            FirebaseFirestore.getInstance().collection("drivers")
                .document(uid).update("isOnline", true)
        } else {
            binding.btnToggleOnline.text = "GO ONLINE"
            binding.btnToggleOnline.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.success_color)
                )
            binding.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_offline)
            binding.tvStatus.text = "Offline"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
            binding.tvStatusMessage.text = "You are currently offline"
            binding.tvStatusSubMessage.text = "Go online to start receiving ride requests"
            if (onlineStartTime > 0) {
                val hoursOnline = (System.currentTimeMillis() - onlineStartTime) / 3600000f
                binding.tvHoursOnline.text = String.format("%.1fh", hoursOnline)
            }
            FirebaseFirestore.getInstance().collection("drivers")
                .document(uid).update("isOnline", false)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        _binding = null
    }
}