package com.example.chalride.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentLoginBinding
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by activityViewModels()

    // Get role passed from RoleSelectionFragment via Bundle
    private val userRole: String by lazy {
        arguments?.getString("userRole") ?: "rider"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.resetState()

        setupRoleBadge(userRole)
        observeAuthState()

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString()
            val password = binding.etPassword.text.toString()
            if (validateInputs(email, password)) {
                viewModel.login(email, password, userRole)  // pass role here
            }
        }

        binding.tvGoToRegister.setOnClickListener {
            viewModel.resetState()
            // Pass role to RegisterFragment
            val bundle = Bundle().apply {
                putString("userRole", userRole)
            }
            findNavController().navigate(R.id.action_login_to_register, bundle)
        }

        binding.tvForgotPassword.setOnClickListener {
            // Phase 3 — password reset
        }
    }

    private fun setupRoleBadge(role: String) {
        if (role == "driver") {
            binding.tvRoleBadge.text = "DRIVER"
            binding.tvRoleBadge.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.driver_color)
            )
            binding.tvRoleBadge.setBackgroundResource(R.drawable.bg_role_badge_driver)
        } else {
            binding.tvRoleBadge.text = "RIDER"
        }
    }

    private fun validateInputs(email: String, password: String): Boolean {
        var valid = true
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email"
            valid = false
        } else {
            binding.tilEmail.error = null
        }
        if (password.length < 6) {
            binding.tilPassword.error = "Password must be at least 6 characters"
            valid = false
        } else {
            binding.tilPassword.error = null
        }
        return valid
    }

    private fun observeAuthState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authState.collect { state ->
                    when (state) {
                        is AuthState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.btnLogin.isEnabled = false
                            binding.tvError.visibility = View.GONE
                        }
                        is AuthState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnLogin.isEnabled = true
                            if (state.user.role == "rider") {
                                findNavController().navigate(R.id.action_login_to_rider_home)
                            } else {
                                findNavController().navigate(R.id.action_login_to_driver_home)
                            }
                        }
                        is AuthState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnLogin.isEnabled = true
                            binding.tvError.text = state.message
                            binding.tvError.visibility = View.VISIBLE
                        }
                        is AuthState.Idle -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnLogin.isEnabled = true
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}