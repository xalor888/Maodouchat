package com.maodouchat.ui.screen.groupplay

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 群 PK 列表项。 */
data class PkListItem(
    val id: String,
    val leftTitle: String,
    val rightTitle: String,
    val active: Boolean,
    val leftCount: Int,
    val rightCount: Int,
    val totalVoters: Int,
    val myChoice: String?
)

class GroupPkViewModel(application: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(application) {

    val chatId: String = savedStateHandle["chatId"] ?: ""

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val pks: List<PkListItem> = emptyList(),
        val leftTitle: String = "",
        val rightTitle: String = "",
        val creating: Boolean = false,
        val votingPkId: String? = null
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
            val text = GroupPlayHttp.get(token(), "/api/chats/$chatId/pk")
            if (text == null) {
                _uiState.value = _uiState.value.copy(loading = false, error = str(R.string.group_play_load_failed))
                return@launch
            }
            val pks = runCatching { parseList(text) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(loading = false, pks = pks)
        }
    }

    fun updateLeft(t: String) {
        _uiState.value = _uiState.value.copy(leftTitle = t)
    }

    fun updateRight(t: String) {
        _uiState.value = _uiState.value.copy(rightTitle = t)
    }

    fun createPk() {
        val s = _uiState.value
        if (s.creating) return
        val left = s.leftTitle.trim()
        val right = s.rightTitle.trim()
        if (left.isBlank() || right.isBlank() || left.length > 120 || right.length > 120) {
            _uiState.value = s.copy(error = str(R.string.group_play_pk_titles_invalid))
            return
        }
        _uiState.value = s.copy(creating = true, error = null)
        viewModelScope.launch {
            val body = JSONObject().apply {
                put("leftTitle", left)
                put("rightTitle", right)
            }.toString()
            val text = GroupPlayHttp.post(token(), "/api/chats/$chatId/pk", body)
            if (text == null) {
                _uiState.value = _uiState.value.copy(creating = false, error = str(R.string.group_play_create_failed))
            } else {
                _uiState.value = _uiState.value.copy(creating = false, leftTitle = "", rightTitle = "")
                refresh()
            }
        }
    }

    fun vote(pkId: String, choice: String) {
        if (_uiState.value.votingPkId != null) return
        _uiState.value = _uiState.value.copy(votingPkId = pkId)
        viewModelScope.launch {
            val body = JSONObject().put("choice", choice).toString()
            val text = GroupPlayHttp.post(token(), "/api/pk/$pkId/vote", body)
            _uiState.value = _uiState.value.copy(votingPkId = null)
            if (text != null) refresh() else {
                _uiState.value = _uiState.value.copy(error = str(R.string.group_play_vote_failed))
            }
        }
    }

    private fun parseList(text: String): List<PkListItem> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PkListItem(
                id = o.optString("id"),
                leftTitle = o.optString("leftTitle"),
                rightTitle = o.optString("rightTitle"),
                active = o.optBoolean("active"),
                leftCount = o.optInt("leftCount"),
                rightCount = o.optInt("rightCount"),
                totalVoters = o.optInt("totalVoters"),
                myChoice = o.optString("myChoice").takeIf { it.isNotBlank() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPkScreen(
    onBack: () -> Unit,
    viewModel: GroupPkViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 群玩法实时刷新：同群成员的签到/接龙/PK 变化通过 GROUP_PLAY_UPDATE 推送到达时自动刷新，
    // 此前该事件在客户端无任何处理，界面只能靠退出重进才能看到他人更新。
    LaunchedEffect(viewModel.chatId) {
        WebSocketClient.events.collect { event ->
            if (event is WebSocketEvent.GroupPlayUpdated && event.chatId == viewModel.chatId) {
                viewModel.refresh()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_play_pk_title)) },
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
            // 9.4xx：imePadding 防止键盘遮挡输入框
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 9.4xx：两个输入框上下排列（此前一行内 weight(1f) 夹 "vs" 被压成窄条）
                    OutlinedTextField(
                        value = state.leftTitle,
                        onValueChange = viewModel::updateLeft,
                        label = { Text(stringResource(R.string.group_play_pk_left_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.rightTitle,
                        onValueChange = viewModel::updateRight,
                        label = { Text(stringResource(R.string.group_play_pk_right_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.createPk() },
                        enabled = !state.creating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.creating) CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        else Text(stringResource(R.string.group_play_pk_create))
                    }
                }
            }

            state.error?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error) }
            }

            if (state.loading) {
                item { CircularProgressIndicator(modifier = Modifier.padding(24.dp)) }
            }

            items(state.pks, key = { it.id }) { pk ->
                PkCard(pk = pk, voting = state.votingPkId == pk.id, onVote = { choice -> viewModel.vote(pk.id, choice) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PkCard(pk: PkListItem, voting: Boolean, onVote: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row {
            Text(
                "${pk.leftTitle}  ${pk.leftCount}  :  ${pk.rightCount}  ${pk.rightTitle}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (!pk.active) Text(stringResource(R.string.group_play_closed), color = MaterialTheme.colorScheme.outline)
        }
        if (pk.active) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onVote("left") },
                    enabled = !voting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.group_play_pk_vote_left))
                }
                Button(
                    onClick = { onVote("right") },
                    enabled = !voting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.group_play_pk_vote_right))
                }
            }
        }
        Text(
            buildString {
                append(pluralStringResource(R.plurals.group_play_voters_count, pk.totalVoters, pk.totalVoters))
                pk.myChoice?.let {
                    append(" · ")
                    append(stringResource(R.string.group_play_voted_choice, if (it == "left") pk.leftTitle else pk.rightTitle))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        if (voting) CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp))
    }
}
