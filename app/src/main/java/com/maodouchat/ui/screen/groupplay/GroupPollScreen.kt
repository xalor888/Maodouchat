package com.maodouchat.ui.screen.groupplay

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maodouchat.R
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.util.GroupPollPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 群投票列表项（服务端 PollSnapshot 的轻量客户端模型）。 */
data class PollListItem(
    val id: String,
    val question: String,
    val options: List<String>,
    val multi: Boolean,
    val anonymous: Boolean,
    val closed: Boolean,
    val counts: List<Int>,
    val totalVoters: Int,
    val myVotes: List<Int>
)

class GroupPollViewModel(application: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(application) {

    val chatId: String = savedStateHandle["chatId"] ?: ""

    data class UiState(
        val loading: Boolean = true,
        val error: String? = null,
        val polls: List<PollListItem> = emptyList(),
        val question: String = "",
        val options: MutableList<String> = mutableListOf("", ""),
        val multi: Boolean = false,
        val anonymous: Boolean = false,
        val creating: Boolean = false,
        val createdShare: String? = null,
        val votingPollId: String? = null
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
            val text = GroupPlayHttp.get(token(), "/api/chats/$chatId/polls/sync")
            if (text == null) {
                _uiState.value = _uiState.value.copy(loading = false, error = str(R.string.group_play_load_failed))
                return@launch
            }
            val polls = runCatching { parsePolls(text) }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(loading = false, polls = polls)
        }
    }

    fun updateQuestion(q: String) {
        _uiState.value = _uiState.value.copy(question = q)
    }

    fun updateOption(index: Int, value: String) {
        val options = _uiState.value.options.toMutableList()
        if (index in options.indices) options[index] = value
        _uiState.value = _uiState.value.copy(options = options)
    }

    fun addOption() {
        val options = _uiState.value.options.toMutableList()
        if (options.size < 12) options.add("")
        _uiState.value = _uiState.value.copy(options = options)
    }

    fun removeOption(index: Int) {
        val options = _uiState.value.options.toMutableList()
        if (options.size > 2 && index in options.indices) options.removeAt(index)
        _uiState.value = _uiState.value.copy(options = options)
    }

    fun toggleMulti() {
        _uiState.value = _uiState.value.copy(multi = !_uiState.value.multi)
    }

    fun toggleAnonymous() {
        _uiState.value = _uiState.value.copy(anonymous = !_uiState.value.anonymous)
    }

    fun createPoll() {
        val s = _uiState.value
        val question = s.question.trim()
        val options = GroupPollPolicy.sanitizePollOptions(s.options)
        if (!GroupPollPolicy.isValidPollQuestion(question) || !GroupPollPolicy.isValidPollOptions(options)) {
            _uiState.value = s.copy(error = str(R.string.group_play_poll_invalid))
            return
        }
        if (s.creating) return
        _uiState.value = s.copy(creating = true, error = null)
        viewModelScope.launch {
            val result = ApiService.createGroupPoll(token(), chatId, question, options, s.multi, s.anonymous)
            val pollId = result.getOrNull()
            if (pollId == null) {
                _uiState.value = _uiState.value.copy(creating = false, error = str(R.string.group_play_create_failed))
            } else {
                val share = GroupPollPolicy.formatPollShare(pollId, question, options, s.multi)
                _uiState.value = _uiState.value.copy(
                    creating = false,
                    createdShare = share,
                    question = "",
                    options = mutableListOf("", "")
                )
                refresh()
            }
        }
    }

    fun clearCreatedShare() {
        _uiState.value = _uiState.value.copy(createdShare = null)
    }

    fun vote(pollId: String, indexes: List<Int>) {
        if (_uiState.value.votingPollId != null) return
        _uiState.value = _uiState.value.copy(votingPollId = pollId)
        viewModelScope.launch {
            val result = ApiService.voteGroupPoll(token(), pollId, indexes)
            // 9.135：成功路径也必须复位投票中标记（此前仅失败分支清除——
            // 首次投票成功后 spinner 永久卡住且 guard 拦截后续所有投票）
            _uiState.value = _uiState.value.copy(votingPollId = null)
            if (result.isSuccess) refresh() else {
                _uiState.value = _uiState.value.copy(error = str(R.string.group_play_vote_failed))
            }
        }
    }

    private fun parsePolls(text: String): List<PollListItem> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val options = o.optJSONArray("options")?.let { ja ->
                (0 until ja.length()).map { ja.optString(it) }
            }.orEmpty()
            val counts = o.optJSONArray("counts")?.let { ja ->
                (0 until ja.length()).map { ja.optInt(it) }
            }.orEmpty()
            val myVotes = o.optJSONArray("myVotes")?.let { ja ->
                (0 until ja.length()).map { ja.optInt(it) }
            }.orEmpty()
            PollListItem(
                id = o.optString("id"),
                question = o.optString("question"),
                options = options,
                multi = o.optBoolean("multi"),
                anonymous = o.optBoolean("anonymous"),
                closed = o.optBoolean("closed"),
                counts = counts,
                totalVoters = o.optInt("totalVoters"),
                myVotes = myVotes
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPollScreen(
    onBack: () -> Unit,
    viewModel: GroupPollViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_play_poll_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.group_play_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 创建投票
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.question,
                        onValueChange = viewModel::updateQuestion,
                        label = { Text(stringResource(R.string.group_play_poll_question_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.options.forEachIndexed { index, opt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = opt,
                                onValueChange = { viewModel.updateOption(index, it) },
                                label = { Text(stringResource(R.string.group_play_poll_option_hint, index + 1)) },
                                modifier = Modifier.weight(1f)
                            )
                            if (state.options.size > 2) {
                                Text(
                                    text = "✕",
                                    modifier = Modifier.padding(start = 8.dp).clickable { viewModel.removeOption(index) }
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.group_play_poll_add_option),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { viewModel.addOption() })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.multi, onCheckedChange = { viewModel.toggleMulti() })
                        Text(stringResource(R.string.group_play_poll_multi))
                        Spacer(Modifier.width(16.dp))
                        Checkbox(checked = state.anonymous, onCheckedChange = { viewModel.toggleAnonymous() })
                        Text(stringResource(R.string.group_play_poll_anonymous))
                    }
                    Button(
                        onClick = { viewModel.createPoll() },
                        enabled = !state.creating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.creating) CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                        else Text(stringResource(R.string.group_play_poll_create))
                    }
                }
            }

            state.createdShare?.let { share ->
                item {
                    Column {
                        Text(share, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = viewModel::clearCreatedShare) {
                            Text(stringResource(R.string.group_play_close))
                        }
                    }
                }
            }

            state.error?.let { err ->
                item { Text(err, color = MaterialTheme.colorScheme.error) }
            }

            if (state.loading) {
                item { CircularProgressIndicator(modifier = Modifier.padding(24.dp)) }
            }

            item {
                Text(stringResource(R.string.group_play_poll_vote), style = MaterialTheme.typography.titleMedium)
            }
            items(state.polls, key = { it.id }) { poll ->
                PollCard(
                    poll = poll,
                    voting = state.votingPollId == poll.id,
                    onVote = { indexes -> viewModel.vote(poll.id, indexes) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PollCard(poll: PollListItem, voting: Boolean, onVote: (List<Int>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(poll.question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (poll.closed) Text(stringResource(R.string.group_play_closed), color = MaterialTheme.colorScheme.outline)
        }
        poll.options.forEachIndexed { index, option ->
            val count = poll.counts.getOrElse(index) { 0 }
            val selected = index in poll.myVotes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !poll.closed && !voting) { onVote(listOf(index)) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${if (selected) "●" else "○"} $option  ($count)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Text(
            stringResource(R.string.group_play_voters_count, poll.totalVoters),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        if (voting) CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp))
    }
}
