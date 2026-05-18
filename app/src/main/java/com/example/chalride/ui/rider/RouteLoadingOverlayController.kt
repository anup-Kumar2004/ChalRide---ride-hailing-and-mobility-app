package com.example.chalride.ui.rider

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * FILE: app/src/main/java/com/example/chalride/ui/rider/RouteLoadingOverlayController.kt
 *
 * Manages the full lifecycle of the route loading overlay:
 *   - shows / hides the overlay
 *   - runs the car translation animation (right → left → repeat)
 *   - runs the pulsing dot animation
 *   - cycles the primary text message
 *   - enforces a minimum display time (MIN_DISPLAY_MS) so the
 *     overlay never flashes for just a few frames on fast responses
 *
 * Usage in DestinationSearchFragment:
 *
 *   private lateinit var routeLoadingController: RouteLoadingOverlayController
 *
 *   // In onViewCreated, after binding is ready:
 *   routeLoadingController = RouteLoadingOverlayController(
 *       overlay        = binding.routeLoadingOverlay,
 *       car            = binding.ivAnimatedCar,
 *       container      = binding.carAnimContainer,
 *       primaryLabel   = binding.tvRouteLoadingPrimary,
 *       secondaryLabel = binding.tvRouteLoadingSecondary,
 *       dot1           = binding.dot1,
 *       dot2           = binding.dot2,
 *       dot3           = binding.dot3,
 *       lifecycleScope = viewLifecycleOwner.lifecycleScope
 *   )
 *
 *   // Show:   routeLoadingController.show()
 *   // Hide:   routeLoadingController.hide()    ← respects MIN_DISPLAY_MS
 *   // Cancel: routeLoadingController.cancel()  ← immediate, used on error
 */
class RouteLoadingOverlayController(
    private val overlay: View,
    private val car: ImageView,
    private val container: View,
    private val primaryLabel: TextView,
    private val secondaryLabel: TextView,
    private val dot1: View,
    private val dot2: View,
    private val dot3: View,
    private val lifecycleScope: LifecycleCoroutineScope
) {

    companion object {
        /** Overlay will always display for at least this long.
         *  Prevents a jarring flash when the API responds instantly. */
        private const val MIN_DISPLAY_MS = 700L
    }

    // ── State ──────────────────────────────────────────────────────────────
    private var showStartMs = 0L
    private var isShowing = false

    // ── Animators ──────────────────────────────────────────────────────────
    private var carAnimator: ValueAnimator? = null
    private var dotJob: Job? = null
    private var textJob: Job? = null

    // ── Text rotation messages ─────────────────────────────────────────────
    private val primaryMessages = listOf(
        "Plotting your route...",
        "Calculating the best path...",
        "Almost there..."
    )
    private val secondaryMessages = listOf(
        "Finding the fastest path for you",
        "Checking road conditions",
        "Hang tight, nearly done"
    )

    // ──────────────────────────────────────────────────────────────────────

    fun show() {
        if (isShowing) return
        isShowing = true
        showStartMs = System.currentTimeMillis()

        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        startCarAnimation()
        startDotAnimation()
        startTextCycling()
    }

    fun hide(onHidden: () -> Unit) {
        if (!isShowing) {
            onHidden()
            return
        }
        lifecycleScope.launch {
            dismissOverlay(onHidden)
        }
    }

    /**
     * Immediate cancel — used on route errors so the UI resets right away
     * without waiting for the minimum display time.
     */
    fun cancel() {
        stopInternalAnimations()
        overlay.animate().cancel()
        overlay.visibility = View.GONE
        overlay.alpha = 1f
        isShowing = false
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun dismissOverlay(onHidden: () -> Unit) {
        stopInternalAnimations()
        overlay.animate()
            .alpha(0f)
            .setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                overlay.visibility = View.GONE
                overlay.alpha = 1f
                isShowing = false
                onHidden()
            }
            .start()
    }

    private fun stopInternalAnimations() {
        carAnimator?.cancel()
        carAnimator = null
        dotJob?.cancel()
        dotJob = null
        textJob?.cancel()
        textJob = null
    }

    // ── Car animation ─────────────────────────────────────────────────────


    private fun startCarAnimation() {
        container.post {
            val containerW = container.width.toFloat()
            val carW = car.width.toFloat()

            // Start just off the LEFT edge, end just off the RIGHT edge
            val startX = -carW
            val endX = containerW

            carAnimator = ValueAnimator.ofFloat(startX, endX).apply {
                duration = 3322L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART

                addUpdateListener { car.translationX = it.animatedValue as Float }

                // On each cycle start: reset car to left edge instantly
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationRepeat(animation: Animator) {
                        car.translationX = startX
                    }
                })
            }
            car.translationX = startX
            carAnimator?.start()
        }
    }

    // ── Dot animation ─────────────────────────────────────────────────────

    private fun startDotAnimation() {
        val dots = listOf(dot1, dot2, dot3)
        dots.forEach { it.alpha = 0.25f; it.scaleX = 0.6f; it.scaleY = 0.6f }

        dotJob = lifecycleScope.launch {
            var index = 0
            while (isActive) {
                val activeDot = dots[index % 3]
                val prevDot = dots[(index + 2) % 3]

                // Dim the previous dot
                prevDot.animate().alpha(0.25f).scaleX(0.6f).scaleY(0.6f).setDuration(200).start()

                // Pulse the active dot
                activeDot.animate().alpha(1f).scaleX(1.2f).scaleY(1.2f).setDuration(300).start()

                delay(400)
                index++
            }
        }
    }

    // ── Text cycling ──────────────────────────────────────────────────────

    private fun startTextCycling() {
        textJob = lifecycleScope.launch {
            // Show first message immediately (already set in XML)
            delay(2500)
            var messageIndex = 1
            while (isActive && messageIndex < primaryMessages.size) {
                // Fade out
                primaryLabel.animate().alpha(0f).setDuration(300).start()
                secondaryLabel.animate().alpha(0f).setDuration(300).start()
                delay(320)

                primaryLabel.text = primaryMessages[messageIndex]
                secondaryLabel.text = secondaryMessages[messageIndex]

                // Fade in
                primaryLabel.animate().alpha(1f).setDuration(300).start()
                secondaryLabel.animate().alpha(1f).setDuration(300).start()

                messageIndex++
                delay(2500)
            }
        }
    }
}