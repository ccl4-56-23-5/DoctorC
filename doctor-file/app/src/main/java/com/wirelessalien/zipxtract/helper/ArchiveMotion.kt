/* Copyright (C) 2026. GPL-3.0-or-later; see License. */
package com.wirelessalien.zipxtract.helper

import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.*
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialSharedAxis
import com.wirelessalien.zipxtract.R

object ArchiveMotion {
    private fun enabled(): Boolean = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    fun install(activity: FragmentActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentPreCreated(fm: FragmentManager, f: Fragment, state: Bundle?) {
                if (f !is DialogFragment && enabled()) {
                    f.enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true).apply { duration = 240 }
                    f.exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true).apply { duration = 200 }
                    f.reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false).apply { duration = 240 }
                    f.returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false).apply { duration = 200 }
                }
            }
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, view: View, state: Bundle?) {
                if (f is DialogFragment && enabled()) f.dialog?.window?.setWindowAnimations(R.style.ArchiveDialogAnimation)
            }
        }, true)
    }
    fun expand(view: View) {
        if (enabled() && view is ViewGroup) TransitionManager.beginDelayedTransition(view, AutoTransition().apply { duration = 180 })
    }
}
