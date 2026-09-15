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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun RestrictionsView(value:ApiRestrictions){
 Text("거래소가 보고한 제한·권한",style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("official-restrictions"))
 Text("계정 상태 응답: "+value.accountStatus.ifEmpty{"미확인"})
 fun flag(v:Boolean?)=when(v){true->"허용 보고";false->"비허용 보고";null->"미확인"}
 Text("거래 ${flag(value.canTrade)} · 입금 ${flag(value.canDeposit)} · 출금 ${flag(value.canWithdraw)}",style=MaterialTheme.typography.bodySmall)
 Text("API 거래 잠금: "+when(value.apiLocked){true->"잠김 보고";false->"잠김 아님 보고";null->"미확인"},modifier=Modifier.testTag("official-api-lock"))
 value.plannedRecoverTime?.takeIf{it>0}?.let{Text("거래소 보고 복구 예정: "+preventionTime(java.time.Instant.ofEpochMilli(it).toString()))}
 value.exchangeUpdatedAt?.takeIf{it>0}?.let{Text("거래소 갱신 시각: "+preventionTime(java.time.Instant.ofEpochMilli(it).toString()))}
 if(value.thresholds.isNotEmpty())Text("API 제공 조건 값: "+value.thresholds.entries.joinToString{"${it.key}=${it.value}"}+"\n조건 값만으로 실제 발동 원인을 특정하지 않습니다.",style=MaterialTheme.typography.bodySmall)
 value.unavailable.forEach{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
 Text("출처: Binance account/status · apiTradingStatus · api/v3/account. 보고된 상태와 권한이며 비공개 심사 사유·KYC 진위는 미확인입니다.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("official-cause-boundary"))
}

@Composable fun OfficialVerificationDialog(onAccounts:()->Unit,close:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope()
 var endpoint by remember{mutableStateOf(DEFAULT_AI_SERVER)};var token by remember{mutableStateOf("")};var consent by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)}
 var message by remember{mutableStateOf("")};var result by remember{mutableStateOf<IdentityResult?>(null)}
 LaunchedEffect(Unit){try{val config=withContext(Dispatchers.IO){AutoPreflightConfigStore(context).load()};endpoint=config.endpoint;token=config.token}catch(_:Exception){message="저장된 서버 설정을 읽지 못했습니다. 이 화면에서 접속 정보를 입력하세요"}}
 Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)){Surface(Modifier.fillMaxSize()){
  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(18.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Row{Text("공식 사유 · 신원 검증",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=close,modifier=Modifier.testTag("close-official-verification")){Text("닫기")}}
   Text("거래소 계정 상태",style=MaterialTheme.typography.titleMedium)
   Text("Binance: UID·계정 상태·거래/입출금 허용 여부·API 거래 잠금. Bybit·Toobit: 지원 API의 KYC 승인 상태. 공식 키를 연결한 계정만 조회합니다.")
   OutlinedButton(onClick=onAccounts,modifier=Modifier.fillMaxWidth().testTag("verification-connect-account")){Text("거래소 계정 연결·공식 응답 보기")}
   HorizontalDivider();Text("신원확인 업체의 실제 결과",style=MaterialTheme.typography.titleMedium)
   Text("Sumsub에서 이미 진행한 검증의 승인·거절·재제출 요청과 제공된 사유를 조회합니다. 서버에 연결된 업체 계정과 조회 권한이 필요합니다. 앱의 이미지 AI와 별개입니다.")
   Text("검증업체 승인만으로 거래소 로그인 계정·기존 KYC와의 일치가 입증되지는 않습니다. 위변조 사유가 제공되면 업체 보고로 표시하고, 문서 진위 항목이 없으면 미확인으로 남깁니다.",modifier=Modifier.testTag("identity-scope"))
   OutlinedTextField(endpoint,{endpoint=it;consent=false;result=null},label={Text("ERS 서버 주소")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
   OutlinedTextField(token,{token=it.trim();consent=false;result=null},label={Text("서버 접속 토큰")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("identity-token"))
   Text("이 화면의 입력 토큰은 닫으면 저장하지 않습니다. 서버에서 허용한 대상만 조회하며 신분증 사진·주민번호·얼굴 데이터는 이 화면에서 받지 않습니다.",style=MaterialTheme.typography.bodySmall)
   Row{Checkbox(consent,{consent=it},enabled=!busy,modifier=Modifier.testTag("identity-consent"));Text("조회 권한이 있으며 신원확인 업체의 검증 결과를 이 기기에서 확인하는 데 동의합니다.",modifier=Modifier.padding(top=8.dp))}
   Button(onClick={busy=true;result=null;message="검증업체 결과 조회 중";scope.launch{try{result=withContext(Dispatchers.IO){requestIdentityResult(endpoint,token)};message=""}catch(e:CancellationException){throw e}catch(e:Exception){message=if(e is IllegalStateException)e.message.orEmpty() else "검증 결과를 확인하지 못했습니다. 현재 결과는 미확인입니다"}finally{busy=false}}},enabled=!busy&&consent&&token.length>=32&&runCatching{kycEndpoint(endpoint)}.isSuccess,modifier=Modifier.fillMaxWidth().testTag("query-identity")){Text(if(busy)"조회 중…" else "권한 있는 검증 결과 조회")}
   if(message.isNotEmpty())Text(message,modifier=Modifier.testTag("identity-message"))
   result?.let{IdentityResultView(it)}
   TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://docs.sumsub.com/reference/get-applicant-review-status")))}){Text("검증 결과 제공 범위 확인")}
  }
 }}
}
@Composable fun IdentityResultView(result:IdentityResult){
 Text(when(result.status){"not_configured"->"검증업체 미연결 · 서버에 Sumsub 계정과 조회 권한을 설정해야 합니다";"no_authorized_subjects"->"이 접속 토큰에 허용된 검증 대상이 없습니다";else->"업체 조회 결과 ${result.subjects.size}건"},modifier=Modifier.testTag("identity-result-state"))
 Text("조회 시각: ${preventionTime(result.checkedAt)} · 화면 조회 당시의 결과",style=MaterialTheme.typography.bodySmall)
 result.subjects.forEach{s->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
  Text(s.label,style=MaterialTheme.typography.titleMedium);Text(identityDecisionLabel(s.decision),modifier=Modifier.testTag("identity-decision-${s.id}"))
  if(s.verificationLevel.isNotEmpty())Text("검증 단계: ${s.verificationLevel}")
  if(s.reviewId.isNotEmpty())Text("업체 검토 ID: ${s.reviewId}")
  if(s.reviewDate.isNotEmpty())Text("업체 검토 시각: ${s.reviewDate}")
  Text("조회 시각: ${preventionTime(s.checkedAt)}")
  s.reasons.forEach{Text(identityReasonLabel(it))}
  if(s.applicantComment.isNotEmpty())Text("업체 안내: ${s.applicantComment}")
  Text(when(s.documentAuthenticity){"provider_reported_forgery"->"문서: 업체가 위변조 거절 사유를 보고함";"provider_reported_edit"->"문서: 업체가 이미지 편집 거절 사유를 보고함";else->"문서 진위 단독 결과: 미제공·미확인"})
  Text("거래소 계정과의 동일성·기존 KYC 연결: 미확인",style=MaterialTheme.typography.bodySmall)
 }}}
}
