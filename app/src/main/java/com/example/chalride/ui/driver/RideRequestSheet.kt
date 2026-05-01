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

    // Callbacks
    var onAccepted: (() -> Unit)? = null
    var onRejected: (() -> Unit)? = null
    var onTimeout:  (() -> Unit)? = null

    // Data passed in
    var rideRequestId = ""
    var riderName     = ""
    var pickupAddress = ""
    var destAddress   = ""
    var vehicleType   = ""
    var estimatedFare = 0
    var distanceKm    = 0.0

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
            timerJob?.cancel()
            onAccepted?.invoke()
            dismiss()
        }

        binding.btnReject.setOnClickListener {
            timerJob?.cancel()
            onRejected?.invoke()
            dismiss()
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
        secondsLeft = 15
        binding.tvTimer.text = secondsLeft.toString()
        binding.timerProgress.progress = 100

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
            if (isActive) {
                onTimeout?.invoke()
                dismiss()
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
    }
}