package com.example.chalride.data.repository

import com.example.chalride.data.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class AuthRepository {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    suspend fun register(
        name: String,
        email: String,
        password: String,
        role: String
    ): Result<User> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val uid = result.user?.uid ?: throw Exception("User ID is null")

            val user = User(
                uid = uid,
                name = name,
                email = email,
                role = role,
                profileStep = 0
            )

            // Store in role-specific collection — riders or drivers separately
            val collection = if (role == "rider") "riders" else "drivers"
            if (role == "rider") {
                // Store user data + phoneVerified flag together in one write
                val riderData = mapOf(
                    "uid" to uid,
                    "name" to name,
                    "email" to email,
                    "role" to role,
                    "profileStep" to 1,
                    "phoneVerified" to false
                )
                firestore.collection(collection).document(uid).set(riderData).await()
            } else {
                firestore.collection(collection).document(uid).set(user).await()
            }

            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(email: String, password: String, expectedRole: String): Result<User> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val uid = result.user?.uid ?: throw Exception("User ID is null")

            // Check ONLY in the expected role's collection
            val collection = if (expectedRole == "rider") "riders" else "drivers"
            val doc = firestore.collection(collection).document(uid).get().await()

            if (!doc.exists()) {
                // User exists in Auth but not in this role's collection
                auth.signOut()
                throw Exception(
                    if (expectedRole == "rider")
                        "No rider account found. Did you register as a driver?"
                    else
                        "No driver account found. Did you register as a rider?"
                )
            }

            val user = doc.toObject(User::class.java)
                ?: throw Exception("User data not found")

            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserRole(uid: String): String? {
        return try {
            // Check riders collection first
            val riderDoc = firestore.collection("riders").document(uid).get().await()
            if (riderDoc.exists()) return "rider"

            // Then check drivers collection
            val driverDoc = firestore.collection("drivers").document(uid).get().await()
            if (driverDoc.exists()) return "driver"

            // Not found in either — sign out
            auth.signOut()
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getDriverProfileStep(uid: String): Int {
        return try {
            val doc = firestore.collection("drivers").document(uid).get().await()
            (doc.getLong("profileStep") ?: 0).toInt()
        } catch (_: Exception) {
            0
        }
    }


    suspend fun getRiderProfileStep(uid: String): Int {
        return try {
            val doc = firestore.collection("riders").document(uid).get().await()
            (doc.getLong("profileStep") ?: 0).toInt()
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Determines the correct start destination fragment ID for MainActivity.
     * Runs both Firestore reads in parallel to minimise latency.
     * Returns a data class so MainActivity doesn't need to import fragment IDs here.
     */
    data class StartDestinationResult(
        val role: String?,          // "rider", "driver", or null
        val profileStep: Int        // 0, 1, or 2+
    )

    suspend fun getStartDestinationInfo(uid: String): StartDestinationResult {
        return try {
            // Run riders and drivers reads in parallel
            val riderTask  = firestore.collection("riders").document(uid).get()
            val driverTask = firestore.collection("drivers").document(uid).get()

            val riderDoc  = riderTask.await()
            val driverDoc = driverTask.await()

            when {
                riderDoc.exists() -> {
                    val step = (riderDoc.getLong("profileStep") ?: 0).toInt()
                    StartDestinationResult(role = "rider", profileStep = step)
                }
                driverDoc.exists() -> {
                    val step = (driverDoc.getLong("profileStep") ?: 0).toInt()
                    StartDestinationResult(role = "driver", profileStep = step)
                }
                else -> {
                    // UID exists in Auth but not in either collection — sign out
                    auth.signOut()
                    StartDestinationResult(role = null, profileStep = 0)
                }
            }
        } catch (_: Exception) {
            // Network failure — treat as logged out to be safe
            StartDestinationResult(role = null, profileStep = 0)
        }
    }


}