package com.example.chalride.ui.rider

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRideCompletionBinding
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*
import androidx.core.net.toUri
import android.util.Log

class RideCompletionFragment : Fragment() {

    private var _binding: FragmentRideCompletionBinding? = null
    private val binding get() = _binding!!

    // ── Arguments ─────────────────────────────────────────────────────────────
    private val rideRequestId by lazy { arguments?.getString("rideRequestId") ?: "" }
    private val driverName    by lazy { arguments?.getString("driverName")    ?: "Driver" }
    private val vehicleType   by lazy { arguments?.getString("vehicleType")   ?: "" }
    private val pickupAddress by lazy { arguments?.getString("pickupAddress") ?: "" }
    private val destAddress   by lazy { arguments?.getString("destAddress")   ?: "" }
    private val estimatedFare by lazy { arguments?.getInt("estimatedFare")    ?: 0 }
    private val pickupLat     by lazy { arguments?.getDouble("pickupLat")     ?: 0.0 }
    private val pickupLng     by lazy { arguments?.getDouble("pickupLng")     ?: 0.0 }
    private val destLat       by lazy { arguments?.getDouble("destLat")       ?: 0.0 }
    private val destLng       by lazy { arguments?.getDouble("destLng")       ?: 0.0 }

    // ── State ─────────────────────────────────────────────────────────────────
    private var selectedRating = 0
    private var ratingSubmitted = false
    private var complaintSubmitted = false
    private var isHelpExpanded = false
    private var selectedChipText = ""

    private val starViews: List<TextView> by lazy {
        listOf(binding.tvStar1, binding.tvStar2, binding.tvStar3, binding.tvStar4, binding.tvStar5)
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRideCompletionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Block back navigation — user must tap a button to leave
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    navigateHome()
                }
            }
        )

        bindStaticData()
        playEntryAnimation()
        fetchRideDetails()      // also triggers driver phone fetch
        setupStarRating()
        setupHelpSection()
        setupButtons()
    }

    // ── Static data we already have from arguments ────────────────────────────

    private fun bindStaticData() {
        binding.tvFareAmount.text    = "₹$estimatedFare"
        binding.tvPickupAddress.text = pickupAddress.ifEmpty { "Pickup location" }
        binding.tvDestAddress.text   = destAddress.ifEmpty  { "Destination" }
        binding.tvDriverName.text    = driverName
        binding.tvDriverVehicle.text = vehicleType.replaceFirstChar { it.uppercase() }

        // Distance from coordinates (Haversine)
        if (pickupLat != 0.0 && destLat != 0.0) {
            val distKm = haversineKm(pickupLat, pickupLng, destLat, destLng)
            binding.tvDistance.text = String.format("%.1f km", distKm)
        }

        binding.tvVehicleType.text = vehicleType.replaceFirstChar { it.uppercase() }
    }

    // ── Fetch Firestore timestamps + driver phone ─────────────────────────────

    private fun fetchRideDetails() {
        if (rideRequestId.isEmpty()) return

        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener

                val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())

                val createdAt   = doc.getLong("createdAt")
                val assignedAt  = doc.getLong("assignedAt")
                val startedAt   = doc.getLong("startedAt")
                val completedAt = doc.getLong("completedAt")

                binding.tvTimeRequested.text = createdAt?.let  { fmt.format(Date(it)) } ?: "--:--"
                binding.tvTimeAssigned.text  = assignedAt?.let { fmt.format(Date(it)) } ?: "--:--"
                binding.tvTimeStarted.text   = startedAt?.let  { fmt.format(Date(it)) } ?: "--:--"
                binding.tvTimeCompleted.text = completedAt?.let{ fmt.format(Date(it)) } ?: "--:--"

                // Trip duration
                if (startedAt != null && completedAt != null) {
                    val durationMs  = completedAt - startedAt
                    val durationMin = (durationMs / 60_000).toInt()
                    binding.tvDuration.text = when {
                        durationMin <= 0 -> "< 1 min"
                        durationMin == 1 -> "1 min"
                        else             -> "$durationMin min"
                    }
                }

                // Use driverId from the ride document to fetch driver phone
                val driverId = doc.getString("driverId") ?: ""
                if (driverId.isNotEmpty()) fetchDriverPhone(driverId)
            }
            .addOnFailureListener {
                // Non-critical — timestamps just show "--:--"
            }
    }

    // ── Fetch driver phone number and set up dial intent ──────────────────────

    private fun fetchDriverPhone(driverId: String) {
        FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(driverId)
            .get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener

                // Try common field names for the phone number
                val phone = doc.getString("phone")
                    ?: doc.getString("phoneNumber")
                    ?: doc.getString("mobile")
                    ?: return@addOnSuccessListener

                if (phone.isBlank()) return@addOnSuccessListener

                // Display phone row and wire up tap-to-dial
                binding.tvDriverPhone.text = phone
                binding.layoutDriverPhone.visibility = View.VISIBLE

                binding.tvCallDialer.setOnClickListener {

                    try {

                        val cleanedPhone = phone.trim()

                        val dialIntent = Intent(
                            Intent.ACTION_DIAL,
                            "tel:$cleanedPhone".toUri()
                        )

                        Log.d("RideCompletion", "Opening dialer for: $cleanedPhone")
                        startActivity(dialIntent)

                    } catch (e: Exception) {

                        Toast.makeText(
                            requireContext(),
                            "Could not open dialer",
                            Toast.LENGTH_SHORT
                        ).show()

                        e.printStackTrace()
                    }
                }
            }
        // Silently ignore — phone row stays hidden if unavailable
    }

    // ── Entry animation ───────────────────────────────────────────────────────

    private fun playEntryAnimation() {
        binding.viewCheckRingOuter.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setStartDelay(100)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        binding.viewCheckRingInner.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setStartDelay(250)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()

        binding.tvCheckmark.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setStartDelay(400)
            .setInterpolator(OvershootInterpolator(2.0f))
            .start()
    }

    // ── Star rating — two-step: select → submit → success state ──────────────

    private fun setupStarRating() {
        starViews.forEachIndexed { index, tv ->
            tv.setOnClickListener {
                // Once submitted the stars become non-interactive
                if (ratingSubmitted) return@setOnClickListener

                selectedRating = index + 1
                updateStarDisplay(selectedRating)
                updateRatingLabel(selectedRating)

                // Reveal the submit button on the first star selection
                if (binding.btnSubmitRating.visibility != View.VISIBLE) {
                    binding.btnSubmitRating.visibility = View.VISIBLE
                    binding.btnSubmitRating.alpha = 0f
                    binding.btnSubmitRating.animate().alpha(1f).setDuration(250).start()
                }
            }
        }

        binding.btnSubmitRating.setOnClickListener {
            if (selectedRating == 0 || ratingSubmitted) return@setOnClickListener
            submitRating(selectedRating)
        }
    }

    private fun updateStarDisplay(rating: Int) {
        starViews.forEachIndexed { index, tv ->
            if (index < rating) {
                tv.text = "★"
                tv.setTextColor(0xFFFFB300.toInt())
                tv.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100)
                    .withEndAction {
                        tv.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }.start()
            } else {
                tv.text = "☆"
                tv.setTextColor(0xFF606080.toInt())
            }
        }
    }

    private fun updateRatingLabel(rating: Int) {
        binding.tvRatingLabel.text = when (rating) {
            1 -> "😞  Poor"
            2 -> "😐  Fair"
            3 -> "🙂  Good"
            4 -> "😊  Great"
            5 -> "🤩  Excellent!"
            else -> "Tap a star to rate"
        }
    }

    private fun submitRating(rating: Int) {
        binding.btnSubmitRating.isEnabled = false
        binding.btnSubmitRating.text = "Submitting..."

        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .update("riderFeedback.rating", rating)
            .addOnSuccessListener {
                if (_binding == null) return@addOnSuccessListener
                ratingSubmitted = true
                showRatingSuccess(rating)
            }
            .addOnFailureListener {
                if (_binding == null) return@addOnFailureListener
                // Re-enable so user can retry
                binding.btnSubmitRating.isEnabled = true
                binding.btnSubmitRating.text = "Submit Rating"
                Toast.makeText(requireContext(), "Failed to submit, please try again.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showRatingSuccess(rating: Int) {
        // Hide the interactive rating UI
        binding.layoutStarRow.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { binding.layoutStarRow.visibility = View.GONE }
            .start()

        binding.tvRatingLabel.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { binding.tvRatingLabel.visibility = View.GONE }
            .start()

        binding.btnSubmitRating.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { binding.btnSubmitRating.visibility = View.GONE }
            .start()

        // Populate success state
        val filledStars   = "★".repeat(rating)
        val emptyStars    = "☆".repeat(5 - rating)
        binding.tvRatingSuccessStars.text = filledStars + emptyStars

        binding.tvRatingSuccessLabel.text = when (rating) {
            1 -> "😞 Poor"
            2 -> "😐 Fair"
            3 -> "🙂 Good"
            4 -> "😊 Great"
            5 -> "🤩 Excellent!"
            else -> ""
        }

        // Animate success state in
        binding.layoutRatingSuccess.visibility = View.VISIBLE
        binding.layoutRatingSuccess.alpha = 0f
        binding.layoutRatingSuccess.translationY = 16f
        binding.layoutRatingSuccess.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(220)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    // ── Help / complaint section ──────────────────────────────────────────────

    private fun setupHelpSection() {
        binding.layoutHelpHeader.setOnClickListener {
            // Prevent re-expanding after a report has been submitted
            if (complaintSubmitted) return@setOnClickListener

            isHelpExpanded = !isHelpExpanded
            if (isHelpExpanded) {
                binding.layoutComplaintExpanded.visibility = View.VISIBLE
                binding.tvHelpChevron.animate().rotation(90f).setDuration(200).start()
            } else {
                binding.layoutComplaintExpanded.visibility = View.GONE
                binding.tvHelpChevron.animate().rotation(0f).setDuration(200).start()
            }
        }

        // Quick-select chips pre-fill the text box
        val chips = listOf(
            binding.chipIssueRoute    to "Wrong route taken by driver",
            binding.chipIssueFare     to "Fare amount seems incorrect",
            binding.chipIssueBehavior to "Driver behavior was inappropriate",
            binding.chipIssueSafety   to "I have a safety concern"
        )
        chips.forEach { (chip, text) ->
            chip.setOnClickListener {
                selectedChipText = text
                binding.etComplaint.setText(text)
                binding.etComplaint.setSelection(text.length)
            }
        }

        binding.btnSubmitComplaint.setOnClickListener {
            submitComplaint()
        }
    }

    private fun submitComplaint() {
        val message = binding.etComplaint.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(requireContext(), "Please describe the issue", Toast.LENGTH_SHORT).show()
            return
        }
        if (complaintSubmitted) {
            Toast.makeText(requireContext(), "Report already submitted", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSubmitComplaint.isEnabled = false
        binding.btnSubmitComplaint.text = "Submitting..."

        val feedback = mapOf(
            "complaint"             to message,
            "complaintSubmittedAt"  to System.currentTimeMillis(),
            "complaintStatus"       to "pending"
        )

        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .update(
                "riderFeedback.complaint",            feedback["complaint"],
                "riderFeedback.complaintSubmittedAt", feedback["complaintSubmittedAt"],
                "riderFeedback.complaintStatus",      feedback["complaintStatus"]
            )
            .addOnSuccessListener {
                if (_binding == null) return@addOnSuccessListener
                complaintSubmitted = true
                showComplaintSuccess()
            }
            .addOnFailureListener {
                if (_binding == null) return@addOnFailureListener
                binding.btnSubmitComplaint.isEnabled = true
                binding.btnSubmitComplaint.text = "Submit Report"
                Toast.makeText(
                    requireContext(),
                    "Failed to submit. Please try again.",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun showComplaintSuccess() {
        // Collapse and rotate chevron back
        binding.tvHelpChevron.animate().rotation(0f).setDuration(200).start()
        isHelpExpanded = false

        // Fade out the expanded section then swap to success state
        binding.layoutComplaintExpanded.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                if (_binding == null) return@withEndAction
                binding.layoutComplaintExpanded.visibility = View.GONE
                binding.layoutComplaintExpanded.alpha = 1f  // reset for any future reference

                // Animate success state in
                binding.layoutComplaintSuccess.visibility = View.VISIBLE
                binding.layoutComplaintSuccess.alpha = 0f
                binding.layoutComplaintSuccess.translationY = 12f
                binding.layoutComplaintSuccess.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.1f))
                    .start()
            }
            .start()
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        // "Book Another Ride" navigates home — rider can immediately start a new booking
        binding.btnBookAgain.setOnClickListener {
            navigateHome()
        }
        // btnGoHome has been removed from the layout
    }

    private fun navigateHome() {
        try {
            findNavController().navigate(R.id.action_rideCompletion_to_riderHome)
        } catch (e: Exception) {
            android.util.Log.e("RideCompletion", "Navigation failed: ${e.message}")
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