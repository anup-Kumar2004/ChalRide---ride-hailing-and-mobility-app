package com.example.chalride.ui.driver

import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.example.chalride.R
import com.google.android.material.button.MaterialButton

/**
 * DriverWarningDialog
 *
 * A single reusable custom dialog that handles all three driver warning states:
 *
 *   STAGE_1  — offlineCancelCount 1–3 — informational, amber
 *   STAGE_2  — offlineCancelCount 4–5 — firm warning, orange-red
 *   SUSPENDED — count 6+ or isAccountFlagged — blocked, deep red
 *
 * Usage:
 *   DriverWarningDialog.newInstance(
 *       stage = DriverWarningDialog.Stage.STAGE_1,
 *       count = 2
 *   ).show(parentFragmentManager, "warning")
 */
class DriverWarningDialog : DialogFragment() {

    enum class Stage { STAGE_1, STAGE_2, SUSPENDED }

    companion object {
        private const val ARG_STAGE = "stage"
        private const val ARG_COUNT = "count"

        fun newInstance(stage: Stage, count: Long = 0): DriverWarningDialog {
            return DriverWarningDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_STAGE, stage.name)
                    putLong(ARG_COUNT, count)
                }
            }
        }
    }

    private val stage by lazy {
        val raw = arguments?.getString(ARG_STAGE) ?: Stage.STAGE_1.name
        try { Stage.valueOf(raw) } catch (_: Exception) { Stage.STAGE_1 }
    }
    private val count by lazy { arguments?.getLong(ARG_COUNT) ?: 0L }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), R.style.DriverWarningDialogTheme)
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_driver_warning, null)
        dialog.setContentView(view)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        setupContent(view)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        // Make dialog fill 88% of screen width with transparent background
        dialog?.window?.apply {
            val width = (resources.displayMetrics.widthPixels * 0.88).toInt()
            setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun setupContent(view: View) {
        val iconContainer  = view.findViewById<FrameLayout>(R.id.iconContainer)
        val tvSeverityChip = view.findViewById<TextView>(R.id.tvSeverityChip)
        val tvTitle        = view.findViewById<TextView>(R.id.tvTitle)
        val dividerAccent  = view.findViewById<View>(R.id.dividerAccent)
        val tvMessage      = view.findViewById<TextView>(R.id.tvMessage)
        val dotsContainer  = view.findViewById<LinearLayout>(R.id.warningDotsContainer)
        val btnAction      = view.findViewById<MaterialButton>(R.id.btnAction)

        when (stage) {
            Stage.STAGE_1 -> setupStage1(
                iconContainer, tvSeverityChip, tvTitle,
                dividerAccent, tvMessage, dotsContainer, btnAction
            )
            Stage.STAGE_2 -> setupStage2(
                iconContainer, tvSeverityChip, tvTitle,
                dividerAccent, tvMessage, dotsContainer, btnAction
            )
            Stage.SUSPENDED -> setupSuspended(
                iconContainer, tvSeverityChip, tvTitle,
                dividerAccent, tvMessage, btnAction
            )
        }

        animateEntrance(view)
    }

    // ── Stage 1 — Amber — informational ──────────────────────────────────────

    private fun setupStage1(
        iconContainer: FrameLayout,
        chip: TextView, title: TextView, divider: View,
        message: TextView, dots: LinearLayout, btn: MaterialButton
    ) {
        val accentColor = Color.parseColor("#F5A623")
        val remaining   = 3 - count

        buildIconCircle(iconContainer, accentColor, "⚠️")
        buildChip(chip, "NOTICE", accentColor)
        title.text = "Stay Connected"
        buildDivider(divider, accentColor)
        message.text =
            "Your last ride was cancelled because your device went offline " +
                    "during an active ride.\n\n" +
                    "Network issues happen — we get it. Please ensure a stable " +
                    "connection before accepting ride requests.\n\n" +
                    "You have $remaining warning(s) before your account is reviewed."
        buildWarningDots(dots, filled = count.toInt(), total = 5, color = accentColor)
        buildButton(btn, "Got It", accentColor)
        btn.setOnClickListener { dismiss() }
    }

    // ── Stage 2 — Orange-Red — firm warning ───────────────────────────────────

    private fun setupStage2(
        iconContainer: FrameLayout,
        chip: TextView, title: TextView, divider: View,
        message: TextView, dots: LinearLayout, btn: MaterialButton
    ) {
        val accentColor = Color.parseColor("#E8533A")
        val ordinal     = if (count == 4L) "4th" else "5th"

        buildIconCircle(iconContainer, accentColor, "🚨")
        buildChip(chip, "FINAL WARNING", accentColor)
        title.text = "Account at Risk"
        buildDivider(divider, accentColor)
        message.text =
            "This is your $ordinal ride cancellation due to going offline " +
                    "during an active ride.\n\n" +
                    "One more occurrence will result in your account being suspended. " +
                    "If you're experiencing repeated connectivity issues, please contact " +
                    "support before accepting new rides."
        buildWarningDots(dots, filled = count.toInt(), total = 5, color = accentColor)
        buildButton(btn, "I Understand", accentColor)
        btn.setOnClickListener { dismiss() }
    }

    // ── Suspended — Deep Red — blocked ───────────────────────────────────────

    private fun setupSuspended(
        iconContainer: FrameLayout,
        chip: TextView, title: TextView, divider: View,
        message: TextView, btn: MaterialButton
    ) {
        val accentColor = Color.parseColor("#C0392B")

        buildIconCircle(iconContainer, accentColor, "🚫")
        buildChip(chip, "ACCOUNT SUSPENDED", accentColor)
        title.text = "Access Restricted"
        buildDivider(divider, accentColor)
        message.text =
            "Your driver account has been suspended due to repeated ride " +
                    "cancellations caused by going offline during active rides.\n\n" +
                    "You will not be able to accept ride requests until this is resolved.\n\n" +
                    "Contact our support team to reactivate your account:\n" +
                    "support@chalride.com"
        buildButton(btn, "Contact Support", accentColor)
        btn.setOnClickListener { dismiss() }
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private fun buildIconCircle(container: FrameLayout, color: Int, emoji: String) {
        container.removeAllViews()

        // Outer glow ring — pulsing
        val glowRing = View(requireContext()).apply {
            val size = dpToPx(80)
            layoutParams = FrameLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
            }
        }

        // Inner circle with emoji
        val innerCircle = TextView(requireContext()).apply {
            val size = dpToPx(64)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(255,
                    (Color.red(color) * 0.15).toInt(),
                    (Color.green(color) * 0.15).toInt(),
                    (Color.blue(color) * 0.15).toInt()
                ))
                setStroke(dpToPx(2), color)
            }
            text = emoji
            textSize = 26f
            gravity = android.view.Gravity.CENTER
        }

        container.addView(glowRing)
        container.addView(innerCircle)

        // Pulse animation on glow ring
        ValueAnimator.ofFloat(0.85f, 1.15f).apply {
            duration = 1200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                glowRing.scaleX = scale
                glowRing.scaleY = scale
                glowRing.alpha  = 1.2f - scale * 0.4f
            }
            start()
        }
    }

    private fun buildChip(chip: TextView, label: String, color: Int) {
        chip.text = label
        chip.setTextColor(color)
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(20).toFloat()
            setColor(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(1, Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)))
        }
    }

    private fun buildDivider(divider: View, color: Int) {
        divider.setBackgroundColor(color)
    }

    /**
     * Builds a row of 5 dots showing how many warnings the driver has used.
     * Filled dots = warnings used, empty dots = remaining.
     */
    private fun buildWarningDots(
        container: LinearLayout,
        filled: Int, total: Int, color: Int
    ) {
        container.visibility = View.VISIBLE
        container.removeAllViews()

        val dotSize    = dpToPx(10)
        val dotSpacing = dpToPx(8)

        for (i in 1..total) {
            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = if (i < total) dotSpacing else 0
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (i <= filled) {
                        setColor(color)
                    } else {
                        setColor(Color.TRANSPARENT)
                        setStroke(dpToPx(1),
                            Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
                        )
                    }
                }
            }
            container.addView(dot)
        }
    }

    private fun buildButton(btn: MaterialButton, label: String, color: Int) {
        btn.text = label
        btn.setBackgroundColor(color)
        btn.setTextColor(Color.WHITE)
    }

    // ── Entrance animation ────────────────────────────────────────────────────

    private fun animateEntrance(view: View) {
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        view.alpha  = 0f
        view.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}