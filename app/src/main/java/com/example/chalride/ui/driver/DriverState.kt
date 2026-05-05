package com.example.chalride.ui.driver

/**
 * Single source of truth for all driver states.
 *
 * Every state change in the app MUST go through toFirestoreMap()
 * so the drivers/{uid} document is always consistent.
 */
enum class DriverState {
    OFFLINE,
    ONLINE_AVAILABLE,
    ON_TRIP_TO_PICKUP,
    WAITING_AT_PICKUP,
    IN_TRIP;

    companion object {
        fun fromString(value: String?): DriverState = when (value) {
            "ONLINE_AVAILABLE"  -> ONLINE_AVAILABLE
            "ON_TRIP_TO_PICKUP" -> ON_TRIP_TO_PICKUP
            "WAITING_AT_PICKUP" -> WAITING_AT_PICKUP
            "IN_TRIP"           -> IN_TRIP
            else                -> OFFLINE
        }
    }
}

/**
 * Converts a DriverState to the canonical Firestore map that must be
 * written to drivers/{uid} on every state transition.
 *
 * @param activeRideId  Required only for ON_TRIP_TO_PICKUP.
 *                      Leave null for every other state.
 */
fun DriverState.toFirestoreMap(activeRideId: String? = null): Map<String, Any?> = when (this) {

    DriverState.OFFLINE -> mapOf(
        "driverState"  to name,
        "isOnline"     to false,
        "isAvailable"  to false,
        "activeRideId" to null,
        "tripPhase"    to null
    )

    DriverState.ONLINE_AVAILABLE -> mapOf(
        "driverState"  to name,
        "isOnline"     to true,
        "isAvailable"  to true,
        "activeRideId" to null,
        "tripPhase"    to null
    )

    DriverState.ON_TRIP_TO_PICKUP -> mapOf(
        "driverState"  to name,
        "isOnline"     to true,
        "isAvailable"  to false,
        "activeRideId" to requireNotNull(activeRideId) {
            "activeRideId must not be null for ON_TRIP_TO_PICKUP"
        },
        "tripPhase"    to "HEADING_TO_PICKUP"
    )

    DriverState.WAITING_AT_PICKUP -> mapOf(
        "driverState"  to name,
        "isOnline"     to true,
        "isAvailable"  to false,
        "tripPhase"    to "ARRIVED_AT_PICKUP"
    )

    DriverState.IN_TRIP -> mapOf(
        "driverState"  to name,
        "isOnline"     to true,
        "isAvailable"  to false,
        "tripPhase"    to "IN_PROGRESS"
    )
}