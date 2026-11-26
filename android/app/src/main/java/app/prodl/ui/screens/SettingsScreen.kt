package app.prodl.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.prodl.BuildConfig
import app.prodl.CookieLoginActivity
import app.prodl.core.ComponentStatus
import app.prodl.ui.SettingsViewModel
import app.prodl.ui.components.StatusPill
import app.prodl.ui.theme.MonoStyle
import app.prodl.ui.theme.ProDLColors
import kotlinx.coroutines.delay

/** Engine status, cookies, notifications and the optional loopback server. */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = it
    }

    // Engine state lives in memory, so a light ticker keeps the cards honest
    // (yt-dlp finishes unpacking a few seconds after launch).
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(2_000)
        }
    }

    val health = state.health
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Engines", style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text)
        EngineCard(
            title = "NewPipeExtractor",
            status = health?.newpipe,
            fallbackNote = "YouTube search, watch and downloads. Pure Java, always available.",
            version = BuildConfig.NEWPIPE_VERSION,
        )
        EngineCard(
            title = "yt-dlp",
            status = health?.ytDlp,
            fallbackNote = if (BuildConfig.SLIM) {
                "Not bundled in the slim build."
            } else {
                "Adds the 1000+ sites NewPipe does not cover."
            },
        )
        EngineCard(
            title = "Native transcoder",
            status = health?.transcoder,
            fallbackNote = "MediaMuxer, Media3 Transformer, MediaCodec and LAME — no ffmpeg binary.",
        )

        Text("Account", style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ProDLColors.Card)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Cookies",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ProDLColors.Text,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    if (state.cookiesConfigured) "Linked" else "Not linked",
                    if (state.cookiesConfigured) ProDLColors.Ready else ProDLColors.TextDim,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (state.cookiesConfigured) {
                    "${state.cookieCount} cookies · updated ${state.cookiesUpdatedAt ?: "just now"}"
                } else {
                    "Sign in once to unlock age-restricted videos and your private playlists."
                },
                style = MaterialTheme.typography.bodySmall,
                color = ProDLColors.TextDim,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { context.startActivity(Intent(context, CookieLoginActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Link, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.cookiesConfigured) "Re-link YouTube account" else "Link YouTube account")
            }
            if (state.cookiesConfigured) {
                TextButton(onClick = viewModel::clearCookies) {
                    Icon(Icons.Filled.CleaningServices, null, Modifier.size(16.dp), tint = ProDLColors.Danger)
                    Spacer(Modifier.width(6.dp))
                    Text("Clear cookies", color = ProDLColors.Danger)
                }
            }
        }

        Text("Notifications", style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ProDLColors.Card)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Notifications, null, tint = ProDLColors.TextDim)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Download progress", style = MaterialTheme.typography.bodyLarge, color = ProDLColors.Text)
                Text(
                    if (notificationsGranted) "Allowed" else "Off — progress stays inside the app",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProDLColors.TextDim,
                )
            }
            if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Allow")
                }
            }
        }

        Text("Local server (optional)", style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ProDLColors.Card)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Loopback API", style = MaterialTheme.typography.bodyLarge, color = ProDLColors.Text)
                Text(
                    text = if (state.serverRunning) {
                        "Listening on 127.0.0.1:${state.port} — for remote control or casting later"
                    } else {
                        "Off. The native UI talks to the engines directly."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ProDLColors.TextDim,
                )
            }
            Switch(
                checked = state.serverRunning,
                onCheckedChange = { enabled ->
                    if (enabled) viewModel.startServer(context) else viewModel.stopServer(context)
                },
            )
        }

        Text("About", style = MaterialTheme.typography.titleMedium, color = ProDLColors.Text)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ProDLColors.Card)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, tint = ProDLColors.TextDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "ProDL ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = ProDLColors.Text,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp), tint = ProDLColors.TextDim)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "AGPL-3.0 · NewPipeExtractor (GPL-3.0) · jump3r (LGPL) · Media3 (Apache-2.0).\n" +
                    "No Google Play Services, no Firebase, no ads, no analytics.",
                style = MaterialTheme.typography.bodySmall,
                color = ProDLColors.TextDim,
            )
            if (health != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "API ${health.apiVersion} · ${health.platform} · ${health.downloadDir}",
                    style = MonoStyle,
                    color = ProDLColors.TextDim,
                )
            }
        }
    }
}

@Composable
private fun EngineCard(
    title: String,
    status: ComponentStatus?,
    fallbackNote: String,
    version: String? = null,
) {
    val available = status?.available == true
    val note = status?.note?.takeIf { it.isNotBlank() } ?: fallbackNote
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ProDLColors.Card)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = ProDLColors.Text)
                Spacer(Modifier.width(8.dp))
                StatusPill(
                    when {
                        available -> "Ready"
                        status?.state == "initialising" -> "Starting"
                        status == null -> "Unknown"
                        else -> "Unavailable"
                    },
                    when {
                        available -> ProDLColors.Ready
                        status?.state == "initialising" -> ProDLColors.Warning
                        else -> ProDLColors.TextDim
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = ProDLColors.TextDim)
            val shownVersion = status?.version ?: version
            if (!shownVersion.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(shownVersion, style = MonoStyle, color = ProDLColors.TextDim)
            }
        }
    }
}
