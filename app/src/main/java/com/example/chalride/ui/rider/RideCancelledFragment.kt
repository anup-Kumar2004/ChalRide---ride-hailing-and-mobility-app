package com.example.chalride.ui.rider

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRideCancelledBinding

class RideCancelledFragment : Fragment() {

    private var _binding: FragmentRideCancelledBinding? = null
    private val binding get() = _binding!!

    private val cancelReason by lazy {
        val raw = arguments?.getString("cancelReason") ?: "RIDER_CANCELLED"
        try { CancelReason.valueOf(raw) } catch (_: Exception) { CancelReason.RIDER_CANCELLED }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRideCancelledBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Block back — rider must tap the button
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { }
            }
        )

        when (cancelReason) {
            CancelReason.RIDER_CANCELLED -> {
                binding.tvTitle.text    = "Ride Cancelled"
                binding.tvMessage.text  = "You cancelled this ride. No charges have been applied."
                binding.tvEmoji.text    = "🚫"
            }
            CancelReason.DRIVER_OFFLINE -> {
                binding.tvTitle.text    = "Driver Went Offline"
                binding.tvMessage.text  =
                    "We're sorry — your driver went offline and couldn't be reached. " +
                            "Your ride has been cancelled. No charges applied."
                binding.tvEmoji.text    = "📵"
            }
            CancelReason.NO_DRIVER_FOUND -> {
                binding.tvTitle.text    = "No Driver Found"
                binding.tvMessage.text  =
                    "No drivers were available nearby. Please try again in a few minutes."
                binding.tvEmoji.text    = "🔍"
            }
            CancelReason.TIMEOUT -> {
                binding.tvTitle.text    = "Request Timed Out"
                binding.tvMessage.text  = "Your ride request expired. Please try booking again."
                binding.tvEmoji.text    = "⏱️"
            }
        }

        binding.btnGoHome.setOnClickListener {
            findNavController().navigate(R.id.action_rideCancelled_to_riderHome)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}