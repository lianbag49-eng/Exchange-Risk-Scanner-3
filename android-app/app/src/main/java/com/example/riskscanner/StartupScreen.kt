package com.example.riskscanner

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StartupState{
 var decision by mutableIntStateOf(0)
 var showConsent by mutableStateOf(false)
 var running by mutableStateOf(false)
 var message by mutableStateOf("첫 실행 자동 점검 준비 중…")
 var policies by mutableStateOf<Map<Int,StartupPolicy>>(emptyMap())
 var policyErrors by mutableStateOf<Map<Int,String>>(emptyMap())
 var aiErrors by mutableStateOf<Map<String,String>>(emptyMap())
 var revision by mutableIntStateOf(0)
 internal var completedKey=""
 fun retry(){completedKey="";aiErrors=emptyMap();policyErrors=emptyMap();revision++}
}

@Composable internal fun rememberStartup(context:Context,installed:InstalledExchangeState,preflight:PreflightState,blocked:Boolean,consentStore:StartupConsent=remember{StartupConsent(context)},gatewayFactory:()->StartupGateway={HttpStartupGateway()}):StartupState{
 val state=remember{StartupState().apply{decision=consentStore.decision();showConsent=decision==0}}
 val gateway by rememberUpdatedState(gatewayFactory)
 val lifecycle=(context as? ComponentActivity)?.lifecycle
 var resumed by remember{mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)!=false)}
 DisposableEffect(lifecycle){val o=LifecycleEventObserver{_,_->resumed=lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true};lifecycle?.addObserver(o);onDispose{lifecycle?.removeObserver(o)}}
 val targets=installed.candidates.flatMap{it.exchanges}.distinctBy{it.id}
 val key=preflightDigest(preflight.reports)+":"+targets.joinToString(","){it.id.toString()}
 LaunchedEffect(state.decision,state.revision,blocked,resumed,installed.scanning,installed.checkedAt,preflight.scanning,key){
  if(state.decision!=1){state.message=if(state.decision==0)"로컬 점검 자동 실행 · AI 전송 동의 대기" else "AI 전송 꺼짐 · 기기 점검은 자동 실행";return@LaunchedEffect}
  if(blocked||!resumed||installed.scanning||preflight.scanning||installed.error.isNotEmpty()||preflight.error.isNotEmpty()||installed.checkedAt.isEmpty()||preflight.reports.map{it.app.packageName}.toSet()!=installed.candidates.map{it.app.packageName}.toSet())return@LaunchedEffect
  if(state.completedKey==key)return@LaunchedEffect
  if(preflight.reports.isEmpty()){state.message="전체 목록 대조 완료 · 이 프로필에서 인식한 설치 앱 없음";state.completedKey=key;return@LaunchedEffect}
  state.running=true
  try{
   val pending=preflight.reports.filter{preflight.ai[it.id]?.fingerprint!=it.fingerprint()}
   val policies=targets.filter{state.policies[it.id]?.status!="public_guidance"}
   withContext(Dispatchers.IO){
    runStartupReview(pending,policies,gateway(),
     onReviews={reviews->withContext(Dispatchers.Main){preflight.ai=preflight.ai+reviews.associateBy{it.id};state.aiErrors=state.aiErrors-reviews.map{it.id}.toSet()}},
     onPolicy={policy->withContext(Dispatchers.Main){state.policies=state.policies+(policy.exchangeId to policy);state.policyErrors=state.policyErrors-policy.exchangeId}},
     onFailure={error,apps,ids->withContext(Dispatchers.Main){state.aiErrors=state.aiErrors+apps.associateWith{error};state.policyErrors=state.policyErrors+ids.associateWith{error}}},
     onProgress={withContext(Dispatchers.Main){state.message=it}})
   }
   state.completedKey=key
   state.message=if(preflight.reports.all{preflight.ai[it.id]?.fingerprint==it.fingerprint()}&&targets.all{state.policies[it.id]?.status=="public_guidance"})"자동 검토 완료 · 공개 안내와 관측 신호 기준" else "일괄 점검 종료 · 자료 부족·미완료 항목을 확인하세요"
  }catch(e:CancellationException){throw e}catch(_:Exception){state.message="자동 점검 중단 · 미완료 항목 재시도 가능"}finally{state.running=false}
 }
 if(state.showConsent)AlertDialog(onDismissRequest={if(state.decision==0){consentStore.save(false);state.decision=-1};state.showConsent=false},title={Text("전체 거래소 자동 AI 검토")},text={Text("첫 실행부터 ERS의 전체 등록 목록으로 이 폰의 앱을 대조합니다.\n\n동의하면 인식된 모든 거래소의 등록 ID와 앱 패키지 해시·기기 신호·미확인 코드가 ERS 서버와 OpenAI로 전달됩니다. 연결해 둔 계정의 제한·KYC 후속 확인 여부와 미해결 사건 유무도 신호에 포함됩니다. 사진·UID·비밀번호·API 키·메모는 보내지 않습니다.\n\n앱 실행·복귀 시 달라진 신호를 자동 검토하며 설정에서 끌 수 있습니다. 다른 앱의 로그인이나 내부 심사에는 자동 접근하지 않습니다.")},confirmButton={TextButton(onClick={consentStore.save(true);state.decision=1;state.showConsent=false;state.retry()},modifier=Modifier.testTag("startup-consent-accept")){Text("동의하고 전체 자동 검토")}},dismissButton={TextButton(onClick={consentStore.save(false);state.decision=-1;state.showConsent=false}){Text("기기 점검만")}})
 return state
}

@Composable fun StartupOverview(state:StartupState,installed:InstalledExchangeState,preflight:PreflightState?,catalog:List<Exchange>){
 val context=LocalContext.current
 var coverage by remember{mutableStateOf(false)}
 val reports=preflight?.reports.orEmpty()
 val aiDone=reports.count{preflight?.ai?.get(it.id)?.fingerprint==it.fingerprint()}
 val targets=installed.candidates.flatMap{it.exchanges}.distinctBy{it.id}
 Card(Modifier.fillMaxWidth().testTag("startup-overview")){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("전체 거래소 자동 검토",style=MaterialTheme.typography.titleLarge)
  Text(if(installed.scanning)"전체 등록 목록 대조 중…" else "등록 목록 ${installed.directory.size} / ${installed.directory.size}개 대조",modifier=Modifier.testTag("startup-directory-count"))
  Text("설치 앱 후보 ${installed.candidates.size}개 · 거래소 이름 일치 ${targets.size}개")
  Text("기기 점검 ${reports.size}/${installed.candidates.size} · AI 검토 $aiDone/${installed.candidates.size}",modifier=Modifier.testTag("startup-ai-count"))
  Text("공개 안내 확보 ${targets.count{state.policies[it.id]?.status=="public_guidance"}}/${targets.size} · 자료 부족 ${targets.count{state.policies[it.id]?.status=="insufficient_sources"}} · 조회 실패 ${targets.count{state.policyErrors.containsKey(it.id)}}")
  if(state.running||installed.scanning||preflight?.scanning==true)LinearProgressIndicator(Modifier.fillMaxWidth())
  Text(state.message,modifier=Modifier.testTag("startup-progress"))
  if(reports.isNotEmpty())Text("관측 신호: 높음 ${reports.count{it.level=="HIGH"}} · 주의 ${reports.count{it.level=="MEDIUM"}} · 낮음 ${reports.count{it.level=="LOW"}} · 미확인 ${reports.count{it.level=="UNKNOWN"}}",style=MaterialTheme.typography.bodySmall)
  state.aiErrors.values.distinct().forEach{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
  Text("목록 대조는 전체 등록 거래소 대상입니다. AI는 인식된 설치 앱과 해당 거래소의 공개 자료를 검토합니다. 미인식·미연결 계정의 안전이나 KYC 진위를 확정하지 않습니다.",style=MaterialTheme.typography.bodySmall)
  TextButton(onClick={coverage=true},modifier=Modifier.testTag("startup-open-coverage")){Text("전체 ${installed.directory.size}개 검토 범위 보기")}
  Row{
   TextButton(onClick={if(state.decision!=1)state.showConsent=true else state.retry()},enabled=!state.running,modifier=Modifier.testTag("startup-retry")){Text(if(state.decision==1)"미완료 재시도" else "자동 AI 검토 켜기")}
   if(state.decision==1)TextButton(onClick={StartupConsent(context).save(false);state.decision=-1}){Text("AI 전송 끄기")}
  }
 }}
 if(coverage)StartupCoverage(installed,state,catalog){coverage=false}
}

@Composable internal fun StartupExchangeView(exchange:CmcExchange,policy:StartupPolicy?,error:String?){
 val uri=LocalUriHandler.current
 var expanded by remember{mutableStateOf(false)}
 Text("${exchange.name} 공개 안내: "+when{error!=null->"조회 실패";policy==null->"대기·미조회";policy.status=="insufficient_sources"->"관련 근거 부족";else->"${policy.topics.size}개 항목 확보"},style=MaterialTheme.typography.bodySmall)
 if(error!=null)Text(error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
 if(policy!=null){
  TextButton(onClick={expanded=!expanded}){Text(if(expanded)"공개 근거 접기" else "공개 근거·출처 보기")}
  if(expanded){
   Text("CMC 연결 사이트 ${policy.domain} · ${preventionTime(policy.reviewedAt)}${if(policy.cached)" · 저장된 공개 분석" else ""}",style=MaterialTheme.typography.bodySmall)
   policy.topics.forEach{topic->TextButton(onClick={runCatching{uri.openUri(topic.sourceUrl)}}){Text("${startupTopics[topic.code]} ↗")}}
   Text("AI가 찾은 일반 안내입니다. 이 계정에 실제 적용된 사유와 신원 진위는 미확인입니다.",style=MaterialTheme.typography.bodySmall)
  }
 }
}

@Composable private fun StartupCoverage(installed:InstalledExchangeState,state:StartupState,catalog:List<Exchange>,close:()->Unit){
 var search by remember{mutableStateOf("")};var onlyInstalled by remember{mutableStateOf(false)}
 val matched=installed.candidates.flatMap{c->c.exchanges.map{it.id to c.app.label}}.groupBy({it.first},{it.second})
 val filtered=installed.directory.filter{(!onlyInstalled||it.id in matched)&&(it.name.contains(search,true)||it.slug.contains(search,true))}
 Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Row{Text("전체 거래소 검토 범위",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=close){Text("닫기")}}
   Text("전체 ${installed.directory.size}개 · 검색 ${filtered.size}개",modifier=Modifier.testTag("startup-coverage-count"))
   OutlinedTextField(search,{search=it.take(100)},label={Text("전체 등록 거래소 검색")},modifier=Modifier.fillMaxWidth().testTag("startup-coverage-search"))
   Row{Checkbox(onlyInstalled,{onlyInstalled=it});Text("설치 이름 일치 후보만")}
   Text("설치 미인식은 안전 판정이 아닙니다. 다른 프로필·숨긴 앱·다른 이름은 누락될 수 있습니다. 이름 일치만으로 공식 앱이라고 인증하지 않습니다.",style=MaterialTheme.typography.bodySmall)
   LazyColumn(Modifier.weight(1f).testTag("startup-coverage-list"),verticalArrangement=Arrangement.spacedBy(8.dp)){
    items(filtered,key={it.id}){exchange->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){ExchangeBrandLogo(catalogExchange(catalog,exchange),32);Text(exchange.name,style=MaterialTheme.typography.titleMedium)}
     Text(if(exchange.id in matched)"설치 이름 일치: ${matched[exchange.id]!!.joinToString()}" else "설치 미인식 · 계정 미조회",style=MaterialTheme.typography.bodySmall)
     if(exchange.id in matched)StartupExchangeView(exchange,state.policies[exchange.id],state.policyErrors[exchange.id])
    }}}
   }
  }}
 }
}
