package com.example.riskscanner

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun ExchangeDiscoveryDialog(exchanges:List<Exchange>,onCatalogChanged:()->Unit,onInspect:(Exchange,DiagnosticApp)->Unit,onRegister:(Exchange)->Unit,close:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val store=remember{CmcDirectoryStore(context)}
 var directory by remember{mutableStateOf(store.load())};var apps by remember{mutableStateOf<List<DiagnosticApp>>(emptyList())}
 var candidates by remember{mutableStateOf<List<ExchangeAppCandidate>>(emptyList())}
 var scanning by remember{mutableStateOf(false)};var updating by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
 var revision by remember{mutableIntStateOf(0)};var search by remember{mutableStateOf("")};var catalogMode by remember{mutableStateOf(false)};var showAllApps by remember{mutableStateOf(false)};var selectedApp by remember{mutableStateOf<DiagnosticApp?>(null)}
 var lastRefreshAttempt by remember{mutableStateOf(0L)}
 val lifecycle=(context as? ComponentActivity)?.lifecycle
 DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)revision++};lifecycle?.addObserver(observer);onDispose{lifecycle?.removeObserver(observer)}}
 LaunchedEffect(revision,directory){scanning=true;try{
  val found=withContext(Dispatchers.IO){diagnosticApps(context)}
  val recognized=withContext(Dispatchers.Default){recognizeExchangeApps(found,directory)}
  apps=found;candidates=recognized
 }catch(e:CancellationException){throw e}catch(_:Exception){message="설치 앱을 읽지 못했습니다. 다시 인식해 주세요";apps=emptyList();candidates=emptyList()}finally{scanning=false}}
 fun refresh(){
  val now=System.currentTimeMillis()
  if(updating)return
  if(lastRefreshAttempt!=0L&&now-lastRefreshAttempt in 0..59999){message="목록 갱신은 1분 뒤 다시 시도할 수 있습니다";return}
  lastRefreshAttempt=now;updating=true;message="CMC 전체 목록을 갱신하고 있습니다"
  scope.launch{try{
   val next=withContext(Dispatchers.IO){fetchCmcDirectory().also{store.save(it)}}
   directory=next;onCatalogChanged();message="CMC 전체 목록 ${next.entries.size}개 갱신 완료"
  }catch(e:CancellationException){throw e}catch(_:Exception){message="CMC 갱신 실패 · 이전 목록 유지. 연결·요청 한도를 확인하세요"}finally{updating=false}}
 }
 LaunchedEffect(Unit){if(directory.needsRefresh(System.currentTimeMillis()))refresh()}
 fun exchange(e:CmcExchange)=exchanges.find{it.infoUrl.trimEnd('/')==e.infoUrl.trimEnd('/')}?:Exchange(e.name,e.name.take(2),Color(0xFFD9B56D),infoUrl=e.infoUrl)
 val matchedPackages=candidates.map{it.app.packageName}.toSet()
 val filteredEntries=directory.entries.filter{it.name.contains(search,true)||it.slug.contains(search,true)}
 Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxSize()){
   Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(18.dp)){
    Row{Text("거래소 앱 자동 인식",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=close,modifier=Modifier.testTag("close-discovery")){Text("닫기")}}
    Text("CMC 목록 ${directory.entries.size}개 · "+when(directory.origin){"live"->"방금 갱신";"cache"->"저장된 목록";else->"내장 목록"},modifier=Modifier.testTag("discovery-catalog-count"))
    Text("목록 확인: ${directory.checkedAt.take(19).replace('T',' ')} UTC",style=MaterialTheme.typography.bodySmall)
    Text("활성·비활성·미추적 거래소를 포함합니다. 등록·이름 일치는 공식 앱 확인이나 KYC 인증 근거가 아닙니다.",style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
     OutlinedButton(onClick={revision++},enabled=!scanning,modifier=Modifier.testTag("rescan-installed")){Text(if(scanning)"인식 중…" else "설치 앱 다시 인식")}
     TextButton(onClick={refresh()},enabled=!updating,modifier=Modifier.testTag("refresh-cmc")){Text(if(updating)"CMC 갱신 중…" else "CMC 목록 갱신")}
    }
    Row{FilterChip(selected=!catalogMode,onClick={catalogMode=false;selectedApp=null;search=""},label={Text("설치 후보 ${candidates.size}")});Spacer(Modifier.width(8.dp));FilterChip(selected=catalogMode,onClick={catalogMode=true;search=""},label={Text("CMC 전체 목록")})}
    if(message.isNotEmpty())Text(message,style=MaterialTheme.typography.bodySmall)
    if(catalogMode){
     selectedApp?.let{app->Text("${app.label}의 거래소를 직접 선택합니다 · 공식 앱 여부 미확인")}
     OutlinedTextField(search,{search=it.take(160)},label={Text("CMC 거래소 검색")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("cmc-search"))
     LazyColumn(Modifier.weight(1f)){
      items(filteredEntries,key={it.id}){e->
       ListItem(headlineContent={Text(e.name)},supportingContent={Text("${cmcStatus(e.status)} · ID ${e.id}")},trailingContent={TextButton(onClick={val app=selectedApp;if(app==null)onRegister(exchange(e)) else onInspect(exchange(e),app)}){Text(if(selectedApp==null)"계정 입력" else "화면 검토")}})
       HorizontalDivider()
      }
      if(filteredEntries.isEmpty())item{Text("목록에서 찾지 못했습니다 · CMC 등록 여부는 별도 확인 필요")}
     }
    }else{
     LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)){
      item{Text("계정 연결 없이 설치 후보를 찾습니다. 로그인 ID·KYC 진위는 미확인입니다. 앱 목록은 기기 안에서만 비교합니다.",modifier=Modifier.testTag("discovery-no-identity-claim"))}
      if(!scanning&&candidates.isEmpty())item{Text("일치하는 설치 후보 없음 · 미설치·다른 앱 이름·Android 조회 제한일 수 있습니다")}
      items(candidates,key={it.app.packageName}){candidate->
       Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(candidate.app.label,style=MaterialTheme.typography.titleMedium)
        Text("${candidate.basis}\n${candidate.app.packageName}",style=MaterialTheme.typography.bodySmall)
        Text("공식 앱: 미확인\n로그인 ID: 미확인\nKYC 승인·신분증 진위: 미확인")
        if(!candidate.app.enabled)Text("앱이 비활성 상태입니다")
        candidate.exchanges.forEach{e->
         Text("${e.name} · ${cmcStatus(e.status)}")
         Row{TextButton(onClick={onInspect(exchange(e),candidate.app)},enabled=candidate.app.enabled){Text("이 앱 화면 AI 검토")};TextButton(onClick={onRegister(exchange(e))}){Text("계정 정보 입력")}}
        }
        TextButton(onClick={try{val intent=context.packageManager.getLaunchIntentForPackage(candidate.app.packageName)?:error("missing");context.startActivity(intent)}catch(_:Exception){message="앱 실행에 실패했습니다"}},enabled=candidate.app.enabled){Text("앱 열기")}
       }}
      }
      item{Row{Checkbox(showAllApps,{showAllApps=it});Text("이름이 일치하지 않은 실행 앱도 보기",modifier=Modifier.padding(top=12.dp))}}
      if(showAllApps)items(apps.filter{it.packageName !in matchedPackages},key={"unmatched:"+it.packageName}){app->
       ListItem(headlineContent={Text(app.label)},supportingContent={Text("거래소 미분류 · ${app.packageName}")},trailingContent={TextButton(onClick={selectedApp=app;catalogMode=true;search=""}){Text("거래소 지정")}})
      }
      item{Text("숨긴 앱·업무 프로필·복제 앱·웹 전용 거래소는 목록에 없을 수 있습니다. 화면 검토에는 캡처 및 전송 동의와 AI 서버 연결이 필요합니다.",style=MaterialTheme.typography.bodySmall)}
     }
    }
   }
  }
 }
}
