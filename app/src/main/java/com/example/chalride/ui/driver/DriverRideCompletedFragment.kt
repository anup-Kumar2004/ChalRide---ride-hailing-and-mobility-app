package com.example.chalride.ui.driver

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentDriverRideCompletedBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/**
 * DriverRideCompletedFragment
 *
 * Shown immediately after the driver completes a trip (taps "Complete Trip").
 *
 * "Find Next Ride" → navigates to DriverHomeFragment.
 *   The driver is already ONLINE_AVAILABLE (set by completeTrip() in
 *   DriverNavigationFragment), so DriverHomeFragment's restoreOnlineStateIfNeeded()
 *   will read isOnline=true from Firestore and automatically restore the
 *   online UI + ride listener. Nothing extra needed here.
 *
 * "Go Offline" → performs the exact same offline sequence as DriverHomeFragment:
 *   1. Stops DriverLocationService (its onDestroy writes OFFLINE to both
 *      Firestore AND RTDB driverPresence — handles everything automatically).
 *   2. Writes DriverState.OFFLINE to Firestore explicitly as a safety net
 *      (mirrors what DriverHomeFragment does when the toggle is tapped).
 *   3. Navigates to DriverHomeFragment, which reads isOnline=false from
 *      Firestore via restoreOnlineStateIfNeeded() and shows the offline UI.
 */
class DriverRideCompletedFragment : Fragment() {

    private var _binding: FragmentDriverRideCompletedBinding? = null
    private val binding get() = _binding!!

    // ── Arguments passed from DriverNavigationFragment.completeTrip() ─────────
    private val rideRequestId by lazy { arguments?.getString("rideRequestId") ?: "" }
    private val riderName     by lazy { arguments?.getString("riderName")     ?: "Rider" }
    private val pickupAddress by lazy { arguments?.getString("pickupAddress") ?: "" }
    private val destAddress   by lazy { arguments?.getString("destAddress")   ?: "" }
    private val estimatedFare by lazy { arguments?.getInt("estimatedFare")    ?: 0 }
    private val vehicleType   by lazy { arguments?.getString("vehicleType")   ?: "" }
    private val pickupLat     by lazy { arguments?.getDouble("pickupLat")     ?: 0.0 }
    private val pickupLng     by lazy { arguments?.getDouble("pickupLng")     ?: 0.0 }
    private val destLat       by lazy { arguments?.getDouble("destLat")       ?: 0.0 }
    private val destLng       by lazy { arguments?.getDouble("destLng")       ?: 0.0 }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverRideCompletedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Block back navigation — driver must use the action buttons
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { navigateHome() }
            }
        )

        bindStaticData()
        playEntryAnimation()
        fetchTripTimestamps()
        fetchDriverSnapshot()
        setupButtons()
    }

    // ── Bind data we already have from arguments ──────────────────────────────

    private fun bindStaticData() {
        binding.tvEarnedAmount.text    = "₹$estimatedFare"
        binding.tvRiderName.text       = riderName
        binding.tvRiderAvatar.text     = riderName.firstOrNull()?.uppercaseChar()?.toString() ?: "R"
        binding.tvPickupAddress.text   = pickupAddress.ifEmpty { "Pickup location" }
        binding.tvDestAddress.text     = destAddress.ifEmpty  { "Destination" }
        binding.tvVehicleType.text     = vehicleType.replaceFirstChar { it.uppercase() }.ifEmpty { "--" }

        // Haversine distance from coordinates
        if (pickupLat != 0.0 && destLat != 0.0) {
            val distKm = haversineKm(pickupLat, pickupLng, destLat, destLng)
            binding.tvTripDistance.text = String.format("%.1f km", distKm)
        }
    }

    // ── Hero entry animation ──────────────────────────────────────────────────

    private fun playEntryAnimation() {
        binding.viewMoneyRingOuter.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setStartDelay(100)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        binding.viewMoneyRingInner.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setStartDelay(250)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()

        binding.tvMoneyIcon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(420)
            .setStartDelay(400)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()
    }

    // ── Fetch Firestore trip timestamps ───────────────────────────────────────

    private fun fetchTripTimestamps() {
        if (rideRequestId.isEmpty()) return

        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener

                val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())

                val assignedAt  = doc.getLong("assignedAt")
                val startedAt   = doc.getLong("startedAt")
                val completedAt = doc.getLong("completedAt")

                binding.tvTimeAssigned.text  = assignedAt?.let  { fmt.format(Date(it)) } ?: "--:--"
                binding.tvTimeStarted.text   = startedAt?.let   { fmt.format(Date(it)) } ?: "--:--"
                binding.tvTimeCompleted.text = completedAt?.let { fmt.format(Date(it)) } ?: "--:--"

                // Trip duration: time from rider OTP-start to drop-off
                if (startedAt != null && completedAt != null) {
                    val durationMin = ((completedAt - startedAt) / 60_000).toInt()
                    binding.tvTripDuration.text = when {
                        durationMin <= 0 -> "< 1 min"
                        durationMin == 1 -> "1 min"
                        else             -> "$durationMin min"
                    }
                }
            }
        // Non-critical — timestamps stay as "--:--" on failure
    }

    // ── Fetch driver's cumulative stats ───────────────────────────────────────

    private fun fetchDriverSnapshot() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener

                val totalEarnings = doc.getLong("earnings")   ?: 0L
                val totalTrips    = doc.getLong("totalTrips") ?: 0L

                binding.tvTotalEarningsToday.text = "₹$totalEarnings"
                binding.tvTotalTripsToday.text    = "$totalTrips"
            }
        // Non-critical — stats stay as "--" on failure
    }

    // ── Action buttons ────────────────────────────────────────────────────────

    private fun setupButtons() {

        // "Find Next Ride"
        // Driver is already ONLINE_AVAILABLE — completeTrip() in DriverNavigationFragment
        // already wrote ONLINE_AVAILABLE to Firestore before navigating here.
        // DriverHomeFragment.restoreOnlineStateIfNeeded() reads isOnline=true and
        // automatically restores the online UI, timer, and ride request listener.
        // Nothing extra to do here — just navigate.
        binding.btnGoOnline.setOnClickListener {
            navigateHome()
        }

        // "Go Offline"
        // Mirrors the exact sequence DriverHomeFragment runs when the toggle is tapped offline:
        //   Step 1 → stopDriverLocationService()
        //            DriverLocationService.onDestroy() then automatically:
        //              • cancels the RTDB onDisconnect() handler
        //              • writes isOnline=false + lastSeen=ServerValue.TIMESTAMP
        //                to RTDB at driverPresence/{uid}
        //              • writes DriverState.OFFLINE.toFirestoreMap() to Firestore
        //                (driverState=OFFLINE, isOnline=false, isAvailable=false,
        //                 activeRideId=null, tripPhase=null)
        //   Step 2 → write OFFLINE to Firestore directly as a safety net,
        //            exactly as DriverHomeFragment does after calling stopService()
        //   Step 3 → navigate to DriverHomeFragment
        //            restoreOnlineStateIfNeeded() reads isOnline=false → shows offline UI:
        //              • button text = "GO ONLINE", button colour = success_color (teal)
        //              • status dot = grey/offline drawable
        //              • tvStatus text = "Offline", colour = text_hint
        //              • statusRingOuter = offline drawable
        //              • tvStatusMessage = "You are currently offline"
        //              • pulse view hidden
        binding.btnGoOffline.setOnClickListener {
            goOfflineAndNavigateHome()
        }
    }

    private fun goOfflineAndNavigateHome() {
        // Step 1: Stop the foreground service — its onDestroy() handles RTDB + Firestore
        stopDriverLocationService()

        // Step 2: Write OFFLINE to Firestore directly as a safety net
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            FirebaseFirestore.getInstance()
                .collection("drivers")
                .document(uid)
                .update(DriverState.OFFLINE.toFirestoreMap())
        }

        // Step 3: Navigate — DriverHomeFragment restores the offline UI automatically
        navigateHome()
    }

    private fun stopDriverLocationService() {
        val intent = Intent(requireContext(), DriverLocationService::class.java)
        requireContext().stopService(intent)
    }

    private fun navigateHome() {
        try {
            findNavController().navigate(
                R.id.action_driverRideCompleted_to_driverHome,
                null,
                androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.nav_graph, true)
                    .build()
            )
        } catch (e: Exception) {
            android.util.Log.e("DriverRideCompleted", "Navigation failed: ${e.message}")
        }
    }

    // ── Math ──────────────────────────────────────────────────────────────────

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r    = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a    = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}