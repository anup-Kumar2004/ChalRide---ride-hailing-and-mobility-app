package com.example.chalride.ui.rider

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
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
import org.json.JSONArray

class DestinationSearchFragment : Fragment() {

    private var _binding: FragmentDestinationSearchBinding? = null
    private val binding get() = _binding!!

    private var searchJob: Job? = null
    private var destinationMarker: Marker? = null
    private var selectedGeoPoint: GeoPoint? = null
    private var selectedAddress: String = ""

    // Receive pickup info from RiderHomeFragment
    private val pickupAddress by lazy {
        arguments?.getString("pickupAddress") ?: "Current Location"
    }
    private val pickupLat by lazy { arguments?.getDouble("pickupLat") ?: 20.5937 }
    private val pickupLng by lazy { arguments?.getDouble("pickupLng") ?: 78.9629 }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDestinationSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPickupInSearch.text = pickupAddress

        initMap()
        setupSearch()
        setupClickListeners()

        // Auto-open keyboard
        binding.etDestinationSearch.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
        imm.showSoftInput(binding.etDestinationSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapFullView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapFullView.setMultiTouchControls(true)

        val startPoint = GeoPoint(pickupLat, pickupLng)
        binding.mapFullView.controller.setZoom(14.0)
        binding.mapFullView.controller.setCenter(startPoint)

        // Tap on map to select destination
        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                placeDestinationMarker(p)
                reverseGeocode(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        binding.mapFullView.overlays.add(0, mapEventsOverlay)
    }

    private fun setupSearch() {
        binding.etDestinationSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearSearch.visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                val query = s.toString().trim()
                if (query.length >= 3) {
                    searchJob = lifecycleScope.launch {
                        delay(500) // debounce
                        searchLocation(query)
                    }
                }
            }
        })

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
            val result = withContext(Dispatchers.IO) {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=1&countrycodes=in"
                val connection = URL(url).openConnection()
                connection.setRequestProperty("User-Agent", requireContext().packageName)
                connection.connect()
                val response = connection.getInputStream().bufferedReader().readText()
                val jsonArray = JSONArray(response)
                if (jsonArray.length() > 0) {
                    val obj = jsonArray.getJSONObject(0)
                    Triple(
                        obj.getDouble("lat"),
                        obj.getDouble("lon"),
                        obj.getString("display_name")
                    )
                } else null
            }

            result?.let { (lat, lon, name) ->
                val geoPoint = GeoPoint(lat, lon)
                placeDestinationMarker(geoPoint)
                selectedAddress = name.split(",").take(3).joinToString(", ")
                binding.etDestinationSearch.setText(selectedAddress)
                binding.etDestinationSearch.setSelection(selectedAddress.length)
                binding.mapFullView.controller.animateTo(geoPoint)
                binding.mapFullView.controller.setZoom(15.0)
            }
        } catch (e: Exception) {
            // Silently fail
        }
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
                binding.etDestinationSearch.setText(address)
                binding.etDestinationSearch.setSelection(address.length)
            } catch (e: Exception) {
                selectedAddress = "Selected Location"
            }
        }
    }

    private fun placeDestinationMarker(geoPoint: GeoPoint) {
        selectedGeoPoint = geoPoint
        if (destinationMarker == null) {
            destinationMarker = Marker(binding.mapFullView)
            destinationMarker!!.title = "Destination"
            binding.mapFullView.overlays.add(destinationMarker)
        }
        destinationMarker!!.position = geoPoint
        binding.mapFullView.invalidate()
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etDestinationSearch.setText("")
            selectedGeoPoint = null
            selectedAddress = ""
            destinationMarker?.let {
                binding.mapFullView.overlays.remove(it)
                binding.mapFullView.invalidate()
            }
            destinationMarker = null
        }

        binding.fabGps.setOnClickListener {
            val pickup = GeoPoint(pickupLat, pickupLng)
            binding.mapFullView.controller.animateTo(pickup)
            binding.mapFullView.controller.setZoom(15.0)
        }

        binding.btnConfirmLocation.setOnClickListener {
            val dest = selectedGeoPoint ?: return@setOnClickListener
            val address = selectedAddress.ifEmpty { "Selected Location" }

            // Pass back to RiderHomeFragment
            val bundle = Bundle().apply {
                putDouble("destLat", dest.latitude)
                putDouble("destLng", dest.longitude)
                putString("destAddress", address)
            }
            findNavController().navigate(
                R.id.action_destination_to_rider_home,
                bundle
            )
        }
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