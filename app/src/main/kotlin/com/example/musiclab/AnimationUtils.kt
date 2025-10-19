package com.example.musiclab

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.animation.doOnEnd

/**
 * Utility per animazioni fluide nell'app
 */
object AnimationUtils {

    // Durate standard
    const val DURATION_SHORT = 200L
    const val DURATION_MEDIUM = 300L
    const val DURATION_LONG = 500L

    /**
     * Fade in graduale di una view
     */
    fun fadeIn(view: View, duration: Long = DURATION_MEDIUM) {
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Fade out graduale di una view
     */
    fun fadeOut(view: View, duration: Long = DURATION_MEDIUM, onEnd: (() -> Unit)? = null) {
        view.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Scala una view con effetto "pop"
     */
    fun scaleButton(view: View) {
        view.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(100L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100L)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * Slide in da destra
     */
    fun slideInFromRight(view: View, duration: Long = DURATION_MEDIUM) {
        view.visibility = View.VISIBLE
        view.translationX = view.width.toFloat()
        view.animate()
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Slide out verso destra
     */
    fun slideOutToRight(view: View, duration: Long = DURATION_MEDIUM, onEnd: (() -> Unit)? = null) {
        view.animate()
            .translationX(view.width.toFloat())
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Slide in da basso
     */
    fun slideInFromBottom(view: View, duration: Long = DURATION_MEDIUM) {
        view.visibility = View.VISIBLE
        view.translationY = view.height.toFloat()
        view.animate()
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Slide out verso basso
     */
    fun slideOutToBottom(view: View, duration: Long = DURATION_MEDIUM, onEnd: (() -> Unit)? = null) {
        view.animate()
            .translationY(view.height.toFloat())
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Rotazione continua (per icone loading)
     */
    fun startRotation(view: View) {
        val animator = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f)
        animator.duration = 1000L
        animator.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.start()
        view.tag = animator // Salva per poterlo fermare dopo
    }

    /**
     * Ferma la rotazione
     */
    fun stopRotation(view: View) {
        (view.tag as? ObjectAnimator)?.cancel()
        view.rotation = 0f
    }

    /**
     * Pulse animation (per indicare qualcosa di attivo)
     */
    fun pulse(view: View, repeatCount: Int = ValueAnimator.INFINITE) {
        val scaleUp = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.1f).apply {
            duration = 600L
            repeatMode = ValueAnimator.REVERSE
            this.repeatCount = repeatCount
            interpolator = AccelerateDecelerateInterpolator()
        }

        val scaleUpY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.1f).apply {
            duration = 600L
            repeatMode = ValueAnimator.REVERSE
            this.repeatCount = repeatCount
            interpolator = AccelerateDecelerateInterpolator()
        }

        scaleUp.start()
        scaleUpY.start()

        view.tag = Pair(scaleUp, scaleUpY) // Salva per poterli fermare
    }

    /**
     * Ferma la pulse animation
     */
    fun stopPulse(view: View) {
        val animators = view.tag as? Pair<*, *>
        (animators?.first as? ObjectAnimator)?.cancel()
        (animators?.second as? ObjectAnimator)?.cancel()
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /**
     * Transizione fluida tra activity
     * Da chiamare dopo startActivity()
     */
    fun overrideActivityTransition(activity: Activity) {
        activity.overridePendingTransition(
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
    }

    /**
     * Slide tra activity (per PlayerActivity)
     */
    fun slideUpActivityTransition(activity: Activity) {
        activity.overridePendingTransition(
            android.R.anim.slide_in_left,
            android.R.anim.slide_out_right
        )
    }

    /**
     * Animazione seekbar smooth
     */
    fun animateSeekBar(
        seekBar: android.widget.SeekBar,
        targetProgress: Int,
        duration: Long = 300L
    ) {
        val animator = ValueAnimator.ofInt(seekBar.progress, targetProgress)
        animator.duration = duration
        animator.addUpdateListener { animation ->
            seekBar.progress = animation.animatedValue as Int
        }
        animator.start()
    }

    /**
     * Shake animation (per errori)
     */
    fun shake(view: View) {
        val animator = ObjectAnimator.ofFloat(
            view,
            "translationX",
            0f, 25f, -25f, 25f, -25f, 15f, -15f, 6f, -6f, 0f
        )
        animator.duration = 500L
        animator.start()
    }
}