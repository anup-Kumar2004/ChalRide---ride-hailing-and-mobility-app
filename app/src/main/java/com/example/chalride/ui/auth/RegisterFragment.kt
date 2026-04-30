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
import com.example.chalride.databinding.FragmentRegisterBinding
import kotlinx.coroutines.launch

class RegisterFragment : Fragment() {

    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by activityViewModels()

    // Get role passed via Bundle from LoginFragment
    private val userRole: String by lazy {
        arguments?.getString("userRole") ?: "rider"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRoleBadge(userRole)
        observeAuthState()

        binding.btnRegister.setOnClickListener {
            val name = binding.etName.text.toString()
            val email = binding.etEmail.text.toString()
            val password = binding.etPassword.text.toString()
            val confirm = binding.etConfirmPassword.text.toString()

            if (validateInputs(name, email, password, confirm)) {
                viewModel.register(name, email, password, userRole)
            }
        }

        binding.tvGoToLogin.setOnClickListener {
            viewModel.resetState()
            findNavController().popBackStack()
        }
    }

    private fun setupRoleBadge(role: String) {
        if (role == "driver") {
            binding.tvRoleBadge.text = "DRIVER"
            binding.tvRoleBadge.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.driver_color)
            )
            binding.tvRoleBadge.setBackgroundResource(R.drawable.bg_role_badge_driver)
            binding.tvSubtitle.text = "Register as a driver partner"
        } else {
            binding.tvRoleBadge.text = "RIDER"
            binding.tvSubtitle.text = "Join ChalRide in seconds"
        }
    }

    private fun validateInputs(
        name: String,
        email: String,
        password: String,
        confirm: String
    ): Boolean {
        var valid = true
        if (name.trim().length < 2) {
            binding.tilName.error = "Enter your full name"
            valid = false
        } else {
            binding.tilName.error = null
        }
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Enter a valid email"
            valid = false
        } else {
            binding.tilEmail.error = null
        }
        if (password.length < 6) {
            binding.tilPassword.error = "Min 6 characters"
            valid = false
        } else {
            binding.tilPassword.error = null
        }
        if (confirm != password) {
            binding.tilConfirmPassword.error = "Passwords do not match"
            valid = false
        } else {
            binding.tilConfirmPassword.error = null
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
                            binding.btnRegister.isEnabled = false
                            binding.tvError.visibility = View.GONE
                        }
                        is AuthState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnRegister.isEnabled = true

                            val role = userRole

                            if (role == "driver") {

                                val bundle = Bundle().apply {
                                    putString("name", binding.etName.text.toString())
                                }

                                findNavController().navigate(
                                    R.id.action_register_to_driver_profile_setup,
                                    bundle
                                )
                            } else {
                                findNavController().navigate(
                                    R.id.action_register_to_rider_home
                                )
                            }

                        }
                        is AuthState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnRegister.isEnabled = true
                            binding.tvError.text = state.message
                            binding.tvError.visibility = View.VISIBLE
                        }
                        is AuthState.Idle -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnRegister.isEnabled = true
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