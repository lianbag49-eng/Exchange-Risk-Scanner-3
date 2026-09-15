package com.example.riskscanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CauseEvidenceUiTest {
    @get:Rule val ui = createComposeRule()
    private val kb get() = CauseKnowledge(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun fixture(k: CauseKnowledge, conflicting: Boolean): CauseReview {
        val time = "2026-09-15T09:00:00Z"
        // The catalog requires BOTH a reported network error and an offline observation.
        // Online counter-evidence is retained rather than weakening that applicability rule.
        val evidence = if (conflicting) listOf(
            CauseEvidence("notice_network_error", "user_report", time),
            CauseEvidence("device_offline", "device_observation", time),
            CauseEvidence("device_online", "device_observation", time)
        ) else emptyList()
        val input = CauseInput(UUID.randomUUID().toString(), "other", k.version, evidence)
        val eligible = k.eligible(input).map { it.getString("id") }
        if (conflicting) org.junit.Assert.assertTrue("Fixture must satisfy the unchanged catalog", "network_connectivity" in eligible)
        val id = UUID.randomUUID().toString()
        val result = JSONObject().put("requestId", id).put("caseId", input.caseId)
            .put("inputSha256", input.digest()).put("knowledgeVersion", k.version)
            .put("reviewedAt", "2026-09-15T09:01:00Z")
            .put("model", if (eligible.isEmpty()) "not_invoked" else "synthetic-test-fixture")
            .put("source", if (eligible.isEmpty()) "rules_insufficient_evidence" else "ai_cause_hypotheses")
            .put("status", if (eligible.isEmpty()) "insufficient_evidence" else "hypotheses")
            .put("actualCauseConfirmed", false).put("officialVerified", false)
            .put("ranked", JSONArray(eligible.take(3))).put("eligible", JSONArray(eligible))
        return parseCauseResponse(result, id, input, k)
    }
    @Composable private fun Screen(review: CauseReview, k: CauseKnowledge) {
        MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { CauseReviewView(review, k) } }
    }
    @Test fun conflictingEvidenceIsLabelledAndChecklistIsOnlyLocalUi() {
        val k = kb; val review = fixture(k, true)
        ui.setContent { Screen(review, k) }
        ui.onNodeWithTag("cause-provenance").assertExists()
        ui.onNodeWithTag("cause-state-network_connectivity").performScrollTo().assertTextContains("충돌", substring = true)
        ui.onNodeWithTag("cause-check-network_connectivity-0").performScrollTo().assertIsOff().performClick().assertIsOn()
        ui.onNodeWithText("공식 문서 · 자료 검토일 ${k.reviewedAt}").performScrollTo().assertExists()
        org.junit.Assert.assertFalse(review.result.getBoolean("actualCauseConfirmed"))
    }
    @Test fun insufficientEvidenceHasNoInventedChecklistOrOfficialVerdict() {
        val k = kb; val review = fixture(k, false)
        ui.setContent { Screen(review, k) }
        ui.onNodeWithTag("cause-insufficient").performScrollTo().assertTextContains("외부 AI 호출 없음", substring = true)
        ui.onNodeWithTag("cause-check-network_connectivity-0").assertDoesNotExist()
        ui.onNodeWithTag("cause-boundary").assertTextContains("미확정", substring = true)
    }
}
