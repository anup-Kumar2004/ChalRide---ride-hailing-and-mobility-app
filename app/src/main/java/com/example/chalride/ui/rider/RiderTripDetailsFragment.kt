package com.example.chalride.ui.rider

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.chalride.R
import com.example.chalride.databinding.FragmentRiderTripDetailsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Data model ───────────────────────────────────────────────────────────────

data class TripRecord(
    val rideId: String,
    val status: String,           // "completed" | "cancelled"
    val pickupAddress: String,
    val destAddress: String,
    val estimatedFare: Int,
    val vehicleType: String,
    val driverName: String,
    val rating: Int,              // 0 = not rated
    val createdAt: Long,          // epoch ms
    val cancellationReason: String
)

// ── Fragment ─────────────────────────────────────────────────────────────────

class RiderTripDetailsFragment : Fragment() {

    private var _binding: FragmentRiderTripDetailsBinding? = null
    private val binding get() = _binding!!

    private val allTrips = mutableListOf<TripRecord>()
    private val displayedTrips = mutableListOf<TripRecord>()
    private lateinit var adapter: TripAdapter

    // "all" | "completed" | "cancelled"
    private var activeFilter = "all"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRiderTripDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupFilterButtons()
        setupBackButton()
        fetchTrips()
    }

    // ── RecyclerView ─────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = TripAdapter(displayedTrips)
        binding.rvTrips.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTrips.adapter = adapter
        binding.rvTrips.setHasFixedSize(false)
    }

    // ── Filter buttons ────────────────────────────────────────────────────────

    private fun setupFilterButtons() {
        binding.btnFilterAll.setOnClickListener       { applyFilter("all") }
        binding.btnFilterCompleted.setOnClickListener { applyFilter("completed") }
        binding.btnFilterCancelled.setOnClickListener { applyFilter("cancelled") }
    }

    private fun applyFilter(filter: String) {
        activeFilter = filter
        updateFilterButtonStyles()

        displayedTrips.clear()
        val filtered = when (filter) {
            "completed"  -> allTrips.filter { it.status == "completed" }
            "cancelled"  -> allTrips.filter { it.status == "cancelled" }
            else         -> allTrips
        }
        displayedTrips.addAll(filtered)
        adapter.notifyDataSetChanged()

        // Update empty state
        val emptySubtitle = when (filter) {
            "completed"  -> "No completed trips yet"
            "cancelled"  -> "No cancelled trips yet"
            else         -> "Your completed and cancelled trips\nwill appear here"
        }
        binding.tvEmptySubtitle.text = emptySubtitle
        binding.layoutEmpty.visibility = if (displayedTrips.isEmpty()) View.VISIBLE else View.GONE
        binding.rvTrips.visibility     = if (displayedTrips.isEmpty()) View.GONE   else View.VISIBLE
    }

    private fun updateFilterButtonStyles() {
        val ctx = requireContext()
        val activeBg    = ContextCompat.getColor(ctx, R.color.brand_primary)
        val activeText  = ContextCompat.getColor(ctx, R.color.white)
        val inactiveText = ContextCompat.getColor(ctx, R.color.text_hint)
        val transparent = ContextCompat.getColor(ctx, R.color.transparent)

        // Reset all to outlined inactive style
        listOf(binding.btnFilterAll, binding.btnFilterCompleted, binding.btnFilterCancelled)
            .forEach { btn ->
                btn.backgroundTintList = ColorStateList.valueOf(transparent)
                btn.setTextColor(inactiveText)
            }

        // Activate the selected one
        when (activeFilter) {
            "all" -> {
                binding.btnFilterAll.backgroundTintList = ColorStateList.valueOf(activeBg)
                binding.btnFilterAll.setTextColor(activeText)
            }
            "completed" -> {
                val color = ContextCompat.getColor(ctx, R.color.success_color)
                binding.btnFilterCompleted.backgroundTintList = ColorStateList.valueOf(color)
                binding.btnFilterCompleted.setTextColor(activeText)
            }
            "cancelled" -> {
                val color = ContextCompat.getColor(ctx, R.color.error_color)
                binding.btnFilterCancelled.backgroundTintList = ColorStateList.valueOf(color)
                binding.btnFilterCancelled.setTextColor(activeText)
            }
        }
    }

    // ── Firestore fetch ───────────────────────────────────────────────────────

    private fun fetchTrips() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            showEmpty()
            return
        }

        binding.layoutLoading.visibility = View.VISIBLE
        binding.rvTrips.visibility       = View.GONE
        binding.layoutEmpty.visibility   = View.GONE
        binding.cardStats.visibility     = View.GONE

        FirebaseFirestore.getInstance()
            .collection("rideRequests")
            .whereEqualTo("riderId", uid)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener

                binding.layoutLoading.visibility = View.GONE

                allTrips.clear()
                for (doc in snapshot.documents) {
                    val status = doc.getString("status") ?: continue
                    if (status != "completed" && status != "cancelled") continue
                    val pickup     = doc.getString("pickupAddress")   ?: ""
                    val dest       = doc.getString("destAddress")     ?: ""
                    val fare       = (doc.getLong("estimatedFare")    ?: 0).toInt()
                    val vehicle    = doc.getString("vehicleType")     ?: ""
                    val driver     = doc.getString("driverName")      ?: ""
                    val createdAt  = doc.getLong("createdAt")         ?: 0L
                    val cancelReason = doc.getString("cancellationReason") ?: ""

                    // Rating lives inside riderFeedback sub-map
                    val ratingRaw  = (doc.get("riderFeedback") as? Map<*, *>)
                        ?.get("rating")
                    val rating     = when (ratingRaw) {
                        is Long   -> ratingRaw.toInt()
                        is Double -> ratingRaw.toInt()
                        else      -> 0
                    }

                    allTrips.add(
                        TripRecord(
                            rideId = doc.id,
                            status = status,
                            pickupAddress = pickup,
                            destAddress = dest,
                            estimatedFare = fare,
                            vehicleType = vehicle,
                            driverName = driver,
                            rating = rating,
                            createdAt = createdAt,
                            cancellationReason = cancelReason
                        )
                    )
                }

                allTrips.sortByDescending { it.createdAt }
                updateStats()
                applyFilter(activeFilter)
            }
            .addOnFailureListener {
                if (_binding == null) return@addOnFailureListener
                binding.layoutLoading.visibility = View.GONE
                showEmpty()
            }
    }

    private fun updateStats() {
        val completed = allTrips.count { it.status == "completed" }
        val cancelled = allTrips.count { it.status == "cancelled" }
        val spent     = allTrips.filter { it.status == "completed" }
            .sumOf { it.estimatedFare }

        binding.tvStatTotal.text     = allTrips.size.toString()
        binding.tvStatCompleted.text = completed.toString()
        binding.tvStatCancelled.text = cancelled.toString()
        binding.tvStatSpent.text     = "₹$spent"
        binding.tvTripCount.text     = "${allTrips.size} trips"

        binding.cardStats.visibility = if (allTrips.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEmpty() {
        binding.layoutEmpty.visibility = View.VISIBLE
        binding.rvTrips.visibility     = View.GONE
        binding.cardStats.visibility   = View.GONE
    }

    // ── Back ─────────────────────────────────────────────────────────────────

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ── Adapter ──────────────────────────────────────────────────────────────────

class TripAdapter(private val trips: List<TripRecord>) :
    RecyclerView.Adapter<TripAdapter.TripViewHolder>() {

    private val dateFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    class TripViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvVehicleEmoji: TextView  = itemView.findViewById(R.id.tvVehicleEmoji)
        val tvVehicleType: TextView   = itemView.findViewById(R.id.tvVehicleType)
        val tvDate: TextView          = itemView.findViewById(R.id.tvDate)
        val tvStatus: TextView        = itemView.findViewById(R.id.tvStatus)
        val tvPickup: TextView        = itemView.findViewById(R.id.tvPickup)
        val tvDestination: TextView   = itemView.findViewById(R.id.tvDestination)
        val tvFare: TextView          = itemView.findViewById(R.id.tvFare)
        val layoutDriverInfo: View    = itemView.findViewById(R.id.layoutDriverInfo)
        val tvDriverName: TextView    = itemView.findViewById(R.id.tvDriverName)
        val tvRating: TextView        = itemView.findViewById(R.id.tvRating)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trip_card, parent, false)
        return TripViewHolder(view)
    }

    override fun getItemCount() = trips.size

    override fun onBindViewHolder(holder: TripViewHolder, position: Int) {
        val trip = trips[position]
        val ctx  = holder.itemView.context

        // Vehicle emoji + type
        holder.tvVehicleEmoji.text = when (trip.vehicleType.lowercase()) {
            "bike"  -> "🏍️"
            "auto"  -> "🛺"
            "sedan" -> "🚗"
            "suv"   -> "🚙"
            else    -> "🚗"
        }
        holder.tvVehicleType.text = trip.vehicleType
            .replaceFirstChar { it.uppercase() }

        // Date
        holder.tvDate.text = if (trip.createdAt > 0)
            dateFormat.format(Date(trip.createdAt))
        else "—"

        // Addresses
        holder.tvPickup.text      = trip.pickupAddress.ifEmpty { "—" }
        holder.tvDestination.text = trip.destAddress.ifEmpty { "—" }

        // Fare
        holder.tvFare.text = if (trip.status == "cancelled") "—" else "₹${trip.estimatedFare}"

        // Status badge
        val isCompleted = trip.status == "completed"
        val statusLabel = if (isCompleted) "Completed" else "Cancelled"
        val statusColor = if (isCompleted)
            ContextCompat.getColor(ctx, R.color.success_color)
        else
            ContextCompat.getColor(ctx, R.color.error_color)

        holder.tvStatus.text = statusLabel
        holder.tvStatus.setTextColor(statusColor)

        // Update badge background stroke color dynamically
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * ctx.resources.displayMetrics.density
            setColor(statusColor and 0x00FFFFFF or 0x1A000000)
            setStroke(
                (1 * ctx.resources.displayMetrics.density).toInt(),
                statusColor
            )
        }
        holder.tvStatus.background = bg

        // Driver info row (only show for completed rides with a driver)
        if (isCompleted && trip.driverName.isNotEmpty()) {
            holder.layoutDriverInfo.visibility = View.VISIBLE
            holder.tvDriverName.text = "Driver: ${trip.driverName}"

            if (trip.rating > 0) {
                holder.tvRating.visibility = View.VISIBLE
                holder.tvRating.text = "⭐ ${trip.rating}"
            } else {
                holder.tvRating.visibility = View.GONE
            }
        } else {
            holder.layoutDriverInfo.visibility = View.GONE
        }
    }
}