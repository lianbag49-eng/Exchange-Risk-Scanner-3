package com.example.riskscanner

import android.content.Intent
import android.net.Uri
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun ExchangeAccountDialog(exchanges:List<Exchange>,records:List<Record>,onEvidence:(Record)->Unit,close:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val storage=remember{ExchangeAccountStorage(context)}
 val prefs=remember{context.getSharedPreferences("ers_exchange_audit_preferences",0)}
 var storageError by remember{mutableStateOf("")};var readable by remember{mutableStateOf(true)}
 val accounts=remember{mutableStateListOf<LinkedExchangeAccount>().apply{try{addAll(storage.load())}catch(_:Exception){readable=false;storageError="연결 정보 복구 실패 · 기존 암호화 데이터를 보존하기 위해 변경을 중단했습니다"}}}
 var busy by remember{mutableStateOf(false)};var job by remember{mutableStateOf<Job?>(null)};var message by remember{mutableStateOf("")}
 var automatic by remember{mutableStateOf(prefs.getBoolean("automatic",false))}
 var adding by remember{mutableStateOf(false)};var provider by remember{mutableStateOf(AccountProvider.BYBIT)}
 var alias by remember{mutableStateOf("")};var apiKey by remember{mutableStateOf("")};var secret by remember{mutableStateOf("")};var targetUid by remember{mutableStateOf("")};var permitted by remember{mutableStateOf(false)}
 var apps by remember{mutableStateOf<List<DiagnosticApp>>(emptyList())};var allApps by remember{mutableStateOf(false)};var appsLoaded by remember{mutableStateOf(false)}
 val lifecycle=(context as? ComponentActivity)?.lifecycle;var resumed by remember{mutableStateOf(true)}
 DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,_->resumed=lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true;if(!resumed)job?.cancel()};lifecycle?.addObserver(observer);onDispose{lifecycle?.removeObserver(observer)}}
 LaunchedEffect(Unit){try{apps=withContext(Dispatchers.IO){diagnosticApps(context)}}catch(e:CancellationException){throw e}catch(_:Exception){message="설치 앱 목록을 읽지 못했습니다"}finally{appsLoaded=true}}
 var displayTime by remember{mutableStateOf(System.currentTimeMillis())}
 LaunchedEffect(Unit){while(true){displayTime=System.currentTimeMillis();delay(30000)}}
 fun persist(next:List<LinkedExchangeAccount>):Boolean {
  if(!readable)return false
  return try{storage.save(next);displayTime=System.currentTimeMillis();accounts.clear();accounts.addAll(next);storageError="";true}catch(_:Exception){storageError="연결 정보 저장 실패 · 기존 저장 내용을 유지합니다";false}
 }
 fun runAll(){
  if(busy||!readable||accounts.isEmpty())return
  busy=true;job=scope.launch{
   try{
    val result=auditConnectedAccounts(accounts.toList(),
     query={account->withContext(Dispatchers.IO){queryExchangeAccount(account)}},
     save={next->persist(accounts.map{if(it.id==next.id)next else it})},
     progress={done,total,account->message="전체 점검 $done/$total · ${account.alias}"})
    message=when(result.reason){
     "storage_failed"->"저장 실패로 점검을 중단했습니다"
     "rate_limit"->{automatic=false;prefs.edit().putBoolean("automatic",false).apply();"요청 한도에 도달해 나머지 조회를 중단했습니다 · ${result.checked}/${result.total}"}
     else->"연결 계정 ${result.checked}개 점검 종료 · 조회 실패 ${result.failures}개 · 미제공 항목은 미확인입니다"
    }
   }catch(e:CancellationException){message="점검 중단 · 완료되지 않은 계정은 이전 조회 결과입니다";throw e}finally{busy=false}
  }
 }
 LaunchedEffect(automatic){if(automatic){while(true){if(resumed&&!adding)runAll();delay(300000)}}}
 val candidates=if(allApps)apps else apps.filter{a->exchanges.any{e->
  val name=e.name.lowercase(Locale.ROOT);val label=a.label.lowercase(Locale.ROOT)
  name.length>=3&&(label==name||label.startsWith(name+" ")||label.startsWith(name+":")||label.startsWith(name+" -"))
 }}
 Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)){
  Surface(Modifier.fillMaxSize()){
   Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(18.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Row{Text("계정 연결 · 전체 점검",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=close,modifier=Modifier.testTag("close-account-audit")){Text("닫기")}}
    Text("공식 계정 조회와 화면 근거 검토를 구분합니다. 앱 설치 여부만으로 로그인된 아이디나 KYC 승인을 알 수는 없습니다.")
    Text("현재 공식 조회: Bybit 개인 읽기 API · Toobit 제휴 API. 다른 거래소는 계정 연동 지원 확인이 필요합니다.",style=MaterialTheme.typography.bodySmall)
    if(storageError.isNotEmpty())Text(storageError,color=MaterialTheme.colorScheme.error)
    Row{Button(onClick={runAll()},enabled=!busy&&readable&&accounts.isNotEmpty(),modifier=Modifier.weight(1f).testTag("audit-all")){Text("연결 계정 전체 점검")};if(busy)TextButton(onClick={job?.cancel()}){Text("중단")}}
    Row{Checkbox(automatic,{automatic=it;prefs.edit().putBoolean("automatic",it).apply()},enabled=readable);Text("이 화면이 활성화된 동안 5분마다 자동 점검",modifier=Modifier.padding(top=10.dp))}
    if(message.isNotEmpty())Text(message)
    OutlinedButton(onClick={adding=!adding;apiKey="";secret="";permitted=false},enabled=!busy&&readable&&accounts.size<100,modifier=Modifier.fillMaxWidth().testTag("add-api-account")){Text(if(adding)"입력 취소" else "읽기 전용 계정 연결")}
    if(adding){
     AccountProvider.values().forEach{p->Row{RadioButton(provider==p,{provider=p;apiKey="";secret="";targetUid="";permitted=false},enabled=!busy);TextButton(onClick={provider=p;apiKey="";secret="";targetUid="";permitted=false},enabled=!busy){Text(p.title)}}}
     if(provider==AccountProvider.TOOBIT_AFFILIATE)Text("Toobit 제휴 권한으로 조회할 수 있는 초대 계정만 지원합니다. 일반 개인 API 키로는 KYC를 조회할 수 없습니다.")
     else Text("Bybit Global의 시스템 생성 HMAC 읽기 전용 키를 사용합니다. API가 반환한 UID로 연결하며 휴대폰 앱의 로그인 계정과 자동으로 동일시하지 않습니다.")
     OutlinedTextField(alias,{alias=it.take(60)},label={Text("계정 이름")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
     OutlinedTextField(targetUid,{targetUid=it.take(32)},label={Text(if(provider==AccountProvider.BYBIT)"확인할 UID · 비워두면 API에서 조회" else "조회할 초대 계정 UID")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
     OutlinedTextField(apiKey,{apiKey=it.trim();permitted=false},label={Text("API Key")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("account-api-key"))
     OutlinedTextField(secret,{secret=it.trim();permitted=false},label={Text("API Secret")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("account-api-secret"))
     Text("키는 이 휴대폰에서 Android 보안 키로 암호화해 보관하고, 선택한 거래소 공식 API에 직접 서명 요청을 보냅니다. 키·API 응답을 AI 서버로 보내지 않습니다. 연결 해제로 저장된 키를 제거할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
     Row{Checkbox(permitted,{permitted=it},enabled=!busy);Text("이 계정의 조회 권한이 있고 읽기 전용 키를 준비했습니다. 위 조회·기기 보관에 동의합니다.",modifier=Modifier.padding(top=8.dp))}
     Button(onClick={
      val candidate=LinkedExchangeAccount(UUID.randomUUID().toString(),provider,alias.trim(),apiKey,secret,targetUid.trim())
      busy=true;message="공식 API에서 계정을 확인하고 있습니다";job=scope.launch{try{
       val report=withContext(Dispatchers.IO){queryExchangeAccount(candidate)}
       if(accounts.any{it.provider==candidate.provider&&it.uid==report.uid}){message="이미 연결된 계정입니다"}
       else if(persist(accounts+candidate.copy(uid=report.uid,status=report,lastAttempt=System.currentTimeMillis()))){adding=false;apiKey="";secret="";alias="";targetUid="";permitted=false;message="공식 계정 연결 완료 · ${apiKycLabel(report)}"}
      }catch(e:CancellationException){throw e}catch(e:Exception){message=if(e is AccountAuditError)e.message.orEmpty() else "계정 연결 실패 · 입력값을 확인하세요"}finally{busy=false}}
     },enabled=!busy&&permitted&&alias.isNotBlank()&&apiKey.length>=8&&secret.length>=8&&(provider==AccountProvider.BYBIT||validExchangeUid(targetUid)),modifier=Modifier.fillMaxWidth().testTag("connect-api-account")){Text("계정 확인 후 연결 저장")}
     TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(provider.docs)))}){Text("공식 조회 API 문서")}
    }
    HorizontalDivider();Text("연결된 계정 ${accounts.size}개",style=MaterialTheme.typography.titleMedium)
    if(accounts.isEmpty())Text("연결된 계정 없음 · KYC 상태 미확인",modifier=Modifier.testTag("no-connected-accounts"))
    accounts.forEach{a->
     Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
      Text("${a.alias} · ${a.provider.exchange}",style=MaterialTheme.typography.titleMedium)
      Text("UID ${a.uid} · 공식 API 응답")
      if(a.error.isNotEmpty())Text("최근 조회 실패 · 현재 상태 미확인\n${a.error}",color=MaterialTheme.colorScheme.error)
      a.status?.let{s->
       Text("조회 시점의 상태: "+apiKycLabel(s))
       if(apiKycNeedsRefresh(s,displayTime))Text("이전 조회 결과 · 현재 상태를 확인하려면 다시 점검하세요")
       if(s.region.isNotEmpty())Text("API KYC 지역: ${s.region}")
       if(s.scope=="subaccount_api")Text("서브계정 API 응답 · KYC 적용 대상은 거래소 확인 필요")
       Text("조회 시각: "+SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.KOREA).format(Date(s.checkedAt)))
       Text(if(s.permissionCheck=="verified_read_only")"읽기 전용 키 확인됨" else "읽기 전용 키: 사용자 확인 · API에서 권한 상세 미제공",style=MaterialTheme.typography.bodySmall)
      }
      Text("신분증 진위·현재 앱 로그인·계정 제한 사유는 이 API 응답으로 확인하지 않습니다.",style=MaterialTheme.typography.bodySmall)
      TextButton(onClick={persist(accounts.filter{it.id!=a.id})},enabled=!busy){Text("연결 해제 · 저장 키 삭제")}
     }}
    }
    HorizontalDivider();Text("기존 등록 계정",style=MaterialTheme.typography.titleMedium)
    records.distinctBy{it.exchange.name+":"+it.uid}.forEach{record->
     val linked=accounts.any{it.provider.exchange.equals(record.exchange.name,true)&&it.uid==record.uid}
     Text("${record.name} · UID ${record.uid}\n"+if(linked)"공식 API 연결됨 · 위 조회 시각 확인" else "공식 계정 미연결 · 현재 KYC 상태 미확인")
     TextButton(onClick={onEvidence(record)},enabled=!busy){Text("이 계정의 AI 화면 근거 검토")}
    }
    HorizontalDivider();Text("휴대폰 설치 앱 확인",style=MaterialTheme.typography.titleMedium)
    Text("목록은 설치 앱 이름에 따른 후보입니다. 공식 발행자·로그인된 아이디는 확인하지 않았습니다. 앱 화면 검토는 사용자 선택과 캡처 동의가 필요합니다.",style=MaterialTheme.typography.bodySmall)
    Row{Checkbox(allApps,{allApps=it});Text("모든 실행 가능한 앱 보기",modifier=Modifier.padding(top=10.dp))}
    if(!appsLoaded)Text("설치 앱 확인 중…") else if(candidates.isEmpty())Text("이름이 일치하는 설치 앱 후보 없음 · 전체 앱에서 확인하세요")
    candidates.forEach{app->TextButton(onClick={try{val intent=context.packageManager.getLaunchIntentForPackage(app.packageName)?:error("missing");context.startActivity(intent)}catch(_:Exception){message="선택 앱을 열지 못했습니다"}},enabled=!busy){Text("${app.label} · 아이디 미확인\n${app.packageName}")}}
   }
  }
 }
}
