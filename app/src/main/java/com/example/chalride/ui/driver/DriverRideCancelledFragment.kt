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

class DriverRideCancelledFragment : Fragment() {

    private var _binding: FragmentDriverRideCancelledBinding? = null
    private val binding get() = _binding!!

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