package com.example.chalride.ui.rider

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRiderProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class RiderProfileFragment : Fragment() {

    private var _binding: FragmentRiderProfileBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRiderProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        loadRiderProfile()
        animateIn()
    }

    private fun loadRiderProfile() {
        val uid = auth.currentUser?.uid ?: return
        val email = auth.currentUser?.email ?: ""

        // Set email immediately from FirebaseAuth
        binding.tvEmail.text = email

        db.collection("riders").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val name = doc.getString("name") ?: "Rider"
                val phone = doc.getString("phone") ?: "Not added"
                val phoneVerified = doc.getBoolean("phoneVerified") ?: false

                binding.tvName.text = name
                binding.tvPhone.text = phone

                // Initials avatar
                val initials = name.trim().split(" ")
                    .take(2)
                    .joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
                binding.tvAvatarInitials.text = initials.ifEmpty { "R" }

                // Phone verification badge
                if (phoneVerified) {
                    binding.tvPhoneVerified.visibility = View.VISIBLE
                } else {
                    binding.tvPhoneVerified.visibility = View.GONE
                }

                // Member since (using Firebase account creation time)
                val creationTime = auth.currentUser?.metadata?.creationTimestamp
                if (creationTime != null && creationTime > 0) {
                    val sdf = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
                    binding.tvMemberSince.text = "Member since ${sdf.format(java.util.Date(creationTime))}"
                }
            }
            .addOnFailureListener {
                if (_binding == null) return@addOnFailureListener
                Toast.makeText(requireContext(), "Failed to load profile", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupClickListeners() {
        // Back button
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // Edit profile (placeholder for Phase N)
        binding.btnEditProfile.setOnClickListener {
            Toast.makeText(requireContext(), "Edit profile coming soon", Toast.LENGTH_SHORT).show()
        }

        // Help & Support
        binding.rowHelpSupport.setOnClickListener {
            Toast.makeText(requireContext(), "Help & Support coming soon", Toast.LENGTH_SHORT).show()
        }

        // Safety
        binding.rowSafety.setOnClickListener {
            Toast.makeText(requireContext(), "Safety features coming soon", Toast.LENGTH_SHORT).show()
        }

        // Trip History
        binding.rowTripHistory.setOnClickListener {
            findNavController().navigate(R.id.action_riderProfile_to_riderTripDetails)
        }

        // Notifications
        binding.rowNotifications.setOnClickListener {
            Toast.makeText(requireContext(), "Notification settings coming soon", Toast.LENGTH_SHORT).show()
        }

        // Privacy
        binding.rowPrivacy.setOnClickListener {
            Toast.makeText(requireContext(), "Privacy settings coming soon", Toast.LENGTH_SHORT).show()
        }

        // About
        binding.rowAbout.setOnClickListener {
            Toast.makeText(requireContext(), "ChalRide v1.0", Toast.LENGTH_SHORT).show()
        }

        // Logout
        binding.btnLogout.setOnClickListener {
            showLogoutDialog()
        }
    }

    private fun showLogoutDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            requireContext(), R.style.ChalRide_LogoutDialog
        )
            .setTitle("Log out?")
            .setMessage("You'll need to sign in again to book rides.")
            .setPositiveButton("Log out") { _, _ ->
                FirebaseAuth.getInstance().signOut()
                findNavController().navigate(
                    R.id.roleSelectionFragment,
                    null,
                    androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.nav_graph, true)
                        .setEnterAnim(R.anim.slide_in_left)
                        .setExitAnim(R.anim.slide_out_right)
                        .build()
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun animateIn() {
        val views = listOf(
            binding.headerSection,
            binding.cardProfileInfo,
            binding.sectionActivity,
            binding.sectionSettings,
            binding.btnLogout
        )
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 24f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280)
                .setStartDelay((i * 70).toLong())
                .start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}