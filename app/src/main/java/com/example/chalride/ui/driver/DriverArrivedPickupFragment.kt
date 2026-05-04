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
    }

    override fun onDestroyView() {
        timerJob?.cancel()
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

    // ─────────────────────────────────────────────────────────────────────────
    // Contact buttons
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupContactButtons() {
        binding.btnCall.setOnClickListener {
            if (riderPhone.isBlank()) {
                android.widget.Toast.makeText(requireContext(), "Rider's phone not available", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$riderPhone"))
            startActivity(intent)
        }

        binding.btnMessage.setOnClickListener {
            if (riderPhone.isBlank()) {
                android.widget.Toast.makeText(requireContext(), "Rider's phone not available", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:$riderPhone"))
            intent.putExtra("sms_body", "Hi, I'm your ChalRide driver! I've arrived at the pickup location. Your OTP is $generatedOtp.")
            startActivity(intent)
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

        FirebaseFirestore.getInstance()
            .collection("rideRequests").document(rideRequestId)
            .update(mapOf(
                "status"    to "in_progress",
                "startedAt" to System.currentTimeMillis(),
                "tripPhase" to "IN_PROGRESS"
            ))

        // Also update driver document
        FirebaseFirestore.getInstance()
            .collection("drivers").document(uid)
            .update("tripPhase", "IN_PROGRESS")

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
        val totalSeconds = 150   // 2 min 30 sec
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
            }
        }
    }
}