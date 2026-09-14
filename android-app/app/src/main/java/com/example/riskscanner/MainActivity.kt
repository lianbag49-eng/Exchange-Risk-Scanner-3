package com.example.riskscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { RiskScannerScreen() }
            }
        }
    }

    @Composable
    private fun RiskScannerScreen() {
        var exchange by remember { mutableStateOf("Toobit") }
        var accountId by remember { mutableStateOf("") }
        var kycCountry by remember { mutableStateOf("KR") }
        var snapshot by remember { mutableStateOf<DeviceSnapshot?>(null) }
        var result by remember { mutableStateOf<RiskResult?>(null) }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Exchange Risk Scanner", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("내부 디바이스·계정 환경 자가 점검")
            OutlinedTextField(exchange, { exchange = it }, label = { Text("거래소") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(accountId, { accountId = it }, label = { Text("Account ID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(kycCountry, { kycCountry = it.uppercase() }, label = { Text("KYC 국가") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val s = DeviceInspector(this@MainActivity).snapshot()
                    snapshot = s
                    result = RiskEngine.evaluate(s, kycCountry)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("검사 실행") }

            result?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("리스크 점수 ${r.score}/100", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("등급 ${r.level}")
                        r.signals.forEach { s ->
                            Text((if (s.triggered) "⚠ " else "✓ ") + s.label + if (s.triggered) "  +${s.points}" else "")
                        }
                    }
                }
            }

            snapshot?.let { s ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("환경 요약", fontWeight = FontWeight.Bold)
                        Text("국가: ${s.deviceCountry.ifBlank { "-" }}")
                        Text("시간대: ${s.timezoneId}")
                        Text("언어: ${s.locale}")
                        Text("네트워크: ${s.networkType}")
                        Text("보안 패치: ${s.securityPatch.ifBlank { "-" }}")
                    }
                }
            }
        }
    }
}
