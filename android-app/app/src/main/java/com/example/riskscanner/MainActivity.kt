package com.example.riskscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AppBg = Color(0xFF071018)
private val Surface1 = Color(0xFF0D1822)
private val Surface2 = Color(0xFF13212D)
private val Border = Color(0xFF263848)
private val Gold = Color(0xFFD8B36A)
private val GoldSoft = Color(0xFFF0D59A)
private val TextPrimary = Color(0xFFF4F6F8)
private val TextSecondary = Color(0xFF9CAEBE)
private val Success = Color(0xFF62D99C)
private val Warning = Color(0xFFF0B34A)
private val Danger = Color(0xFFFF6B6B)

private val ErsColors = darkColorScheme(
    primary = Gold,
    onPrimary = AppBg,
    secondary = GoldSoft,
    background = AppBg,
    surface = Surface1,
    surfaceVariant = Surface2,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = Border
)

data class ExchangeOption(val name: String, val mark: String, val color: Color)

private val exchangeOptions = listOf(
    ExchangeOption("Binance", "◆", Color(0xFFF3BA2F)),
    ExchangeOption("Bybit", "BY", Color(0xFFF7A600)),
    ExchangeOption("OKX", "OK", Color(0xFFF4F4F4)),
    ExchangeOption("Bitget", "BG", Color(0xFF00D3B7)),
    ExchangeOption("BingX", "BX", Color(0xFF2D7CFF)),
    ExchangeOption("Toobit", "TB", Color(0xFF19C7B5)),
    ExchangeOption("CoinW", "CW", Color(0xFF2A75FF)),
    ExchangeOption("Deepcoin", "DC", Color(0xFF7258FF)),
    ExchangeOption("Gate.io", "G", Color(0xFF17C6B3)),
    ExchangeOption("MEXC", "M", Color(0xFF2F6BFF)),
    ExchangeOption("KuCoin", "K", Color(0xFF23AF91)),
    ExchangeOption("HTX", "H", Color(0xFF2B7FFF)),
    ExchangeOption("LBank", "LB", Color(0xFF2D74FF)),
    ExchangeOption("BitMart", "BM", Color(0xFF3464FF)),
    ExchangeOption("OURBIT", "OB", Color(0xFF7C5CFF)),
    ExchangeOption("Tabit", "TA", Color(0xFF39B5FF)),
    ExchangeOption("MGBX", "MG", Color(0xFFFF8A3D)),
    ExchangeOption("기타 (직접 입력)", "+", Gold)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 16, 24)
        window.navigationBarColor = android.graphics.Color.rgb(7, 16, 24)
        setContent {
            MaterialTheme(colorScheme = ErsColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = AppBg) {
                    RiskScannerScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RiskScannerScreen() {
        var selectedExchange by remember { mutableStateOf(exchangeOptions[5]) }
        var customExchange by remember { mutableStateOf("") }
        var exchangeExpanded by remember { mutableStateOf(false) }
        var accountId by remember { mutableStateOf("") }
        var kycCountry by remember { mutableStateOf("KR") }
        var snapshot by remember { mutableStateOf<DeviceSnapshot?>(null) }
        var result by remember { mutableStateOf<RiskResult?>(null) }

        val exchangeName = if (selectedExchange.name.startsWith("기타")) customExchange.ifBlank { "직접 입력" } else selectedExchange.name

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF09141E), AppBg, Color(0xFF050B10))
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PremiumHeader()
            OverviewCard(exchangeName, accountId, result)

            Text("EXCHANGE ACCOUNT", color = GoldSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)

            Card(
                colors = CardDefaults.cardColors(containerColor = Surface1),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Border, RoundedCornerShape(22.dp))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("거래소 계정 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("거래소를 직접 입력하지 않고 목록에서 선택할 수 있습니다.", color = TextSecondary, fontSize = 13.sp)

                    ExposedDropdownMenuBox(
                        expanded = exchangeExpanded,
                        onExpandedChange = { exchangeExpanded = !exchangeExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedExchange.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("거래소") },
                            leadingIcon = { ExchangeMark(selectedExchange) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = exchangeExpanded) },
                            colors = premiumFieldColors(),
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = exchangeExpanded,
                            onDismissRequest = { exchangeExpanded = false },
                            modifier = Modifier.background(Surface2)
                        ) {
                            exchangeOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            ExchangeMark(option)
                                            Text(option.name, color = TextPrimary)
                                        }
                                    },
                                    onClick = {
                                        selectedExchange = option
                                        exchangeExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (selectedExchange.name.startsWith("기타")) {
                        OutlinedTextField(
                            value = customExchange,
                            onValueChange = { customExchange = it },
                            label = { Text("거래소 이름 직접 입력") },
                            colors = premiumFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    OutlinedTextField(
                        value = accountId,
                        onValueChange = { accountId = it },
                        label = { Text("UID (Account ID)") },
                        supportingText = { Text("이메일 대신 거래소 UID를 입력하세요.") },
                        colors = premiumFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = kycCountry,
                        onValueChange = { kycCountry = it.uppercase().take(2) },
                        label = { Text("KYC 국가 코드") },
                        supportingText = { Text("예: KR, JP, US") },
                        colors = premiumFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            val s = DeviceInspector(this@MainActivity).snapshot()
                            snapshot = s
                            result = RiskEngine.evaluate(s, kycCountry)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = AppBg),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) {
                        Text("SCAN RISK", fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp)
                    }
                }
            }

            result?.let { r ->
                RiskResultCard(r)
            }

            snapshot?.let { s ->
                EnvironmentCard(s)
            }

            SecurityNote()
            BottomNavigationMock()
            Spacer(Modifier.height(8.dp))
        }
    }

    @Composable
    private fun PremiumHeader() {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF111A21)).border(1.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("ERS", color = GoldSoft, fontWeight = FontWeight.Black, fontSize = 14.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("ERS", color = GoldSoft, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 2.sp)
                Text("EXCHANGE RISK SCANNER", color = TextSecondary, fontSize = 10.sp, letterSpacing = 1.4.sp)
            }
            Box(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Success.copy(alpha = 0.10f)).border(1.dp, Success.copy(alpha = 0.35f), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("SECURE", color = Success, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun OverviewCard(exchangeName: String, uid: String, result: RiskResult?) {
        val level = result?.level ?: "READY"
        val accent = when (level) {
            "HIGH" -> Danger
            "MEDIUM" -> Warning
            "LOW" -> Success
            else -> Gold
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Border, RoundedCornerShape(22.dp))
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Overall Risk Level", color = TextSecondary, fontSize = 12.sp)
                        Text(level, color = accent, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                    }
                    Box(
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(accent.copy(alpha = 0.10f)).border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(result?.score?.toString() ?: "—", color = accent, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    }
                }
                HorizontalDivider(color = Border)
                Row(modifier = Modifier.fillMaxWidth()) {
                    InfoMini("Exchange", exchangeName, Modifier.weight(1f))
                    InfoMini("UID", uid.ifBlank { "미입력" }, Modifier.weight(1f))
                    InfoMini("Mode", "Device", Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun InfoMini(label: String, value: String, modifier: Modifier = Modifier) {
        Column(modifier, horizontalAlignment = Alignment.Start) {
            Text(label, color = TextSecondary, fontSize = 10.sp)
            Text(value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun RiskResultCard(r: RiskResult) {
        val accent = when (r.level) {
            "HIGH" -> Danger
            "MEDIUM" -> Warning
            else -> Success
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(22.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RISK ANALYSIS", color = GoldSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("리스크 점수 ${r.score}/100", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text(r.level, color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                r.signals.forEach { signal ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape).background(if (signal.triggered) Danger else Success)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(signal.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(signal.value, color = TextSecondary, fontSize = 11.sp)
                        }
                        Text(if (signal.triggered) "+${signal.points}" else "OK", color = if (signal.triggered) Danger else Success, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun EnvironmentCard(s: DeviceSnapshot) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface1),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Border, RoundedCornerShape(22.dp))
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DEVICE ENVIRONMENT", color = GoldSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                EnvRow("국가", s.deviceCountry.ifBlank { "-" })
                EnvRow("시간대", s.timezoneId)
                EnvRow("언어", s.locale)
                EnvRow("네트워크", s.networkType)
                EnvRow("보안 패치", s.securityPatch.ifBlank { "-" })
            }
        }
    }

    @Composable
    private fun EnvRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(92.dp))
            Text(value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }

    @Composable
    private fun SecurityNote() {
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Gold.copy(alpha = 0.07f)).border(1.dp, Gold.copy(alpha = 0.18f), RoundedCornerShape(16.dp)).padding(14.dp)
        ) {
            Text("ERS는 내부 디바이스·계정 환경 자가 점검용입니다. 거래소의 비공개 탐지 규칙을 추정하거나 우회하는 기능은 포함하지 않습니다.", color = TextSecondary, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }

    @Composable
    private fun BottomNavigationMock() {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surface1).border(1.dp, Border, RoundedCornerShape(20.dp)).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BottomItem("HOME", false)
            BottomItem("SCAN", true)
            BottomItem("ALERTS", false)
            BottomItem("SETTINGS", false)
        }
    }

    @Composable
    private fun BottomItem(label: String, active: Boolean) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (active) Gold else Color.Transparent))
            Spacer(Modifier.height(5.dp))
            Text(label, color = if (active) GoldSoft else TextSecondary, fontSize = 9.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
        }
    }

    @Composable
    private fun ExchangeMark(option: ExchangeOption) {
        val textColor = if (option.name == "OKX") AppBg else Color.White
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(option.color.copy(alpha = if (option.name == "OKX") 1f else 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            Text(option.mark, color = textColor, fontWeight = FontWeight.Black, fontSize = if (option.mark.length > 1) 10.sp else 15.sp)
        }
    }

    @Composable
    private fun premiumFieldColors() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Gold,
        unfocusedBorderColor = Border,
        focusedLabelColor = GoldSoft,
        unfocusedLabelColor = TextSecondary,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Gold,
        focusedContainerColor = Surface2.copy(alpha = 0.45f),
        unfocusedContainerColor = Surface2.copy(alpha = 0.25f)
    )
}
