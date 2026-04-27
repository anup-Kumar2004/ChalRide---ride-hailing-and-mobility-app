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
import android.graphics.Color
import android.widget.ArrayAdapter
import android.widget.TextView

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

        binding.etDestinationSearch.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etDestinationSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun initMap() {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        binding.mapFullView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapFullView.setMultiTouchControls(true)

        val startPoint = GeoPoint(pickupLat, pickupLng)
        binding.mapFullView.controller.setZoom(14.0)
        binding.mapFullView.controller.setCenter(startPoint)

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
                binding.btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if ((s?.length ?: 0) < 3) hideDestinationDropdown()
            }
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                val query = s.toString().trim()
                if (query.length >= 3) {
                    searchJob = lifecycleScope.launch {
                        delay(500)
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
            val results = withContext(Dispatchers.IO) {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5&countrycodes=in"
                val connection = URL(url).openConnection()
                connection.setRequestProperty("User-Agent", requireContext().packageName)
                connection.connect()
                val response = connection.getInputStream().bufferedReader().readText()
                val jsonArray = JSONArray(response)
                (0 until jsonArray.length()).map { i ->
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.getString("display_name").split(",").take(3).joinToString(", ")
                    Pair(name, GeoPoint(obj.getDouble("lat"), obj.getDouble("lon")))
                }
            }
            searchResults.clear()
            searchResults.addAll(results)
            if (results.isNotEmpty()) showDestinationDropdown(results.map { it.first })
            else hideDestinationDropdown()
        } catch (_: Exception) { hideDestinationDropdown() }
    }

    private fun showDestinationDropdown(items: List<String>) {
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
        binding.lvDestinationResults.adapter = adapter
        binding.lvDestinationResults.visibility = View.VISIBLE
    }

    private fun hideDestinationDropdown() {
        binding.lvDestinationResults.visibility = View.GONE
        searchResults.clear()
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
                    json.optString("display_name", "Selected Location").split(",").take(3).joinToString(", ")
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
            destinationMarker = Marker(binding.mapFullView).apply { title = "Destination" }
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
            hideDestinationDropdown()
            destinationMarker?.let {
                binding.mapFullView.overlays.remove(it)
                binding.mapFullView.invalidate()
            }
            destinationMarker = null
        }

        binding.lvDestinationResults.setOnItemClickListener { _, _, position, _ ->
            if (position >= searchResults.size) return@setOnItemClickListener
            val (label, geoPoint) = searchResults[position]
            hideDestinationDropdown()
            selectedAddress = label
            selectedGeoPoint = geoPoint
            binding.etDestinationSearch.setText(label)
            binding.etDestinationSearch.setSelection(label.length)
            placeDestinationMarker(geoPoint)
            binding.mapFullView.controller.animateTo(geoPoint)
            binding.mapFullView.controller.setZoom(15.0)
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.etDestinationSearch.windowToken, 0)
        }

        binding.fabGps.setOnClickListener {
            val pickup = GeoPoint(pickupLat, pickupLng)
            binding.mapFullView.controller.animateTo(pickup)
            binding.mapFullView.controller.setZoom(15.0)
        }

        binding.btnConfirmLocation.setOnClickListener {
            val dest = selectedGeoPoint ?: return@setOnClickListener
            val address = selectedAddress.ifEmpty { "Selected Location" }

            // ✅ CORRECT way to pass data back — set on previous entry's savedStateHandle
            findNavController().previousBackStackEntry?.savedStateHandle?.apply {
                set("destLat", dest.latitude)
                set("destLng", dest.longitude)
                set("destAddress", address)
            }
            findNavController().popBackStack()
        }
    }

    override fun onResume() { super.onResume(); binding.mapFullView.onResume() }
    override fun onPause() { super.onPause(); binding.mapFullView.onPause() }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}