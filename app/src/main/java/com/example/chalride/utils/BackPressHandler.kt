package com.example.chalride.utils

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment

object BackPressHandler {

    fun enableDoubleBackToExit(
        fragment: Fragment,
        message: String = "Press back again to exit"
    ) {

        var doubleBackPressed = false

        fragment.requireActivity()
            .onBackPressedDispatcher
            .addCallback(
                fragment.viewLifecycleOwner,
                object : OnBackPressedCallback(true) {

                    override fun handleOnBackPressed() {

                        if (doubleBackPressed) {
                            fragment.requireActivity().finish()
                            return
                        }

                        doubleBackPressed = true

                        Toast.makeText(
                            fragment.requireContext(),
                            message,
                            Toast.LENGTH_SHORT
                        ).show()

                        Handler(Looper.getMainLooper()).postDelayed({
                            doubleBackPressed = false
                        }, 2000)
                    }
                }
            )
    }
}