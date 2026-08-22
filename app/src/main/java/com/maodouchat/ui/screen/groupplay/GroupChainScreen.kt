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
import androidx.compose.ui.Modifier
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

/** 群接龙列表项。 */
data class ChainListItem(
    val id: String,
    val title: String,
    val topic: String,
    val active: Boolean,
    val entryCount: Int,
    val maxEntries: Int,
    val myJoined: Boolean
)

/** 群接龙明细项。 */
data class ChainEntryItem(
    val id: String,
    val userId: String,
    val sequence: Int,
    val content: String
)

class GroupChainViewModel(application: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(application) {

    val chatId: String = savedStateHandle["chatId"] ?: ""

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val chains: List<ChainListItem> = emptyList(),
        val title: String = "",
        val topic: String = "",
        val creating: Boolean = false,
        val detail: ChainDetailState? = null,
        val entryInput: String = ""
    )

    data class ChainDetailState(
        val chainId: String,
        val title: String,
        val topic: String,
        val active: Boolean,
        val entryCount: Int,
        val maxEntries: Int,
        val myJoined: Boolean,
        val entries: List<ChainEntryItem>,
        val submitting: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private fun token(): String = TokenManager.getInstance(getApplication()).getToken().orEmpty()
    // 1.314：i18n —— 用资源字符串替代硬编码中文错误文案
    private fun text(id: Int): String = getApplication<Application>().getString(id)

    init {
        if (chatId.isNotBlank()) refresh()
    }

    fun refresh() {
        if (chatId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val resp = GroupPlayHttp.get(token(), "/api/chats/$chatId/chains")
            if (!resp.ok) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = resp.errorText ?: text(R.string.group_play_load_failed)
                )
                return@launch
            }
            val chains = runCatching { parseList(resp.body) }.getOrNull()
            if (chains == null) {
                _uiState.value = _uiState.value.copy(loading = false, error = text(R.string.group_play_load_failed))
                return@launch
            }
            _uiState.value = _uiState.value.copy(loading = false, chains = chains)
        }
    }

    fun updateTitle(t: String) {
        _uiState.value = _uiState.value.copy(title = t)
    }

    fun updateTopic(t: String) {
        _uiState.value = _uiState.value.copy(topic = t)
    }

    fun createChain() {
        val s = _uiState.value
        if (s.creating) return
        val title = s.title.trim()
        val topic = s.topic.trim()
        if (title.isBlank() || title.length > 200 || topic.length > 500) {
            _uiState.value = s.copy(error = text(R.string.group_play_chain_title_invalid))
            return
        }
        _uiState.value = s.copy(creating = true, error = null)
        viewModelScope.launch {
            val body = JSONObject().apply {
                put("title", title)
                put("topic", topic)
                put("maxEntries", 200)
            }.toString()
            val resp = GroupPlayHttp.post(token(), "/api/chats/$chatId/chains", body)
            if (!resp.ok) {
                _uiState.value = _uiState.value.copy(
                    creating = false,
                    error = resp.errorText ?: text(R.string.group_play_create_failed)
                )
            } else {
                _uiState.value = _uiState.value.copy(creating = false, title = "", topic = "")
                refresh()
            }
        }
    }

    fun openChain(chainId: String) {
        viewModelScope.launch {
            val resp = GroupPlayHttp.get(token(), "/api/chains/$chainId")
            if (!resp.ok) {
                _uiState.value = _uiState.value.copy(error = resp.errorText ?: text(R.string.group_play_chain_not_found))
                return@launch
            }
            val o = runCatching { JSONObject(resp.body) }.getOrNull()
            if (o == null) {
                _uiState.value = _uiState.value.copy(error = text(R.string.group_play_chain_not_found))
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                detail = ChainDetailState(
                    chainId = o.optString("id"),
                    title = o.optString("title"),
                    topic = o.optString("topic"),
                    active = o.optBoolean("active"),
                    entryCount = o.optInt("entryCount"),
                    maxEntries = o.optInt("maxEntries"),
                    myJoined = o.optBoolean("myJoined"),
                    entries = parseEntries(o.optJSONArray("entries"))
                )
            )
        }
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(detail = null)
    }

    fun updateEntryInput(v: String) {
        _uiState.value = _uiState.value.copy(entryInput = v)
    }

    fun joinChain() {
        val detail = _uiState.value.detail ?: return
        if (detail.submitting) return
        if (detail.myJoined || !detail.active) {
            _uiState.value = _uiState.value.copy(error = text(R.string.group_play_chain_entry_failed))
            return
        }
        val content = _uiState.value.entryInput.trim()
        if (content.isBlank() || content.length > 500) {
            _uiState.value = _uiState.value.copy(error = text(R.string.group_play_chain_entry_failed))
            return
        }
        _uiState.value = _uiState.value.copy(detail = detail.copy(submitting = true), error = null)
        viewModelScope.launch {
            val body = JSONObject().put("content", content).toString()
            val resp = GroupPlayHttp.post(token(), "/api/chains/${detail.chainId}/entries", body)
            if (resp.ok) {
                _uiState.value = _uiState.value.copy(
                    entryInput = "",
                    detail = detail.copy(submitting = false, myJoined = true)
                )
                openChain(detail.chainId)
                refresh()
            } else {
                _uiState.value = _uiState.value.copy(
                    detail = detail.copy(submitting = false),
                    error = resp.errorText ?: text(R.string.group_play_chain_entry_failed)
                )
            }
        }
    }

    private fun parseList(text: String): List<ChainListItem> {
        val arr = JSONArray(GroupPlayJson.arrayText(text))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ChainListItem(
                id = o.optString("id"),
                title = o.optString("title"),
                topic = o.optString("topic"),
                active = o.optBoolean("active"),
                entryCount = o.optInt("entryCount"),
                maxEntries = o.optInt("maxEntries"),
                myJoined = o.optBoolean("myJoined")
            )
        }
    }

    private fun parseEntries(arr: JSONArray?): List<ChainEntryItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ChainEntryItem(
                id = o.optString("id"),
                userId = o.optString("userId"),
                sequence = o.optInt("sequence"),
                content = o.optString("content")
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChainScreen(
    onBack: () -> Unit,
    viewModel: GroupChainViewModel = viewModel()
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
                title = { Text(stringResource(R.string.group_play_chain_title)) },
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::updateTitle,
                        label = { Text(stringResource(R.string.group_play_chain_title_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.topic,
                        onValueChange = viewModel::updateTopic,
                        label = { Text(stringResource(R.string.group_play_chain_topic_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.createChain() },
                        enabled = !state.creating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.creating) CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        else Text(stringResource(R.string.group_play_chain_create))
                    }
                }
            }

            state.error?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error) }
            }

            if (state.loading) {
                item { CircularProgressIndicator(modifier = Modifier.padding(24.dp)) }
            }

            items(state.chains, key = { it.id }) { chain ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.openChain(chain.id) }
                        .padding(vertical = 8.dp)
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(chain.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (chain.myJoined) {
                            Text(
                                stringResource(R.string.group_play_chain_joined),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        "${chain.entryCount}/${chain.maxEntries} · ${chain.topic}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                HorizontalDivider()
            }

            // 接龙明细（内嵌展示）
            val detail = state.detail
            if (detail != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            Text("${detail.title}（${detail.entryCount}/${detail.maxEntries}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text("✕", modifier = Modifier.clickable { viewModel.closeDetail() })
                        }
                        detail.entries.forEach { entry ->
                            Text(
                                "${entry.sequence}. ${entry.userId}: ${entry.content}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (detail.active && !detail.myJoined) {
                            OutlinedTextField(
                                value = state.entryInput,
                                onValueChange = viewModel::updateEntryInput,
                                label = { Text(stringResource(R.string.group_play_chain_entry_hint)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { viewModel.joinChain() },
                                enabled = !detail.submitting,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.group_play_chain_join))
                            }
                        } else if (detail.myJoined) {
                            Text(stringResource(R.string.group_play_chain_joined), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
