package com.example.riskscanner

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InstalledExchangeState{
 var candidates by mutableStateOf<List<ExchangeAppCandidate>>(emptyList());private set
 var scanning by mutableStateOf(true);private set
 var error by mutableStateOf("");private set
 var checkedAt by mutableStateOf("");private set
 var revision by mutableIntStateOf(0);private set
 fun refresh(){revision++}
 suspend fun scan(apps:()->List<DiagnosticApp>,directory:()->CmcDirectory){
  scanning=true;error=""
  try{
   val found=withContext(Dispatchers.IO){recognizeExchangeApps(apps(),directory())}
   candidates=found;checkedAt=Instant.now().toString()
  }catch(e:CancellationException){throw e}catch(_:Exception){error="앱 인식 실패 · 마지막 조회 목록을 유지합니다. 다시 인식해 주세요"}finally{scanning=false}
 }
}

/** Executes on first composition and each foreground resume, without an account or button. */
@Composable fun rememberInstalledExchanges(context:Context,catalogRevision:Any,appReader:()->List<DiagnosticApp> = {diagnosticApps(context)},directoryReader:()->CmcDirectory = {CmcDirectoryStore(context).load()}):InstalledExchangeState{
 val state=remember{InstalledExchangeState()}
 val latestApps by rememberUpdatedState(appReader);val latestDirectory by rememberUpdatedState(directoryReader)
 val lifecycle=(context as? ComponentActivity)?.lifecycle
 DisposableEffect(lifecycle){
  val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)state.refresh()}
  lifecycle?.addObserver(observer);onDispose{lifecycle?.removeObserver(observer)}
 }
 LaunchedEffect(state.revision,catalogRevision){state.scan(latestApps,latestDirectory)}
 return state
}

fun preventionTime(value:String):String=runCatching{DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))}.getOrDefault(value)

@Composable fun PreventionHome(installed:InstalledExchangeState,cases:List<PreventionCase>,loading:Boolean,error:String,onRecord:(CmcExchange,DiagnosticApp)->Unit,onInspect:(CmcExchange,DiagnosticApp)->Unit,onCase:(PreventionCase)->Unit,onDiscovery:()->Unit,onAccounts:()->Unit,onReset:()->Unit){
 var confirmReset by remember{mutableStateOf(false)}
 LazyColumn(Modifier.fillMaxSize().testTag("prevention-home"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item{
   Text("원인 조사 · 재발 예방",style=MaterialTheme.typography.headlineSmall)
   Text("거래소의 안내, 당시 상황, 조치 후 결과를 함께 쌓습니다.",style=MaterialTheme.typography.bodySmall)
  }
  item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
   Text("내 폰의 거래소 앱",style=MaterialTheme.typography.titleLarge)
   Text(if(installed.scanning)"설치된 앱을 자동으로 찾는 중…" else "설치 후보 ${installed.candidates.size}개",modifier=Modifier.testTag("home-installed-count"))
   Text("첫 실행과 ERS로 돌아올 때 자동 조회합니다. 앱 목록 비교는 이 폰 안에서 처리합니다.",style=MaterialTheme.typography.bodySmall)
   if(installed.checkedAt.isNotEmpty())Text("마지막 조회 ${preventionTime(installed.checkedAt)}",style=MaterialTheme.typography.bodySmall)
   Text("앱 인식과 계정 연결은 별도입니다. 로그인 ID·KYC·실제 제한 사유는 아직 미확인입니다.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("home-identity-boundary"))
   if(installed.error.isNotEmpty())Text(installed.error,color=MaterialTheme.colorScheme.error)
   Row{TextButton(onClick=installed::refresh,enabled=!installed.scanning,modifier=Modifier.testTag("home-rescan")){Text("다시 인식")};TextButton(onClick=onDiscovery){Text("못 찾은 앱 지정")}}
  }}}
  if(!installed.scanning&&installed.error.isEmpty()&&installed.candidates.isEmpty())item{Text("일치하는 앱을 찾지 못했습니다. 다른 앱 이름·숨긴 앱·업무 프로필은 자동 인식되지 않을 수 있습니다.",style=MaterialTheme.typography.bodySmall)}
  items(installed.candidates,key={"app:"+it.app.packageName}){candidate->
   Card(Modifier.fillMaxWidth().testTag("home-app-${candidate.app.packageName}")){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
    Text(candidate.app.label,style=MaterialTheme.typography.titleMedium)
    Text("버전 ${candidate.app.version} · ${candidate.basis}\n${candidate.app.packageName}",style=MaterialTheme.typography.bodySmall)
    Text("공식 배포 앱 여부 미확인 · 계정 없이 원인 기록 가능",style=MaterialTheme.typography.bodySmall)
    candidate.exchanges.forEach{exchange->
     if(candidate.exchanges.size>1)Text("이름 일치 후보: ${exchange.name}")
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
      Button(onClick={onRecord(exchange,candidate.app)},enabled=!loading&&error.isEmpty(),modifier=Modifier.testTag("record-${candidate.app.packageName}-${exchange.id}")){Text("원인 기록")}
      OutlinedButton(onClick={onInspect(exchange,candidate.app)},enabled=candidate.app.enabled&&!loading&&error.isEmpty()){Text("오류 화면 AI 검토")}
     }
    }
   }}
  }
  item{HorizontalDivider();Text("사건과 조치 이력",style=MaterialTheme.typography.titleLarge)
   if(loading)Text("암호화된 기록을 불러오는 중…")
   else if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
   else Text("누적 ${cases.size}건 · 확인 중 ${cases.count{it.outcome!="resolved"}}건 · 해결 기록 ${cases.count{it.outcome=="resolved"}}건",modifier=Modifier.testTag("home-case-count"))
   Text("해결 여부는 사용자 기록입니다. 기기 상태와 반복 횟수만으로 거래소의 판단 원인을 확정하지 않습니다.",style=MaterialTheme.typography.bodySmall)
  }
  if(!loading&&error.isEmpty()&&cases.isEmpty())item{Text("아직 사건 기록이 없습니다. 거래소 앱의 ‘원인 기록’에서 안내 문구와 발생 상황을 남겨 주세요.")}
  if(error.isEmpty())items(cases,key={"case:"+it.id}){case->
   OutlinedCard(onClick={onCase(case)},modifier=Modifier.fillMaxWidth().testTag("case-${case.id}")){
    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
     Text(case.exchangeName+case.accountLabel.takeIf{it.isNotEmpty()}.let{if(it==null)"" else " · $it"},style=MaterialTheme.typography.titleMedium)
     Text("${preventionNotices[case.noticeType]} · ${preventionOutcomes[case.outcome]}")
     Text("${preventionTime(case.createdAt)} · 조치 ${case.updates.size} · AI 근거 ${case.reviews.size}",style=MaterialTheme.typography.bodySmall)
     if(case.notice.isNotEmpty())Text(case.notice.take(120),maxLines=2)
    }
   }
  }
  item{
   Text("사건과 선택 앱 정보는 이 폰에 암호화해 저장합니다. 계정별 공식 조회는 상단의 계정 연결에서 진행할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
   TextButton(onClick=onAccounts){Text("등록 계정 · 기기 점검 이력")}
   if(cases.isNotEmpty()||error.isNotEmpty())TextButton(onClick={confirmReset=true},enabled=!loading,modifier=Modifier.testTag("reset-prevention")){Text("원인·조치 기록 전체 삭제")}
  }
 }
 if(confirmReset)AlertDialog(onDismissRequest={confirmReset=false},title={Text("원인·조치 기록 삭제")},text={Text("이 폰에 저장된 모든 사건·조치·사건에 첨부한 AI 결과를 삭제합니다. 되돌릴 수 없습니다.")},confirmButton={TextButton(onClick={onReset();confirmReset=false}){Text("전체 삭제")}},dismissButton={TextButton(onClick={confirmReset=false}){Text("취소")}})
}

@Composable private fun PreventionChoice(label:String,value:String,options:Map<String,String>,tag:String,onValue:(String)->Unit){
 var expanded by remember{mutableStateOf(false)}
 Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().testTag(tag)){Text("$label · ${options[value]}")};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){options.forEach{(key,text)->DropdownMenuItem(text={Text(text)},onClick={onValue(key);expanded=false},modifier=Modifier.testTag("$tag-$key"))}}}
}

@Composable private fun PreventionDeviceView(device:PreventionDevice){
 Text("기기에서 직접 관측 · ${preventionTime(device.observedAt)}\nAndroid SDK ${device.sdk} · ${device.network} · 인터넷 ${when(device.validated){true->"검증됨";false->"검증 안 됨";null->"미확인"}}\nVPN ${if(device.vpn)"감지" else "미감지"} · 프록시 ${if(device.proxy)"감지" else "미감지"} · 자동 시간 ${if(device.autoTime)"켜짐" else "꺼짐"}",style=MaterialTheme.typography.bodySmall)
 Text("기록 시점의 상태입니다. 이전 발생 시점의 상태나 거래소의 판정 사유를 증명하지 않습니다.",style=MaterialTheme.typography.bodySmall)
}

@Composable fun PreventionCaseDialog(draft:PreventionCase,existing:Boolean,allCases:List<PreventionCase>,onSave:(PreventionCase)->Unit,onDelete:()->Unit,onInspect:()->Unit,close:()->Unit){
 val context=LocalContext.current
 var noticeType by remember(draft.id){mutableStateOf(draft.noticeType)};var notice by remember(draft.id){mutableStateOf(draft.notice)}
 var occurred by remember(draft.id){mutableStateOf(draft.occurredAt)};var account by remember(draft.id){mutableStateOf(draft.accountLabel)}
 var action by remember(draft.id){mutableStateOf("")};var support by remember(draft.id){mutableStateOf("")};var outcome by remember(draft.id){mutableStateOf(draft.outcome)}
 var message by remember(draft.id){mutableStateOf("")};var confirmDelete by remember{mutableStateOf(false)}
 Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxSize()){
   Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Row{Text(if(existing)"원인 조사 이력" else "새 원인 기록",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=close,modifier=Modifier.testTag("close-prevention-case")){Text("닫기")}}
    Text(draft.exchangeName,style=MaterialTheme.typography.titleLarge)
    Text("${draft.app.label} · ${draft.app.version}\n${draft.app.packageName}",style=MaterialTheme.typography.bodySmall)
    Text("로그인 ID 미확인 · 실제 판정 원인 미확정",modifier=Modifier.testTag("case-source-boundary"))
    if(!existing){
     Text("거래소에 표시된 안내를 남겨 주세요. 비밀번호·인증번호·신분증 번호는 입력하지 마세요.")
     PreventionChoice("안내 유형",noticeType,preventionNotices,"case-notice-type"){noticeType=it}
     OutlinedTextField(notice,{notice=it.take(2000)},label={Text("거래소 안내 문구·오류 코드")},modifier=Modifier.fillMaxWidth().testTag("case-notice"))
     OutlinedTextField(occurred,{occurred=it.take(80)},label={Text("실제 발생 시각 · 기억하는 경우만")},modifier=Modifier.fillMaxWidth())
     OutlinedTextField(account,{account=it.take(80)},label={Text("계정 구분 별칭 · 선택")},supportingText={Text("여러 계정을 쓰는 경우 직접 구분하는 메모입니다")},modifier=Modifier.fillMaxWidth())
     Text("안내와 발생 시각은 사용자 입력으로 저장합니다. 기기 상태는 저장 버튼을 누른 시점에 수집합니다.",style=MaterialTheme.typography.bodySmall)
     Button(onClick={try{onSave(draft.copy(noticeType=noticeType,notice=notice.trim(),occurredAt=occurred.trim(),accountLabel=account.trim(),createdAt=Instant.now().toString(),device=PreventionDevice.capture(context)));message="사건을 암호화해 저장했습니다"}catch(_:Exception){message="저장하지 못했습니다. 기존 기록은 유지됩니다"}},enabled=notice.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("save-prevention-case")){Text("사건과 현재 기기 상태 저장")}
    }else{
     Text("사용자 기록 · ${preventionTime(draft.createdAt)}\n${preventionNotices[draft.noticeType]}\n${draft.notice.ifEmpty{"안내 문구 입력 없음 · AI 검토 결과 첨부"}}")
     if(draft.accountLabel.isNotEmpty())Text("계정 구분 별칭: ${draft.accountLabel} · 실제 ID 확인 아님")
     Text("발생 시각: ${draft.occurredAt.ifEmpty{"미입력"}} · 사용자 입력",style=MaterialTheme.typography.bodySmall)
     draft.device?.let{PreventionDeviceView(it)}
     Text("현재 기록: ${preventionOutcomes[draft.outcome]}",modifier=Modifier.testTag("case-current-outcome"))
     val similar=allCases.filter{it.app.packageName==draft.app.packageName&&it.exchangeName==draft.exchangeName&&it.noticeType==draft.noticeType}
     Text("이 앱에서 같은 안내 유형 ${similar.size}건 · 해결 기록 ${similar.count{it.outcome=="resolved"}}건\n사용자가 남긴 사건 기준입니다. 원인·조치 효과의 통계적 검증은 아닙니다.",style=MaterialTheme.typography.bodySmall)
     OutlinedButton(onClick=onInspect,modifier=Modifier.fillMaxWidth().testTag("case-ai-review")){Text("이 사건에 오류 화면 AI 검토 추가")}
     draft.reviews.forEach{review->HorizontalDivider();Text("AI 화면 근거 · ${preventionTime(review.reviewedAt)}");AppDiagnosticSummary(review)}
     Text("조치와 이후 결과",style=MaterialTheme.typography.titleMedium)
     draft.updates.forEach{update->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
      Text("${preventionTime(update.recordedAt)} · ${preventionOutcomes[update.outcome]}")
      Text("사용자가 기록한 조치: ${update.action.ifEmpty{"입력 없음"}}")
      if(update.supportReply.isNotEmpty())Text("고객지원 답변 · 사용자 입력 / 원문 진위 미검증\n${update.supportReply}")
      update.device?.let{PreventionDeviceView(it)}
     }}}
     OutlinedTextField(action,{action=it.take(1500)},label={Text("수행한 조치·추가 상황")},modifier=Modifier.fillMaxWidth().testTag("case-action"))
     OutlinedTextField(support,{support=it.take(2000)},label={Text("고객지원 답변 메모 · 선택")},supportingText={Text("직접 옮긴 답변은 거래소가 인증한 응답으로 표시하지 않습니다")},modifier=Modifier.fillMaxWidth().testTag("case-support"))
     PreventionChoice("조치 후 상태",outcome,preventionOutcomes,"case-outcome"){outcome=it}
     Button(onClick={try{onSave(draft.append(PreventionUpdate(action=action.trim(),outcome=outcome,supportReply=support.trim(),device=PreventionDevice.capture(context))));action="";support="";message="조치와 결과를 추가했습니다"}catch(_:Exception){message="조치 저장 실패 · 저장 한도와 기록 상태를 확인하세요"}},enabled=action.isNotBlank()||support.isNotBlank()||outcome!=draft.outcome,modifier=Modifier.fillMaxWidth().testTag("save-prevention-update")){Text("조치·결과 추가 저장")}
     HorizontalDivider()
     Text("재발 예방을 위해 확인할 항목",style=MaterialTheme.typography.titleMedium)
     Text("• 거래소가 명시한 사유·요청 자료·대기 시간을 확인하기\n• 안내 문구와 발생 시각, 변경한 설정을 함께 기록하기\n• 공식 고객지원에 정확한 사유와 해결 조건 문의하기\n• 조치 후 같은 현상이 재발하는지 결과 남기기")
     TextButton(onClick={confirmDelete=true},modifier=Modifier.testTag("delete-prevention-case")){Text("이 사건 삭제")}
    }
    if(message.isNotEmpty())Text(message,modifier=Modifier.testTag("case-save-message"))
    Text("이 기록은 폰 안에 암호화해 보관합니다. AI 검토는 따로 선택하고 전송에 동의한 화면만 사용합니다.",style=MaterialTheme.typography.bodySmall)
   }
  }
 }
 if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("이 사건 삭제")},text={Text("이 사건의 안내·조치·AI 검토 기록을 삭제합니다.")},confirmButton={TextButton(onClick={try{onDelete();confirmDelete=false}catch(_:Exception){message="삭제 실패";confirmDelete=false}}){Text("삭제")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("취소")}})
}
