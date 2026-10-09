package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FitnessTestRecord
import com.treadz.console.userdata.UserData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FitnessResultUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val record: FitnessTestRecord? = null,
    val comparison: FitnessComparison? = null,
    val heartRateMax: HeartRateMaxDecision? = null,
    val confirmingDelete: Boolean = false,
    val failed: Boolean = false,
)

/** One stored fitness test result with its comparison to the previous test of the same type. */
class FitnessResultViewModel(private val testId: String, private val userData: UserData) : ViewModel() {

    private val state = MutableStateFlow(FitnessResultUiState())
    val uiState: StateFlow<FitnessResultUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val first = userData.fitnessTests.get(testId)
            if (first == null) {
                state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            combine(userData.fitnessTests.results(first.userId), userData.fitnessTests.heartRateMax(first.userId)) { results, hrMax -> results to hrMax }
                .collect { (results, hrMax) ->
                    val record = results.firstOrNull { it.id == testId }
                    state.update {
                        if (record == null) {
                            it.copy(loading = false, notFound = true, record = null)
                        } else {
                            it.copy(
                                loading = false, notFound = false, record = record, heartRateMax = hrMax,
                                comparison = FitnessResults.compare(record.result, results.map { r -> r.result }),
                            )
                        }
                    }
                }
        }
    }

    fun requestDelete() = state.update { it.copy(confirmingDelete = true) }

    fun cancelDelete() = state.update { it.copy(confirmingDelete = false) }

    fun confirmDelete(onDeleted: () -> Unit) {
        state.update { it.copy(confirmingDelete = false) }
        viewModelScope.launch {
            when (userData.fitnessTests.delete(testId)) {
                is DataResult.Success, DataResult.NotFound -> onDeleted()
                else -> state.update { it.copy(failed = true) }
            }
        }
    }

    fun useMeasuredHeartRateMax() = answer { userData.fitnessTests.useMeasuredHeartRateMax(it) }

    fun keepManualHeartRateMax() = answer { userData.fitnessTests.keepManualHeartRateMax(it) }

    private fun answer(call: suspend (Long) -> DataResult<Unit>) {
        val owner = state.value.record?.userId ?: return
        viewModelScope.launch {
            if (call(owner) is DataResult.StorageError) state.update { it.copy(failed = true) }
        }
    }
}
