package com.maodouchat.ui.screen.groupplay

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 签到排行项。 */
data class CheckinRankItem(
    val userId: String,
    val streak: Int,
    val totalCount: Int
)

class GroupCheckinViewModel(application: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(application) {

    val chatId: String = savedStateHandle["chatId"] ?: ""

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val checkedIn: Boolean = false,
        val streak: Int = 0,
        val totalCount: Int = 0,
        val todayRank: Int = 0,
        val todayCount: Int = 0,
        val ranking: List<CheckinRankItem> = emptyList(),
        val checking: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private fun token(): String = TokenManager.getInstance(getApplication()).getToken().orEmpty()
    // 1.314：i18n —— 用资源字符串替代硬编码中文错误文案
    private fun str(id: Int): String = getApplication<Application>().getString(id)

    init {
        if (chatId.isNotBlank()) refresh()
    }

    fun refresh() {
        if (chatId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val meText = GroupPlayHttp.get(token(), "/api/chats/$chatId/checkins/me")
            val rankText = GroupPlayHttp.get(token(), "/api/chats/$chatId/checkins/rank")
            if (meText == null || rankText == null) {
                _uiState.value = _uiState.value.copy(loading = false, error = str(R.string.group_play_load_failed))
                return@launch
            }
            val me = runCatching { JSONObject(meText) }.getOrNull()
            val ranking = runCatching { parseRanking(rankText) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(
                loading = false,
                checkedIn = me?.optBoolean("alreadyCheckedIn") == true,
                streak = me?.optInt("streak") ?: 0,
                totalCount = me?.optInt("totalCount") ?: 0,
                todayRank = me?.optInt("todayRank") ?: 0,
                todayCount = me?.optInt("todayCount") ?: 0,
                ranking = ranking
            )
        }
    }

    fun checkIn() {
        if (_uiState.value.checking) return
        _uiState.value = _uiState.value.copy(checking = true)
        viewModelScope.launch {
            val text = GroupPlayHttp.post(token(), "/api/chats/$chatId/checkins", "{}")
            if (text == null) {
                _uiState.value = _uiState.value.copy(checking = false, error = str(R.string.group_play_checkin_failed))
            } else {
                _uiState.value = _uiState.value.copy(checking = false)
                refresh()
            }
        }
    }

    private fun parseRanking(text: String): List<CheckinRankItem> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            CheckinRankItem(
                userId = o.optString("userId"),
                streak = o.optInt("streak"),
                totalCount = o.optInt("totalCount")
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCheckinScreen(
    onBack: () -> Unit,
    viewModel: GroupCheckinViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_play_checkin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.group_play_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.checkIn() },
                        enabled = !state.checkedIn && !state.checking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.checking) CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        else Text(stringResource(if (state.checkedIn) R.string.group_play_checkin_done else R.string.group_play_checkin_now))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(
                            stringResource(R.string.group_play_checkin_streak, state.streak),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            pluralStringResource(R.plurals.group_play_checkin_total, state.totalCount, state.totalCount),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (state.checkedIn && state.todayRank > 0) {
                        Text(
                            stringResource(R.string.group_play_checkin_rank, state.todayRank, state.todayCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            state.error?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error) }
            }

            item {
                Text(stringResource(R.string.group_play_checkin_leaderboard), style = MaterialTheme.typography.titleMedium)
            }
            if (state.loading) {
                item { CircularProgressIndicator(modifier = Modifier.padding(24.dp)) }
            }
            items(state.ranking, key = { it.userId }) { entry ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        text = "${entry.totalCount}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(40.dp)
                    )
                    Text(
                        text = entry.userId,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.group_play_checkin_streak, entry.streak),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
