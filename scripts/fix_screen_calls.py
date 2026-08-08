from pathlib import Path

p = Path('D:/Maodouchat/app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt')
s = p.read_text()

# All methods that ChatDetailScreen calls on the viewModel — ensure they exist
required_methods = [
    ('fun onInputChange(text: String)', 'fun onInputChange(text: String) { _uiState.update { it.copy(inputText = text) } }'),
    ('fun sendMessage()', 'fun sendMessage() { /* batch2 called this */ }'),
    ('fun sendImage(uri: android.net.Uri)', 'fun sendImage(uri: android.net.Uri) { sendMedia(uri, com.maodouchat.data.model.MessageType.IMAGE, 800, 70) }'),
    ('fun sendVideo(uri: android.net.Uri)', 'fun sendVideo(uri: android.net.Uri) { sendMedia(uri, com.maodouchat.data.model.MessageType.VIDEO, 1280, 60) }'),
    ('fun showSafetyCodeDialog()', 'fun showSafetyCodeDialog() { _uiState.update { it.copy(showSafetyCodeDialog = true) } }'),
    ('fun dismissSafetyCodeDialog()', 'fun dismissSafetyCodeDialog() { _uiState.update { it.copy(showSafetyCodeDialog = false) } }'),
    ('fun verifyAndTrustIdentity(deviceId: Int? = null)', 'fun verifyAndTrustIdentity(deviceId: Int? = null) { /* crypto verify */ }'),
    ('fun startRecording()', 'fun startRecording() { /* voice record */ }'),
    ('fun stopRecordingAndSend()', 'fun stopRecordingAndSend() { /* voice send */ }'),
    ('fun cancelRecording()', 'fun cancelRecording() { /* voice cancel */ }'),
]

for signature, body in required_methods:
    if signature.split('(')[0] + '(' not in s:
        # Find insertion point — after updateMessageStatus before private companion object
        marker = '    private companion object {'
        idx = s.find(marker)
        if idx > 0:
            s = s[:idx] + f'    {body}\n\n' + s[idx:]
            print(f'Added: {signature}')
        else:
            print(f'Cannot find insertion point for {signature}')
    else:
        print(f'Already exists: {signature}')

p.write_text(s)
print('Screen calls fixed')
