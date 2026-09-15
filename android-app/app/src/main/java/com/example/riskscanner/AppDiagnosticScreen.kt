package com.example.riskscanner

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun AppDiagnosticSummary(report:AppDiagnosticReport){
 Text(diagnosticText(report.status),style=MaterialTheme.typography.titleMedium,color=Color(0xFFE5C77F))
 Text("${report.app.label} · ${report.app.version}\n${report.app.packageName}",style=MaterialTheme.typography.bodySmall)
 Text("기기에서 확인한 사실",style=MaterialTheme.typography.titleSmall)
 Text(report.facts.joinToString("\n"){"• "+diagnosticText(it)}.ifBlank{"분류된 이상 항목 없음 · 모든 문제가 없다는 뜻은 아닙니다"})
 Text("화면 안내: "+diagnosticText(report.screenNotice))
 Text("원인 후보",style=MaterialTheme.typography.titleSmall)
 Text(report.candidates.joinToString("\n"){"• "+diagnosticText(it)}.ifBlank{"판단 근거 부족"})
 Text("다음 확인",style=MaterialTheme.typography.titleSmall)
 Text(report.checks.joinToString("\n"){"• "+diagnosticText(it)})
 Text("실제 원인·공식 계정 상태 미확정. 선택한 앱과 이미지 출처의 동일성은 사용자 확인이 필요합니다.\n${report.reviewedAt} · ${report.model}",style=MaterialTheme.typography.bodySmall)
}

@Composable fun AppDiagnosticDialog(record:Record,persisted:Boolean,onSave:(AppDiagnosticReport)->Unit,close:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val owner=remember{UUID.randomUUID().toString()}
 val prefs=remember{context.getSharedPreferences("ers_ai_review",0)}
 var endpoint by remember{mutableStateOf(reviewServerAddress(prefs.getString("endpoint",null)))};var token by remember{mutableStateOf("")}
 var appsLoading by remember{mutableStateOf(true)};var apps by remember{mutableStateOf<List<DiagnosticApp>>(emptyList())};var query by remember{mutableStateOf("")};var selected by remember{mutableStateOf<DiagnosticApp?>(null)}
 var choose by remember{mutableStateOf(true)};var image by remember{mutableStateOf<ByteArray?>(null)};val masks=remember{mutableStateListOf<MaskRect>()}
 var consent by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};var connection by remember{mutableStateOf("")};var report by remember{mutableStateOf<AppDiagnosticReport?>(null)}
 var device by remember{mutableStateOf(diagnosticDevice(context))};val capture by DiagnosticCaptureBus.state.collectAsState();val capturing=capture.owner==owner&&capture.active
 val currentImage by rememberUpdatedState(image)
 fun accept(bytes:ByteArray){image?.fill(0);image=bytes;masks.clear();consent=false;report=null;device=diagnosticDevice(context)}
 LaunchedEffect(Unit){try{apps=withContext(Dispatchers.IO){diagnosticApps(context)}}finally{appsLoading=false}}
 LaunchedEffect(capture){if(capture.owner==owner){message=capture.message;if(capture.image!=null){accept(capture.image!!.copyOf());DiagnosticCaptureBus.clear(owner)}}}
 DisposableEffect(owner){onDispose{context.stopService(Intent(context,DiagnosticCaptureService::class.java));DiagnosticCaptureBus.clear(owner);currentImage?.fill(0)}}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null){busy=true;scope.launch{try{val bytes=withContext(Dispatchers.IO){prepareKycImage(context,uri)};accept(bytes);message="전송할 화면을 확인하고 개인정보를 가려 주세요"}catch(e:Exception){message=e.message?:"이미지 준비 실패"}finally{busy=false}}}}
 fun openApp(){val app=selected?:return;try{val intent=context.packageManager.getLaunchIntentForPackage(app.packageName)?:error("앱 실행 화면을 찾지 못했습니다");context.startActivity(intent)}catch(e:Exception){message="앱을 열지 못했습니다. 설치·활성 상태를 확인하세요"}}
 val projection=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
  if(result.resultCode==Activity.RESULT_OK&&result.data!=null){
   image?.fill(0);image=null;masks.clear();consent=false;report=null
   DiagnosticCaptureBus.state.value=DiagnosticCapture(owner,true,message="화면 공유를 준비하고 있습니다")
   try{ContextCompat.startForegroundService(context,Intent(context,DiagnosticCaptureService::class.java).setAction("start").putExtra("owner",owner).putExtra("result",result.resultCode).putExtra("consent",result.data));openApp()}
   catch(e:Exception){DiagnosticCaptureBus.clear(owner);message="화면 공유를 시작하지 못했습니다. 이미지 선택을 이용하세요"}
  }else{message="화면 공유를 허용하지 않았습니다. 캡처한 이미지 선택도 가능합니다"}
 }
 fun requestScreen(){projection.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())}
 val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)requestScreen()else{message="캡처 버튼을 알림으로 제공하려면 알림 허용이 필요합니다. 이미지 선택도 가능합니다"}}
 fun startCapture(){if(!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()){if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else {message="ERS 알림을 허용하거나 이미지 선택을 이용하세요"};return};requestScreen()}
 val configured=runCatching{kycEndpoint(endpoint)}.isSuccess&&token.trim().length>=32
 Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(modifier=Modifier.fillMaxSize(),color=Color(0xFF101115),contentColor=Color.White){
   Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("거래소 앱 AI 진단",style=MaterialTheme.typography.titleLarge);TextButton(onClick=close,modifier=Modifier.testTag("close-app-diagnostic")){Text("닫기")}}
    Text("${record.name} 계정 · 오류 화면과 기기 상태 검토",color=Color(0xFFE5C77F))
    Text("앱의 비공개 데이터·서버 로그에는 접근하지 않습니다. 인증 입력 화면, OTP, 비밀번호, 시드·개인키는 제외하세요. 계정 제한의 실제 사유는 거래소 확인이 필요합니다.",style=MaterialTheme.typography.bodySmall)
    Text("1. 휴대폰에 설치된 거래소 앱 선택",style=MaterialTheme.typography.titleMedium)
    if(choose){
     OutlinedTextField(query,{query=it},label={Text("앱 이름 또는 패키지 검색")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("diagnostic-app-search"))
     Column(Modifier.fillMaxWidth().heightIn(max=220.dp).verticalScroll(rememberScrollState())){
      val filtered=apps.filter{it.label.contains(query,true)||it.packageName.contains(query,true)}
      if(appsLoading)Text("설치된 앱을 불러오고 있습니다…") else if(filtered.isEmpty())Text("검색 결과가 없습니다. 앱 이름·패키지명 또는 설치 상태를 확인하세요.")
      filtered.forEach{app->TextButton(onClick={selected=app;choose=false;image?.fill(0);image=null;masks.clear();consent=false;report=null;message=""},enabled=!busy&&!capturing,modifier=Modifier.fillMaxWidth()){Text("${app.label} · ${app.version}\n${app.packageName}",modifier=Modifier.fillMaxWidth())}}
     }
    }
    selected?.let{app->
     Text("${app.label} · ${app.version}\n${app.packageName}")
     Text("이름만으로 공식 앱 여부가 검증되지는 않습니다. 위 앱이 이 계정의 거래소 앱인지 확인하세요.",style=MaterialTheme.typography.bodySmall)
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick=::openApp,enabled=!busy){Text("앱 열기")};TextButton(onClick={choose=!choose},enabled=!busy&&!capturing){Text("앱 변경")};TextButton(onClick={runCatching{context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+app.packageName)))}.onFailure{message="앱 설정을 열지 못했습니다"}},enabled=!busy){Text("앱 설정")}}
    }
    Text("기기에서 직접 확인한 상태\n네트워크 ${device.optString("networkType")} · 인터넷 검증 ${if(device.isNull("networkValidated"))"정보 없음" else if(device.optBoolean("networkValidated"))"확인됨" else "미확인"}\nVPN ${if(device.optBoolean("vpn"))"감지" else "미감지"} · 프록시 ${if(device.optBoolean("proxy"))"설정됨" else "미감지"} · 자동 시간 ${if(device.optBoolean("autoTime"))"켜짐" else "꺼짐"}",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={device=diagnosticDevice(context)},enabled=!busy&&!capturing){Text("기기 상태 다시 확인 · AI 연결 없이 가능")}
    Text("2. 오류 화면 한 장 준비",style=MaterialTheme.typography.titleMedium)
    Text("화면 공유 허용 → 거래소 오류 화면 열기 → 알림에서 ‘현재 화면 1회 캡처’ → ERS로 돌아오기. Android 14 이상에서는 거래소 앱 하나만 선택할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
     OutlinedButton(onClick=::startCapture,enabled=selected!=null&&!busy&&!capturing,modifier=Modifier.testTag("diagnostic-capture")){Text("화면 1회 캡처")}
     OutlinedButton(onClick={consent=false;picker.launch("image/*")},enabled=selected!=null&&!busy&&!capturing,modifier=Modifier.testTag("diagnostic-image-picker")){Text("이미지 선택")}
    }
    if(capturing)OutlinedButton(onClick={context.startService(Intent(context,DiagnosticCaptureService::class.java).setAction("stop").putExtra("owner",owner))}){Text("화면 공유 중지")}
    image?.let{bytes->
     Text("3. 개인정보 가리기",style=MaterialTheme.typography.titleMedium)
     Text("이미지에서 드래그하면 검은 사각형이 생깁니다. UID·이름·주소·잔액 등 불필요한 정보를 가린 뒤 적용하세요. 오류 문구는 읽을 수 있게 남겨 주세요.",style=MaterialTheme.typography.bodySmall)
     DiagnosticRedactor(bytes,masks,enabled=!busy&&!capturing,onMask={masks.add(it);consent=false;report=null})
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
      OutlinedButton(onClick={busy=true;scope.launch{try{val updated=withContext(Dispatchers.IO){redactDiagnosticImage(bytes,masks.toList())};accept(updated);message="가리기를 이미지에 적용했습니다"}catch(e:Exception){message="가리기 적용 실패"}finally{busy=false}}},enabled=masks.isNotEmpty()&&!busy,modifier=Modifier.testTag("diagnostic-apply-masks")){Text("가리기 적용 (${masks.size})")}
      TextButton(onClick={masks.clear();consent=false},enabled=masks.isNotEmpty()&&!busy){Text("선택 취소")}
     }
    }
    Text("4. 외부 AI 연결",style=MaterialTheme.typography.titleMedium)
    OutlinedTextField(endpoint,{endpoint=it;connection="";consent=false},label={Text("HTTPS 검토 서버 주소")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth().testTag("diagnostic-endpoint"))
    OutlinedTextField(token,{token=it;connection=""},label={Text("서버 접속 토큰 · 현재 세션만")},singleLine=true,enabled=!busy,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().testTag("diagnostic-token"))
    Text("OpenAI API 키는 서버에만 설정합니다. 아래 동의 후 가린 이미지, 선택 앱의 이름·패키지·버전·활성 상태와 요청 시점의 Android·네트워크·VPN·프록시·자동 시간 상태를 이 서버로 전송합니다. 서버는 이미지 한 장을 OpenAI로 전달합니다. 설치 앱 전체 목록과 계정 UID는 전송하지 않습니다.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={busy=true;scope.launch{try{connection=withContext(Dispatchers.IO){diagnosticConnection(endpoint,token)};prefs.edit().putString("endpoint",endpoint.trim()).apply()}catch(e:Exception){connection=e.message?:"연결 실패"}finally{busy=false}}},enabled=configured&&!busy&&!capturing,modifier=Modifier.testTag("diagnostic-check-connection")){Text("AI 서버 연결 확인")}
    Text("가상 오류 이미지 한 장으로 실제 AI 분석까지 테스트할 수 있습니다. API 사용량이 발생하며 테스트 결과는 계정 기록에 저장하지 않습니다.",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={busy=true;connection="테스트 준비 중…";scope.launch{try{val result=withContext(Dispatchers.IO){aiConnectionSelfTest(endpoint,token,onProgress={stage->scope.launch{connection=stage}})};connection=result;prefs.edit().putString("endpoint",endpoint.trim()).apply()}catch(e:CancellationException){throw e}catch(e:Exception){connection=e.message?:"AI 테스트 실패"}finally{busy=false}}},enabled=configured&&!busy&&!capturing,modifier=Modifier.testTag("diagnostic-test-ai")){Text("연결 + 가상 이미지 분석 테스트")}
    if(connection.isNotBlank())Text(connection,color=Color(0xFFE5C77F))
    Row{Checkbox(consent,{consent=it},enabled=!busy&&!capturing&&image!=null&&masks.isEmpty(),modifier=Modifier.testTag("diagnostic-consent"));Text("선택 앱의 오류 화면인지 확인했고, 비밀번호·OTP·시드 등 민감정보가 없습니다. 위 범위의 외부 AI 전송에 동의합니다.",modifier=Modifier.padding(top=8.dp))}
    Button(onClick={val bytes=image?.copyOf()?:return@Button;val app=selected?:return@Button;device=diagnosticDevice(context);val requestDevice=device;busy=true;report=null;message="오류 안내를 검토하고 있습니다";scope.launch{try{report=withContext(Dispatchers.IO){requestDiagnostic(endpoint,token,bytes,app,requestDevice)};prefs.edit().putString("endpoint",endpoint.trim()).apply();message="검토 완료 · 실제 원인을 확정한 결과는 아닙니다"}catch(e:Exception){message=e.message?:"AI 검토 실패"}finally{bytes.fill(0);busy=false}}},enabled=configured&&selected!=null&&image!=null&&consent&&masks.isEmpty()&&!busy&&!capturing,modifier=Modifier.fillMaxWidth().testTag("run-app-diagnostic")){Text(if(busy)"처리 중…" else "오류 화면 AI 검토")}
    if(message.isNotBlank())Text(message,color=Color(0xFFE5C77F),modifier=Modifier.testTag("diagnostic-message"))
    report?.let{r->HorizontalDivider();AppDiagnosticSummary(r);Button(onClick={onSave(r)},modifier=Modifier.fillMaxWidth()){Text(if(persisted)"암호화된 계정 기록에 결과 저장" else "현재 세션에 결과 보관")}}
    Text("이미지는 기기에 저장하지 않으며 대화창을 닫으면 메모리에서 정리합니다. 결과 저장 시에는 판독 코드와 선택 앱 정보만 보관합니다. 서버는 원본을 저장하지 않지만 외부 AI 제공자의 데이터 처리 정책이 적용됩니다.",style=MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(24.dp))
   }
  }
 }
}

@Composable private fun DiagnosticRedactor(bytes:ByteArray,masks:List<MaskRect>,enabled:Boolean,onMask:(MaskRect)->Unit){
 val bitmap=remember(bytes){BitmapFactory.decodeByteArray(bytes,0,bytes.size)}?:return
 DisposableEffect(bitmap){onDispose{bitmap.recycle()}}
 var start by remember(bytes){mutableStateOf<Offset?>(null)};var end by remember(bytes){mutableStateOf<Offset?>(null)}
 Canvas(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height).background(Color.DarkGray).testTag("diagnostic-image-preview").pointerInput(bytes,enabled){if(enabled)detectDragGestures(onDragStart={start=it;end=it},onDragCancel={start=null;end=null},onDragEnd={val a=start;val b=end;if(a!=null&&b!=null&&kotlin.math.abs(a.x-b.x)>3&&kotlin.math.abs(a.y-b.y)>3)onMask(MaskRect((minOf(a.x,b.x)/size.width).coerceIn(0f,1f),(minOf(a.y,b.y)/size.height).coerceIn(0f,1f),(maxOf(a.x,b.x)/size.width).coerceIn(0f,1f),(maxOf(a.y,b.y)/size.height).coerceIn(0f,1f)));start=null;end=null}){change,_->change.consume();end=change.position}}){
  drawImage(bitmap.asImageBitmap(),dstSize=IntSize(size.width.toInt(),size.height.toInt()))
  masks.forEach{r->drawRect(Color.Black,Offset(r.left*size.width,r.top*size.height),Size((r.right-r.left)*size.width,(r.bottom-r.top)*size.height))}
  val a=start;val b=end;if(a!=null&&b!=null)drawRect(Color.Black,Offset(minOf(a.x,b.x),minOf(a.y,b.y)),Size(kotlin.math.abs(a.x-b.x),kotlin.math.abs(a.y-b.y)))
 }
}
