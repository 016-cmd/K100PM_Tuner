package com.k100pm.tuner.v4a2.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.k100pm.tuner.v4a2.ui.screens.main.MainScreen

@Composable
fun ViperNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main",
    ) {
        composable("main") {
            MainScreen()
        }
    }
}
