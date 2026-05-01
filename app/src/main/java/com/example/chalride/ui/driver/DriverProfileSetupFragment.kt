package com.example.chalride.ui.driver

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.chalride.R
import com.example.chalride.databinding.FragmentDriverProfileSetupBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.IOException



class DriverProfileSetupFragment : Fragment() {

    private var _binding: FragmentDriverProfileSetupBinding? = null
    private val binding get() = _binding!!

    private var selectedPhotoUri: Uri? = null

    // ── Photo picker launcher ────────────────────────────────────────────────
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            selectedPhotoUri = uri
            binding.ivDriverPhoto.setImageURI(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverProfileSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ If coming from midway login → skip to vehicle setup
        val goToVehicleStep = arguments?.getBoolean("goToVehicleStep") ?: false

        if (goToVehicleStep) {
            findNavController().navigate(
                R.id.action_driverProfileSetup_to_driverVehicleSetup
            )
            return
        }

        val nameFromRegister = arguments?.getString("name")
        if (!nameFromRegister.isNullOrEmpty()) {
            // Came fresh from RegisterFragment — use bundle name
            binding.etName.setText(nameFromRegister)
        } else {
            // App restarted — fetch name from Firestore
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                FirebaseFirestore.getInstance()
                    .collection("drivers")
                    .document(uid)
                    .get()
                    .addOnSuccessListener { doc ->
                        val savedName = doc.getString("name") ?: ""
                        if (savedName.isNotEmpty()) {
                            binding.etName.setText(savedName)
                        }
                    }
            }
        }

        // Pre-fill phone from Firebase Auth if available
        val currentUser = FirebaseAuth.getInstance().currentUser
        currentUser?.phoneNumber?.let { phone ->
            if (phone.isNotEmpty()) binding.etPhone.setText(phone)
        }

        // Photo picker
        binding.framePhotoContainer.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            photoPickerLauncher.launch(intent)
        }

        // Continue button
        binding.btnNext.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val phone = binding.etPhone.text.toString().trim()

            if (name.isEmpty()) {
                binding.etName.error = "Please enter your name"
                return@setOnClickListener
            }
            if (phone.isEmpty()) {
                binding.etPhone.error = "Please enter your phone number"
                return@setOnClickListener
            }

            binding.btnNext.isEnabled = false
            binding.btnNext.text = "Saving..."

            saveProfileAndNavigate(name, phone)
        }
    }

    private fun saveProfileAndNavigate(name: String, phone: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        if (selectedPhotoUri != null) {
            uploadToCloudinary(uid, name, phone, selectedPhotoUri!!)
        } else {
            saveToFirestore(uid, name, phone, photoUrl = null)
        }
    }

    private fun uploadToCloudinary(uid: String, name: String, phone: String, uri: Uri) {

        val cloudName = "dkbrlandj"
        val uploadPreset = "chalride_driver_upload"

        val bytes = requireContext().contentResolver.openInputStream(uri)?.use {
            it.readBytes()
        } ?: return

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "$uid.jpg",
                RequestBody.create("image/*".toMediaTypeOrNull(), bytes))
            .addFormDataPart("upload_preset", uploadPreset)
            .build()

        val request = Request.Builder()
            .url("https://api.cloudinary.com/v1_1/$cloudName/image/upload")
            .post(requestBody)
            .build()

        val client = OkHttpClient()

        requireActivity().runOnUiThread {
            binding.progressUpload.visibility = View.VISIBLE
            binding.tvUploadStatus.visibility = View.VISIBLE
            binding.tvUploadStatus.text = "Uploading image..."
            binding.btnNext.isEnabled = false
        }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                requireActivity().runOnUiThread {
                    binding.progressUpload.visibility = View.GONE
                    binding.tvUploadStatus.visibility = View.GONE

                    binding.btnNext.isEnabled = true
                    binding.btnNext.text = "Continue →"

                    Toast.makeText(requireContext(), "Image upload failed", Toast.LENGTH_SHORT).show()

                    saveToFirestore(uid, name, phone, photoUrl = null)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "")

                val imageUrl = json.optString("secure_url", null)

                requireActivity().runOnUiThread {
                    binding.progressUpload.visibility = View.GONE
                    binding.tvUploadStatus.visibility = View.GONE

                    saveToFirestore(uid, name, phone, photoUrl = imageUrl)
                }
            }
        })
    }

    private fun saveToFirestore(uid: String, name: String, phone: String, photoUrl: String?) {

        val docRef = FirebaseFirestore.getInstance()
            .collection("drivers")
            .document(uid)

        // Basic fields (always update)
        val baseData = mutableMapOf<String, Any>(
            "name" to name,
            "phone" to phone,
            "role" to "driver",
            "profileStep" to 1
        )

        if (photoUrl != null) {
            baseData["photoUrl"] = photoUrl
        }

        docRef.get().addOnSuccessListener { snapshot ->

            val updateData = mutableMapOf<String, Any>()
            updateData.putAll(baseData)

            // ✅ Set defaults ONLY if they don't exist
            if (!snapshot.contains("isOnline")) {
                updateData["isOnline"] = false
            }

            if (!snapshot.contains("isAvailable")) {
                updateData["isAvailable"] = false
            }

            if (!snapshot.contains("lat")) {
                updateData["lat"] = 0.0
            }

            if (!snapshot.contains("lng")) {
                updateData["lng"] = 0.0
            }

            if (!snapshot.contains("geohash")) {
                updateData["geohash"] = ""
            }

            if (!snapshot.contains("totalTrips")) {
                updateData["totalTrips"] = 0
            }

            if (!snapshot.contains("earnings")) {
                updateData["earnings"] = 0
            }

            // ✅ Safe update (NO overwrite)
            docRef.set(updateData, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener {
                    findNavController().navigate(
                        R.id.action_driverProfileSetup_to_driverVehicleSetup
                    )
                }
                .addOnFailureListener { e ->
                    binding.btnNext.isEnabled = true
                    binding.btnNext.text = "Continue →"
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }

        }.addOnFailureListener { e ->
            binding.btnNext.isEnabled = true
            binding.btnNext.text = "Continue →"
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}