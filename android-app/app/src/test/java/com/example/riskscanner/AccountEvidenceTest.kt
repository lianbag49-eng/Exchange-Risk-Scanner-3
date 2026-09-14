package com.example.riskscanner
import org.junit.Assert.*
import org.junit.Test
class AccountEvidenceTest {
 @Test fun missingEvidenceNeverBecomesVerified(){val s=AccountEvidenceEngine.signals(AccountEvidence(),"KR");assertTrue(s.last().value.contains("미확인"));assertEquals(0,s.sumOf{it.points})}
 @Test fun approvedInputDoesNotProveAuthenticity(){val s=AccountEvidenceEngine.signals(AccountEvidence(kyc="승인",restriction="제한 없음",notice="Verification completed"),"KR");assertTrue(s.last().value.contains("공식 응답 필요"));assertTrue(s.any{it.value.contains("진위 확인을 의미하지 않음")})}
 @Test fun noticesAndUnexpectedLoginsRaiseAttention(){val s=AccountEvidenceEngine.signals(AccountEvidence(unusualLogin="있음",notice="Your account restricted: withdrawal suspended"),"KR");assertTrue(s.sumOf{it.points}>=45);assertFalse(s.any{it.value.contains("Your account")})}
 @Test fun countryDifferenceIsOnlyAnInputComparison(){val s=AccountEvidenceEngine.signals(AccountEvidence(loginCountry="JP"),"KR");assertEquals(10,s.sumOf{it.points});assertTrue(s.any{it.label.contains("입력값 비교")})}
}
