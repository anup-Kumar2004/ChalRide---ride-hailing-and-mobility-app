package com.example.chalride.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRoleSelectionBinding

class RoleSelectionFragment : Fragment() {

    private var _binding: FragmentRoleSelectionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRoleSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCardClicks()
        animateIn()
    }

    private fun setupCardClicks() {
        binding.cardRider.setOnClickListener {
            it.animate().scaleX(0.94f).scaleY(0.94f).setDuration(40).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(40).withEndAction {
                    navigateToLogin("rider")
                }.start()
            }.start()
        }

        binding.cardDriver.setOnClickListener {
            it.animate().scaleX(0.94f).scaleY(0.94f).setDuration(40).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(40).withEndAction {
                    navigateToLogin("driver")
                }.start()
            }.start()
        }
    }

    private fun navigateToLogin(role: String) {
        val bundle = Bundle().apply {
            putString("userRole", role)
        }
        findNavController().navigate(R.id.action_role_to_login, bundle)
    }

    private fun animateIn() {
        binding.ivLogo.alpha = 0f
        binding.tvAppName.alpha = 0f
        binding.tvTagline.alpha = 0f
        binding.tvSelectRole.alpha = 0f
        binding.cardRider.alpha = 0f
        binding.cardDriver.alpha = 0f

        binding.ivLogo.animate().alpha(1f).translationYBy(-8f).setDuration(200).setStartDelay(0).start()
        binding.tvAppName.animate().alpha(1f).setDuration(180).setStartDelay(60).start()
        binding.tvTagline.animate().alpha(1f).setDuration(180).setStartDelay(90).start()
        binding.tvSelectRole.animate().alpha(1f).setDuration(180).setStartDelay(120).start()
        binding.cardRider.animate().alpha(1f).translationYBy(-6f).setDuration(200).setStartDelay(150).start()
        binding.cardDriver.animate().alpha(1f).translationYBy(-6f).setDuration(200).setStartDelay(180).start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}