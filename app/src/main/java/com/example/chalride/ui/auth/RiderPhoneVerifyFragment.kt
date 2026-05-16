package com.example.chalride.ui.auth

import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRiderPhoneVerifyBinding
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit

class RiderPhoneVerifyFragment : Fragment() {

    private var _binding: FragmentRiderPhoneVerifyBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    // Holds the verification ID returned by Firebase after SMS is sent
    private var storedVerificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    private var countDownTimer: CountDownTimer? = null

    // The phone number the user entered (without country code, raw 10 digits)
    private var enteredPhone: String = ""

    // ── Step tracking ────────────────────────────────────────────────────────
    private enum class Step { PHONE, OTP }
    private var currentStep = Step.PHONE

    // ── OTP boxes list (for easy iteration) ─────────────────────────────────
    private lateinit var otpFields: List<EditText>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRiderPhoneVerifyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        otpFields = listOf(
            binding.etOtp1,
            binding.etOtp2,
            binding.etOtp3,
            binding.etOtp4,
            binding.etOtp5,
            binding.etOtp6
        )

        // Make the lock icon background a circle
        binding.tvLockIcon.post {
            binding.tvLockIcon.background = ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_role_badge
            )
        }

        setupPhoneInputFocusBorder()
        setupOtpBoxes()
        setupClickListeners()
    }

    // ── Phone input: highlight border on focus ───────────────────────────────
    private fun setupPhoneInputFocusBorder() {
        binding.etPhone.setOnFocusChangeListener { _, hasFocus ->
            binding.containerPhoneField.background = ContextCompat.getDrawable(
                requireContext(),
                if (hasFocus) R.drawable.bg_phone_input_focused
                else R.drawable.bg_phone_input_container
            )
        }
    }

    // ── OTP box auto-advance + backspace ─────────────────────────────────────
    private fun setupOtpBoxes() {
        otpFields.forEachIndexed { index, editText ->
            editText.background = ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_otp_box
            )

            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString() ?: ""
                    if (text.length == 1) {
                        // Fill state
                        editText.background = ContextCompat.getDrawable(
                            requireContext(), R.drawable.bg_otp_box_filled
                        )
                        // Auto-advance
                        if (index < otpFields.size - 1) {
                            otpFields[index + 1].requestFocus()
                        } else {
                            // Last box filled — try auto-verify
                            hideKeyboard()
                            attemptVerifyOtp()
                        }
                    } else {
                        editText.background = ContextCompat.getDrawable(
                            requireContext(), R.drawable.bg_otp_box
                        )
                    }
                }
            })

            // Backspace to go back to previous field
            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL
                    && event.action == KeyEvent.ACTION_DOWN
                    && editText.text.isEmpty()
                    && index > 0
                ) {
                    otpFields[index - 1].apply {
                        requestFocus()
                        setText("")
                    }
                    return@setOnKeyListener true
                }
                false
            }

            // Focused box highlight
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && editText.text.isEmpty()) {
                    editText.background = ContextCompat.getDrawable(
                        requireContext(), R.drawable.bg_otp_box_active
                    )
                } else if (!hasFocus && editText.text.isEmpty()) {
                    editText.background = ContextCompat.getDrawable(
                        requireContext(), R.drawable.bg_otp_box
                    )
                }
            }
        }
    }

    // ── Button + link listeners ───────────────────────────────────────────────
    private fun setupClickListeners() {

        // PHONE STEP — Send OTP
        binding.btnSendOtp.setOnClickListener {
            val phone = binding.etPhone.text.toString().trim()
            if (validatePhone(phone)) {
                enteredPhone = phone
                sendOtp(phone)
            }
        }

        // OTP STEP — Verify button
        binding.btnVerifyOtp.setOnClickListener {
            attemptVerifyOtp()
        }

        // Resend OTP (visible after timer expires)
        binding.tvResendOtp.setOnClickListener {
            binding.tvResendOtp.visibility = View.GONE
            binding.layoutResendRow.visibility = View.VISIBLE
            clearOtpFields()
            resendOtp()
        }

        // Change number → go back to phone step
        binding.tvChangeNumber.setOnClickListener {
            switchToPhoneStep()
        }
    }

    // ── Phone validation ─────────────────────────────────────────────────────
    private fun validatePhone(phone: String): Boolean {
        return when {
            phone.isEmpty() -> {
                showPhoneError("Please enter your phone number")
                false
            }
            phone.length != 10 -> {
                showPhoneError("Enter a valid 10-digit number")
                false
            }
            !phone.matches(Regex("^[6-9][0-9]{9}$")) -> {
                showPhoneError("Enter a valid Indian mobile number")
                false
            }
            else -> {
                hidePhoneError()
                true
            }
        }
    }

    // ── Firebase: Send OTP ────────────────────────────────────────────────────
    private fun sendOtp(phone: String) {
        showLoading(true)
        hidePhoneError()

        val fullPhone = "+91$phone"

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                // Auto-retrieval or instant verification (emulator / whitelisted numbers)
                showLoading(false)
                signInWithCredential(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                showLoading(false)
                showPhoneError(parseFirebaseError(e))
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                showLoading(false)
                storedVerificationId = verificationId
                resendToken = token
                switchToOtpStep(phone)
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(fullPhone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(requireActivity())
            .setCallbacks(callbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    // ── Firebase: Re-send OTP ─────────────────────────────────────────────────
    private fun resendOtp() {
        val token = resendToken ?: run {
            sendOtp(enteredPhone)
            return
        }
        showLoading(true)

        val fullPhone = "+91$enteredPhone"

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                showLoading(false)
                signInWithCredential(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                showLoading(false)
                showOtpError(parseFirebaseError(e))
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                showLoading(false)
                storedVerificationId = verificationId
                resendToken = token
                startResendTimer()
                clearOtpFields()
                otpFields[0].requestFocus()
                showKeyboard(otpFields[0])
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(fullPhone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(requireActivity())
            .setCallbacks(callbacks)
            .setForceResendingToken(token)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    // ── Attempt verification with entered code ────────────────────────────────
    private fun attemptVerifyOtp() {
        val code = otpFields.joinToString("") { it.text.toString() }
        if (code.length < 6) {
            showOtpError("Please enter the complete 6-digit code")
            return
        }
        val vId = storedVerificationId ?: run {
            showOtpError("Session expired. Please resend the OTP.")
            return
        }
        showLoading(true)
        hideOtpError()
        val credential = PhoneAuthProvider.getCredential(vId, code)
        signInWithCredential(credential)
    }

    // ── Firebase sign-in with credential ─────────────────────────────────────
    private fun signInWithCredential(credential: PhoneAuthCredential) {
        // We link the phone credential to the existing Firebase user (email+password auth)
        val currentUser = auth.currentUser
        if (currentUser == null) {
            showLoading(false)
            showOtpError("Session lost. Please log in again.")
            return
        }

        currentUser.linkWithCredential(credential)
            .addOnSuccessListener {
                // Mark phone as verified in Firestore
                markPhoneVerifiedInFirestore(currentUser.uid, "+91$enteredPhone")
            }
            .addOnFailureListener { e ->
                // If already linked with a phone number, use updatePhoneNumber instead
                // This handles the case where the user re-verifies
                currentUser.updatePhoneNumber(credential)
                    .addOnSuccessListener {
                        markPhoneVerifiedInFirestore(currentUser.uid, "+91$enteredPhone")
                    }
                    .addOnFailureListener { e2 ->
                        showLoading(false)
                        showOtpError(parseFirebaseError(e2 as? FirebaseException))
                    }
            }
    }

    // ── Write phoneVerified = true to Firestore ───────────────────────────────
    private fun markPhoneVerifiedInFirestore(uid: String, fullPhone: String) {
        firestore.collection("riders")
            .document(uid)
            .update(
                mapOf(
                    "phone" to fullPhone,
                    "phoneVerified" to true,
                    "profileStep" to 2
                )
            )
            .addOnSuccessListener {
                showLoading(false)
                // Navigate to Rider Home, clearing the entire back stack
                findNavController().navigate(
                    R.id.action_riderPhoneVerify_to_riderHome
                )
            }
            .addOnFailureListener {
                // Firestore write failed — still navigate, phone is verified in Firebase Auth
                showLoading(false)
                findNavController().navigate(
                    R.id.action_riderPhoneVerify_to_riderHome
                )
            }
    }

    // ── Step switching ────────────────────────────────────────────────────────
    private fun switchToOtpStep(phone: String) {
        currentStep = Step.OTP

        // Update step indicator — second dot becomes active
        binding.stepDot2.background = ContextCompat.getDrawable(
            requireContext(), R.drawable.bg_status_dot_online
        )

        // Update icon to shield
        binding.tvLockIcon.text = "🛡️"

        // Update subtitle with masked phone
        val masked = "******${phone.takeLast(4)}"
        binding.tvOtpSubtitle.text = "Sent to +91 $masked"

        // Swap visibility with fade
        binding.tvPhoneTitle.animate().alpha(0f).setDuration(150).withEndAction {
            binding.tvPhoneTitle.visibility = View.GONE
            binding.tvPhoneSubtitle.visibility = View.GONE
            binding.layoutPhoneInput.visibility = View.GONE
            binding.tvPhoneError.visibility = View.GONE
            binding.btnSendOtp.visibility = View.GONE

            binding.layoutOtpStep.visibility = View.VISIBLE
            binding.layoutOtpStep.alpha = 0f
            binding.layoutOtpStep.animate().alpha(1f).setDuration(200).start()

            // Reposition the shared progress bar to appear below btnVerifyOtp
            val params = binding.progressBar.layoutParams
                    as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            // Progress bar is now below btnVerifyOtp inside the OTP layout
            binding.progressBar.visibility = View.GONE
        }.start()

        startResendTimer()

        // Focus first OTP box
        binding.etOtp1.postDelayed({
            binding.etOtp1.requestFocus()
            showKeyboard(binding.etOtp1)
        }, 300)
    }

    private fun switchToPhoneStep() {
        currentStep = Step.PHONE
        countDownTimer?.cancel()

        // Reset step indicator
        binding.stepDot2.background = ContextCompat.getDrawable(
            requireContext(), R.drawable.bg_otp_box
        )
        binding.tvLockIcon.text = "🔐"

        binding.layoutOtpStep.animate().alpha(0f).setDuration(150).withEndAction {
            binding.layoutOtpStep.visibility = View.GONE

            binding.tvPhoneTitle.visibility = View.VISIBLE
            binding.tvPhoneSubtitle.visibility = View.VISIBLE
            binding.layoutPhoneInput.visibility = View.VISIBLE
            binding.btnSendOtp.visibility = View.VISIBLE
            binding.tvPhoneTitle.alpha = 0f
            binding.tvPhoneTitle.animate().alpha(1f).setDuration(200).start()

            binding.etPhone.requestFocus()
            showKeyboard(binding.etPhone)
        }.start()

        clearOtpFields()
        hideOtpError()
    }

    // ── Countdown timer (40 seconds) ─────────────────────────────────────────
    private fun startResendTimer() {
        countDownTimer?.cancel()

        binding.layoutResendRow.visibility = View.VISIBLE
        binding.tvResendOtp.visibility = View.GONE

        countDownTimer = object : CountDownTimer(40_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                if (_binding == null) return
                val seconds = millisUntilFinished / 1000
                binding.tvTimer.text = "0:${seconds.toString().padStart(2, '0')}"
            }

            override fun onFinish() {
                if (_binding == null) return
                binding.layoutResendRow.visibility = View.GONE
                binding.tvResendOtp.visibility = View.VISIBLE
            }
        }.start()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSendOtp.isEnabled = !show
        binding.btnVerifyOtp.isEnabled = !show
    }

    private fun showPhoneError(msg: String) {
        binding.tvPhoneError.text = msg
        binding.tvPhoneError.visibility = View.VISIBLE
        binding.containerPhoneField.background = ContextCompat.getDrawable(
            requireContext(), R.drawable.bg_phone_input_focused
        )
    }

    private fun hidePhoneError() {
        binding.tvPhoneError.visibility = View.GONE
    }

    private fun showOtpError(msg: String) {
        binding.tvOtpError.text = msg
        binding.tvOtpError.visibility = View.VISIBLE
        // Shake all OTP boxes
        otpFields.forEach { field ->
            field.background = ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_otp_box_active
            )
        }
    }

    private fun hideOtpError() {
        binding.tvOtpError.visibility = View.GONE
    }

    private fun clearOtpFields() {
        otpFields.forEach {
            it.setText("")
            it.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_otp_box)
        }
    }

    private fun showKeyboard(view: View) {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun parseFirebaseError(e: Exception?): String {
        val msg = e?.message ?: return "Something went wrong. Try again."
        return when {
            msg.contains("TOO_SHORT") || msg.contains("INVALID_PHONE_NUMBER") ->
                "Invalid phone number. Check and retry."
            msg.contains("TOO_MANY_REQUESTS") || msg.contains("quota") ->
                "Too many attempts. Please try again later."
            msg.contains("INVALID_CODE") || msg.contains("invalid-verification-code") ->
                "Incorrect code. Please check and retry."
            msg.contains("CODE_EXPIRED") || msg.contains("session-expired") ->
                "Code expired. Please request a new one."
            msg.contains("CREDENTIAL_ALREADY_IN_USE") ->
                "This number is linked to another account."
            msg.contains("network") || msg.contains("NETWORK") ->
                "No internet connection. Check and retry."
            else -> "Verification failed. Please try again."
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onDestroyView() {
        countDownTimer?.cancel()
        super.onDestroyView()
        _binding = null
    }
}