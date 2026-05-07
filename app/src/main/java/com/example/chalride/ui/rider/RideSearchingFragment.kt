package com.example.chalride.ui.rider

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRideSearchingBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RideSearchingFragment : Fragment() {

    private var _binding: FragmentRideSearchingBinding? = null
    private val binding get() = _binding!!
    private val triedDriverIds = mutableSetOf<String>()

    private val vehicleType   by lazy { arguments?.getString("vehicleType")   ?: "" }
    private val estimatedFare by lazy { arguments?.getInt("estimatedFare")    ?: 0  }
    private val pickupLat     by lazy { arguments?.getDouble("pickupLat")     ?: 0.0 }
    private val pickupLng     by lazy { arguments?.getDouble("pickupLng")     ?: 0.0 }
    private val pickupAddress by lazy { arguments?.getString("pickupAddress") ?: "" }
    private val destLat       by lazy { arguments?.getDouble("destLat")       ?: 0.0 }
    private val destLng       by lazy { arguments?.getDouble("destLng")       ?: 0.0 }
    private val destAddress   by lazy { arguments?.getString("destAddress")   ?: "" }
    private val searchRadiusKm by lazy { arguments?.getInt("searchRadiusKm") ?: 5 }

    // ── State ─────────────────────────────────────────────────────────────
    private var rideRequestId: String = ""
    private var rideDocumentCreated = false

    // ── Jobs and listeners ────────────────────────────────────────────────
    private var outerTimerJob: Job? = null
    private var perDriverJob: Job? = null
    private var countdownDisplayJob: Job? = null
    private var rideStatusListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRideSearchingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Block back press — rider must cancel explicitly
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { }
            }
        )

        setVehicleIcon()
        startPulseAnimation()
        startOuterCountdownDisplay()
        createRideRequestAndStartWaterfall()

        binding.btnCancelSearch.setOnClickListener {
            cancelRideRequest(navigateHome = true)
        }
    }

    // ── Vehicle icon ──────────────────────────────────────────────────────

    private fun setVehicleIcon() {
        binding.tvVehicleIcon.text = when (vehicleType) {
            "bike"  -> "🏍️"
            "auto"  -> "🛺"
            "sedan" -> "🚗"
            "suv"   -> "🚙"
            else    -> "🚗"
        }
    }

    // ── Pulse animation ───────────────────────────────────────────────────

    private fun startPulseAnimation() {
        animateRing(binding.pulseRing1, 0L)
        animateRing(binding.pulseRing2, 400L)
        animateRing(binding.pulseRing3, 800L)
    }

    private fun animateRing(view: View, startDelay: Long) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 0.4f
        view.animate()
            .scaleX(1.3f).scaleY(1.3f).alpha(0f)
            .setDuration(1800)
            .setStartDelay(startDelay)
            .withEndAction {
                view.scaleX = 1f
                view.scaleY = 1f
                view.alpha = 0.4f
                if (_binding != null) animateRing(view, 0L)
            }
            .start()
    }

    // ── Outer 60s countdown display ───────────────────────────────────────

    private fun startOuterCountdownDisplay() {
        var secondsLeft = 60
        countdownDisplayJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && secondsLeft > 0) {
                binding.tvTimer.text = "${secondsLeft}s"
                delay(1000)
                secondsLeft--
            }
        }
    }

    // ── Step 1: Create Firestore document, then start waterfall ──────────

    private fun createRideRequestAndStartWaterfall() {
        if (rideDocumentCreated) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Toast.makeText(requireContext(), "Not signed in", Toast.LENGTH_SHORT).show()
            return
        }

        FirebaseFirestore.getInstance().collection("riders").document(uid).get()
            .addOnSuccessListener { doc ->
                val rideData = hashMapOf(
                    "riderId"          to uid,
                    "riderName"        to (doc.getString("name") ?: "Rider"),
                    "pickupLat"        to pickupLat,
                    "pickupLng"        to pickupLng,
                    "pickupAddress"    to pickupAddress,
                    "destLat"          to destLat,
                    "destLng"          to destLng,
                    "destAddress"      to destAddress,
                    "vehicleType"      to vehicleType,
                    "estimatedFare"    to estimatedFare,
                    "status"           to "pending",
                    "createdAt"        to System.currentTimeMillis(),
                    "targetDriverId"   to "",
                    "driverId"         to "",
                    "driverName"       to ""
                )

                FirebaseFirestore.getInstance().collection("rideRequests").add(rideData)
                    .addOnSuccessListener { docRef ->
                        rideRequestId = docRef.id
                        rideDocumentCreated = true
                        startOuterTimeoutJob()
                        targetNextDriver()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            requireContext(),
                            "Failed to create ride: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Failed to load profile", Toast.LENGTH_SHORT).show()
            }
    }

    // ── Step 2: Outer 60s hard cap ────────────────────────────────────────

    private fun startOuterTimeoutJob() {
        outerTimerJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(60_000)
            if (_binding == null) return@launch
            // 60s hard cap hit — truly no driver found
            cancelRideRequest(
                navigateHome = false,
                cancellationReason = CancelReason.NO_DRIVER_FOUND.name
            )
            vibrateAndNavigateCancelled()
        }
    }

    // ── Step 3: Waterfall engine ──────────────────────────────────────────

    private fun targetNextDriver() {
        if (_binding == null) return

        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)

        FirebaseFirestore.getInstance()
            .collection("drivers")
            .whereEqualTo("isOnline", true)
            .whereEqualTo("isAvailable", true)
            .whereEqualTo("vehicleType", vehicleType)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener

                // Filter: not already tried, location fresh, within radius
                val candidates = snapshot.documents
                    .filter { doc ->
                        val driverId = doc.id
                        if (driverId in triedDriverIds) return@filter false

                        val lat = doc.getDouble("lat") ?: return@filter false
                        val lng = doc.getDouble("lng") ?: return@filter false
                        val lastUpdated = doc.getLong("lastUpdated") ?: 0L
                        if (lastUpdated < fiveMinutesAgo) return@filter false

                        haversineDistance(pickupLat, pickupLng, lat, lng) <= searchRadiusKm.toDouble()
                    }
                    .sortedBy { doc ->
                        val lat = doc.getDouble("lat") ?: 0.0
                        val lng = doc.getDouble("lng") ?: 0.0
                        haversineDistance(pickupLat, pickupLng, lat, lng)
                    }

                if (candidates.isEmpty()) {
                    // No untried drivers available right now — wait and retry
                    // The outer 60s timer will cancel if time runs out
                    perDriverJob?.cancel()
                    perDriverJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(5_000) // wait 5 seconds before checking again
                        if (_binding == null) return@launch
                        targetNextDriver()
                    }
                    return@addOnSuccessListener
                }

                val nextDriver = candidates.first()
                val driverId = nextDriver.id

                // Write targetDriverId + reset status to pending
                FirebaseFirestore.getInstance()
                    .collection("rideRequests")
                    .document(rideRequestId)
                    .update(mapOf(
                        "targetDriverId" to driverId,
                        "status" to "pending"
                    ))
                    .addOnSuccessListener {
                        if (_binding == null) return@addOnSuccessListener
                        triedDriverIds.add(driverId)   // only mark as tried after confirmed write
                        listenForDriverResponse(driverId)
                        startPerDriverTimeout()
                    }
                    .addOnFailureListener {
                        // Write failed — driver not marked as tried, retry waterfall after short delay
                        perDriverJob?.cancel()
                        perDriverJob = viewLifecycleOwner.lifecycleScope.launch {
                            delay(3_000)
                            if (_binding == null) return@launch
                            targetNextDriver()
                        }
                    }
            }
            .addOnFailureListener {
                // Firestore error — retry after a short delay
                perDriverJob?.cancel()
                perDriverJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(3_000)
                    if (_binding == null) return@launch
                    targetNextDriver()
                }
            }
    }

    // ── Step 4: Listen for this driver's response ─────────────────────────

    private fun listenForDriverResponse(targetDriverId: String) {
        // Remove any previous listener first
        rideStatusListener?.remove()

        rideStatusListener = FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                if (_binding == null) return@addSnapshotListener

                val status = snapshot.getString("status") ?: return@addSnapshotListener

                when (status) {
                    "accepted" -> {
                        val acceptedBy = snapshot.getString("driverId") ?: ""
                        if (acceptedBy != targetDriverId) return@addSnapshotListener

                        // Correct driver accepted — stop everything and go to live screen
                        perDriverJob?.cancel()
                        outerTimerJob?.cancel()
                        countdownDisplayJob?.cancel()
                        rideStatusListener?.remove()

                        val driverName = snapshot.getString("driverName") ?: ""
                        val driverId   = acceptedBy

                        val bundle = Bundle().apply {
                            putString("rideRequestId", rideRequestId)
                            putString("driverId",      driverId)
                            putString("driverName",    driverName)
                            putString("vehicleType",   vehicleType)
                            putDouble("pickupLat",     pickupLat)
                            putDouble("pickupLng",     pickupLng)
                            putDouble("destLat",       destLat)
                            putDouble("destLng",       destLng)
                            putString("pickupAddress", pickupAddress)
                            putString("destAddress",   destAddress)
                            putInt("estimatedFare",    estimatedFare)
                        }
                        findNavController().navigate(
                            R.id.action_rideSearching_to_rideLive, bundle
                        )
                    }
                    "rejected" -> {
                        perDriverJob?.cancel()
                        rideStatusListener?.remove()
                        targetNextDriver()
                    }

                    "cancelled" -> {
                        // Cancelled externally
                        perDriverJob?.cancel()
                        outerTimerJob?.cancel()
                        countdownDisplayJob?.cancel()
                        rideStatusListener?.remove()
                        if (_binding != null) {
                            findNavController().navigate(
                                R.id.action_rideSearching_to_riderHome
                            )
                        }
                    }
                }
            }
    }

    // ── Step 5: Per-driver 15s timeout ────────────────────────────────────

    private fun startPerDriverTimeout() {
        perDriverJob?.cancel()
        perDriverJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(15_000)
            if (_binding == null) return@launch
            // No response in 15s — move to next driver
            rideStatusListener?.remove()
            targetNextDriver()
        }
    }

    // ── Cancel ────────────────────────────────────────────────────────────

    private fun cancelRideRequest(
        navigateHome: Boolean = true,
        cancellationReason: String = CancelReason.RIDER_CANCELLED.name
    ) {
        perDriverJob?.cancel()
        outerTimerJob?.cancel()
        countdownDisplayJob?.cancel()
        rideStatusListener?.remove()

        if (rideRequestId.isNotEmpty()) {
            FirebaseFirestore.getInstance()
                .collection("rideRequests")
                .document(rideRequestId)
                .update(
                    mapOf(
                        "status"             to "cancelled",
                        "cancellationReason" to cancellationReason,
                        "targetDriverId"     to ""
                    )
                )
        }

        if (navigateHome && _binding != null) {
            Toast.makeText(requireContext(), "Ride Search Cancelled", Toast.LENGTH_SHORT).show()
            findNavController().navigate(R.id.action_rideSearching_to_riderHome)
        }
    }

    // ── Vibrate and navigate to cancelled screen ──────────────────────────

    private fun vibrateAndNavigateCancelled() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)
        )
        viewLifecycleOwner.lifecycleScope.launch {
            delay(800)
            if (_binding == null) return@launch
            val bundle = Bundle().apply {
                putString("cancelReason", CancelReason.NO_DRIVER_FOUND.name)
            }
            findNavController().navigate(R.id.action_rideSearching_to_rideCancelled, bundle)
        }
    }

    private fun haversineDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onDestroyView() {
        perDriverJob?.cancel()
        outerTimerJob?.cancel()
        countdownDisplayJob?.cancel()
        rideStatusListener?.remove()
        super.onDestroyView()
        _binding = null
    }
}