package com.comtrak.app.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comtrak.app.data.remote.ApiClient
import com.comtrak.app.data.remote.GoogleAuthRequestDto
import com.comtrak.app.data.remote.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    val sessionManager = SessionManager(application)
    private val apiService = ApiClient(application).apiService

    val isLoading = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)
    val serverUrl = MutableStateFlow(sessionManager.serverBaseUrl)

    fun onServerUrlChanged(newUrl: String) {
        serverUrl.value = newUrl
        sessionManager.serverBaseUrl = newUrl.trimEnd('/')
    }

    fun handleGoogleIdToken(idToken: String, onLoginSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading.value = true
            errorMessage.value = null
            try {
                val resp = apiService.googleLogin(GoogleAuthRequestDto(id_token = idToken))
                if (resp.isSuccessful && resp.body() != null) {
                    val authData = resp.body()!!
                    sessionManager.token = authData.token
                    sessionManager.userId = authData.user.id
                    sessionManager.userEmail = authData.user.email
                    sessionManager.userDisplayName = authData.user.display_name
                    onLoginSuccess()
                } else {
                    errorMessage.value = "Authentication failed: ${resp.errorBody()?.string() ?: resp.message()}"
                }
            } catch (e: Exception) {
                errorMessage.value = "Connection error: ${e.message}"
            } finally {
                isLoading.value = false
            }
        }
    }

    fun devBypassLogin(onLoginSuccess: () -> Unit) {
        handleGoogleIdToken("mock_dev_token_potter", onLoginSuccess)
    }
}
