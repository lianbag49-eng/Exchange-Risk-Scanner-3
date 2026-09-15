package com.example.riskscanner

import android.content.Context
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreflightTest{
 @get:Rule val ui=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val app=DiagnosticApp("org.example.ers.fixture.binance","Binance","fixture",true)
 private fun report(signals:List<String> = listOf("auto_time_off"),unknowns:List<String> = listOf("exchange_risk_unknown"))=PreflightReport(app,1L,signals,unknowns,"unknown","unknown")
 @Test fun limitedObservationsDoNotPredictExchangeRestrictions(){
  assertEquals("HIGH",preflightLevel(listOf("app_debuggable"),emptyList()))
  assertEquals("MEDIUM",preflightLevel(listOf("auto_time_off"),emptyList()))
  assertEquals("LOW",preflightLevel(listOf("vpn_present","proxy_present"),listOf("exchange_risk_unknown","api_status_unknown")))
  assertEquals("UNKNOWN",preflightLevel(emptyList(),listOf("app_metadata_missing")))
  val candidate=ExchangeAppCandidate(app,listOf(CmcExchange(521,"Bybit","bybit","active")),"test")
  val account=LinkedExchangeAccount(UUID.randomUUID().toString(),AccountProvider.BYBIT,"fixture","test-key-only","test-secret-only","12345",ApiKycStatus("12345","LEVEL_DEFAULT","","level_reported","main_account","verified_read_only",1000))
  assertTrue(summarizePreflightAccounts(listOf(account),candidate,2000).needsFollowup)
  val stale=summarizePreflightAccounts(listOf(account),candidate,400000);assertTrue(stale.unknown);assertFalse(stale.needsFollowup)
  assertFalse(summarizePreflightAccounts(listOf(account.copy(error="failed")),candidate,2000).needsFollowup)
 }
 @Test fun localInspectionCollectsOnlyAvailableMetadataWithoutAnyCapture(){
  val directory=CmcDirectoryStore(context).load();val actual=diagnosticApps(context).first{it.packageName=="com.android.settings"}
  val result=collectPreflight(context,ExchangeAppCandidate(actual,listOf(directory.entries.first()),"manual fixture"),emptyList(),emptyList())
  assertEquals(actual,result.app);assertTrue(result.checkedAt>0)
  assertTrue(result.unknowns.containsAll(listOf("official_app_unverified","account_identity_unknown","exchange_risk_unknown","api_status_unknown")))
  assertFalse(result.item().toString().contains(actual.packageName));assertEquals(setOf("id","signals","unknowns"),result.item().keys().asSequence().toSet())
 }
 @Test fun aiResponseIsBoundToEvidenceAndCannotAddClaims(){
  val r=report();val id=UUID.randomUUID().toString()
  fun response()=JSONObject().put("requestId",id).put("inputSha256",preflightDigest(listOf(r))).put("source","ai_preflight").put("riskScope","observed_technical_signals").put("actualCauseConfirmed",false).put("officialVerified",false).put("reviewedAt",Instant.now().toString()).put("model","mock").put("reviews",JSONArray().put(JSONObject().put("id",r.id).put("level","MEDIUM").put("focus",JSONArray(listOf("auto_time_off"))).put("checks",JSONArray(listOf("check_clock")))))
  assertEquals(listOf("check_clock"),parseAiPreflight(response(),id,listOf(r)).single().checks)
  assertTrue(runCatching{parseAiPreflight(response().put("officialVerified",true),id,listOf(r))}.isFailure)
  assertTrue(runCatching{parseAiPreflight(response().put("inputSha256","b".repeat(64)),id,listOf(r))}.isFailure)
  val changed=response();changed.getJSONArray("reviews").getJSONObject(0).put("level","HIGH")
  assertTrue(runCatching{parseAiPreflight(changed,id,listOf(r))}.isFailure)
  val invented=response();invented.getJSONArray("reviews").getJSONObject(0).put("focus",JSONArray(listOf("app_debuggable")))
  assertTrue(runCatching{parseAiPreflight(invented,id,listOf(r))}.isFailure)
 }
 @Test fun brandLogosReuseBundledAssetsAndBoundRemoteCache(){
  val exchanges=ExchangeCatalog.load(context);val bundled=exchanges.filter{it.logo!=null};assertEquals(52,bundled.size)
  bundled.forEach{assertNotNull(decodeExchangeLogo(Base64.decode(it.logo,Base64.DEFAULT)))}
  assertTrue(exchanges.filter{it.infoUrl.isNotEmpty()}.all{it.cmcId>0})
  assertEquals("https://s2.coinmarketcap.com/static/img/exchanges/64x64/270.png",exchangeLogoUrl(270))
  assertNull(decodeExchangeLogo(ByteArray(131073)))
  val bytes=Base64.decode(bundled.first().logo,Base64.DEFAULT);val cache=ExchangeLogoCache(context);val id=987654321;val file=File(context.cacheDir,"exchange-logos-v1/$id.png")
  try{file.delete();assertNotNull(cache.load(id){bytes});assertNotNull(cache.load(id){error("offline")});file.writeText("invalid");assertNull(cache.load(id){ByteArray(8)})}finally{file.delete()}
 }
 @Test fun automaticAiSettingsAreOptInEncryptedAndRemovable(){
  val name="ers_preflight_test_"+UUID.randomUUID();val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);val store=AutoPreflightConfigStore(context,name)
  try{assertFalse(store.load().enabled);val config=AutoPreflightConfig("https://example.invalid","test-only-token-".repeat(4),true);store.save(config)
   assertEquals(config,store.load());assertFalse(prefs.getString("encrypted","")!!.contains(config.token));assertFalse(config.toString().contains(config.token))
   prefs.edit().putString("encrypted","broken").commit();assertTrue(runCatching{store.load()}.isFailure);assertEquals("broken",prefs.getString("encrypted",null))
   store.clear();assertFalse(store.load().enabled)
  }finally{prefs.edit().clear().commit()}
 }
 @Test fun firstLaunchShowsBrandAndPhotoFreeInspectionBeforeAnyUpload(){
  val directory=CmcDirectoryStore(context).load();val catalog=ExchangeCatalog.load(context)
  var settings by mutableStateOf(false)
  ui.setContent{
   MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFD9B56D))){Surface{
    val ctx=LocalContext.current
    val installed=rememberInstalledExchanges(ctx,Unit,appReader={listOf(app)},directoryReader={directory})
    val state=rememberPreflight(ctx,installed,emptyList(),settings,0)
    PreventionHome(installed,emptyList(),false,"",onRecord={_,_->},onInspect={_,_->},onCase={},onDiscovery={},onAccounts={},onReset={},exchanges=catalog,preflight=state,onAiSettings={settings=true})
    if(settings)AutoPreflightDialog(changed={},close={settings=false})
   }}
  }
  ui.waitUntil(15000){ui.onAllNodes(hasTestTag("home-rescan") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithTag("prevention-home").performScrollToNode(hasTestTag("home-app-${app.packageName}"))
  ui.waitUntil(15000){ui.onAllNodes(hasTestTag("preflight-level-${app.packageName}")).fetchSemanticsNodes().isNotEmpty()}
  ui.onNodeWithContentDescription("Binance 로고").assertExists()
  ui.onNodeWithTag("preflight-level-${app.packageName}").assertExists()
  fun screenshot(name:String){ui.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
   for(command in listOf("mkdir -p /sdcard/Download/ers-ui","screencap -p /sdcard/Download/ers-ui/$name.png"))android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use{it.readBytes()}
  }
  screenshot("preflight-logos")
  ui.onNodeWithTag("prevention-home").performScrollToNode(hasTestTag("preflight-overview"));screenshot("preflight-no-photo")
  ui.onNodeWithTag("open-preflight-settings").performClick()
  ui.onNodeWithTag("preflight-endpoint").assertTextContains(DEFAULT_AI_SERVER)
  ui.onNodeWithTag("test-preflight-ai").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("enable-preflight-ai").performScrollTo().assertIsNotEnabled()
  ui.onNodeWithTag("close-preflight-settings").performScrollTo().performClick()
 }
}
