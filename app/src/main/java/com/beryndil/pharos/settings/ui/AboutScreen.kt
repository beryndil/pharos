package com.beryndil.pharos.settings.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.BuildConfig
import com.beryndil.pharos.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onOpenLicense: () -> Unit,
    onOpenLegal: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.ClearWindowSecurity()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val backCd = stringResource(R.string.cd_back_button)
    val uriHandler = LocalUriHandler.current

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.screen_about)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backCd },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // ── App identity ─────────────────────────────────────────────────
            item(key = "app_info") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // painterResource(R.mipmap.ic_launcher) crashes on API 26+ because the
                    // mipmap resolves to an adaptive-icon XML that Compose can't decode.
                    // Render the adaptive icon manually: black background + white foreground vector.
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_version_label) + " " + BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_companion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.about_author),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://hardknocks.university/developer.html")
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    Image(
                        painter = painterResource(R.drawable.about_beryndil),
                        contentDescription = stringResource(R.string.about_author_name),
                        modifier = Modifier
                            .size(160.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .clickable { uriHandler.openUri("https://hardknocks.university/developer.html") }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.about_enjoy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.about_support),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            // ── Medical disclaimer link ───────────────────────────────────────
            item(key = "disclaimer") {
                Text(
                    text = stringResource(R.string.about_medical_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLegal() },
                )
            }

            // ── Data sources ─────────────────────────────────────────────────
            item(key = "data_sources") {
                AboutSectionHeader(stringResource(R.string.about_section_data_sources))
                AboutCard {
                    Text(
                        text = stringResource(R.string.about_rxnorm_attribution),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AboutDivider()
                    Text(
                        text = stringResource(R.string.about_openfda_attribution),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Credits & license ────────────────────────────────────────────
            item(key = "credits") {
                AboutSectionHeader(stringResource(R.string.about_section_credits))
                AboutCard {
                    Text(
                        text = stringResource(R.string.about_app_name),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.about_author),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = stringResource(R.string.about_license_type),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenLicense() },
                    )
                }
            }

            // ── Copyright ────────────────────────────────────────────────────
            item(key = "copyright") {
                Text(
                    text = stringResource(R.string.about_copyright),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── Privacy ──────────────────────────────────────────────────────
            item(key = "privacy") {
                AboutSectionHeader(stringResource(R.string.about_section_privacy))
                AboutCard {
                    Text(
                        text = stringResource(R.string.about_privacy_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_privacy_policy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://hardknocks.university/privacy-policy.html")
                        },
                    )
                }
            }

            // ── Feedback ─────────────────────────────────────────────────────
            item(key = "feedback") {
                AboutSectionHeader(stringResource(R.string.about_section_feedback))
                AboutCard {
                    val issuesUrl = "https://github.com/beryndil/pharos/issues"
                    Text(
                        text = stringResource(R.string.about_report_bug),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri(issuesUrl) }
                            .padding(vertical = 4.dp),
                    )
                    AboutDivider()
                    Text(
                        text = stringResource(R.string.about_request_feature),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uriHandler.openUri(issuesUrl) }
                            .padding(vertical = 4.dp),
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun AboutSectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 0.5.dp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AboutCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
}
