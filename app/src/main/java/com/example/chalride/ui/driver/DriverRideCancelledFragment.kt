package com.example.chalride.ui.driver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentDriverRideCancelledBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.example.chalride.ui.rider.CancelReason

class DriverRideCancelledFragment : Fragment() {

    private var _binding: FragmentDriverRideCancelledBinding? = null
    private val binding get() = _binding!!

    private val cancelReason by lazy {
        val raw = arguments?.getString("cancelReason") ?: "RIDER_CANCELLED"
        try { com.example.chalride.ui.rider.CancelReason.valueOf(raw) }
        catch (_: Exception) { com.example.chalride.ui.rider.CancelReason.RIDER_CANCELLED }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverRideCancelledBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Block back navigation — driver must use the button
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { }
            }
        )

        when (cancelReason) {
            CancelReason.RIDER_NO_SHOW -> {
                binding.tvTitle.text    = "Rider Didn't Show Up"
                binding.tvMessage.text  = "The rider did not board within the waiting time. You are now available for new rides."
                binding.tvInfoLabel.text = "No impact on your earnings"
                binding.tvInfoSub.text   = "Listening for new nearby requests"
            }
            CancelReason.RIDER_CANCELLED -> {
                binding.tvTitle.text    = "Rider Cancelled"
                binding.tvMessage.text  = "The rider has cancelled this trip. You are now available for new ride requests."
                binding.tvInfoLabel.text = "Your status is now active"
                binding.tvInfoSub.text   = "Listening for new nearby requests"
            }
            else -> {
                binding.tvTitle.text    = "Ride Cancelled"
                binding.tvMessage.text  = "This ride was cancelled. You are now available for new ride requests."
                binding.tvInfoLabel.text = "Your status is now active"
                binding.tvInfoSub.text   = "Listening for new nearby requests"
            }
        }

        // Transition driver back to ONLINE_AVAILABLE the moment this screen opens
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            android.util.Log.d("DriverRideCancelled",
                "Rider cancelled — transitioning driver to ONLINE_AVAILABLE")
            FirebaseFirestore.getInstance()
                .collection("drivers").document(uid)
                .update(DriverState.ONLINE_AVAILABLE.toFirestoreMap())
        }

        // Primary CTA: go back to home (clears entire back stack)
        binding.btnBackToHome.setOnClickListener {
            findNavController().navigate(
                R.id.action_driverRideCancelled_to_driverHome,
                null,
                androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.nav_graph, true)
                    .build()
            )
        }

        // Secondary CTA: open full earnings dashboard
        binding.btnViewEarnings.setOnClickListener {
            findNavController().navigate(
                R.id.action_driverRideCancelled_to_driverEarnings
                // No popUpTo here — driver can press back from earnings to return here,
                // then tap "Back to Home". Or you can popUpTo driverHome if preferred.
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}