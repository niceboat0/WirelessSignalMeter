package com.craxiom.networksurvey.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craxiom.networksurvey.BuildConfig
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.ui.common.AppDialog
import com.craxiom.networksurvey.ui.common.PreferenceChevron
import com.craxiom.networksurvey.ui.common.PreferenceDivider
import com.craxiom.networksurvey.ui.common.PreferenceLink
import com.craxiom.networksurvey.ui.common.PreferenceRow
import com.craxiom.networksurvey.update.GitHubReleaseChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator

@Composable
internal fun AboutSettings(onNavigateToAcknowledgments: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<GitHubReleaseChecker.Result?>(null) }
    var showResult by remember { mutableStateOf(false) }

    val checkForUpdate: () -> Unit = {
        if (!checking) {
            checking = true
            scope.launch {
                try {
                    val checked = GitHubReleaseChecker.check(BuildConfig.VERSION_NAME)
                    result = checked
                    showResult = checked !is GitHubReleaseChecker.Result.UpToDate
                    if (checked is GitHubReleaseChecker.Result.UpToDate) {
                        Toast.makeText(context, R.string.github_update_up_to_date, Toast.LENGTH_SHORT).show()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    result = GitHubReleaseChecker.Result.Error(GitHubReleaseChecker.Failure.NETWORK)
                    showResult = true
                } finally {
                    checking = false
                }
            }
        }
    }

    PreferenceRow(
        Icons.Rounded.Info,
        stringResource(R.string.app_name),
        stringResource(R.string.github_update_current_version, BuildConfig.VERSION_NAME),
    )
    PreferenceDivider()
    PreferenceRow(
        Icons.Rounded.SystemUpdate,
        stringResource(R.string.github_update_check),
        summary = when {
            checking -> stringResource(R.string.github_update_checking)
            result is GitHubReleaseChecker.Result.Available -> stringResource(
                R.string.github_update_available,
                (result as GitHubReleaseChecker.Result.Available).release.versionName,
            )
            result is GitHubReleaseChecker.Result.UpToDate -> stringResource(R.string.github_update_up_to_date)
            result is GitHubReleaseChecker.Result.NoPublishedRelease -> stringResource(R.string.github_update_no_release)
            result is GitHubReleaseChecker.Result.Error -> stringResource(
                (result as GitHubReleaseChecker.Result.Error).reason.messageResource(),
            )
            else -> null
        },
        enabled = !checking,
        onClick = checkForUpdate,
        trailing = {
            if (checking) {
                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
            } else {
                PreferenceChevron()
            }
        },
    )
    PreferenceDivider()
    PreferenceRow(
        Icons.Rounded.Feedback,
        stringResource(R.string.github_feedback),
        onClick = { openGitHubLink(context, GitHubReleaseChecker.ISSUES_URL) },
        trailing = {
            Icon(
                Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    PreferenceDivider()
    PreferenceLink(
        Icons.Rounded.VolunteerActivism,
        stringResource(R.string.acknowledgments_title),
        onClick = onNavigateToAcknowledgments,
    )

    val closeDialog = { showResult = false }
    val cancel = stringResource(android.R.string.cancel)
    when (val checked = result) {
        is GitHubReleaseChecker.Result.Available -> {
            val release = checked.release
            AppDialog(
                show = showResult,
                title = stringResource(R.string.github_update_available, release.versionName),
                onDismissRequest = closeDialog,
                confirmText = stringResource(
                    if (release.apkUrl != null) R.string.github_update_download else R.string.github_update_view_release,
                ),
                dismissText = cancel,
                onConfirm = {
                    if (openGitHubLink(context, release.apkUrl ?: release.releasePageUrl)) closeDialog()
                },
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.github_update_current_version, BuildConfig.VERSION_NAME), letterSpacing = 0.sp)
                    if (release.releaseNotes.isNotBlank()) {
                        Text(release.releaseNotes, style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp))
                    }
                }
            }
        }
        GitHubReleaseChecker.Result.NoPublishedRelease -> AppDialog(
            show = showResult,
            title = stringResource(R.string.github_update_check),
            onDismissRequest = closeDialog,
            confirmText = stringResource(R.string.github_update_view_release),
            dismissText = cancel,
            onConfirm = {
                if (openGitHubLink(context, GitHubReleaseChecker.RELEASES_URL)) closeDialog()
            },
        ) { Text(stringResource(R.string.github_update_no_release), letterSpacing = 0.sp) }
        is GitHubReleaseChecker.Result.Error -> AppDialog(
            show = showResult,
            title = stringResource(R.string.github_update_failed),
            onDismissRequest = closeDialog,
            confirmText = stringResource(R.string.github_update_retry),
            dismissText = cancel,
            onConfirm = {
                closeDialog()
                checkForUpdate()
            },
        ) { Text(stringResource(checked.reason.messageResource()), letterSpacing = 0.sp) }
        else -> Unit
    }
}

@StringRes
private fun GitHubReleaseChecker.Failure.messageResource(): Int = when (this) {
    GitHubReleaseChecker.Failure.NETWORK -> R.string.github_update_network_error
    GitHubReleaseChecker.Failure.RATE_LIMITED -> R.string.github_update_rate_limited
    GitHubReleaseChecker.Failure.SERVER -> R.string.github_update_server_error
    GitHubReleaseChecker.Failure.INVALID_RESPONSE -> R.string.github_update_invalid_response
}

private fun openGitHubLink(context: Context, url: String): Boolean {
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.external_link_unavailable, Toast.LENGTH_SHORT).show()
        false
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.external_link_unavailable, Toast.LENGTH_SHORT).show()
        false
    }
}
