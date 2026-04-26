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

            val user = User(uid = uid, name = name, email = email, role = role)

            // Store in role-specific collection — riders or drivers separately
            val collection = if (role == "rider") "riders" else "drivers"
            firestore.collection(collection).document(uid).set(user).await()

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
        } catch (e: Exception) {
            null
        }
    }

    fun logout() {
        auth.signOut()
    }
}