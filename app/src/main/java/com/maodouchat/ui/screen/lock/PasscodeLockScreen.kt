package com.maodouchat.ui.screen.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.maodouchat.R
import com.maodouchat.security.AppLockManager
import com.maodouchat.ui.theme.LocalMotionSettings
import com.maodouchat.ui.theme.Primary

/** Full-screen privacy gate using strong biometrics or the system device credential. */
@Composable
fun PasscodeLockScreen(
    onUnlocked: () -> Unit = {},
    onFailed: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val motion = LocalMotionSettings.current
    val currentOnUnlocked by rememberUpdatedState(onUnlocked)
    val currentOnFailed by rememberUpdatedState(onFailed)
    val lockAuthFailedMsg = stringResource(R.string.lock_auth_failed)
    val lockAuthCancelledMsg = stringResource(R.string.lock_auth_cancelled)
    val lockAuthUnavailableMsg = stringResource(R.string.lock_auth_unavailable)
    val lockPromptTitle = stringResource(R.string.lock_prompt_title)
    val lockCredentialSubtitle = stringResource(R.string.lock_credential_subtitle)
    var isAuthenticating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var failedPulse by remember { mutableStateOf(false) }
    val shakeScale by animateFloatAsState(
        targetValue = if (failedPulse) 0.94f else 1f,
        animationSpec = if (motion.animationsEnabled) spring(dampingRatio = 0.45f, stiffness = 520f) else snap(),
        label = "lockFailurePulse"
    )

    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val biometricPrompt = remember(activity, executor, lockAuthFailedMsg, lockAuthCancelledMsg) {
        activity?.let { host ->
            BiometricPrompt(host, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isAuthenticating = false
                    errorMessage = null
                    currentOnUnlocked()
                }

                override fun onAuthenticationFailed() {
                    failedPulse = !failedPulse
                    errorMessage = lockAuthFailedMsg
                    currentOnFailed()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    isAuthenticating = false
                    failedPulse = !failedPulse
                    errorMessage = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> lockAuthCancelledMsg
                        else -> errString.toString().takeIf(String::isNotBlank)
                            ?: lockAuthFailedMsg
                    }
                    currentOnFailed()
                }
            })
        }
    }
    val promptInfo = remember(lockPromptTitle, lockCredentialSubtitle) {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(lockPromptTitle)
            .setSubtitle(lockCredentialSubtitle)
            .setAllowedAuthenticators(AppLockManager.authenticators())
            .setConfirmationRequired(false)
            .build()
    }

    fun launchAuthentication() {
        if (isAuthenticating) return
        if (activity == null || biometricPrompt == null || !AppLockManager.isAuthenticationAvailable(context)) {
            errorMessage = lockAuthUnavailableMsg
            failedPulse = !failedPulse
            currentOnFailed()
            return
        }
        errorMessage = null
        isAuthenticating = true
        runCatching { biometricPrompt.authenticate(promptInfo) }
            .onFailure {
                isAuthenticating = false
                errorMessage = lockAuthFailedMsg
                failedPulse = !failedPulse
                currentOnFailed()
            }
    }

    DisposableEffect(biometricPrompt) {
        onDispose { biometricPrompt?.cancelAuthentication() }
    }
    LaunchedEffect(biometricPrompt) { launchAuthentication() }

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.scale(shakeScale)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(88.dp).background(Primary.copy(alpha = 0.12f), CircleShape)
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = Primary, modifier = Modifier.size(40.dp))
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.lock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = ::launchAuthentication, enabled = !isAuthenticating) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.lock_unlock_action))
            }
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
