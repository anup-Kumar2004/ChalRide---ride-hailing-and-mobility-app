package com.example.chalride.ui.driver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentDriverProfileBinding

class DriverProfileFragment : Fragment() {

    private var _binding: FragmentDriverProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener {
            findNavController().navigate(R.id.action_driverProfile_to_driverHome)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}