package com.example.chalride.ui.rider

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRideSummaryBinding
import com.google.firebase.firestore.FirebaseFirestore

/**
 * RideDetailsFragment
 *
 * Shows a premium, full-screen breakdown of the current ongoing ride.
 * Navigated to from RideLiveFragment when the rider taps the "Ride Details" chip.
 *
 * Arguments (Bundle) — all passed from RideLiveFragment:
 *   rideRequestId  String
 *   driverId       String
 *   driverName     String
 *   vehicleType    String
 *   pickupAddress  String
 *   destAddress    String
 *   estimatedFare  Int
 *   currentStatus  String   — latest ride status (accepted / arrived_at_pickup / in_progress)
 */
class RideSummaryFragment : Fragment() {

    private var _binding: FragmentRideSummaryBinding? = null
    private val binding get() = _binding!!

    // ── Arguments ─────────────────────────────────────────────────────────────
    private val rideRequestId by lazy { arguments?.getString("rideRequestId") ?: "" }
    private val driverId      by lazy { arguments?.getString("driverId")      ?: "" }
    private val driverName    by lazy { arguments?.getString("driverName")    ?: "Driver" }
    private val vehicleType   by lazy { arguments?.getString("vehicleType")   ?: "" }
    private val pickupAddress by lazy { arguments?.getString("pickupAddress") ?: "" }
    private val destAddress   by lazy { arguments?.getString("destAddress")   ?: "" }
    private val estimatedFare by lazy { arguments?.getInt("estimatedFare")    ?: 0 }
    private val currentStatus by lazy { arguments?.getString("currentStatus") ?: "accepted" }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRideSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        bindArgumentData()
        startStatusDotPulse()
        fetchDriverDetails()
        setupCopyRideId()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Toolbar
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bind data from arguments (available immediately, no network needed)
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindArgumentData() {
        // Status banner
        binding.tvDetailStatus.text = when (currentStatus) {
            "arrived_at_pickup" -> "✅ Driver has arrived at pickup"
            "in_progress"       -> "🛣️ Trip is in progress"
            else                -> "🚗 Your driver is on the way"
        }
        binding.tvDetailPhase.text = when (currentStatus) {
            "in_progress" -> "TRIP"
            else          -> "PICKUP"
        }

        // Driver name (will be overwritten by Firestore fetch if available)
        binding.tvDetailDriverName.text = driverName
        binding.tvDriverInitial.text    = driverName.firstOrNull()?.uppercaseChar()?.toString() ?: "D"
        binding.tvDetailVehicleType.text = vehicleType.replaceFirstChar { it.uppercase() }

        // Route
        binding.tvDetailPickupAddress.text = pickupAddress.ifEmpty { "Pickup location" }
        binding.tvDetailDestAddress.text   = destAddress.ifEmpty { "Destination" }

        // Fare
        binding.tvDetailFare.text = if (estimatedFare > 0) "₹$estimatedFare" else "₹--"

        // Ride ID (truncated for display — copy gives full)
        binding.tvRideId.text = rideRequestId.ifEmpty { "--" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fetch driver details from Firestore
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchDriverDetails() {
        if (driverId.isEmpty()) return

        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(driverId)
            .get()
            .addOnSuccessListener { doc ->
                if (_binding == null || !doc.exists()) return@addOnSuccessListener

                val name         = doc.getString("name")         ?: driverName
                val phone        = doc.getString("phone")        ?: "--"
                val vehicleModel = doc.getString("vehicleModel") ?: "--"
                val vehicleColor = doc.getString("vehicleColor") ?: "--"
                val vehiclePlate = doc.getString("vehiclePlate") ?: "--"
                val photoUrl     = doc.getString("photoUrl")     ?: ""   // Cloudinary URL
                val totalTrips   = doc.getLong("totalTrips")     ?: 0L

                // Name + initial
                binding.tvDetailDriverName.text = name
                binding.tvDriverInitial.text    = name.firstOrNull()?.uppercaseChar()?.toString() ?: "D"

                // Contact
                binding.tvDriverPhone.text = phone.ifBlank { "--" }

                // Vehicle details
                binding.tvVehicleModel.text = vehicleModel.ifBlank { "--" }
                    .replaceFirstChar { it.uppercase() }
                binding.tvVehicleColor.text = vehicleColor.ifBlank { "--" }
                    .replaceFirstChar { it.uppercase() }
                binding.tvVehiclePlate.text = vehiclePlate.uppercase().ifBlank { "-- -- -- ----" }

                // Trips count
                binding.tvTotalTrips.text = "$totalTrips trip${if (totalTrips == 1L) "" else "s"}"

                // ── Driver photo via Glide (Cloudinary URL) ───────────────────
                if (photoUrl.isNotBlank()) {
                    binding.tvDriverInitial.visibility = View.GONE
                    binding.imgDriverPhoto.visibility  = View.VISIBLE

                    Glide.with(this)
                        .load(photoUrl)
                        .transition(DrawableTransitionOptions.withCrossFade(300))
                        .placeholder(android.R.color.transparent)
                        .error(android.R.color.transparent)
                        .circleCrop()
                        .into(binding.imgDriverPhoto)
                } else {
                    // Fallback: show initials — already set above
                    binding.tvDriverInitial.visibility = View.VISIBLE
                    binding.imgDriverPhoto.visibility  = View.GONE
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.e("RideDetails", "Firestore fetch failed: ${e.message}")
                // Gracefully degrade — arguments data already bound, no crash
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Copy ride ID to clipboard
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupCopyRideId() {
        if (rideRequestId.isEmpty()) {
            binding.tvCopyRideId.visibility = View.GONE
            return
        }
        binding.tvCopyRideId.setOnClickListener {
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Ride ID", rideRequestId))
            Toast.makeText(requireContext(), "Ride ID copied!", Toast.LENGTH_SHORT).show()

            // Quick scale pop feedback
            it.animate()
                .scaleX(1.3f).scaleY(1.3f).setDuration(100)
                .withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status dot pulse animation (matches RideLiveFragment style)
    // ─────────────────────────────────────────────────────────────────────────

    private fun startStatusDotPulse() {
        ObjectAnimator.ofPropertyValuesHolder(
            binding.viewDetailsDot,
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.25f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.40f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.40f, 1f)
        ).apply {
            duration     = 1600L
            repeatCount  = ObjectAnimator.INFINITE
            repeatMode   = ObjectAnimator.RESTART
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }.start()
    }
}