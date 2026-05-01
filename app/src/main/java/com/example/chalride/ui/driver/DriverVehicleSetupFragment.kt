package com.example.chalride.ui.driver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentDriverVehicleSetupBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class DriverVehicleSetupFragment : Fragment() {

    private var _binding: FragmentDriverVehicleSetupBinding? = null
    private val binding get() = _binding!!

    private var selectedVehicleType = ""   // "bike" | "auto" | "sedan" | "suv"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverVehicleSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupVehicleCards()

        binding.btnFinish.setOnClickListener {
            val model = binding.etModel.text.toString().trim()
            val plate = binding.etPlate.text.toString().trim()
            val color = binding.etColor.text.toString().trim()

            if (selectedVehicleType.isEmpty()) {
                Toast.makeText(requireContext(), "Please select your vehicle type", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (model.isEmpty()) {
                binding.etModel.error = "Please enter vehicle make & model"
                return@setOnClickListener
            }
            if (plate.isEmpty()) {
                binding.etPlate.error = "Please enter registration plate"
                return@setOnClickListener
            }
            if (color.isEmpty()) {
                binding.etColor.error = "Please enter vehicle color"
                return@setOnClickListener
            }

            binding.btnFinish.isEnabled = false
            binding.btnFinish.text = "Setting up..."

            saveVehicleData(model, plate.uppercase(), color)
        }
    }

    // ── Vehicle type card selection ──────────────────────────────────────────

    private fun setupVehicleCards() {
        val cards = listOf(
            Triple(binding.cardVehicleBike,  binding.tvLabelBike,  "bike"),
            Triple(binding.cardVehicleAuto,  binding.tvLabelAuto,  "auto"),
            Triple(binding.cardVehicleSedan, binding.tvLabelSedan, "sedan"),
            Triple(binding.cardVehicleSuv,   binding.tvLabelSuv,   "suv")
        )

        cards.forEach { (card, label, type) ->
            card.setOnClickListener {
                selectedVehicleType = type
                // Update all cards to unselected state
                cards.forEach { (c, l, _) -> setCardState(c, l, selected = false) }
                // Highlight the tapped card
                setCardState(card, label, selected = true)
            }
        }
    }

    private fun setCardState(card: LinearLayout, label: TextView, selected: Boolean) {
        if (selected) {
            card.setBackgroundResource(R.drawable.bg_vehicle_card_selected)
            label.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_primary))
        } else {
            card.setBackgroundResource(R.drawable.bg_vehicle_card_unselected)
            label.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }
    }

    // ── Save to Firestore ────────────────────────────────────────────────────

    private fun saveVehicleData(model: String, plate: String, color: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val vehicleData = mapOf(
            "vehicleType" to selectedVehicleType,
            "vehicleModel" to model,
            "vehiclePlate" to plate,
            "vehicleColor" to color,
            "profileStep" to 2
        )

        FirebaseFirestore.getInstance().collection("drivers").document(uid)
            .update(vehicleData)
            .addOnSuccessListener {
                // Navigate to driver home — pop entire setup stack
                findNavController().navigate(
                    R.id.action_driverVehicleSetup_to_driverHome
                )
            }
            .addOnFailureListener { e ->
                binding.btnFinish.isEnabled = true
                binding.btnFinish.text = "Start Driving →"
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}