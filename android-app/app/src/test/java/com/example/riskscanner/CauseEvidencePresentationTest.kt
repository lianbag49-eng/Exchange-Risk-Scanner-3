package com.example.riskscanner

import org.junit.Assert.*
import org.junit.Test

class CauseEvidencePresentationTest {
    @Test fun absentEvidenceStaysUnknown() {
        assertEquals(CauseEvidenceState.UNKNOWN, causeEvidenceState(emptyList(), emptyList()))
    }
    @Test fun supportingEvidenceNeverBecomesAnOfficialVerdict() {
        assertEquals(CauseEvidenceState.SUPPORTED_HYPOTHESIS, causeEvidenceState(listOf("api_locked"), emptyList()))
        assertTrue(causeEvidenceState(listOf("api_locked"), emptyList()).label.contains("미확정"))
    }
    @Test fun counterEvidenceTakesPriorityEvenWithoutSupport() {
        assertEquals(CauseEvidenceState.CONFLICTING_EVIDENCE, causeEvidenceState(listOf("device_offline"), listOf("device_online")))
        assertEquals(CauseEvidenceState.CONFLICTING_EVIDENCE, causeEvidenceState(emptyList(), listOf("device_online")))
    }
    @Test fun unknownProvenanceIsNotUpgradedToApiEvidence() {
        assertEquals("출처 미확인", causeOriginLabel("model_guess"))
        assertTrue(causeOriginLabel("user_report").contains("미검증"))
        assertTrue(causeOriginLabel("exchange_api").contains("재검증 없음"))
    }
    @Test fun timelinePreservesCollectionTimesAndDoesNotParseReportedOccurrence() {
        val c = PreventionCase(
            createdAt = "2026-09-15T09:00:00Z",
            app = DiagnosticApp("com.ers.fixture", "Fixture", "fixture", true),
            exchangeName = "Fixture",
            occurredAt = "어제 오후 · 사용자 기억",
            device = PreventionDevice("2026-09-15T09:03:00Z", 35, "WIFI", true, false, false, true),
            updates = listOf(PreventionUpdate(recordedAt = "2026-09-15T09:02:00Z", action = "PRIVATE_TEST_ACTION", outcome = "resolved", supportReply = "PRIVATE_TEST_REPLY"))
        )
        val rows = causeTimeline(c)
        assertEquals(listOf("2026-09-15T09:00:00Z", "2026-09-15T09:02:00Z", "2026-09-15T09:03:00Z"), rows.map { it.at })
        assertTrue(rows[1].source.contains("원인 확정 아님"))
        assertFalse(rows.toString().contains("PRIVATE_TEST"))
        assertFalse(rows.toString().contains(c.occurredAt))
        assertEquals("2026-09-15T09:03:00Z", c.device?.observedAt)
    }
    @Test fun invalidLegacyTimestampDoesNotCrashPresentation() {
        val c = PreventionCase(createdAt = "unavailable", app = DiagnosticApp("com.ers.fixture", "Fixture", "fixture", true), exchangeName = "Fixture")
        assertEquals("unavailable", causeTimeline(c).single().at)
    }
}
