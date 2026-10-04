package com.comtrak.app.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comtrak.app.data.local.entities.CommissionEntity
import com.comtrak.app.data.repository.CommissionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CommissionListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CommissionRepository(application)

    val searchQuery = MutableStateFlow("")

    val commissions: StateFlow<List<CommissionEntity>> = repository.getAllCommissions()
        .combine(searchQuery) { list, query ->
            if (query.isBlank()) {
                list
            } else {
                list.filter {
                    it.title.contains(query, ignoreCase = true) ||
                    it.customerName.contains(query, ignoreCase = true) ||
                    it.itemName.contains(query, ignoreCase = true) ||
                    (it.clayBody?.contains(query, ignoreCase = true) == true) ||
                    (it.glazes?.contains(query, ignoreCase = true) == true)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteCommission(id: String) {
        viewModelScope.launch {
            repository.deleteCommission(id)
        }
    }

    fun syncNow() {
        repository.scheduleSync()
    }
}
