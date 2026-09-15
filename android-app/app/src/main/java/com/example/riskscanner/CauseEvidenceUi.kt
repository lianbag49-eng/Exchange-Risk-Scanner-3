package com.example.riskscanner

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant

/** Presentation states never turn observations or a model ranking into an official verdict. */
enum class CauseEvidenceState(val label: String) {
    UNKNOWN("미확인 · 근거 부족"),
    SUPPORTED_HYPOTHESIS("지지 단서 있음 · 원인 미확정"),
    CONFLICTING_EVIDENCE("충돌 단서 있음 · 재확인 필요")
}
internal fun causeEvidenceState(support: List<String>, counter: List<String>) = when {
    counter.isNotEmpty() -> CauseEvidenceState.CONFLICTING_EVIDENCE
    support.isNotEmpty() -> CauseEvidenceState.SUPPORTED_HYPOTHESIS
    else -> CauseEvidenceState.UNKNOWN
}
internal fun causeOriginLabel(origin: String) = when (origin) {
    "exchange_api" -> "계정 API 조회 · 서버 재검증 없음"
    "device_observation" -> "기기 직접 관측"
    "user_report" -> "사용자 기록 · 원문 미검증"
    else -> "출처 미확인"
}
internal data class CauseTimelineItem(val at: String, val title: String, val source: String)
internal fun causeTimeline(case: PreventionCase): List<CauseTimelineItem> = buildList {
    add(CauseTimelineItem(case.createdAt, "사건 등록", "사용자 기록 · 발생 시각과 다를 수 있음"))
    case.device?.let { add(CauseTimelineItem(it.observedAt, "기기 상태 수집", causeOriginLabel("device_observation"))) }
    case.updates.forEach { update ->
        add(CauseTimelineItem(update.recordedAt, "조치 기록 · ${preventionOutcomes[update.outcome] ?: "결과 미확인"}", "사용자 기록 · 원인 확정 아님"))
        update.device?.let { add(CauseTimelineItem(it.observedAt, "조치 후 기기 상태 수집", causeOriginLabel("device_observation"))) }
    }
    case.causeReviews.forEach { add(CauseTimelineItem(it.reviewedAt, "원인 후보 분석", "규칙·AI 후보 · 공식 판정 아님")) }
    case.causeFindings.forEach { add(CauseTimelineItem(it.recordedAt, "사후 대조 기록", "${causeFindingSources[it.source] ?: "출처 미확인"} · 사용자 대조")) }
}.sortedBy { runCatching { Instant.parse(it.at) }.getOrDefault(Instant.MAX) }

@Composable internal fun CauseCaseTimeline(case: PreventionCase) {
    var expanded by remember(case.id) { mutableStateOf(false) }
    val items = remember(case) { causeTimeline(case) }
    OutlinedCard(Modifier.fillMaxWidth().testTag("cause-timeline")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("원인 조사 주요 이력", style = MaterialTheme.typography.titleSmall)
            Text("발생 시각 · 사용자 입력: ${case.occurredAt.ifBlank { "미기록" }}", style = MaterialTheme.typography.bodySmall)
            Text("아래는 수집·등록 시각입니다. 사건 당시 상태로 소급하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("toggle-cause-timeline")) {
                Text(if (expanded) "이력 접기" else "이력 ${items.size}건 펼치기")
            }
            if (expanded) items.forEach { item ->
                Text("${preventionTime(item.at)} · ${item.title}\n${item.source}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun EvidenceLine(evidence: CauseEvidence, kb: CauseKnowledge) {
    Text("${kb.label(evidence.code)}\n${causeOriginLabel(evidence.origin)} · ${preventionTime(evidence.observedAt)}", style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun EvidenceFirstCauseReview(review: CauseReview, kb: CauseKnowledge) {
    val context = LocalContext.current
    var sourceError by remember(review.requestId) { mutableStateOf(false) }
    var showEvidence by remember(review.requestId) { mutableStateOf(false) }
    Text("${preventionTime(review.reviewedAt)} · 원인 후보", style = MaterialTheme.typography.titleMedium)
    Text("실제 심사 사유·KYC 진위 미확정 · 확률 점수 없음", modifier = Modifier.testTag("cause-boundary"))
    if (review.input.knowledgeVersion != kb.version) {
        Text("이전 공식 자료 버전 ${review.input.knowledgeVersion} · 후보: " + review.ranked.joinToString { causeLabels[it].orEmpty() })
        Text("현재 자료로 이전 근거를 재해석하지 않습니다. 새 분석이 필요합니다.")
        return
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("cause-provenance")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("근거 출처", style = MaterialTheme.typography.titleSmall)
            listOf("device_observation", "exchange_api", "user_report").forEach { origin ->
                val evidence = review.input.evidence.filter { it.origin == origin }
                Text("${causeOriginLabel(origin)} · ${evidence.size}건", style = MaterialTheme.typography.bodySmall)
                if (showEvidence) evidence.sortedBy { Instant.parse(it.observedAt) }.forEach { EvidenceLine(it, kb) }
            }
            Text("AI는 아래 후보의 조사 순서만 정합니다. 공식 문서는 일반 기준이며 이 계정의 확정 사유가 아닙니다.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showEvidence = !showEvidence }, modifier = Modifier.testTag("toggle-cause-evidence")) {
                Text(if (showEvidence) "근거 접기" else "근거와 수집 시각 펼치기")
            }
        }
    }
    if (review.ranked.isEmpty()) Text("근거 부족 · 원인 후보를 만들지 않았습니다. 공식 안내의 오류 코드·추가 요청·발생 시각을 확인하세요. 외부 AI 호출 없음.", modifier = Modifier.testTag("cause-insufficient"))
    review.ranked.forEachIndexed { index, id ->
        val rule = kb.rules.single { it.getString("id") == id }
        val support = kb.support(rule, review.input)
        val counter = causeStrings(rule, "counter").filter { code -> review.input.evidence.any { it.code == code } }
        val evidenceState = causeEvidenceState(support, counter)
        val checks = causeStrings(rule, "checks")
        var checked by remember(review.requestId, id, checks) { mutableStateOf(emptySet<String>()) }
        Card(Modifier.fillMaxWidth().testTag("cause-result-$id")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${index + 1}. ${rule.getString("title")}", style = MaterialTheme.typography.titleMedium)
                Text(evidenceState.label, color = if (evidenceState == CauseEvidenceState.CONFLICTING_EVIDENCE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("cause-state-$id"))
                Text(rule.getString("meaning"))
                Text("지지 단서", style = MaterialTheme.typography.labelLarge)
                support.forEach { code -> review.input.evidence.find { it.code == code }?.let { EvidenceLine(it, kb) } }
                Text("반대 단서·충돌", style = MaterialTheme.typography.labelLarge)
                if (counter.isEmpty()) Text("수집된 반대 단서 없음 · 반대 사실이 없다는 뜻은 아닙니다.", style = MaterialTheme.typography.bodySmall)
                counter.forEach { code -> review.input.evidence.find { it.code == code }?.let { EvidenceLine(it, kb) } }
                Text(rule.getString("limit"), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("추가 확인·재발 예방", style = MaterialTheme.typography.labelLarge)
                checks.forEachIndexed { checkIndex, check ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(checked = check in checked, onCheckedChange = { value -> checked = if (value) checked + check else checked - check }, modifier = Modifier.testTag("cause-check-$id-$checkIndex"))
                        Text(check, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 10.dp))
                    }
                }
                Text("체크는 이 결과 화면에만 임시 표시됩니다. 영구 조치 이력이나 원인 확정을 뜻하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                Text("공식 문서 · 자료 검토일 ${kb.reviewedAt}", style = MaterialTheme.typography.labelLarge)
                kb.sourceIds(rule, review.input.exchange).forEach { sourceId ->
                    val source = kb.sources.single { it.getString("id") == sourceId }
                    TextButton(onClick = {
                        sourceError = runCatching {
                            val uri = Uri.parse(source.getString("url"))
                            require(uri.scheme == "https")
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }.isFailure
                    }) { Text(source.getString("title")) }
                }
            }
        }
    }
    if (sourceError) Text("공식 문서를 열지 못했습니다. 브라우저 연결을 확인하세요.", color = MaterialTheme.colorScheme.error)
    val omitted = causeStrings(review.result, "eligible").filter { it !in review.ranked }
    if (omitted.isNotEmpty()) Text("함께 검토한 다른 후보: " + omitted.joinToString { causeLabels[it].orEmpty() }, style = MaterialTheme.typography.bodySmall)
    Text("공식 자료 버전 ${review.input.knowledgeVersion} · ${review.result.getString("model")}\n분석 순서는 조사 우선순위입니다. 기록 시각과 사건 발생 시각이 다를 수 있으며, 현재 정상 상태로 과거 제한을 부정하지 않습니다.", style = MaterialTheme.typography.bodySmall)
}
