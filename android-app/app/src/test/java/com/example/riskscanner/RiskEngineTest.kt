package com.example.riskscanner
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class RiskEngineTest {
 private val today=LocalDate.of(2026,9,14)
 private val clean=DeviceSnapshot("KR","Asia/Seoul","ko-KR","WIFI",false,false,false,false,false,true,"2026-09-01",false,true)
 @Test fun cleanDeviceIsLow(){val r=RiskEngine.evaluate(clean,"KR",false,today);assertEquals(0,r.score);assertEquals("LOW",r.level)}
 @Test fun strictModeUsesEarlierPatchThreshold(){val s=clean.copy(securityPatch="2026-05-01");assertEquals(0,RiskEngine.evaluate(s,"KR",false,today).score);assertEquals("MEDIUM",RiskEngine.evaluate(s,"KR",true,today).level)}
 @Test fun invalidPatchIsUnknownNotPass(){for(p in listOf("","invalid","2099-01-01")){val r=RiskEngine.evaluate(clean.copy(securityPatch=p),"KR",false,today);assertFalse(r.signals.first{it.label=="Android 보안 패치"}.checked)}}
 @Test fun localeIsNotGeolocation(){val r=RiskEngine.evaluate(clean,"US",false,today);assertEquals(5,r.score);assertEquals("LOW",r.level)}
 @Test fun scoreIsBoundedAndRootIsHigh(){val r=RiskEngine.evaluate(clean.copy(rootedSuspected=true,emulatorSuspected=true,adbEnabled=true,secureLockScreen=false,proxyConfigured=true,securityPatch="2020-01-01"),"US",true,today);assertEquals(100,r.score);assertEquals("HIGH",r.level);assertEquals("HIGH",RiskEngine.evaluate(clean.copy(rootedSuspected=true),"KR",false,today).level)}
}
