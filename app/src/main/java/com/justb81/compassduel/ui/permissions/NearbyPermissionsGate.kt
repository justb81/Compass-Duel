package com.justb81.compassduel.ui.permissions

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.justb81.compassduel.R

/**
 * Runtime-permission gate for the Nearby Connections API.
 *
 * On minSdk 35 all required Bluetooth and Wi-Fi permissions are runtime permissions:
 * BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, and NEARBY_WIFI_DEVICES.
 *
 * If all permissions are already granted, [content] is shown directly. Otherwise a
 * rationale prompt is displayed and the system permission dialog is launched.
 * After permanent denial, an "Open settings" button directs the user to app settings
 * (ACTION_APPLICATION_DETAILS_SETTINGS).
 *
 * @param content Shown once all permissions are granted.
 */
@Composable
fun NearbyPermissionsGate(content: @Composable () -> Unit) {
    val context = LocalContext.current

    fun allGranted(): Boolean = NEARBY_PERMISSIONS.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED
    }

    var granted by rememberSaveable { mutableStateOf(allGranted()) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val nowGranted = results.values.all { it }
        granted = nowGranted
        if (!nowGranted) permanentlyDenied = true
    }

    if (granted) {
        content()
        return
    }

    LaunchedEffect(Unit) {
        launcher.launch(NEARBY_PERMISSIONS.toTypedArray())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CONTENT_HORIZONTAL_PADDING_DP.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (permanentlyDenied) {
                stringResource(R.string.permissions_denied_message)
            } else {
                stringResource(R.string.permissions_rationale)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(SPACER_HEIGHT_DP.dp))
        if (!permanentlyDenied) {
            Button(onClick = { launcher.launch(NEARBY_PERMISSIONS.toTypedArray()) }) {
                Text(text = stringResource(R.string.permissions_grant_button))
            }
        } else {
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
            ) {
                Text(text = stringResource(R.string.permissions_open_settings))
            }
        }
    }
}

private val NEARBY_PERMISSIONS = listOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.NEARBY_WIFI_DEVICES,
)

private const val CONTENT_HORIZONTAL_PADDING_DP = 32
private const val SPACER_HEIGHT_DP = 16
