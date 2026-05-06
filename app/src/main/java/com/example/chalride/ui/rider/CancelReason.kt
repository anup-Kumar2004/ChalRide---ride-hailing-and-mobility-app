package com.example.chalride.ui.rider

enum class CancelReason {
    RIDER_CANCELLED,   // Rider tapped cancel themselves (Phase 1 only)
    DRIVER_OFFLINE,    // Driver went offline and didn't return within 5 minutes
    NO_DRIVER_FOUND,   // No driver accepted in 60 seconds
    TIMEOUT            // Reserved for future use
}