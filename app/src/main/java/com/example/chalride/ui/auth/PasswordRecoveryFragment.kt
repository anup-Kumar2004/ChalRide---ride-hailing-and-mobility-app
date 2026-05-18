package com.example.chalride.ui.auth

import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentPasswordRecoveryBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import android.os.CountDownTimer


class PasswordRecoveryFragment : Fragment() {

    private var _binding: FragmentPasswordRecoveryBinding? = null
    private val binding get() = _binding!!

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private var sentToEmail: String = ""
    private var pulseAnimator: AnimatorSet? = null
    private var resendCooldownTimer: CountDownTimer? = null  // ← ADD THIS LINE

    // ─────────────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPasswordRecoveryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        startPulseAnimation()
        setupClickListeners()
        animateInputStateIn()
    }

    // ── Pulse animation ───────────────────────────────────────────────────

    private fun startPulseAnimation() {
        val animator = AnimatorInflater.loadAnimator(
            requireContext(), R.animator.anim_pulse_ring
        ) as? AnimatorSet ?: return

        animator.setTarget(binding.viewPulseRing)
        animator.start()
        pulseAnimator = animator
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    // ── Click listeners ───────────────────────────────────────────────────

    private fun setupClickListeners() {


        binding.btnSendReset.setOnClickListener {
            val email = binding.etEmail.text?.toString()?.trim() ?: ""
            if (validateEmail(email)) {
                checkEmailThenSendReset(email)
            }
        }

        binding.tvBackToLogin.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnReturnToLogin.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.tvResendLink.setOnClickListener {
            if (sentToEmail.isNotEmpty()) {
                resendResetEmail(sentToEmail)
            }
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    private fun validateEmail(email: String): Boolean {
        return if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email address"
            false
        } else {
            binding.tilEmail.error = null
            hideError()
            true
        }
    }

    // ── Firebase logic ────────────────────────────────────────────────────

    /**
     * STEP 1 — Check Firestore.
     *
     * Queries both `riders` and `drivers` collections for a document whose
     * `email` field matches the input. We run both queries in parallel using
     * Tasks.whenAllComplete so the total wait time equals the slower of the
     * two queries, not the sum.
     *
     * Outcomes:
     *   • Found in riders or drivers → proceed to sendPasswordReset()
     *   • Found in neither           → show "No account found" error
     *   • Network / Firestore error  → show connection error
     */
    private fun checkEmailThenSendReset(email: String) {
        setLoadingState(true)

        val ridersQuery  = firestore.collection("riders")
            .whereEqualTo("email", email)
            .limit(1)
            .get()

        val driversQuery = firestore.collection("drivers")
            .whereEqualTo("email", email)
            .limit(1)
            .get()

        // Run both Firestore queries in parallel
        com.google.android.gms.tasks.Tasks.whenAllComplete(ridersQuery, driversQuery)
            .addOnCompleteListener { combinedTask ->
                if (_binding == null) return@addOnCompleteListener

                if (!combinedTask.isSuccessful) {
                    // The Tasks.whenAllComplete wrapper itself failed — very unusual
                    setLoadingState(false)
                    showError("Connection error. Please try again.")
                    return@addOnCompleteListener
                }

                val riderDocs  = ridersQuery.result
                val driverDocs = driversQuery.result

                val ridersOk  = ridersQuery.isSuccessful  && riderDocs  != null
                val driversOk = driversQuery.isSuccessful && driverDocs != null

                when {
                    // At least one query failed due to network / permissions
                    !ridersOk && !driversOk -> {
                        setLoadingState(false)
                        showError("Connection error. Please try again.")
                    }

                    // Email found in riders or drivers → send the reset link
                    (ridersOk  && !riderDocs!!.isEmpty) ||
                            (driversOk && !driverDocs!!.isEmpty) -> {
                        // STEP 2 happens inside here
                        sendPasswordReset(email)
                    }

                    // Email not found in either collection
                    else -> {
                        setLoadingState(false)
                        showEmailNotFoundError()
                    }
                }
            }
    }

    /**
     * STEP 2 — Send the reset email via Firebase Auth.
     * Only called after Firestore confirms the email is registered.
     * Sends exactly ONE email (the double-send bug is fixed here).
     */
    private fun sendPasswordReset(email: String) {
        // Loading state already active from checkEmailThenSendReset — keep it on

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (_binding == null) return@addOnCompleteListener
                setLoadingState(false)

                if (task.isSuccessful) {
                    sentToEmail = email
                    transitionToSuccessState(email)
                } else {
                    val message = when {
                        task.exception?.message?.contains("network", ignoreCase = true) == true ->
                            "Connection error. Please try again."
                        else ->
                            "Could not send reset link. Please try again."
                    }
                    showError(message)
                }
            }
    }


    /**
     * Re-sends the reset email from the success state.
     * After sending, starts a fresh 30-second cooldown before the
     * resend link is shown again.
     */
    private fun resendResetEmail(email: String) {
        // Hide resend link immediately to prevent double-taps
        binding.tvResendLink.visibility = View.GONE
        binding.tvResendCooldown.text = "Sending…"
        binding.tvResendCooldown.setTextColor(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.brand_primary)
        )
        binding.tvResendCooldown.visibility = View.VISIBLE

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (_binding == null) return@addOnCompleteListener
                if (task.isSuccessful) {
                    // Start a fresh 30-second cooldown after resend
                    startResendCooldown()
                } else {
                    // Resend failed — show the link again immediately so user can retry
                    binding.tvResendCooldown.visibility = View.GONE
                    binding.tvResendLink.visibility = View.VISIBLE
                    binding.tvResendLink.text = "Didn't receive it? Resend"
                }
            }
    }

    /**
     * Starts a 30-second countdown during which the resend link is hidden.
     * The countdown text ticks down every second in @color/text_hint.
     * When it expires, the text_hint countdown is replaced by the
     * brand_primary coloured resend link.
     */
    private fun startResendCooldown() {
        // Cancel any existing timer (e.g. user navigated away and came back)
        resendCooldownTimer?.cancel()

        // Show the countdown label, hide the resend link
        binding.tvResendLink.visibility = View.GONE
        binding.tvResendCooldown.setTextColor(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_hint)
        )
        binding.tvResendCooldown.visibility = View.VISIBLE

        resendCooldownTimer = object : CountDownTimer(30_000L, 1_000L) {

            override fun onTick(millisUntilFinished: Long) {
                if (_binding == null) return
                val secondsLeft = (millisUntilFinished / 1_000L).toInt() + 1
                binding.tvResendCooldown.text = "Resend available in ${secondsLeft}s"
            }

            override fun onFinish() {
                if (_binding == null) return
                // Cooldown over — swap countdown for the resend link
                binding.tvResendCooldown.visibility = View.GONE
                binding.tvResendLink.text = "Didn't receive it? Resend"
                binding.tvResendLink.visibility = View.VISIBLE

                // Gentle fade-in so it doesn't just snap into place
                binding.tvResendLink.alpha = 0f
                binding.tvResendLink.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
        }.start()
    }

    // ── Error display ─────────────────────────────────────────────────────

    /**
     * "No account found" error — professional, concise, brand-consistent.
     * Sets the TextInputLayout stroke to error red + shows the inline message.
     */
    private fun showEmailNotFoundError() {
        // Highlight the input field to draw the eye to it
        binding.tilEmail.error = "No account found with this email"
        // Inline message below the field with a hint toward resolution
        showError("Try a different address or sign up for a new account.")
    }

    // ── UI state helpers ──────────────────────────────────────────────────

    private fun setLoadingState(loading: Boolean) {
        binding.btnSendReset.isEnabled = !loading
        binding.btnSendReset.alpha    = if (loading) 0.5f else 1f
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.etEmail.isEnabled     = !loading
        if (loading) hideError()
    }

    private fun showError(message: String) {
        if (message.isNotEmpty()) {
            binding.tvError.text = message
            binding.tvError.visibility = View.VISIBLE
        }
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }

    // ── State transitions with animation ─────────────────────────────────

    /**
     * Staggered fade-in for the input state elements on first entry.
     */
    private fun animateInputStateIn() {
        val elements = listOf(
            binding.frameIcon,
            binding.dividerLine,
            binding.tvHeadline,
            binding.tvSubHeadline,
            binding.tilEmail,
            binding.btnSendReset,
            binding.tvBackToLogin
        )
        elements.forEach { it.alpha = 0f; it.translationY = 24f }

        elements.forEachIndexed { index, view ->
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280)
                .setStartDelay((index * 60).toLong())
                .start()
        }
    }

    /**
     * Cross-fades from the input state to the success state.
     * The pulse animation is stopped once we're in success state.
     */
    private fun transitionToSuccessState(email: String) {
        // Populate success state before showing it
        binding.tvSentToEmail.text = email

        // Fade out input layout
        binding.layoutInput.animate()
            .alpha(0f)
            .translationY(-20f)
            .setDuration(250)
            .withEndAction {
                if (_binding == null) return@withEndAction
                binding.layoutInput.visibility = View.GONE

                // Stop the pulse — we're in success state now
                stopPulseAnimation()
                binding.viewPulseRing.visibility = View.GONE

                // Reveal success layout
                binding.layoutSuccess.visibility = View.VISIBLE

                // Staggered entrance for success elements
                val successElements = listOf(
                    binding.ivSuccessIcon,
                    binding.tvSuccessBadge,
                    binding.tvSuccessHeadline,
                    binding.tvSentToEmail,
                    binding.tvSuccessBody,
                    binding.dividerSuccess,
                    binding.btnReturnToLogin,
                    binding.resendArea          // ← animate the container, not individual views inside it
                )
                successElements.forEach { it.alpha = 0f; it.translationY = 20f }

                binding.layoutSuccess.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()

                successElements.forEachIndexed { index, view ->
                    view.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(280)
                        .setStartDelay((index * 55).toLong())
                        .start()
                }
                startResendCooldown()
            }
            .start()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onDestroyView() {
        stopPulseAnimation()
        resendCooldownTimer?.cancel()   // ← ADD THIS LINE
        resendCooldownTimer = null      // ← ADD THIS LINE
        super.onDestroyView()
        _binding = null
    }
}