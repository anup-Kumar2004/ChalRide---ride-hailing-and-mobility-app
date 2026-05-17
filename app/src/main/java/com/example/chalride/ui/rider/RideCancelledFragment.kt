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

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {

                    findNavController().navigate(
                        R.id.action_rideCancelled_to_riderHome
                    )
                }
            }
        )

        when (cancelReason) {
            CancelReason.RIDER_CANCELLED -> {
                binding.tvEmoji.text       = "🚫"
                binding.tvTitle.text       = "Ride Cancelled"
                binding.tvMessage.text     = "Your trip has been cancelled and no charges have been applied to your account."
                binding.tvInfoLabel.text   = "Looking to go somewhere?"
                binding.tvInfoSub.text     = "Head back home to book your next ride anytime."
            }
            CancelReason.DRIVER_OFFLINE -> {
                binding.tvEmoji.text       = "📵"
                binding.tvTitle.text       = "Driver Unavailable"
                binding.tvMessage.text     = "Your driver lost connection and could not be reached. Your ride was cancelled automatically."
                binding.tvInfoLabel.text   = "You have not been charged"
                binding.tvInfoSub.text     = "If this keeps happening, reach out to support."
            }
            CancelReason.NO_DRIVER_FOUND -> {
                binding.tvEmoji.text       = "🔍"
                binding.tvTitle.text       = "No Driver Found"
                binding.tvMessage.text     = "We could not find an available driver in your area right now. Please try again in a few minutes."
                binding.tvInfoLabel.text   = "No charges applied"
                binding.tvInfoSub.text     = "Your payment method was not charged."
            }
            CancelReason.TIMEOUT -> {
                binding.tvEmoji.text       = "⏱️"
                binding.tvTitle.text       = "Request Expired"
                binding.tvMessage.text     = "Your ride request timed out before a driver could accept. Demand may be high in your area."
                binding.tvInfoLabel.text   = "No charges applied"
                binding.tvInfoSub.text     = "Your payment method was not charged."
            }
            CancelReason.RIDER_NO_SHOW -> {
                binding.tvEmoji.text     = "⏳"
                binding.tvTitle.text     = "Ride Cancelled"
                binding.tvMessage.text   = "Your driver waited but could not reach you in time. The ride was automatically cancelled."
                binding.tvInfoLabel.text = "Missed your ride?"
                binding.tvInfoSub.text   = "Head back home to book a new one anytime."
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