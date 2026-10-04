package one.nullstring.ComTrak

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
import one.nullstring.ComTrak.data.remote.SessionManager
import one.nullstring.ComTrak.ui.detail.CommissionDetailScreen
import one.nullstring.ComTrak.ui.detail.CommissionDetailViewModel
import one.nullstring.ComTrak.ui.edit.EditCommissionScreen
import one.nullstring.ComTrak.ui.edit.EditCommissionViewModel
import one.nullstring.ComTrak.ui.list.CommissionListScreen
import one.nullstring.ComTrak.ui.login.LoginScreen
import one.nullstring.ComTrak.ui.login.LoginViewModel
import one.nullstring.ComTrak.ui.theme.ComTrakTheme

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
            val context = androidx.compose.ui.platform.LocalContext.current
            val app = context.applicationContext as android.app.Application
            val commissionId = backStackEntry.arguments?.getString("id") ?: return@composable
            val detailViewModel = viewModel<CommissionDetailViewModel>(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
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
            val context = androidx.compose.ui.platform.LocalContext.current
            val app = context.applicationContext as android.app.Application
            val existingId = backStackEntry.arguments?.getString("id")
            val isNew = existingId.isNullOrEmpty()

            val editViewModel = viewModel<EditCommissionViewModel>(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
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
