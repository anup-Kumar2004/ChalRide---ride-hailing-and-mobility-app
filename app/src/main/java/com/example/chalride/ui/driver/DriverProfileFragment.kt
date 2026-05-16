package com.example.chalride.ui.driver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentDriverProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class DriverProfileFragment : Fragment() {

    private var _binding: FragmentDriverProfileBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        loadDriverProfile()
        animateIn()
    }

    private fun loadDriverProfile() {
        val uid = auth.currentUser?.uid ?: return
        val email = auth.currentUser?.email ?: ""

        // Set email immediately from FirebaseAuth
        binding.tvEmail.text = email

        db.collection("drivers").document(uid).get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener

                val name         = doc.getString("name")         ?: "Driver"
                val phone        = doc.getString("phone")        ?: "Not added"
                val vehicleModel = doc.getString("vehicleModel") ?: "—"
                val vehicleType  = doc.getString("vehicleType")  ?: ""
                val vehiclePlate = doc.getString("vehiclePlate") ?: ""
                val vehicleColor = doc.getString("vehicleColor") ?: ""
                val totalTrips   = (doc.getLong("totalTrips")    ?: 0L).toInt()
                val earnings     = (doc.getLong("earnings")      ?: 0L).toInt()
                val isOnline     = doc.getBoolean("isOnline")    ?: false

                // ── Name & initials ──────────────────────────────────────
                binding.tvName.text = name
                val initials = name.trim().split(" ")
                    .take(2)
                    .joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
                binding.tvAvatarInitials.text = initials.ifEmpty { "D" }

                // ── Contact info ─────────────────────────────────────────
                binding.tvPhone.text = phone

                // ── Vehicle info ─────────────────────────────────────────
                binding.tvVehicleModel.text = vehicleModel.ifBlank { "—" }

                if (vehiclePlate.isNotBlank()) {
                    binding.tvVehiclePlate.text = vehiclePlate.uppercase()
                    binding.tvVehiclePlate.visibility = View.VISIBLE
                } else {
                    binding.tvVehiclePlate.visibility = View.GONE
                }

                binding.tvVehicleColor.text = if (vehicleColor.isNotBlank()) vehicleColor else ""

                // Vehicle type badge (AUTO / BIKE / CAR …)
                if (vehicleType.isNotBlank()) {
                    binding.tvVehicleTypeBadge.text = vehicleType.uppercase()
                    binding.tvVehicleTypeBadge.visibility = View.VISIBLE
                } else {
                    binding.tvVehicleTypeBadge.visibility = View.GONE
                }

                // ── Stats strip ──────────────────────────────────────────
                binding.tvTotalTrips.text = totalTrips.toString()
                binding.tvEarnings.text   = "₹$earnings"


                // ── Online status dot + label (in hero) ──────────────────
                if (isOnline) {
                    binding.viewOnlineDot.setBackgroundResource(R.drawable.bg_status_dot_online)
                    binding.tvOnlineStatus.text = "Online"
                } else {
                    binding.viewOnlineDot.setBackgroundResource(R.drawable.bg_status_dot_offline)
                    binding.tvOnlineStatus.text = "Offline"
                }

                // ── Member since ─────────────────────────────────────────
                val creationTime = auth.currentUser?.metadata?.creationTimestamp
                if (creationTime != null && creationTime > 0) {
                    val sdf = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
                    binding.tvMemberSince.text =
                        "Partner since ${sdf.format(java.util.Date(creationTime))}"
                }
            }
            .addOnFailureListener {
                if (_binding == null) return@addOnFailureListener
                Toast.makeText(requireContext(), "Failed to load profile", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupClickListeners() {

        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnEditProfile.setOnClickListener {
            Toast.makeText(requireContext(), "Edit profile coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.rowTripHistory.setOnClickListener {
            Toast.makeText(requireContext(), "Trip history coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.rowEarningsHistory.setOnClickListener {
            findNavController().navigate(
                R.id.action_driverProfile_to_driverEarnings
            )
        }

        binding.rowNotifications.setOnClickListener {
            Toast.makeText(requireContext(), "Notification settings coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.rowPrivacy.setOnClickListener {
            Toast.makeText(requireContext(), "Privacy settings coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.rowHelpSupport.setOnClickListener {
            Toast.makeText(requireContext(), "Help & Support coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.rowAbout.setOnClickListener {
            Toast.makeText(requireContext(), "ChalRide v1.0", Toast.LENGTH_SHORT).show()
        }

        binding.btnLogout.setOnClickListener {
            showLogoutDialog()
        }
    }

    private fun showLogoutDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(
            requireContext(), R.style.ChalRide_LogoutDialog
        )
            .setTitle("Log out?")
            .setMessage("You'll need to sign in again to accept rides.")
            .setPositiveButton("Log out") { _, _ ->
                val uid = FirebaseAuth.getInstance().currentUser?.uid

                // 1. Stop the foreground location service immediately
                val serviceIntent = android.content.Intent(
                    requireContext(),
                    DriverLocationService::class.java
                )
                requireContext().stopService(serviceIntent)


                // After stopService, before the Firestore update:
                val rtdbRef = com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("driverPresence/$uid")
                rtdbRef.onDisconnect().cancel()   // cancel the crash handler
                rtdbRef.setValue(mapOf(
                    "isOnline" to false,
                    "lastSeen" to com.google.firebase.database.ServerValue.TIMESTAMP
                ))

                // 2. Write OFFLINE state to Firestore before signing out
                if (uid != null) {
                    FirebaseFirestore.getInstance()
                        .collection("drivers").document(uid)
                        .update(
                            mapOf(
                                "isOnline"      to false,
                                "isAvailable"   to false,
                                "driverState"   to "OFFLINE"
                            )
                        )
                        .addOnCompleteListener {
                            // 3. Sign out and navigate regardless of Firestore result
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
                } else {
                    // uid already null — just sign out
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
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun animateIn() {
        val views = listOf(
            binding.headerSection,
            binding.cardStats,
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