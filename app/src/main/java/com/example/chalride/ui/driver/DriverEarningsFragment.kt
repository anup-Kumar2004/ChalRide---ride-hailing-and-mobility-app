package com.example.chalride.ui.driver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.chalride.R
import com.example.chalride.databinding.FragmentDriverEarningsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class DriverEarningsFragment : Fragment() {

    private var _binding: FragmentDriverEarningsBinding? = null
    private val binding get() = _binding!!

    private val db = FirebaseFirestore.getInstance()
    private val uid by lazy { FirebaseAuth.getInstance().currentUser?.uid }

    private lateinit var tripAdapter: TripEarningsAdapter

    // ← Store registrations so we can cancel them on destroy
    private var earningsListener: ListenerRegistration? = null
    private var tripsListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverEarningsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        loadDriverEarnings()
        loadTripHistory()

        binding.btnBack.setOnClickListener {
            android.util.Log.d("EarningsBack", "Back button tapped!")
            findNavController().popBackStack()
        }
    }

    private fun setupRecyclerView() {
        tripAdapter = TripEarningsAdapter()
        binding.rvTrips.apply {
            adapter = tripAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun loadDriverEarnings() {
        val driverUid = uid ?: return
        earningsListener = db.collection("drivers").document(driverUid)
            .addSnapshotListener { snapshot, error ->
                if (_binding == null) return@addSnapshotListener  // view already destroyed
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val totalEarnings = snapshot.getLong("earnings") ?: 0L
                binding.tvTotalEarnings.text = "₹$totalEarnings"
            }
    }

    private fun loadTripHistory() {
        val driverUid = uid ?: return
        showLoading(true)

        tripsListener = db.collection("rideRequests")
            .whereEqualTo("driverId", driverUid)
            .addSnapshotListener { snapshots, error ->
                if (_binding == null) return@addSnapshotListener  // view already destroyed
                showLoading(false)

                if (error != null) {
                    android.util.Log.e("DriverEarnings", "Error loading trips: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshots == null || snapshots.isEmpty) {
                    showEmptyState(true)
                    return@addSnapshotListener
                }

                showEmptyState(false)

                val trips = snapshots.documents
                    .mapNotNull { doc ->
                        TripEarningsItem(
                            rideId      = doc.id,
                            status      = doc.getString("status") ?: "unknown",
                            pickupAddr  = doc.getString("pickupAddress") ?: "—",
                            destAddr    = doc.getString("destAddress") ?: "—",
                            riderName   = doc.getString("riderName") ?: "Rider",
                            vehicleType = doc.getString("vehicleType") ?: "bike",
                            fare        = doc.getLong("estimatedFare") ?: 0L,
                            startedAt   = doc.getLong("startedAt")
                                ?: doc.getLong("assignedAt")
                                ?: doc.getLong("createdAt")
                                ?: 0L,
                            completedAt = doc.getLong("completedAt") ?: 0L,
                        )
                    }
                    .sortedByDescending { it.startedAt }

                val completedCount = trips.count { it.status == "completed" }
                val cancelledCount = trips.count { it.status == "cancelled" }
                val totalCount     = trips.size

                binding.tvCompletedCount.text = completedCount.toString()
                binding.tvCancelledCount.text = cancelledCount.toString()
                binding.tvTotalTrips.text     = totalCount.toString()

                val listItems: List<EarningsListItem> =
                    listOf(EarningsListItem.Header(totalCount)) +
                            trips.map { EarningsListItem.Trip(it) }

                tripAdapter.submitList(listItems)
            }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showEmptyState(show: Boolean) {
        binding.emptyState.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvTrips.visibility    = if (show) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Remove listeners BEFORE nulling _binding to prevent crash
        earningsListener?.remove()
        tripsListener?.remove()
        earningsListener = null
        tripsListener = null
        _binding = null
    }
}