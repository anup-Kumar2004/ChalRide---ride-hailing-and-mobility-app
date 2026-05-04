package com.example.chalride.ui.rider

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRideSearchingBinding
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RideSearchingFragment : Fragment() {

    private var _binding: FragmentRideSearchingBinding? = null
    private val binding get() = _binding!!

    private val rideRequestId by lazy { arguments?.getString("rideRequestId") ?: "" }
    private val vehicleType   by lazy { arguments?.getString("vehicleType")   ?: "" }

    private var rideListener: ListenerRegistration? = null
    private var timerJob: Job? = null
    private var timeoutJob: Job? = null

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
        startCountdownTimer()
        listenForDriverAcceptance()
        startTimeoutJob()

        binding.btnCancelSearch.setOnClickListener {
            cancelRideRequest()
        }
    }

    private fun setVehicleIcon() {
        binding.tvVehicleIcon.text = when (vehicleType) {
            "bike"  -> "🏍️"
            "auto"  -> "🛺"
            "sedan" -> "🚗"
            "suv"   -> "🚙"
            else    -> "🚗"
        }
    }

    // ── Pulse animation ───────────────────────────────────────────────────────

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
            .scaleX(1.3f)
            .scaleY(1.3f)
            .alpha(0f)
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

    // ── Countdown timer ───────────────────────────────────────────────────────

    private fun startCountdownTimer() {
        var secondsLeft = 60
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && secondsLeft > 0) {
                binding.tvTimer.text = "${secondsLeft}s"
                delay(1000)
                secondsLeft--
            }
        }
    }

    // ── Timeout after 60s ─────────────────────────────────────────────────────

    private fun startTimeoutJob() {
        timeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(60_000)
            if (_binding == null) return@launch
            // No driver accepted in 60s — cancel and go back
            cancelRideRequest(navigateHome = true, showMessage = true)
        }
    }

    // ── Listen for driver acceptance ──────────────────────────────────────────

    private fun listenForDriverAcceptance() {
        if (rideRequestId.isEmpty()) return

        rideListener = FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                if (_binding == null) return@addSnapshotListener

                val status = snapshot.getString("status") ?: return@addSnapshotListener

                when (status) {
                    "accepted" -> {
                        // Driver accepted — navigate to live tracking
                        timerJob?.cancel()
                        timeoutJob?.cancel()
                        rideListener?.remove()

                        val driverId   = snapshot.getString("driverId")   ?: ""
                        val driverName = snapshot.getString("driverName") ?: ""

                        val bundle = Bundle().apply {
                            putString("rideRequestId", rideRequestId)
                            putString("driverId",      driverId)
                            putString("driverName",    driverName)
                            putString("vehicleType",   vehicleType)
                            putDouble("pickupLat",     arguments?.getDouble("pickupLat")     ?: 0.0)
                            putDouble("pickupLng",     arguments?.getDouble("pickupLng")     ?: 0.0)
                            putDouble("destLat",       arguments?.getDouble("destLat")       ?: 0.0)
                            putDouble("destLng",       arguments?.getDouble("destLng")       ?: 0.0)
                            putString("pickupAddress", arguments?.getString("pickupAddress") ?: "")
                            putString("destAddress",   arguments?.getString("destAddress")   ?: "")
                            putInt("estimatedFare",    arguments?.getInt("estimatedFare")    ?: 0)
                        }
                        findNavController().navigate(
                            R.id.action_rideSearching_to_rideLive,
                            bundle
                        )
                    }
                    "cancelled" -> {
                        // Cancelled externally
                        timerJob?.cancel()
                        timeoutJob?.cancel()
                        findNavController().navigate(R.id.action_rideSearching_to_riderHome)
                    }
                }
            }
    }

    // ── Cancel ride request ───────────────────────────────────────────────────

    private fun cancelRideRequest(
        navigateHome: Boolean = true,
        showMessage: Boolean = false
    ) {
        timerJob?.cancel()
        timeoutJob?.cancel()
        rideListener?.remove()

        if (rideRequestId.isNotEmpty()) {
            FirebaseFirestore.getInstance()
                .collection("rideRequests")
                .document(rideRequestId)
                .update("status", "cancelled")
        }

        if (navigateHome && _binding != null) {
            if (showMessage) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "No drivers found nearby. Please try again.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            findNavController().navigate(R.id.action_rideSearching_to_riderHome)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroyView() {
        timerJob?.cancel()
        timeoutJob?.cancel()
        rideListener?.remove()
        super.onDestroyView()
        _binding = null
    }
}