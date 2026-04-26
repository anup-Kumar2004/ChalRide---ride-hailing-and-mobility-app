package com.example.chalride

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import com.example.chalride.data.repository.AuthRepository
import com.example.chalride.databinding.ActivityMainBinding
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)

        // Determine start destination BEFORE graph is set
        val startDestination = getStartDestination()
        navGraph.setStartDestination(startDestination)
        navController.graph = navGraph
    }

    private fun getStartDestination(): Int {
        val currentUser = authRepository.currentUser
            ?: return R.id.roleSelectionFragment

        // Use runBlocking here intentionally — we MUST know the role
        // before any fragment draws. This runs during splash screen.
        val role = runBlocking {
            authRepository.getUserRole(currentUser.uid)
        }

        return when (role) {
            "rider" -> R.id.riderHomeFragment
            "driver" -> R.id.driverHomeFragment
            else -> R.id.roleSelectionFragment
        }
    }
}