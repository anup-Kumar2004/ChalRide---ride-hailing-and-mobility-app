package com.example.chalride.ui.driver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentDriverArrivedPickupBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DriverArrivedPickupFragment
 *
 * Shown when the driver reaches the pickup location.
 *
 * Features:
 *  1. 2.5-minute (150s) countdown timer — how long to wait for rider.
 *  2. Call button — opens dialler with rider's phone number.
 *  3. Message button — opens SMS with rider's number.
 *  4. Four-digit OTP entry — driver enters OTP given by rider.
 *     OTP is generated here, stored in Firestore on the rideRequests doc
 *     (field: "riderOtp"), and the rider sees it on their RideLiveFragment.
 *  5. On OTP match → start trip → navigate back to DriverActiveRideFragment
 *     with tripPhase = IN_PROGRESS.
 *
 * OTP timing rationale:
 *   OTP is generated and saved when this fragment opens (i.e. when driver
 *   marks arrived). The rider sees it in their live-tracking screen.
 *   This prevents drivers from starting a trip without the rider on board.
 */
class DriverArrivedPickupFragment : Fragment() {

    private var _binding: FragmentDriverArrivedPickupBinding? = null
    private val binding get() = _binding!!

    private val rideRequestId by lazy { arguments?.getString("rideRequestId") ?: "" }
    private val riderName     by lazy { arguments?.getString("riderName")     ?: "Rider" }
    private val riderPhone    by lazy { arguments?.getString("riderPhone")    ?: "" }
    private val pickupLat     by lazy { arguments?.getDouble("pickupLat")     ?: 0.0 }
    private val pickupLng     by lazy { arguments?.getDouble("pickupLng")     ?: 0.0 }
    private val destLat       by lazy { arguments?.getDouble("destLat")       ?: 0.0 }
    private val destLng       by lazy { arguments?.getDouble("destLng")       ?: 0.0 }
    private val pickupAddress by lazy { arguments?.getString("pickupAddress") ?: "" }
    private val destAddress   by lazy { arguments?.getString("destAddress")   ?: "" }
    private val estimatedFare by lazy { arguments?.getInt("estimatedFare")    ?: 0 }
    private val vehicleType   by lazy { arguments?.getString("vehicleType")   ?: "" }


    private var riderCancelListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var timerJob: Job? = null
    private var generatedOtp  = ""

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverArrivedPickupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { /* block back during OTP step */ }
            }
        )

        binding.tvRiderName.text = riderName

        // Generate OTP and save to Firestore so rider can see it
        generatedOtp = generateOtp()
        saveOtpToFirestore(generatedOtp)

        startWaitingTimer()
        setupOtpInput()
        setupContactButtons()
        setupStartTripButton()
        binding.btnCancelAfterDialog.setOnClickListener {
            performNoShowCancellation()
        }
        listenForRiderCancellation()
    }

    override fun onDestroyView() {
        timerJob?.cancel()
        riderCancelListener?.remove()    // ADD THIS LINE
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OTP
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateOtp(): String = (1000..9999).random().toString()

    private fun saveOtpToFirestore(otp: String) {
        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .update(mapOf(
                "status"    to "arrived_at_pickup",  // set status here too — ensures atomic update
                "riderOtp"  to otp,
                "otpSentAt" to System.currentTimeMillis()
            ))
            .addOnSuccessListener {
                android.util.Log.d("CHALRIDE_OTP", "OTP saved successfully: $otp")
            }
            .addOnFailureListener { e ->
                android.util.Log.e("CHALRIDE_OTP", "OTP save failed: ${e.message}")
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OTP 4-digit input — moves focus automatically between boxes
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupOtpInput() {
        val fields = listOf(
            binding.etOtp1, binding.etOtp2, binding.etOtp3, binding.etOtp4
        )

        fields.forEachIndexed { index, editText ->
            editText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!s.isNullOrEmpty() && s.length == 1) {
                        // Move to next
                        if (index < fields.size - 1) {
                            fields[index + 1].requestFocus()
                        } else {
                            // Last digit — hide keyboard
                            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                    as android.view.inputmethod.InputMethodManager
                            imm.hideSoftInputFromWindow(editText.windowToken, 0)
                        }
                        binding.tvOtpError.visibility = View.GONE
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            // Handle backspace to move to previous field
            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL &&
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    editText.text.isEmpty() && index > 0
                ) {
                    fields[index - 1].requestFocus()
                    fields[index - 1].text.clear()
                    true
                } else false
            }
        }
    }

    private fun getEnteredOtp(): String {
        return listOf(
            binding.etOtp1, binding.etOtp2, binding.etOtp3, binding.etOtp4
        ).joinToString("") { it.text.toString() }
    }

    private fun listenForRiderCancellation() {
        if (rideRequestId.isEmpty()) return
        riderCancelListener = FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || _binding == null) return@addSnapshotListener
                if (snapshot.getString("status") == "cancelled") {
                    android.util.Log.d("DriverArrivedPickup",
                        "Rider cancelled — navigating to DriverRideCancelled")
                    timerJob?.cancel()
                    riderCancelListener?.remove()
                    findNavController().navigate(
                        R.id.action_driverArrivedPickup_to_driverRideCancelled)
                }
            }
    }



    // ─────────────────────────────────────────────────────────────────────────
    // Contact buttons
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupContactButtons() {
        binding.btnCall.setOnClickListener {
            if (riderPhone.isNotBlank()) {
                // Phone was passed in bundle — use it directly
                dialNumber(riderPhone)
            } else {
                // Phone not in bundle — fetch from Firestore via rideRequests → riderId → riders
                fetchRiderPhoneAndDial()
            }
        }

        binding.btnMessage.setOnClickListener {
            android.widget.Toast.makeText(
                requireContext(), "Coming soon...", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun dialNumber(phone: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
        startActivity(intent)
    }

    private fun fetchRiderPhoneAndDial() {
        if (rideRequestId.isEmpty()) {
            android.widget.Toast.makeText(
                requireContext(), "Rider's phone not available", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .document(rideRequestId)
            .get()
            .addOnSuccessListener { rideDoc ->
                val riderId = rideDoc.getString("riderId") ?: run {
                    android.widget.Toast.makeText(
                        requireContext(), "Rider's phone not available", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@addOnSuccessListener
                }

                FirebaseFirestore.getInstance()
                    .collection("riders")
                    .document(riderId)
                    .get()
                    .addOnSuccessListener { riderDoc ->
                        val phone = riderDoc.getString("phone") ?: ""
                        if (phone.isBlank()) {
                            android.widget.Toast.makeText(
                                requireContext(), "Rider's phone not available", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            dialNumber(phone)
                        }
                    }
                    .addOnFailureListener {
                        android.widget.Toast.makeText(
                            requireContext(), "Could not fetch rider's number", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
            }
            .addOnFailureListener {
                android.widget.Toast.makeText(
                    requireContext(), "Could not fetch rider's number", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Start Trip button — validates OTP
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupStartTripButton() {
        binding.btnStartTrip.setOnClickListener {
            val entered = getEnteredOtp()
            if (entered.length < 4) {
                binding.tvOtpError.text = "Please enter the 4-digit OTP"
                binding.tvOtpError.visibility = View.VISIBLE
                shakeOtpBoxes()
                return@setOnClickListener
            }
            android.util.Log.d("CHALRIDE_OTP", "Entered: $entered | Expected: $generatedOtp")
            if (entered != generatedOtp) {
                binding.tvOtpError.text = "Incorrect OTP. Ask the rider again."
                binding.tvOtpError.visibility = View.VISIBLE
                shakeOtpBoxes()
                clearOtpFields()
                return@setOnClickListener
            }
            // OTP correct → start trip
            startTrip()
        }
    }

    private fun shakeOtpBoxes() {
        val boxes = binding.otpContainer
        boxes.animate()
            .translationX(-10f).setDuration(60)
            .withEndAction {
                boxes.animate().translationX(10f).setDuration(60)
                    .withEndAction {
                        boxes.animate().translationX(-10f).setDuration(60)
                            .withEndAction {
                                boxes.animate().translationX(10f).setDuration(60)
                                    .withEndAction {
                                        boxes.animate().translationX(0f).setDuration(60).start()
                                    }.start()
                            }.start()
                    }.start()
            }.start()
    }

    private fun clearOtpFields() {
        listOf(binding.etOtp1, binding.etOtp2, binding.etOtp3, binding.etOtp4)
            .forEach { it.text.clear() }
        binding.etOtp1.requestFocus()
    }

    private fun startTrip() {

        timerJob?.cancel()

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Update ride document — remove tripPhase from here (it lives on the driver doc only)
        FirebaseFirestore.getInstance()
            .collection("rideRequests").document(rideRequestId)
            .update(mapOf(
                "status"    to "in_progress",
                "startedAt" to System.currentTimeMillis()
            ))

        // Transition driver to IN_TRIP — sets driverState, tripPhase, isAvailable atomically
        FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)
            .update(DriverState.IN_TRIP.toFirestoreMap())
        // Navigate back to DriverActiveRideFragment with IN_PROGRESS phase
        val bundle = Bundle().apply {
            putString("rideRequestId", rideRequestId)
            putString("riderName",     riderName)
            putString("riderPhone",    riderPhone)
            putDouble("pickupLat",     pickupLat)
            putDouble("pickupLng",     pickupLng)
            putDouble("destLat",       destLat)
            putDouble("destLng",       destLng)
            putString("pickupAddress", pickupAddress)
            putString("destAddress",   destAddress)
            putInt("estimatedFare",    estimatedFare)
            putString("vehicleType",   vehicleType)
            putString("tripPhase",     "IN_PROGRESS")  // ← tells overview to switch phase
        }
        findNavController().navigate(R.id.action_driverArrivedPickup_to_driverActiveRide, bundle)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2.5 minute waiting timer
    // ─────────────────────────────────────────────────────────────────────────

    private fun startWaitingTimer() {
        val totalSeconds = 150 // 2 min 30 sec
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            var remaining = totalSeconds
            while (isActive && remaining >= 0) {
                val min = remaining / 60
                val sec = remaining % 60
                if (_binding != null) {
                    binding.tvTimer.text = String.format("%d:%02d", min, sec)
                    // Change color when under 30 seconds
                    binding.tvTimer.setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            if (remaining <= 30) R.color.error_color else R.color.brand_primary
                        )
                    )
                }
                delay(1000)
                remaining--
            }
            // Timer expired
            if (_binding != null && isActive) {
                binding.tvTimerLabel.text = "Wait time expired"
                showNoShowDialog()
            }
        }
    }


    private fun showNoShowDialog() {
        if (_binding == null) return

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_no_show, null)

        val dialog = android.app.Dialog(requireContext(), R.style.TransparentDialog)
        dialog.setContentView(dialogView)
        dialog.setCancelable(false)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            // Dim the background behind the dialog
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.also { it.dimAmount = 0.85f }
        }

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNoShow)
            .setOnClickListener {
                dialog.dismiss()
                performNoShowCancellation()
            }

        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEnterOtp)
            .setOnClickListener {
                dialog.dismiss()
                // Reveal the fallback cancel button so driver can still cancel
                // without needing the dialog to reappear
                binding.btnCancelAfterDialog.visibility = View.VISIBLE
            }

        dialog.show()
    }

    private fun performNoShowCancellation() {
        timerJob?.cancel()
        riderCancelListener?.remove()

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseFirestore.getInstance()
            .collection("rideRequests").document(rideRequestId)
            .update(mapOf(
                "status"             to "cancelled",
                "cancellationReason" to com.example.chalride.ui.rider.CancelReason.RIDER_NO_SHOW.name
            ))

        FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)
            .update(DriverState.ONLINE_AVAILABLE.toFirestoreMap())

        val bundle = Bundle().apply {
            putString("cancelReason", com.example.chalride.ui.rider.CancelReason.RIDER_NO_SHOW.name)
        }
        findNavController().navigate(
            R.id.action_driverArrivedPickup_to_driverRideCancelled, bundle
        )
    }


}