package com.example.chalride.ui.driver

import android.graphics.Paint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.chalride.R
import com.example.chalride.databinding.ItemEarningsHeaderBinding
import com.example.chalride.databinding.ItemTripEarningsBinding
import java.text.SimpleDateFormat
import java.util.*

// ── Sealed list item types ────────────────────────────────────────────────────
sealed class EarningsListItem {
    /** The "TRIP HISTORY  ·  N trips" row at the top of the list */
    data class Header(val tripCount: Int) : EarningsListItem()
    /** A single ride card */
    data class Trip(val data: TripEarningsItem) : EarningsListItem()
}

// ── Adapter ───────────────────────────────────────────────────────────────────
class TripEarningsAdapter :
    ListAdapter<EarningsListItem, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val VT_HEADER = 0
        private const val VT_TRIP   = 1

        val DiffCallback = object : DiffUtil.ItemCallback<EarningsListItem>() {
            override fun areItemsTheSame(old: EarningsListItem, new: EarningsListItem): Boolean =
                when {
                    old is EarningsListItem.Header && new is EarningsListItem.Header -> true
                    old is EarningsListItem.Trip   && new is EarningsListItem.Trip   ->
                        old.data.rideId == new.data.rideId
                    else -> false
                }

            override fun areContentsTheSame(old: EarningsListItem, new: EarningsListItem) =
                old == new
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is EarningsListItem.Header -> VT_HEADER
        is EarningsListItem.Trip   -> VT_TRIP
    }

    // ── ViewHolder: Header ────────────────────────────────────────────────────
    class HeaderViewHolder(private val b: ItemEarningsHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: EarningsListItem.Header) {
            val label = if (item.tripCount == 1) "1 trip" else "${item.tripCount} trips"
            b.tvTripCountHeader.text = label
        }
    }

    // ── ViewHolder: Trip card ─────────────────────────────────────────────────
    class TripViewHolder(private val b: ItemTripEarningsBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: TripEarningsItem) {
            val isCompleted = item.status == "completed"

            b.tvPickup.text      = item.pickupAddr
            b.tvDestination.text = item.destAddr
            b.tvRiderName.text   = item.riderName
            b.tvVehicleType.text = item.vehicleType.uppercase()
            b.tvDate.text        = formatTimestamp(item.startedAt)

            if (isCompleted) {
                // ── COMPLETED ─────────────────────────────────────────────
                b.tvFare.visibility          = android.view.View.VISIBLE
                b.tvFareCancelled.visibility = android.view.View.GONE
                b.tvFare.text                = "₹${item.fare}"

                // Reset any paint flags from recycled views
                b.tvFareCancelled.paintFlags =
                    b.tvFareCancelled.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

                b.tvFareLabel.text = "earned"
                b.tvFareLabel.setTextColor(
                    ContextCompat.getColor(b.root.context, R.color.text_secondary)
                )
                b.statusBar.setBackgroundResource(R.drawable.bg_status_bar_green)
                b.contentGroup.alpha = 1f
                b.tvStatusBadge.text = "COMPLETED"
                b.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_completed)
                b.tvStatusBadge.setTextColor(
                    ContextCompat.getColor(b.root.context, R.color.green_deep)
                )

            } else {
                // ── CANCELLED ─────────────────────────────────────────────
                b.tvFare.visibility          = android.view.View.GONE
                b.tvFareCancelled.visibility = android.view.View.VISIBLE
                b.tvFareCancelled.text       = "₹${item.fare}"


                // Bold + strikethrough — line color matches text (yellow),
                // which is perfectly visible. No custom span needed.
                b.tvFareCancelled.setTypeface(b.tvFareCancelled.typeface, Typeface.BOLD)
                b.tvFareCancelled.textSize = 22f
                b.tvFareCancelled.paintFlags =
                    b.tvFareCancelled.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

                // "not paid" label — same muted color as "earned" label
                b.tvFareLabel.text = "not paid"
                b.tvFareLabel.setTextColor(
                    ContextCompat.getColor(b.root.context, R.color.text_secondary)
                )

                b.statusBar.setBackgroundResource(R.drawable.bg_status_bar_red)
                b.contentGroup.alpha = 0.70f
                b.tvStatusBadge.text = "CANCELLED"
                b.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_cancelled)
                b.tvStatusBadge.setTextColor(
                    ContextCompat.getColor(b.root.context, R.color.error_red)
                )
            }
        }

        /**
         * FIX: Firestore stores timestamps as milliseconds (13 digits, e.g. 1777971825423).
         * Values > 1_000_000_000_000 are already ms. Values <= that are seconds → × 1000.
         */
        private fun formatTimestamp(rawValue: Long): String {
            if (rawValue == 0L) return "—"
            val millis = if (rawValue > 1_000_000_000_000L) rawValue else rawValue * 1000L
            val date   = Date(millis)
            val now    = Calendar.getInstance()
            val cal    = Calendar.getInstance().also { it.time = date }
            val time   = SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
            return when {
                isSameDay(cal, now)   -> "Today, $time"
                isYesterday(cal) -> "Yesterday, $time"
                else -> SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(date)
            }
        }

        private fun isSameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        private fun isYesterday(cal: Calendar): Boolean {
            val yesterday = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -1) }
            return cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                    cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
        }
    }

    // ── Inflate ───────────────────────────────────────────────────────────────
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VT_HEADER -> HeaderViewHolder(
                ItemEarningsHeaderBinding.inflate(inflater, parent, false)
            )
            else -> TripViewHolder(
                ItemTripEarningsBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is EarningsListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is EarningsListItem.Trip   -> (holder as TripViewHolder).bind(item.data)
        }
    }
}