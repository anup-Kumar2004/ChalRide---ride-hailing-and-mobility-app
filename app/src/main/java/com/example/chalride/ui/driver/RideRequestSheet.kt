package com.example.chalride.ui.driver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.example.chalride.databinding.LayoutRideRequestSheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RideRequestSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutRideRequestSheetBinding? = null
    private val binding get() = _binding!!

    private var timerJob: Job? = null
    private var secondsLeft = 15
    private var hasResponded = false

    // Callbacks
    var onAccepted: (() -> Unit)? = null
    var onRejected: (() -> Unit)? = null
    var onTimeout:  (() -> Unit)? = null

    // Data passed in

    private val riderName     get() = arguments?.getString("riderName") ?: "Rider"
    private val pickupAddress get() = arguments?.getString("pickupAddress") ?: ""
    private val destAddress   get() = arguments?.getString("destAddress") ?: ""
    private val vehicleType   get() = arguments?.getString("vehicleType") ?: ""
    private val estimatedFare get() = arguments?.getInt("estimatedFare") ?: 0
    private val distanceKm    get() = arguments?.getDouble("distanceKm") ?: 0.0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutRideRequestSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isCancelable = false  // driver must explicitly accept or reject

        bindData()
        startTimer()

        binding.btnAccept.setOnClickListener {
            if (hasResponded) return@setOnClickListener
            hasResponded = true
            timerJob?.cancel()
            onAccepted?.invoke()
            dismissAllowingStateLoss()
        }

        binding.btnReject.setOnClickListener {
            if (hasResponded) return@setOnClickListener
            hasResponded = true
            timerJob?.cancel()
            onRejected?.invoke()
            dismissAllowingStateLoss()
        }
    }

    private fun bindData() {
        binding.tvRiderName.text   = riderName
        binding.tvPickup.text      = pickupAddress
        binding.tvDest.text        = destAddress
        binding.tvFare.text        = "₹$estimatedFare"

        binding.tvVehicleChip.text = vehicleType.replaceFirstChar { it.uppercase() }

        val distText = if (distanceKm < 1.0)
            "${(distanceKm * 1000).toInt()} m away"
        else
            String.format("%.1f km away", distanceKm)
        binding.tvDistanceChip.text = distText
    }

    private fun startTimer() {
        secondsLeft = (arguments?.getInt("remainingSec", 15) ?: 15).coerceIn(1, 15)
        binding.tvTimer.text = secondsLeft.toString()
        binding.timerProgress.progress = (secondsLeft / 15f * 100).toInt()

        timerJob = lifecycleScope.launch {
            while (isActive && secondsLeft > 0) {
                delay(1000)
                secondsLeft--
                binding.tvTimer.text = secondsLeft.toString()

                // Update ring progress
                val progress = (secondsLeft / 15f * 100).toInt()
                binding.timerProgress.progress = progress

                // Turn timer red in last 5 seconds
                if (secondsLeft <= 5) {
                    binding.tvTimer.setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            requireContext(),
                            com.example.chalride.R.color.error_color
                        )
                    )
                }
            }

            // Timeout — no response from driver
            if (isActive && !hasResponded) {
                hasResponded = true
                onTimeout?.invoke()
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onDestroyView() {
        timerJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    companion object {

        const val TAG = "RideRequestSheet"

        fun newInstance(
            rideRequestId: String,
            riderName: String,
            pickupAddress: String,
            destAddress: String,
            vehicleType: String,
            estimatedFare: Int,
            distanceKm: Double,
            remainingSec: Int = 15
        ): RideRequestSheet {

            return RideRequestSheet().apply {
                arguments = Bundle().apply {
                    putString("rideRequestId", rideRequestId)
                    putString("riderName", riderName)
                    putString("pickupAddress", pickupAddress)
                    putString("destAddress", destAddress)
                    putString("vehicleType", vehicleType)
                    putInt("estimatedFare", estimatedFare)
                    putDouble("distanceKm", distanceKm)
                    putInt("remainingSec", remainingSec)
                }
            }
        }
    }
}