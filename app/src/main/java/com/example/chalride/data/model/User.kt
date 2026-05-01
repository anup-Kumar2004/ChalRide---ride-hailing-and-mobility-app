package com.example.chalride.data.model

data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",   // rider or driver
    val profileStep: Int = 0
)