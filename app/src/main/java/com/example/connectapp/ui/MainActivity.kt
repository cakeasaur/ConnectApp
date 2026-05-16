package com.example.connectapp.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.connectapp.R
import com.example.connectapp.ui.bluetooth.BluetoothActivity
import com.example.connectapp.ui.history.HistoryActivity
import com.example.connectapp.ui.test.TestActivity
import com.example.connectapp.ui.theme.AppThemeWithSettings
import com.example.connectapp.ui.wifi.WifiActivity

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppThemeWithSettings {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.main_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            ConnectOption(
                icon = Icons.Filled.Wifi,
                title = stringResource(R.string.btn_wifi),
                subtitle = stringResource(R.string.wifi_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.tertiary
                ),
                onClick = { ctx.startActivity(Intent(ctx, WifiActivity::class.java)) }
            )

            ConnectOption(
                icon = Icons.Filled.Bluetooth,
                title = stringResource(R.string.btn_bluetooth),
                subtitle = stringResource(R.string.bluetooth_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.primary
                ),
                onClick = { ctx.startActivity(Intent(ctx, BluetoothActivity::class.java)) }
            )

            ConnectOption(
                icon = Icons.Filled.Science,
                title = stringResource(R.string.btn_test),
                subtitle = stringResource(R.string.test_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.secondary
                ),
                onClick = { ctx.startActivity(Intent(ctx, TestActivity::class.java)) }
            )

            ConnectOption(
                icon = Icons.Filled.History,
                title = stringResource(R.string.btn_history),
                subtitle = stringResource(R.string.history_subtitle),
                gradient = listOf(
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.tertiary
                ),
                onClick = { ctx.startActivity(Intent(ctx, HistoryActivity::class.java)) }
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = stringResource(R.string.main_footer),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    gradient: List<androidx.compose.ui.graphics.Color>,
    onClick: () -> Unit
) {
    // Card(onClick=) даёт ripple, focus и роль Button для TalkBack.
    // Раньше pointerInput { detectTapGestures } лишал accessibility.
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp)
            .semantics { role = Role.Button },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(brush = Brush.linearGradient(gradient), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
