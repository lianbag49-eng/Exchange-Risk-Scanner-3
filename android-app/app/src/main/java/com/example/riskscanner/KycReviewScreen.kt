package com.example.riskscanner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun KycReviewSummary(review:KycReview){
 Text(kycText(review.assessment),style=MaterialTheme.typography.titleMedium)
 Text("AI 화면 판독 · 진위 및 공식 승인 미검증",color=MaterialTheme.colorScheme.primary)
 Text("표시된 상태: "+kycText(review.displayedStatus))
 review.matches.forEach{(key,value)->Text((mapOf("exchange" to "거래소","uid" to "UID","country" to "KYC 국가")[key]?:key)+": "+kycText(value))}
 review.issues.forEach{Text("• "+kycText(it))}
 Text(kycText(review.humanDecision)+" · "+review.reviewedAt.take(19).replace('T',' ')+" UTC",style=MaterialTheme.typography.bodySmall)
 Text("이미지 지문 "+review.imageSha256.take(12)+" · "+review.model,style=MaterialTheme.typography.bodySmall)
}
@Composable fun KycReviewDialog(record:Record,persisted:Boolean,onSave:(KycReview)->Unit,close:()->Unit){
 val context=LocalContext.current;val prefs=remember{context.getSharedPreferences("ers_ai_review",Context.MODE_PRIVATE)};val scope=rememberCoroutineScope()
 var endpoint by remember{mutableStateOf(reviewServerAddress(prefs.getString("endpoint",null)))};var token by remember{mutableStateOf("")}
 var connection by remember{mutableStateOf("")}
 var image by remember{mutableStateOf<ByteArray?>(null)};var busy by remember{mutableStateOf(false)};var consent by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var review by remember{mutableStateOf<KycReview?>(null)}
 val preview=remember(image){image?.let{BitmapFactory.decodeByteArray(it,0,it.size)}}
 DisposableEffect(preview){onDispose{preview?.recycle()}}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null){busy=true;review=null;consent=false;error="";scope.launch{try{image=withContext(Dispatchers.IO){prepareKycImage(context,uri)}}catch(e:CancellationException){throw e}catch(_:Exception){image=null;error="이미지를 읽지 못했습니다. 12 MB 이하 이미지를 선택하세요."}finally{busy=false}}}}
 Dialog(onDismissRequest={if(!busy)close()},properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxSize()){
   Column(Modifier.fillMaxSize().padding(18.dp)){
    Row{Text("AI KYC 검토",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=close){Text(if(busy)"닫기 · 결과 무시" else "닫기")}}
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
     Text(record.name+" · UID "+record.uid.take(2)+"••••"+record.uid.takeLast(2))
     Text("KYC 상태 화면을 선택하세요. AI는 표시된 문구와 UID·국가를 대조합니다. 신분증 진위·얼굴 인증·거래소 실제 승인 여부는 확인하지 않습니다.")
     OutlinedTextField(endpoint,{endpoint=it;consent=false;review=null;connection=""},label={Text("HTTPS 검토 서버 주소")},supportingText={Text("서버가 연결되어야 AI 판독이 가능합니다")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("kyc-endpoint"))
     OutlinedTextField(token,{token=it;consent=false;review=null;connection=""},label={Text("검토 서버 접속 토큰")},supportingText={Text("접속 토큰은 저장하지 않습니다 · OpenAI API 키 입력 금지")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
     Text("가상 오류 이미지로 API 인증과 실제 AI 분석을 테스트합니다. API 사용량이 발생하며 계정 기록에는 저장하지 않습니다. KYC 상태 판독은 증빙 선택 후 별도로 요청하세요.",style=MaterialTheme.typography.bodySmall)
     OutlinedButton(onClick={busy=true;connection="테스트 준비 중…";scope.launch{try{connection=withContext(Dispatchers.IO){aiConnectionSelfTest(endpoint,token,onProgress={stage->scope.launch{connection=stage}})};prefs.edit().putString("endpoint",endpoint.trim()).apply()}catch(e:CancellationException){throw e}catch(e:Exception){connection=e.message?:"AI 테스트 실패"}finally{busy=false}}},enabled=!busy&&token.trim().length>=32&&runCatching{kycEndpoint(endpoint)}.isSuccess,modifier=Modifier.testTag("kyc-test-ai")){Text("연결 + 가상 이미지 분석 테스트")}
     if(connection.isNotBlank())Text(connection)
     OutlinedButton(onClick={picker.launch("image/*")},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(image==null)"증빙 이미지 선택" else "이미지 다시 선택")}
     preview?.let{Image(it.asImageBitmap(),"선택한 증빙",modifier=Modifier.fillMaxWidth().heightIn(max=280.dp))}
     Text("전송할 항목: 선택한 이미지, 거래소 이름, UID, KYC 국가. 지정한 검토 서버가 이미지 내용을 OpenAI에 전달합니다. 계정 입력값 대조는 검토 서버에서 처리합니다. 불필요한 개인정보는 선택 전에 가려 주세요.",style=MaterialTheme.typography.bodySmall)
     Row{Checkbox(consent,{consent=it},enabled=!busy);Text("이미지와 위 정보를 검토 서버에 전송하는 데 동의합니다",modifier=Modifier.weight(1f))}
     Text("ERS와 검토 서버는 원본 이미지를 저장하지 않습니다. AI 제공자 보관 정책은 서버 운영자가 확인해야 합니다.",style=MaterialTheme.typography.bodySmall)
     if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
     Button(onClick={val selected=image?:return@Button;error="";busy=true;review=null;scope.launch{try{val result=withContext(Dispatchers.IO){requestKycReview(endpoint,token,selected,record)};review=result;prefs.edit().putString("endpoint",endpoint.trim()).apply()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?.take(180)?:"AI 연결 실패"}finally{busy=false}}},enabled=!busy&&consent&&image!=null&&token.trim().length>=32&&runCatching{kycEndpoint(endpoint)}.isSuccess,modifier=Modifier.fillMaxWidth().testTag("kyc-run")){Text(if(busy)"처리 중…" else "AI 검토 요청")}
     review?.let{r->HorizontalDivider();KycReviewSummary(r);Text("담당자 검토 분류");Row{TextButton(onClick={review=r.copy(humanDecision="follow_up")}){Text("추가 증빙 요청")};TextButton(onClick={review=r.copy(humanDecision="official_check_requested")}){Text("공식 확인 요청")}};Button(onClick={onSave(r)},modifier=Modifier.fillMaxWidth()){Text(if(persisted)"검토 결과 저장" else "현재 세션에 보관")}}
     if(record.kycReviews.isNotEmpty()){HorizontalDivider();Text("이 계정의 검토 이력 · 최근 10건");record.kycReviews.forEach{r->KycReviewSummary(r);HorizontalDivider()}}
     if(!persisted)Text("계정 저장이 꺼져 있어 검토 결과는 현재 세션에만 보관됩니다.")
     Text("저장 시 판독 결과·시간·이미지 지문만 기존 암호화 기록에 보관합니다. 계정 기록을 삭제하면 검토 이력도 삭제됩니다.",style=MaterialTheme.typography.bodySmall)
    }
   }
  }
 }
}
