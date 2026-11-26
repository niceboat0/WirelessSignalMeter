package com.craxiom.networksurvey.ui.acknowledgments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.craxiom.networksurvey.R
import com.craxiom.networksurvey.model.LibraryAcknowledgment
import com.craxiom.networksurvey.ui.main.appbar.TitleBar
import com.craxiom.networksurvey.ui.theme.LocalInterfaceStyle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults

/**
 * Main Acknowledgments screen that displays all open source library acknowledgments.
 */
@Composable
fun AcknowledgmentsScreen(
    onNavigateUp: () -> Unit,
    viewModel: AcknowledgmentsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AcknowledgmentsContent(
        uiState = uiState,
        onNavigateUp = onNavigateUp
    )
}

@Composable
private fun AcknowledgmentsContent(
    uiState: AcknowledgmentsUiState,
    onNavigateUp: () -> Unit
) {
    val style = LocalInterfaceStyle.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TitleBar(
                title = stringResource(R.string.acknowledgments_title),
                compact = true,
                onBackClick = onNavigateUp,
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues).consumeWindowInsets(paddingValues),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = style.cardPadding, end = style.cardPadding,
                    top = style.parameterRowPadding, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(style.sectionSpacing),
            ) {
                item("introduction") {
                    LicenseIntroduction()
                }

                if (uiState.specialAcknowledgments.isNotEmpty()) {
                    item("special-heading") {
                        CategoryHeader(stringResource(R.string.acknowledgments_special_title))
                    }
                    items(uiState.specialAcknowledgments, key = { "special:${it.name}:${it.sourceUrl}" }) { library ->
                        LibraryCard(library)
                    }
                }

                if (uiState.coreLibraries.isNotEmpty()) {
                    item("core-heading") {
                        CategoryHeader(stringResource(R.string.acknowledgments_core_title))
                    }
                    items(uiState.coreLibraries, key = { "core:${it.name}:${it.sourceUrl}" }) { library ->
                        LibraryCard(library)
                    }
                }

                if (uiState.uiLibraries.isNotEmpty()) {
                    item("ui-heading") {
                        CategoryHeader(stringResource(R.string.acknowledgments_ui_title))
                    }
                    items(uiState.uiLibraries, key = { "ui:${it.name}:${it.sourceUrl}" }) { library ->
                        LibraryCard(library)
                    }
                }

                if (uiState.utilityLibraries.isNotEmpty()) {
                    item("utility-heading") {
                        CategoryHeader(stringResource(R.string.acknowledgments_utility_title))
                    }
                    items(uiState.utilityLibraries, key = { "utility:${it.name}:${it.sourceUrl}" }) { library ->
                        LibraryCard(library)
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseIntroduction() {
    val style = LocalInterfaceStyle.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = style.parameterRowPadding),
        verticalArrangement = Arrangement.spacedBy(style.parameterRowPadding),
    ) {
        Text(
            text = stringResource(R.string.acknowledgments_license_title),
            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.sp),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.acknowledgments_upstream_notice),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryHeader(title: String) {
    val style = LocalInterfaceStyle.current
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = style.parameterRowPadding, bottom = 2.dp)
            .semantics { heading() },
    )
}

@Composable
private fun LibraryCard(library: LibraryAcknowledgment) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val style = LocalInterfaceStyle.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = style.cardRadius,
        colors = CardDefaults.defaultColors(color = colors.surfaceContainerLow, contentColor = colors.onSurface),
    ) {
        BasicComponent(
            modifier = Modifier.heightIn(min = 48.dp),
            insideMargin = PaddingValues(horizontal = style.cardPadding, vertical = style.parameterRowPadding + 4.dp),
            onClick = { openAcknowledgmentLink(context, library.sourceUrl) },
            role = Role.Button,
            endActions = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.onSurfaceVariant,
                )
            },
        ) {
            Text(
                text = library.name,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = library.author,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
                color = colors.onSurfaceVariant,
            )
            Text(
                text = library.description,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
                color = colors.onSurfaceVariant,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = style.cardPadding),
            color = colors.outlineVariant.copy(alpha = 0.4f),
        )
        TextButton(
            onClick = { openAcknowledgmentLink(context, library.licenseUrl) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = style.cardPadding).heightIn(min = 48.dp),
            contentPadding = PaddingValues(vertical = style.parameterRowPadding),
        ) {
            Text(
                text = library.licenseName,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp),
                textAlign = TextAlign.Start,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun openAcknowledgmentLink(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.external_link_unavailable, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.external_link_unavailable, Toast.LENGTH_SHORT).show()
    }
}
