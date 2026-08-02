package com.deeprecovery.pro.ui.main

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.deeprecovery.pro.R
import com.deeprecovery.pro.databinding.ActivityMainBinding
import com.deeprecovery.pro.ui.common.InfoDialogs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* الإشعار اختياري — الفحص يعمل بدونه */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.navHost) as NavHostFragment
        val navController = navHost.navController

        val appBarConfig = AppBarConfiguration(setOf(R.id.mainFragment))
        binding.toolbar.setupWithNavController(navController, appBarConfig)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sessions -> {
                    navController.navigate(R.id.sessionsFragment)
                    true
                }

                R.id.action_disclaimer -> {
                    InfoDialogs.showDisclaimer(this)
                    true
                }

                R.id.action_privacy -> {
                    InfoDialogs.showPrivacy(this)
                    true
                }

                R.id.action_crash_log -> {
                    InfoDialogs.showCrashReport(this)
                    true
                }

                else -> false
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
