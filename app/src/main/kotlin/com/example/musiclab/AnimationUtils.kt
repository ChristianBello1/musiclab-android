package com.example.musiclab

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Utility per animazioni fluide nell'app
 */
object AnimationUtils {

    // Durate standard
    const val DURATION_SHORT = 200L
    const val DURATION_MEDIUM = 300L

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
     * Slide in da basso
     */
    fun slideInFromBottom(view: View, duration: Long = DURATION_MEDIUM) {
        view.visibility = View.VISIBLE
        view.translationY = view.height.toFloat()
        view.animate()
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
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
    fun overrideActivityTransition(activity: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                android.app.Activity.OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        }
    }

    /**
     * Slide tra activity (per PlayerActivity)
     */
    fun slideUpActivityTransition(activity: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                android.app.Activity.OVERRIDE_TRANSITION_OPEN,
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right
            )
        }
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