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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.random.Random
import android.content.Intent
import android.net.Uri
import android.provider.Settings

class RiderPhoneVerifyFragment : Fragment() {

    private var _binding: FragmentRiderPhoneVerifyBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private var countDownTimer: CountDownTimer? = null
    private var generatedOtp: String = ""
    private val OTP_NOTIFICATION_ID = 1001

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
        createNotificationChannel()
        requestNotificationPermission()

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
                            hideKeyboard()
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
            removeOtpNotification()
            switchToPhoneStep()
        }

        binding.btnOpenNotificationSettings.setOnClickListener {

            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().packageName, null)
            )

            startActivity(intent)
        }

        binding.btnRecheckPermission.setOnClickListener {

            if (isNotificationPermissionGranted()) {

                restoreMainVerificationUi()

            } else {

                showNotificationPermissionLayout()

                android.widget.Toast.makeText(
                    requireContext(),
                    "Notification permission still not granted",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
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

    private fun sendOtp(phone: String) {
        binding.btnSendOtp.isEnabled = false

        showLoading(true)

        hidePhoneError()

        generatedOtp = Random.nextInt(100000, 999999).toString()

        android.util.Log.d("LOCAL_OTP", generatedOtp)

        showOtpNotification(generatedOtp)

        showLoading(false)

        if (!isNotificationPermissionGranted()) {
            showNotificationPermissionLayout()
            return
        }

        switchToOtpStep(phone)
    }


    private fun resendOtp() {

        generatedOtp = Random.nextInt(100000, 999999).toString()

        android.util.Log.d("LOCAL_OTP", generatedOtp)

        showOtpNotification(generatedOtp)

        startResendTimer()

        clearOtpFields()

        otpFields[0].requestFocus()

        showKeyboard(otpFields[0])
    }

    private fun attemptVerifyOtp() {

        val enteredCode = otpFields.joinToString("") {
            it.text.toString()
        }

        if (enteredCode.length < 6) {
            showOtpError("Please enter the complete 6-digit code")
            return
        }

        showLoading(true)

        hideOtpError()

        if (enteredCode == generatedOtp) {
            removeOtpNotification()
            generatedOtp = ""

            val currentUser = auth.currentUser

            if (currentUser == null) {

                showLoading(false)

                showOtpError("Session lost. Please login again.")

                return
            }

            markPhoneVerifiedInFirestore(
                currentUser.uid,
                "+91$enteredPhone"
            )

        } else {

            showLoading(false)

            showOtpError("Incorrect OTP. Please try again.")
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "chalride_otp_channel",
                "ChalRide OTP",
                NotificationManager.IMPORTANCE_HIGH
            )

            val manager = requireContext().getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (
                ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )

            } else {

                restoreMainVerificationUi()
            }
        }
    }

    private fun isNotificationPermissionGranted(): Boolean {

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        } else {
            true
        }
    }

    private fun showNotificationPermissionLayout() {

        binding.layoutNotificationPermission.visibility = View.VISIBLE

        // Hide all main views
        binding.layoutStepIndicator.visibility = View.GONE
        binding.tvLockIcon.visibility = View.GONE
        binding.tvPhoneTitle.visibility = View.GONE
        binding.tvPhoneSubtitle.visibility = View.GONE
        binding.layoutPhoneInput.visibility = View.GONE
        binding.tvPhoneError.visibility = View.GONE
        binding.btnSendOtp.visibility = View.GONE
        binding.layoutOtpStep.visibility = View.GONE
    }

    private fun restoreMainVerificationUi() {

        binding.layoutNotificationPermission.visibility = View.GONE

        binding.layoutStepIndicator.visibility = View.VISIBLE
        binding.tvLockIcon.visibility = View.VISIBLE
        binding.tvPhoneTitle.visibility = View.VISIBLE
        binding.tvPhoneSubtitle.visibility = View.VISIBLE
        binding.layoutPhoneInput.visibility = View.VISIBLE
        binding.btnSendOtp.visibility = View.VISIBLE
    }

    private fun showOtpNotification(otp: String) {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(
            requireContext(),
            "chalride_otp_channel"
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ChalRide Verification")
            .setContentText("Your OTP is: $otp")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(requireContext())
            .notify(OTP_NOTIFICATION_ID, notification)
    }

    private fun removeOtpNotification() {

        NotificationManagerCompat
            .from(requireContext())
            .cancel(OTP_NOTIFICATION_ID)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 101) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {

                restoreMainVerificationUi()

            } else {

                showNotificationPermissionLayout()
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
            binding.progressBar.layoutParams
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
        binding.btnSendOtp.isEnabled = true
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


    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onDestroyView() {

        countDownTimer?.cancel()

        removeOtpNotification()

        generatedOtp = ""

        super.onDestroyView()

        _binding = null
    }
}