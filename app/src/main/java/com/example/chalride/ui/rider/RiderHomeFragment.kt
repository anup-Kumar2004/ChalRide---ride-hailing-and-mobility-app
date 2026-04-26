package com.example.chalride.ui.rider

import androidx.navigation.fragment.findNavController
import android.Manifest
import android.app.Activity
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.Geocoder
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
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import com.example.chalride.R

class RiderHomeFragment : Fragment() {

    private var _binding: FragmentRiderHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var settingsClient: SettingsClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var locationFetched = false
    private var currentMarker: Marker? = null
    private var currentLocation: GeoPoint? = null
    private var userIsInteracting = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            checkLocationSettings()
        } else {
            binding.tvPickupLocation.text = "Location permission denied"
            binding.progressLocation.visibility = View.GONE
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
        _binding = FragmentRiderHomeBinding.inflate(inflater, container, false)
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

        initMap()
        buildLocationRequest()
        setupLocationCallback()
        checkAndRequestPermission()
        setupClickListeners()

        // Check if returning from destination search with a result
        findNavController().currentBackStackEntry?.savedStateHandle?.apply {
            getLiveData<Double>("destLat").observe(viewLifecycleOwner) { lat ->
                val lng = get<Double>("destLng") ?: return@observe
                val address = get<String>("destAddress") ?: "Destination"
                onDestinationSelected(lat, lng, address)
            }
        }
    }

    private fun onDestinationSelected(lat: Double, lng: Double, address: String) {
        binding.tvDestinationHint.text = address
        binding.tvDestinationHint.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        )

        // Show vehicle selector and fare estimates
        binding.tvChooseRide.visibility = View.VISIBLE
        binding.layoutVehicles.visibility = View.VISIBLE
        binding.btnFindRide.visibility = View.VISIBLE

        // Calculate distance and estimate fares
        currentLocation?.let { pickup ->
            val destPoint = GeoPoint(lat, lng)
            val distanceKm = pickup.distanceToAsDouble(destPoint) / 1000.0
            calculateFares(distanceKm)
        }
    }

    private fun calculateFares(distanceKm: Double) {
        val bikeRate = 8.0
        val autoRate = 12.0
        val sedanRate = 16.0
        val suvRate = 22.0
        val baseFare = 20.0

        binding.tvBikeFare.text = "₹${(baseFare + distanceKm * bikeRate).toInt()}"
        binding.tvAutoFare.text = "₹${(baseFare + distanceKm * autoRate).toInt()}"
        binding.tvSedanFare.text = "₹${(baseFare + distanceKm * sedanRate).toInt()}"
        binding.tvSuvFare.text = "₹${(baseFare + distanceKm * suvRate).toInt()}"
    }

    private fun buildLocationRequest() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).setMinUpdateIntervalMillis(2000L)
            .setMaxUpdates(5)
            .build()
    }


    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(5.0)
        binding.mapView.controller.setCenter(GeoPoint(20.5937, 78.9629))

        // Detect manual user interaction
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

    private fun checkLocationSettings() {
        val locationSettingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        settingsClient.checkLocationSettings(locationSettingsRequest)
            .addOnSuccessListener {
                startLocationUpdates()
            }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
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

                // Only animate if user is not manually interacting
                if (!userIsInteracting) {
                    binding.mapView.controller.animateTo(geoPoint)
                    binding.mapView.controller.setZoom(17.0)
                }

                if (currentMarker == null) {
                    currentMarker = Marker(binding.mapView)
                    currentMarker!!.title = "You are here"
                    binding.mapView.overlays.add(currentMarker)
                }
                currentMarker!!.position = geoPoint
                binding.mapView.invalidate()

                if (!locationFetched) {
                    locationFetched = true
                    getAddressFromLocation(location.latitude, location.longitude)
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

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && !userIsInteracting) {
                val geoPoint = GeoPoint(location.latitude, location.longitude)
                currentLocation = geoPoint
                binding.mapView.controller.animateTo(geoPoint)
                binding.mapView.controller.setZoom(17.0)

                if (currentMarker == null) {
                    currentMarker = Marker(binding.mapView)
                    currentMarker!!.title = "You are here"
                    binding.mapView.overlays.add(currentMarker)
                }
                currentMarker!!.position = geoPoint
                binding.mapView.invalidate()

                if (!locationFetched) {
                    locationFetched = true
                    getAddressFromLocation(location.latitude, location.longitude)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun getAddressFromLocation(lat: Double, lon: Double) {
        lifecycleScope.launch {
            try {
                val address = withContext(Dispatchers.IO) {
                    val geocoder = Geocoder(requireContext(), Locale.getDefault())
                    val results = geocoder.getFromLocation(lat, lon, 1)
                    if (!results.isNullOrEmpty()) {
                        val addr = results[0]
                        buildString {
                            addr.subLocality?.let { append("$it, ") }
                            addr.locality?.let { append(it) }
                            if (isEmpty()) append(
                                addr.getAddressLine(0) ?: "Current Location"
                            )
                        }
                    } else "Current Location"
                }
                binding.tvPickupLocation.text = address
                binding.progressLocation.visibility = View.GONE
            } catch (e: Exception) {
                binding.tvPickupLocation.text = "Current Location"
                binding.progressLocation.visibility = View.GONE
            }
        }
    }

    private fun setupClickListeners() {
        binding.fabMyLocation.setOnClickListener {
            userIsInteracting = false
            currentLocation?.let { location ->
                binding.mapView.controller.animateTo(location)
                binding.mapView.controller.setZoom(17.0)
            } ?: checkLocationSettings()
        }

        binding.cardDestination.setOnClickListener {
            openDestinationSearch()
        }
    }

    private fun openDestinationSearch() {
        val bundle = Bundle().apply {
            putString("pickupAddress", binding.tvPickupLocation.text.toString())
            putDouble("pickupLat", currentLocation?.latitude ?: 20.5937)
            putDouble("pickupLng", currentLocation?.longitude ?: 78.9629)
        }
        findNavController().navigate(R.id.destinationSearchFragment, bundle)
    }



    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        _binding = null
    }
}