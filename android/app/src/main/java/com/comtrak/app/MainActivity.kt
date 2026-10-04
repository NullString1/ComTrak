package com.comtrak.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.comtrak.app.data.remote.SessionManager
import com.comtrak.app.ui.detail.CommissionDetailScreen
import com.comtrak.app.ui.detail.CommissionDetailViewModel
import com.comtrak.app.ui.edit.EditCommissionScreen
import com.comtrak.app.ui.edit.EditCommissionViewModel
import com.comtrak.app.ui.list.CommissionListScreen
import com.comtrak.app.ui.login.LoginScreen
import com.comtrak.app.ui.login.LoginViewModel
import com.comtrak.app.ui.theme.ComTrakTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionManager = SessionManager(this)
        val startDestination = if (sessionManager.isLoggedIn) "commissions" else "login"

        setContent {
            ComTrakTheme {
                ComTrakNavHost(startDestination = startDestination)
            }
        }
    }
}

@Composable
fun ComTrakNavHost(startDestination: String) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        // 1. Login Screen
        composable("login") {
            val loginViewModel: LoginViewModel = viewModel()
            LoginScreen(
                viewModel = loginViewModel,
                onLoginSuccess = {
                    navController.navigate("commissions") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }

        // 2. Commission List Screen (Home)
        composable("commissions") {
            CommissionListScreen(
                onNavigateToDetail = { commissionId ->
                    navController.navigate("commission_detail/$commissionId")
                },
                onNavigateToNew = {
                    navController.navigate("commission_edit")
                }
            )
        }

        // 3. Commission Detail & Timeline Screen
        composable(
            route = "commission_detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { backStackEntry ->
            val commissionId = backStackEntry.arguments?.getString("id") ?: return@composable
            val detailViewModel = viewModel<CommissionDetailViewModel>(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        val app = backStackEntry.destination.let {
                            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
                        }
                        return CommissionDetailViewModel(app, commissionId) as T
                    }
                }
            )

            CommissionDetailScreen(
                viewModel = detailViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = { id -> navController.navigate("commission_edit?id=$id") }
            )
        }

        // 4. Create / Edit Commission Screen
        composable(
            route = "commission_edit?id={id}",
            arguments = listOf(navArgument("id") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStackEntry ->
            val existingId = backStackEntry.arguments?.getString("id")
            val isNew = existingId.isNullOrEmpty()

            val editViewModel = viewModel<EditCommissionViewModel>(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
                        return EditCommissionViewModel(app, existingId) as T
                    }
                }
            )

            EditCommissionScreen(
                viewModel = editViewModel,
                isNew = isNew,
                onNavigateBack = { navController.popBackStack() },
                onSaved = { savedId ->
                    if (isNew) {
                        navController.navigate("commission_detail/$savedId") {
                            popUpTo("commissions")
                        }
                    } else {
                        navController.popBackStack()
                    }
                }
            )
        }
    }
}
