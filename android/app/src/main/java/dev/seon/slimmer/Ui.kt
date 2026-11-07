package dev.seon.slimmer

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun SlimmerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(resumeTick: Int, incomingCode: String?, onIncomingCodeHandled: () -> Unit) {
    val ctx = LocalContext.current
    val pairing by Prefs.pairingFlow.collectAsState()
    val link by Hub.link.collectAsState()
    var pendingCode by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun tryPair(code: String) {
        val p = Pairing.parse(code)
        if (p == null) {
            error = "올바른 Slimmer 페어링 코드가 아닙니다"
        } else {
            pendingCode = null
            Prefs.setPairing(p)
            LinkService.start(ctx)
        }
    }

    LaunchedEffect(incomingCode) {
        if (incomingCode != null) {
            pendingCode = incomingCode
            onIncomingCodeHandled()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Slimmer") }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(pairing, link)
            PairingCard(pairing, onCode = { pendingCode = it }, onUnpair = { Prefs.setPairing(null) })
            PermissionsCard(resumeTick)
            OptionsCard()
            Text(
                "알림과 문자는 종단간 암호화되어 전송됩니다. 릴레이 서버는 내용을 볼 수 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingCode?.let { code ->
        val p = Pairing.parse(code)
        AlertDialog(
            onDismissRequest = { pendingCode = null },
            title = { Text("이 Mac과 연결할까요?") },
            text = {
                if (p == null) {
                    Text("올바른 Slimmer 페어링 코드가 아닙니다.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.macName, fontWeight = FontWeight.Bold)
                        Text("확인 코드: ${p.fingerprint}", fontFamily = FontFamily.Monospace)
                        Text("Mac 화면의 확인 코드와 같은지 확인하세요.", style = MaterialTheme.typography.bodySmall)
                        Text("릴레이: ${p.relayHost ?: "없음 (같은 네트워크에서만 연결)"}", style = MaterialTheme.typography.bodySmall)
                        p.direct?.let { Text("직접 주소: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                if (p != null) TextButton(onClick = { tryPair(code) }) { Text("연결") }
            },
            dismissButton = { TextButton(onClick = { pendingCode = null }) { Text("취소") } },
        )
    }

    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("확인") } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusCard(pairing: Pairing?, link: LinkState) {
    val ok = link.macOnline
    val color = when {
        ok -> Color(0xFF2E7D32)
        link.route != Route.NONE -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.outline
    }
    SectionCard("상태") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    when {
                        pairing == null -> "Mac과 페어링되지 않음"
                        ok -> "${pairing.macName}에 연결됨"
                        else -> "${pairing.macName} 연결 대기 중"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                val detail = if (pairing == null) "아래에서 Mac의 QR 코드를 스캔하세요" else link.detail
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (pairing != null && !ok) {
            val ctx = LocalContext.current
            OutlinedButton(onClick = { LinkService.start(ctx, LinkService.ACTION_RECONNECT) }) { Text("지금 다시 연결") }
        }
    }
}

@Composable
private fun PairingCard(pairing: Pairing?, onCode: (String) -> Unit, onUnpair: () -> Unit) {
    val ctx = LocalContext.current
    var showPaste by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onCode)
    }

    SectionCard("Mac 페어링") {
        if (pairing == null) {
            Text(
                "Mac의 메뉴바에서 Slimmer → 설정을 열고 QR 코드를 스캔하세요.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scan.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Mac의 Slimmer QR 코드를 비추세요")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                }) { Text("QR 스캔") }
                OutlinedButton(onClick = { showPaste = true }) { Text("코드 붙여넣기") }
            }
        } else {
            InfoRow("Mac", pairing.macName)
            InfoRow("확인 코드", pairing.fingerprint, mono = true)
            InfoRow("릴레이", pairing.relayHost ?: "없음 (같은 네트워크 전용)")
            pairing.direct?.let { InfoRow("직접 주소", it) }
            OutlinedButton(onClick = { confirmUnpair = true }) { Text("페어링 해제") }
        }
    }

    if (showPaste) {
        var text by remember {
            val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
            val s = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            mutableStateOf(if (s.startsWith("slimmer://")) s else "")
        }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("페어링 코드") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("slimmer://pair?…") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = { showPaste = false; onCode(text) }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("취소") } },
        )
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("페어링을 해제할까요?") },
            text = { Text("다시 연결하려면 Mac의 QR 코드를 다시 스캔해야 합니다.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; onUnpair() }) { Text("해제") } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row {
        Text(label, Modifier.width(88.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
private fun PermissionsCard(resumeTick: Int) {
    val ctx = LocalContext.current
    // Re-evaluated on every resume (and after runtime permission dialogs).
    var tick by remember { mutableStateOf(0) }
    val key = resumeTick + tick

    val listenerOn = remember(key) { NotifListener.isEnabled(ctx) }
    val smsOn = remember(key) { granted(ctx, Manifest.permission.READ_SMS) && granted(ctx, Manifest.permission.SEND_SMS) }
    val contactsOn = remember(key) { granted(ctx, Manifest.permission.READ_CONTACTS) }
    val postOn = remember(key) { Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS) }
    val batteryOn = remember(key) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    SectionCard("권한") {
        PermissionRow(
            "알림 접근",
            "폰의 알림을 Mac으로 전달하고 답장합니다",
            listenerOn,
        ) {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!listenerOn) {
            Text(
                "목록에서 Slimmer가 회색으로 막혀 있다면: 설정 → 애플리케이션 → Slimmer → 우측 상단 ⋮ → ‘제한된 설정 허용’ 후 다시 시도하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        PermissionRow("문자", "문자 목록 조회 및 보내기", smsOn) {
            request.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS))
        }
        PermissionRow("연락처", "문자 목록에 이름 표시", contactsOn) {
            request.launch(arrayOf(Manifest.permission.READ_CONTACTS))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            PermissionRow("알림 표시", "연결 상태 알림 (백그라운드 유지용)", postOn) {
                request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        PermissionRow("배터리 최적화 제외", "화면이 꺼져 있어도 연결을 유지합니다", batteryOn) {
            @Suppress("BatteryLife")
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            )
        }
    }
}

private fun granted(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionRow(title: String, desc: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (ok) Color(0xFF2E7D32) else Color(0xFFF9A825),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) FilledTonalButton(onClick = onFix) { Text("허용") }
    }
}

@Composable
private fun OptionsCard() {
    val notifs by Prefs.forwardNotifs.collectAsState()
    val sms by Prefs.smsEnabled.collectAsState()
    SectionCard("연동") {
        SwitchRow("알림 전달", "폰 알림을 Mac에 표시", notifs, Prefs::setForwardNotifs)
        SwitchRow("문자 연동", "Mac에서 문자 조회 및 보내기", sms, Prefs::setSmsEnabled)
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
