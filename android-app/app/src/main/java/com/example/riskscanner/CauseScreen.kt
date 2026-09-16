package com.example.riskscanner

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable private fun CauseChoice(label:String,value:String,options:Map<String,String>,tag:String,enabled:Boolean=true,onChange:(String)->Unit){
 var open by remember{mutableStateOf(false)}
 Box{OutlinedButton(onClick={open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag(tag)){Text("$label · ${options[value]?:"선택"}")};DropdownMenu(open,{open=false}){options.forEach{(code,text)->DropdownMenuItem(text={Text(text)},onClick={onChange(code);open=false},modifier=Modifier.testTag("$tag-$code"))}}}
}
@Composable fun CauseDialog(case:PreventionCase,onSave:(PreventionCase)->Unit,liveAccounts:List<LinkedExchangeAccount> = emptyList(),close:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val kb=remember{CauseKnowledge(context)};val latestCase by rememberUpdatedState(case)
 val focusManager=LocalFocusManager.current
 var tab by remember{mutableIntStateOf(0)};var selected by remember{mutableStateOf<List<String>>(emptyList())};var accountId by remember{mutableStateOf("")}
 var accounts by remember{mutableStateOf<List<LinkedExchangeAccount>>(emptyList())};var sameAccount by remember{mutableStateOf(false)}
 var endpoint by remember{mutableStateOf(DEFAULT_AI_SERVER)};var token by remember{mutableStateOf("")};var consent by remember{mutableStateOf(false)}
 var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
 var category by remember{mutableStateOf("unknown")};var findingSource by remember{mutableStateOf("support_message")};var reference by remember{mutableStateOf("")};var reviewed by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){
  try{val config=withContext(Dispatchers.IO){AutoPreflightConfigStore(context).load()};endpoint=config.endpoint;token=config.token}catch(_:Exception){message="저장된 AI 설정을 읽지 못했습니다. 접속 정보를 입력하세요"}
  try{val stored=withContext(Dispatchers.IO){ExchangeAccountStorage(context).load()};accounts=stored.map{a->liveAccounts.find{it.id==a.id&&it.apiKey==a.apiKey&&it.secret==a.secret}?:a}.filter{it.provider.exchange.equals(causeExchange(case),true)}}catch(_:Exception){message="저장된 계정 조회 실패 · 계정 자료 없이 분석할 수 있습니다"}
 }
 val account=accounts.find{it.id==accountId}
 val inputAttempt=remember(case,selected,account){runCatching{causeInput(case,kb,selected,account)}}
 val input=inputAttempt.getOrNull()
 LaunchedEffect(input?.digest(),accountId){consent=false}
 // Insets are applied explicitly below. Do not also use a floating, decor-fitted
 // dialog window, whose measured height can clip the bottom action area.
 Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false,securePolicy=SecureFlagPolicy.SecureOn)){Surface(Modifier.fillMaxSize()){
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()){
   Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Row{Text("근거 기반 원인 분석",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=close,modifier=Modifier.testTag("close-causes")){Text("닫기")}}
    Text(case.exchangeName+" · 공개 자료 ${kb.reviewedAt} 확인",style=MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("분석","결과","사후 대조").forEachIndexed{i,label->FilterChip(selected=tab==i,onClick={focusManager.clearFocus();tab=i},label={Text(label)},modifier=Modifier.weight(1f).testTag("cause-tab-$i"))}}
    when(tab){
     0->{
      Text("거래소의 공개 기준에 맞는 원인 후보를 좁힙니다. 상세 기준은 Bybit·Binance·OKX 일부 항목을 지원합니다. 단서가 부족하면 미확인으로 남깁니다.")
      Text("화면 사진 없이도 분석할 수 있습니다. 기존 사건의 안내 유형·Bybit 오류 코드·기기 관측을 불러옵니다. 다른 앱의 로그인 세션이나 비공개 심사 시스템에 접근하지 않습니다.",style=MaterialTheme.typography.bodySmall)
      Text("사건 당시 직접 확인한 항목만 선택",style=MaterialTheme.typography.titleMedium)
      kb.manualCodes().forEach{code->Row{Checkbox(code in selected,{checked->selected=if(checked)selected+code else selected-code;consent=false},enabled=!busy,modifier=Modifier.testTag("cause-evidence-$code"));Text(kb.label(code),modifier=Modifier.padding(top=8.dp))}}
      Text("선택하지 않은 항목은 미확인입니다. 서로 모순된 기록도 지우지 않고 대조합니다.",style=MaterialTheme.typography.bodySmall)
      CauseChoice("참고할 연결 계정",accountId,linkedMapOf("" to "계정 자료 사용 안 함").apply{accounts.forEach{put(it.id,it.alias)}},"cause-account",!busy){accountId=it;sameAccount=false;consent=false}
      account?.let{a->
       Text("계정 조회 ${a.status?.checkedAt?.let{preventionTime(Instant.ofEpochMilli(it).toString())}?:"결과 없음"} · 사건 이후 현재 상태일 수 있습니다",style=MaterialTheme.typography.bodySmall)
       Row{Checkbox(sameAccount,{sameAccount=it;consent=false},enabled=!busy);Text("이 사건에서 사용한 계정임을 직접 확인했습니다. 앱 로그인 계정과 자동 검증되지는 않습니다.",modifier=Modifier.padding(top=8.dp))}
       OutlinedButton(onClick={busy=true;consent=false;scope.launch{try{val status=withContext(Dispatchers.IO){queryExchangeAccount(a)};accounts=accounts.map{if(it.id==a.id)it.copy(status=status,error="",lastAttempt=System.currentTimeMillis())else it};message="이 화면에서 현재 계정 응답을 갱신했습니다"}catch(e:CancellationException){throw e}catch(_:Exception){accounts=accounts.map{if(it.id==a.id)it.copy(error="조회 실패")else it};message="조회 실패 · 이전 상태를 현재 근거로 사용하지 않습니다"}finally{busy=false}}},enabled=!busy){Text("선택 계정 읽기 조회 갱신")}
      }
      HorizontalDivider();Text("전송할 근거 미리보기",style=MaterialTheme.typography.titleMedium)
      if(input==null)Text(inputAttempt.exceptionOrNull()?.message?:"계정 상태를 먼저 갱신하세요",color=MaterialTheme.colorScheme.error)
      else{if(input.evidence.isEmpty())Text("분석에 사용할 단서 없음")else input.evidence.forEach{CauseEvidenceView(it,kb)} }
      Text("전송: 거래소 구분·임의 사건 ID·위 항목 코드와 기록 시각. UID·API 키·안내 원문·사진·고객지원 메모·이전 AI 추정은 전송하지 않습니다. 서버는 위 항목을 외부 AI로 분석하고 응답 저장을 요청하지 않습니다.",style=MaterialTheme.typography.bodySmall)
      OutlinedTextField(endpoint,{endpoint=it;consent=false},label={Text("ERS 서버 주소")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
      OutlinedTextField(token,{token=it.trim();consent=false},label={Text("서버 접속 토큰")},visualTransformation=PasswordVisualTransformation(),enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
      Row{Checkbox(consent,{consent=it},enabled=!busy,modifier=Modifier.testTag("cause-consent"));Text("미리보기의 근거를 이 서버와 외부 AI로 전송하는 데 동의합니다.",modifier=Modifier.padding(top=8.dp))}
      Button(onClick={val snapshot=requireNotNull(input);busy=true;message="공식 기준과 근거 대조 중…";scope.launch{try{
       // Recheck freshness after the user has spent time reading the consent preview.
       if(account!=null)require(account.status!=null&&System.currentTimeMillis()-account.status.checkedAt in 0..300000){"계정 조회 결과가 오래되었습니다. 갱신 후 다시 분석하세요"}
       val result=withContext(Dispatchers.IO){requestCauseReview(endpoint,token,snapshot,kb,accountId)};onSave(latestCase.withCauseReview(result));tab=1;message="분석 결과와 근거를 사건에 저장했습니다";consent=false
      }catch(e:CancellationException){throw e}catch(e:Exception){message=if(e is IllegalStateException||e is IllegalArgumentException)e.message.orEmpty() else "분석 또는 저장 실패 · 기존 기록은 유지됩니다"}finally{busy=false}}},enabled=!busy&&input!=null&&consent&&token.length>=32&&(account==null||sameAccount)&&case.causeReviews.size<20&&runCatching{kycEndpoint(endpoint)}.isSuccess,modifier=Modifier.fillMaxWidth().testTag("run-cause-analysis")){Text(if(busy)"조회 중…" else "원인 후보 분석·저장")}
     }
     1->{
      CauseCaseTimeline(case)
      if(case.causeReviews.isEmpty())Text("아직 원인 분석이 없습니다. 분석 탭에서 근거를 확인하세요.")
      case.causeReviews.asReversed().forEach{CauseReviewView(it,kb);HorizontalDivider()}
     }
     2->{
      Text(causeComparison(case),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("cause-comparison"))
      Text("사후 기록이 생기기 전의 최초 예측과 비교합니다. 아래 분류는 사용자가 직접 확인한 기록이며, 거래소 인증이나 AI 학습 정답으로 취급하지 않습니다.")
      CauseChoice("확인한 원인 분류",category,linkedMapOf("unknown" to "아직 사유 미확인").apply{putAll(causeLabels);put("other","다른 원인")},"cause-finding-category"){category=it;reviewed=false}
      CauseChoice("확인 근거",findingSource,causeFindingSources,"cause-finding-source"){findingSource=it;reviewed=false}
      OutlinedTextField(reference,{reference=it.take(1500);reviewed=false},label={Text("확인 시각·지원 티켓·답변 요지")},supportingText={Text("10자 이상 · 신분증 번호·비밀번호 제외. 이 메모는 폰에만 보관합니다.")},modifier=Modifier.fillMaxWidth().testTag("cause-finding-reference"))
      Row{Checkbox(reviewed,{focusManager.clearFocus();reviewed=it},modifier=Modifier.testTag("cause-finding-reviewed"));Text("AI 후보를 복사한 것이 아니라 위 자료를 직접 대조해 기록했습니다.",modifier=Modifier.padding(top=8.dp))}
      Text("수정이 필요하면 새 기록을 추가하세요. 이전 기록도 남습니다. 단순히 해결됐다는 결과만으로 원인을 확정하지 않습니다.",style=MaterialTheme.typography.bodySmall)
      case.causeFindings.asReversed().forEach{f->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text("${preventionTime(f.recordedAt)} · ${causeLabels[f.category]?:if(f.category=="other")"다른 원인" else "미확인"}");Text(causeFindingSources[f.source].orEmpty());Text(f.reference);Text("사용자 대조 · 원문 진위 별도 검증 안 됨",style=MaterialTheme.typography.bodySmall)}}}
     }
    }
    if(message.isNotEmpty())Text(message,modifier=Modifier.testTag("cause-message"))
   }
   // Keep the save action outside the scrollable form and above the IME.
   // Long notes, evidence history and focus-driven scrolling cannot hide it.
   if(tab==2){Surface(tonalElevation=3.dp,modifier=Modifier.fillMaxWidth().testTag("cause-save-bar")){
    Button(onClick={focusManager.clearFocus();try{onSave(latestCase.withCauseFinding(CauseFinding(category=category,source=findingSource,reference=reference.trim())));reference="";reviewed=false;message="사후 대조 기록을 추가했습니다"}catch(_:Exception){message="사후 기록 저장 실패 · 기존 기록은 유지됩니다"}},enabled=reviewed&&reference.trim().length>=10&&case.causeFindings.size<50&&!busy,modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp).testTag("save-cause-finding")){Text("확인 근거 추가 저장")}
   }}
  }
 }}
}
@Composable private fun CauseEvidenceView(e:CauseEvidence,kb:CauseKnowledge){
 val origin=when(e.origin){"exchange_api"->"기기에서 조회한 계정 API · 서버 재검증 없음";"device_observation"->"기기 직접 관측";else->"사용자 기록 · 원문 미검증"}
 Text("${kb.label(e.code)}\n$origin · ${preventionTime(e.observedAt)}",style=MaterialTheme.typography.bodySmall)
}
@Composable fun CauseReviewView(review:CauseReview,kb:CauseKnowledge){
 EvidenceFirstCauseReview(review,kb)
}
