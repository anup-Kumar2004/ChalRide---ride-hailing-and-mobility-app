package com.example.chalride.ui.driver

data class TripEarningsItem(
    val rideId      : String,
    val status      : String,   // "completed" | "cancelled"
    val pickupAddr  : String,
    val destAddr    : String,
    val riderName   : String,
    val vehicleType : String,   // "bike" | "car" | "auto"
    val fare        : Long,
    val startedAt   : Long,     // epoch seconds (Firestore number field)
    val completedAt : Long,
)