package com.example.riskscanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AutoPreflightConfig(val endpoint:String=DEFAULT_AI_SERVER,val token:String="",val enabled:Boolean=false){override fun toString()="AutoPreflightConfig(enabled=$enabled, credentials=redacted)"}
class AutoPreflightConfigStore(context:Context,private val name:String="ers_auto_preflight"){
 private val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
 private fun key(create:Boolean):SecretKey{
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  (store.getKey("ers_auto_preflight_v1",null) as? SecretKey)?.let{return it};check(create)
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("ers_auto_preflight_v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
 }
 fun load():AutoPreflightConfig{
  val text=prefs.getString("encrypted",null)?:return AutoPreflightConfig();val blob=Base64.decode(text,Base64.NO_WRAP);require(blob.size in 29..16384)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,blob.copyOfRange(0,12)));val plain=cipher.doFinal(blob.copyOfRange(12,blob.size))
  try{val j=JSONObject(String(plain,Charsets.UTF_8));require(j.getInt("version")==1&&j.getBoolean("consent"));val c=AutoPreflightConfig(j.getString("endpoint"),j.getString("token"),true);kycEndpoint(c.endpoint);require(c.token.length in 32..4096);return c}finally{plain.fill(0)}
 }
 fun save(config:AutoPreflightConfig){
  require(config.enabled);kycEndpoint(config.endpoint);require(config.token.length in 32..4096)
  val plain=JSONObject().put("version",1).put("consent",true).put("endpoint",config.endpoint).put("token",config.token).toString().toByteArray(Charsets.UTF_8)
  try{val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));check(prefs.edit().putString("encrypted",Base64.encodeToString(cipher.iv+cipher.doFinal(plain),Base64.NO_WRAP)).commit())}finally{plain.fill(0)}
 }
 fun clear(){check(prefs.edit().remove("encrypted").commit())}
}

class PreflightState{
 var reports by mutableStateOf<List<PreflightReport>>(emptyList())
 var ai by mutableStateOf<Map<String,AiPreflightReview>>(emptyMap())
 var scanning by mutableStateOf(false)
 var aiMessage by mutableStateOf("AI 자동 검토 연결 대기 · 로컬 점검은 자동 실행")
 var error by mutableStateOf("")
 var revision by mutableIntStateOf(0)
 var lastApiAttempt=0L
 var lastAiAttempt=0L
 var lastConfigRevision=-1
 var auditedAccounts:List<LinkedExchangeAccount> = emptyList()
 fun refresh(){revision++}
}

@Composable fun rememberPreflight(context:Context,installed:InstalledExchangeState,cases:List<PreventionCase>,blocked:Boolean,configRevision:Int):PreflightState{
 val state=remember{PreflightState()};val lifecycle=(context as? ComponentActivity)?.lifecycle
 var resumed by remember{mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)!=false)}
 DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,_->resumed=lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true};lifecycle?.addObserver(observer);onDispose{lifecycle?.removeObserver(observer)}}
 LaunchedEffect(installed.checkedAt,installed.scanning,installed.error,cases,blocked,resumed,configRevision,state.revision){
  if(installed.error.isNotEmpty())state.error="설치 앱 인식 실패 · 이전 사전 점검 결과입니다"
  if(blocked||!resumed||installed.scanning||installed.error.isNotEmpty()||installed.checkedAt.isEmpty())return@LaunchedEffect
  while(true){
   state.scanning=true;state.error=""
   try{
    var accounts=emptyList<LinkedExchangeAccount>();var accountError=false
    try{accounts=withContext(Dispatchers.IO){ExchangeAccountStorage(context).load()}}catch(_:Exception){accountError=true}
    // Never write account credentials/results from this screen, so a connection edit cannot be overwritten.
    if(System.currentTimeMillis()-state.lastApiAttempt in 0..299999){
     accounts=accounts.map{a->state.auditedAccounts.find{it.id==a.id&&it.apiKey==a.apiKey&&it.secret==a.secret}?:a}
    }
    state.reports=withContext(Dispatchers.IO){installed.candidates.map{collectPreflight(context,it,cases,accounts,accountError)}}
    if(!accountError&&System.currentTimeMillis()-state.lastApiAttempt !in 0..299999){
     state.lastApiAttempt=System.currentTimeMillis();val updated=accounts.toMutableList()
     for(i in updated.indices){currentCoroutineContext().ensureActive();val a=updated[i]
      if(a.status==null||a.error.isNotEmpty()||apiKycNeedsRefresh(a.status,System.currentTimeMillis())){
       var limited=false
       updated[i]=try{a.copy(status=withContext(Dispatchers.IO){queryExchangeAccount(a)},lastAttempt=System.currentTimeMillis(),error="")}catch(e:CancellationException){throw e}catch(e:Exception){limited=(e as? AccountAuditError)?.kind=="rate_limit";a.copy(lastAttempt=System.currentTimeMillis(),error="조회 실패 · 현재 상태 미확인")}
       state.reports=withContext(Dispatchers.IO){installed.candidates.map{collectPreflight(context,it,cases,updated,accountError)}}
       if(limited)break
       delay(300)
      }
     }
     state.auditedAccounts=updated
    }
    state.scanning=false
    val currentFingerprints=state.reports.associate{it.id to it.fingerprint()}
    val retained=state.ai.filter{(id,review)->currentFingerprints[id]==review.fingerprint}
    if(retained.size!=state.ai.size){state.ai=retained;state.aiMessage="관측 신호가 변경됨 · 다음 자동 AI 검토 대기"}
    val config=try{withContext(Dispatchers.IO){AutoPreflightConfigStore(context).load()}}catch(_:Exception){state.aiMessage="AI 설정 복구 실패 · 자동 전송 중단. AI 연결에서 다시 설정하세요";null}
    if(config!=null){
     if(!config.enabled){state.ai=emptyMap();state.aiMessage="AI 자동 검토 연결 대기 · 로컬 점검 완료"}
     else if(state.reports.isEmpty()){state.ai=emptyMap();state.aiMessage="AI 검토할 거래소 앱 후보 없음"}
     else if(configRevision!=state.lastConfigRevision||System.currentTimeMillis()-state.lastAiAttempt !in 0..299999){
      state.lastAiAttempt=System.currentTimeMillis();state.lastConfigRevision=configRevision;state.ai=emptyMap()
      val batches=state.reports.chunked(50)
      for((index,batch) in batches.withIndex()){
       currentCoroutineContext().ensureActive();state.aiMessage="사진 없이 AI 사전 검토 중 · ${index+1}/${batches.size}"
       try{val reviews=withContext(Dispatchers.IO){requestAiPreflight(config.endpoint,config.token,batch)};state.ai=state.ai+reviews.associateBy{it.id}}
       catch(e:CancellationException){throw e}catch(e:Exception){state.aiMessage=e.message?:"AI 검토 실패 · 로컬 점검 결과를 확인하세요";break}
       state.aiMessage="AI 사전 검토 완료 · ${state.ai.size}/${state.reports.size}개 · 실제 제한 사유 미확정"
       if(index<batches.lastIndex)delay(7000)
      }
     }
    }
   }catch(e:CancellationException){throw e}catch(_:Exception){state.error="사전 점검 실패 · 현재 상태 미확인"}finally{state.scanning=false}
   delay(300000)
  }
 }
 DisposableEffect(Unit){onDispose{state.auditedAccounts=emptyList()}}
 return state
}

@Composable fun PreflightOverview(state:PreflightState,onSettings:()->Unit){
 Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
  Text("사진 없이 자동 사전 점검",style=MaterialTheme.typography.titleMedium)
  Text(if(state.scanning)"앱·기기·연결 계정 확인 중…" else "높음 ${state.reports.count{it.level=="HIGH"}} · 주의 ${state.reports.count{it.level=="MEDIUM"}} · 낮음 ${state.reports.count{it.level=="LOW"}} · 미확인 ${state.reports.count{it.level=="UNKNOWN"}}",modifier=Modifier.testTag("preflight-overview"))
  Text("ERS에서 관측한 기술적 위험 신호 기준입니다. 거래소의 내부 판정이나 정지 확률이 아닙니다.",style=MaterialTheme.typography.bodySmall)
  if(state.error.isNotEmpty())Text(state.error,color=MaterialTheme.colorScheme.error)
  Text(state.aiMessage,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("preflight-ai-state"))
  Row{TextButton(onClick=state::refresh,enabled=!state.scanning){Text("사전 점검 다시 실행")};TextButton(onClick=onSettings,modifier=Modifier.testTag("open-preflight-settings")){Text("AI 자동 연결")}}
 }
}

@Composable fun PreflightReportView(report:PreflightReport,ai:AiPreflightReview?){
 var expanded by remember{mutableStateOf(false)}
 val matched=ai?.takeIf{it.fingerprint==report.fingerprint()}
 Text("사전 점검: ${preflightLevelLabel(report.level)}",color=when(report.level){"HIGH"->MaterialTheme.colorScheme.error;"MEDIUM"->MaterialTheme.colorScheme.primary;else->MaterialTheme.colorScheme.onSurface},modifier=Modifier.testTag("preflight-level-${report.app.packageName}"))
 Text("기기 직접 점검 · ${preventionTime(java.time.Instant.ofEpochMilli(report.checkedAt).toString())}",style=MaterialTheme.typography.bodySmall)
 if(report.signals.isEmpty())Text("관측 범위에서 주의 신호 없음 · 계정 안전 보장 아님",style=MaterialTheme.typography.bodySmall)
 else report.signals.take(3).forEach{Text("• ${preflightSignals[it]}",style=MaterialTheme.typography.bodySmall)}
 matched?.let{Text("AI 검토 · ${preventionTime(it.reviewedAt)} · ${it.model}",style=MaterialTheme.typography.bodySmall);it.focus.take(2).forEach{code->Text("AI 우선 확인: ${preflightSignals[code]}",style=MaterialTheme.typography.bodySmall)};it.checks.take(2).forEach{code->Text("다음 확인: ${preflightChecks[code]}",style=MaterialTheme.typography.bodySmall)}}
 TextButton(onClick={expanded=!expanded}){Text(if(expanded)"점검 근거 접기" else "점검 근거·미확인 항목")}
 if(expanded){
  report.signals.drop(3).forEach{Text("• ${preflightSignals[it]}",style=MaterialTheme.typography.bodySmall)}
  Text("설치 처리 앱: ${report.installer} · 공식성 인증 아님",style=MaterialTheme.typography.bodySmall)
  Text(report.accountSummary,style=MaterialTheme.typography.bodySmall)
  Text("미확인: "+report.unknowns.joinToString(" · "){preflightUnknowns[it].orEmpty()},style=MaterialTheme.typography.bodySmall)
  Text("ERS 분류 기준: 디버깅 허용 앱은 높음, 잠금·디버깅·패치·연결·시간·KYC 후속 확인과 미해결 사용자 사건은 주의. 정보 부족은 미확인으로 남깁니다. VPN·프록시만으로 높이지 않습니다.",style=MaterialTheme.typography.bodySmall)
 }
}

@Composable fun AutoPreflightDialog(changed:()->Unit,close:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val store=remember{AutoPreflightConfigStore(context)}
 var initialError by remember{mutableStateOf("")};val initial=remember{runCatching{store.load()}.getOrElse{initialError="저장된 AI 연결을 읽지 못했습니다. 다시 설정하거나 연결을 해제하세요";AutoPreflightConfig()}}
 var endpoint by remember{mutableStateOf(initial.endpoint)};var token by remember{mutableStateOf(initial.token)};var consent by remember{mutableStateOf(initial.enabled)};var message by remember{mutableStateOf(initialError)};var busy by remember{mutableStateOf(false)}
 val ready=consent&&token.trim().length in 32..4096&&runCatching{kycEndpoint(endpoint)}.isSuccess
 Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)){
  Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Row{Text("AI 자동 사전 검토",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=close,modifier=Modifier.testTag("close-preflight-settings")){Text("닫기")}}
   Text("사진 없이 앱·기기 상태를 먼저 점검하고 AI가 확인 순서와 다음 조치를 제안합니다. 로컬 점검은 연결 없이도 동작합니다.")
   OutlinedTextField(endpoint,{endpoint=it;consent=false},label={Text("HTTPS 검토 서버 주소")},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("preflight-endpoint"))
   OutlinedTextField(token,{token=it;consent=false},label={Text("서버 접속 토큰")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("preflight-token"))
   Text("자동 검토를 켜면 실행·복귀 시와 화면 활성 중 5분마다 점검합니다. 5분 이내의 추가 AI 요청은 제한합니다. 화면·UID·계정 별칭·API 키·개인 메모는 전송하지 않습니다. 앱 패키지의 해시 식별자, 관측된 신호 코드, 미확인 항목 코드만 검토 서버와 OpenAI에 전달합니다. 연결 계정의 KYC 후속 확인 여부와 미해결 사건 존재 여부도 신호 코드에 포함됩니다.",style=MaterialTheme.typography.bodySmall)
   Text("서버 접속 토큰은 Android 보안 키로 암호화해 저장하며, OpenAI API 키는 서버에만 둡니다. 자동 검토에는 API 사용료가 발생할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
   Row{Checkbox(consent,{consent=it},enabled=!busy,modifier=Modifier.testTag("preflight-consent"));Text("위 범위의 자동 AI 전송과 서버 토큰 저장에 동의합니다.")}
   OutlinedButton(onClick={busy=true;message="가상 신호로 연결·AI 응답 확인 중…";scope.launch{try{
    val sample=PreflightReport(DiagnosticApp("org.example.ers.preflighttest","Test", "test",true),System.currentTimeMillis(),listOf("auto_time_off"),listOf("official_app_unverified","exchange_risk_unknown","account_identity_unknown"),"test","test")
    val result=withContext(Dispatchers.IO){requestAiPreflight(endpoint,token,listOf(sample))};message="사진 없는 AI 샘플 검토 성공 · ${result.single().model}"
   }catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"연결 실패"}finally{busy=false}}},enabled=ready&&!busy,modifier=Modifier.fillMaxWidth().testTag("test-preflight-ai")){Text("연결 + 가상 신호 AI 테스트")}
   Button(onClick={try{store.save(AutoPreflightConfig(endpoint.trim(),token.trim(),true));changed();close()}catch(_:Exception){message="AI 연결 저장 실패"}},enabled=ready&&!busy,modifier=Modifier.fillMaxWidth().testTag("enable-preflight-ai")){Text("저장하고 자동 검토 시작")}
   TextButton(onClick={try{store.clear();token="";consent=false;changed();close()}catch(_:Exception){message="연결 해제 실패"}},enabled=!busy){Text("AI 자동 검토 끄기 · 저장 토큰 삭제")}
   if(message.isNotEmpty())Text(message,modifier=Modifier.testTag("preflight-config-message"))
  }}
 }
}
