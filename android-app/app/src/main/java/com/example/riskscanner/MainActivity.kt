package com.example.riskscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import android.graphics.BitmapFactory
import android.util.Base64
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg=Color(0xFF070A09)
private val Card=Color(0xFF101815)
private val Card2=Color(0xFF16211C)
private val Line=Color(0xFF304238)
private val Gold=Color(0xFFD9B56D)
private val Gold2=Color(0xFFF2D89E)
private val Cyan=Color(0xFF55D8FF)
private val Txt=Color(0xFFF4F7F9)
private val Muted=Color(0xFF91A5B6)
private val Green=Color(0xFF57D69A)
private val Amber=Color(0xFFF4B64D)
private val Red=Color(0xFFFF6D75)

data class Exchange(val name:String,val mark:String,val color:Color,val logo:String?=null,val rank:Int=0,val infoUrl:String="")
data class Record(val exchange:Exchange,val name:String,val uid:String,val country:String,val result:RiskResult,val snapshot:DeviceSnapshot,val time:String,val worker:String="",val kycReviews:List<KycReview> = emptyList(),val diagnostics:List<AppDiagnosticReport> = emptyList())

class MainActivity:ComponentActivity(){
 private val exchanges by lazy{ExchangeCatalog.load(this)}
 private val recordStorage by lazy{RecordStorage(this)}
 private var storageError by mutableStateOf("")
 private var storageReadable=true
 private var adviceEnabled by mutableStateOf(true)
 private fun persist(items:List<Record>){if(!storageReadable)return;try{recordStorage.save(items);storageError=""}catch(e:Exception){storageError="암호화 저장 실패 · 현재 세션에만 보관됩니다"}}
 override fun onCreate(b:Bundle?){super.onCreate(b)
  window.statusBarColor=android.graphics.Color.rgb(5,10,16)
  window.navigationBarColor=android.graphics.Color.rgb(5,10,16)
  setContent{
   MaterialTheme(colorScheme=darkColorScheme(
    primary=Gold,onPrimary=Bg,secondary=Cyan,onSecondary=Bg,
    background=Bg,onBackground=Txt,surface=Card,onSurface=Txt,
    surfaceVariant=Card2,onSurfaceVariant=Muted,outline=Line,error=Red,onError=Bg
   )){Surface(modifier=Modifier.fillMaxSize(),color=Bg,contentColor=Txt){App()}}
  }
 }

 @Composable private fun App(){
  val prefs=remember{getSharedPreferences("ers_preferences",MODE_PRIVATE)}
  var tab by remember{mutableIntStateOf(0)}
  var ex by remember{mutableStateOf(exchanges[0])}
  var custom by remember{mutableStateOf("")}
  var uid by remember{mutableStateOf("")}
  var country by remember{mutableStateOf("KR")}
  var latest by remember{mutableStateOf<Record?>(null)}
  val records=remember{mutableStateListOf<Record>().apply{try{addAll(recordStorage.load(exchanges))}catch(e:Exception){storageReadable=false;storageError="기록 복구 실패 · 기존 기록 보존을 위해 자동저장을 잠급니다"}}}
  var detail by remember{mutableStateOf<Record?>(null)}
  var save by remember{mutableStateOf(prefs.getBoolean("save",true))}
  var strict by remember{mutableStateOf(prefs.getBoolean("strict",false))}
  var tips by remember{mutableStateOf(prefs.getBoolean("tips",true))}
  var privacy by remember{mutableStateOf(prefs.getBoolean("privacy",false))}
  LaunchedEffect(privacy){if(privacy)window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}
  SideEffect{adviceEnabled=tips}
  Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF101610),Bg,Color(0xFF03070B))))){
   Header{tab=3}
   if(storageError.isNotEmpty())Note(storageError,Red)
   Box(Modifier.weight(1f)){
    when(tab){
     0->Home(latest?:records.firstOrNull(),records,{tab=1},{detail=it})
     1->Scan(ex,custom,uid,country,strict,{ex=it},{custom=it},{uid=it},{country=it}){r->latest=r;if(save){records.add(0,r);if(records.size>200)records.removeAt(records.lastIndex);persist(records)};detail=r}
     2->History(records,{detail=it},{records.clear();persist(records)})
     else->Settings(save,strict,tips,privacy,{save=it;prefs.edit().putBoolean("save",it).apply()},{strict=it;prefs.edit().putBoolean("strict",it).apply()},{tips=it;prefs.edit().putBoolean("tips",it).apply()},{privacy=it;prefs.edit().putBoolean("privacy",it).apply()})
    }
   }
   Nav(tab){tab=it}
  }
  detail?.let{r->ReportDialog(r,persisted=records.contains(r),onReview={review->
   val updated=r.copy(kycReviews=(listOf(review)+r.kycReviews).take(10));detail=updated
   if(latest==r)latest=updated
   val index=records.indexOf(r);if(index>=0){records[index]=updated;persist(records)}
  },onDiagnostic={review->
   val updated=r.copy(diagnostics=(listOf(review)+r.diagnostics).take(5));detail=updated
   if(latest==r)latest=updated
   val index=records.indexOf(r);if(index>=0){records[index]=updated;persist(records)}
  },close={detail=null})}
 }

 @Composable private fun Header(settings:()->Unit){
  Row(Modifier.fillMaxWidth().padding(20.dp,18.dp),verticalAlignment=Alignment.CenterVertically){
   Spacer(Modifier.width(40.dp))
   Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Text("ERS",color=Gold2,fontSize=30.sp,fontWeight=FontWeight.Black,letterSpacing=3.sp);Text("EXCHANGE RISK SCANNER",color=Gold2,fontSize=9.sp,letterSpacing=1.5.sp)}
   IconButton(onClick=settings){Icon(Icons.Outlined.Settings,"설정",tint=Txt)}
  }
 }
 @Composable private fun Home(latest:Record?,history:List<Record>,start:()->Unit,open:(Record)->Unit){
  val accounts=history.distinctBy{it.name+":"+it.uid}
  val worst=accounts.maxByOrNull{it.result.score}
  val c=levelColor(worst?.result?.level)
  Page{
   Panel(c.copy(.25f)){
    Row(Modifier.fillMaxWidth().clickable{if(worst==null)start() else open(worst)},verticalAlignment=Alignment.CenterVertically){
     Icon(Icons.Outlined.VerifiedUser,null,tint=c,modifier=Modifier.size(64.dp));Spacer(Modifier.width(16.dp))
     Column(Modifier.weight(1f)){Text("Overall Risk Level",fontSize=12.sp);Text(worst?.result?.level?:"READY",color=c,fontSize=30.sp,fontWeight=FontWeight.Bold);Text(if(worst==null)"첫 계정을 추가해 점검하세요" else "기기 진단 + 입력 정보 기준",color=c,fontSize=10.sp)}
     Icon(Icons.Outlined.ChevronRight,"리포트",tint=Muted)
    }
   }
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("Exchanges",accounts.map{it.name}.distinct().size.toString(),Txt,Modifier.weight(1f));Metric("High Risk",accounts.count{it.result.level=="HIGH"}.toString(),Red,Modifier.weight(1f));Metric("Medium",accounts.count{it.result.level=="MEDIUM"}.toString(),Amber,Modifier.weight(1f));Metric("Low",accounts.count{it.result.level=="LOW"}.toString(),Green,Modifier.weight(1f))}
   Row(verticalAlignment=Alignment.CenterVertically){Text("Monitored Accounts",fontSize=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));OutlinedButton(onClick=start,shape=RoundedCornerShape(10.dp)){Text("+ Add",color=Txt)}}
   if(accounts.isEmpty())Panel(){Text("등록된 계정이 없습니다",fontWeight=FontWeight.Bold);Text("거래소와 UID를 추가하면 점검 결과가 여기에 표시됩니다.",color=Muted,fontSize=12.sp)}
   accounts.forEach{r->HistoryRow(r){open(r)}}
   Text("지원 거래소 52개 · CMC 상위 50 + Tapbit · BitMart",color=Gold2,fontSize=11.sp)
   Text("목록 기준 2026.09.14 · 로고 오프라인 내장",color=Muted,fontSize=10.sp)
   Note("LOW는 수집된 신호의 낮은 점수입니다. 계정 안전·KYC 진위·거래 가능 여부를 보증하지 않습니다.",Gold)
  }
 }

 @OptIn(ExperimentalMaterial3Api::class)
 @Composable private fun Scan(ex:Exchange,custom:String,uid:String,country:String,strict:Boolean,setEx:(Exchange)->Unit,setCustom:(String)->Unit,setUid:(String)->Unit,setCountry:(String)->Unit,done:(Record)->Unit){
  var menu by remember{mutableStateOf(false)}
  var search by remember{mutableStateOf("")}
  var worker by remember{mutableStateOf("")}
  var evidence by remember{mutableStateOf(AccountEvidence())}
  var showEvidence by remember{mutableStateOf(false)}
  var result by remember{mutableStateOf<Record?>(null)}
  var error by remember{mutableStateOf("")}
  val name=if(ex.name=="기타 거래소")custom.trim() else ex.name
  Page{
   Title("계정 추가","계정 정보를 입력해 점검을 시작하세요")
   Panel(){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable{menu=true}.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Mark(ex,42);Spacer(Modifier.width(12.dp));Text(ex.name,modifier=Modifier.weight(1f));Text("선택 ›",color=Gold)}
    if(menu){androidx.compose.ui.window.Dialog(onDismissRequest={menu=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
     Surface(modifier=Modifier.fillMaxSize(),color=Bg){Column(Modifier.fillMaxSize().padding(20.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Text("거래소 선택",fontSize=24.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(onClick={menu=false}){Text("닫기")}}
      OutlinedTextField(value=search,onValueChange={search=it},label={Text("거래소 검색")},singleLine=true,colors=fields(),modifier=Modifier.fillMaxWidth())
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){exchanges.filter{it.name.contains(search,true)}.forEach{o->
       Row(Modifier.fillMaxWidth().clickable{setEx(o);menu=false;result=null}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Mark(o,42);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(o.name);Text(if(o.rank in 1..50)"CMC #${o.rank}" else if(o.rank>0)"추가 지원 거래소" else "직접 입력",color=Muted,fontSize=10.sp)};Text("›",color=Muted)};HorizontalDivider(color=Line)
      }}
     }}
    }}
    if(ex.name=="기타 거래소"){Spacer(Modifier.height(12.dp));OutlinedTextField(value=custom,onValueChange={setCustom(it);result=null},label={Text("거래소 이름")},singleLine=true,colors=fields(),modifier=Modifier.fillMaxWidth())}
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(value=worker,onValueChange={worker=it.take(40)},label={Text("작업자 (선택)")},singleLine=true,colors=fields(),modifier=Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(value=uid,onValueChange={setUid(it.filterNot(Char::isWhitespace));result=null},label={Text("거래소 UID")},supportingText={Text("이메일이 아닌 거래소 UID를 입력하세요")},singleLine=true,colors=fields(),modifier=Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(value=country,onValueChange={setCountry(it.filter(Char::isLetter).uppercase().take(2));result=null},label={Text("KYC 국가 코드")},supportingText={Text("예: KR, JP, US")},singleLine=true,colors=fields(),modifier=Modifier.fillMaxWidth())
   }
   Note("UID 입력만으로 거래소 로그인 이력·KYC 진위·계정 제한 여부를 조회할 수 없습니다. 이 결과는 현재 기기 점검입니다.",Gold)
   OutlinedButton(onClick={showEvidence=!showEvidence},modifier=Modifier.fillMaxWidth()){Text(if(showEvidence)"로그인·KYC 정보 접기" else "+ 로그인·KYC 정보 추가")}
   if(showEvidence){Panel(){
    Text("직접 확인한 정보",fontSize=16.sp,fontWeight=FontWeight.Bold)
    Text("거래소 앱에서 확인한 내용을 선택하세요. 비밀번호·OTP·API 비밀키는 입력하지 마세요.",color=Muted,fontSize=11.sp)
    EvidenceChoice("KYC 상태",evidence.kyc,listOf("미확인","승인","심사 중","거절")){evidence=evidence.copy(kyc=it)}
    EvidenceChoice("계정 제한",evidence.restriction,listOf("미확인","제한 없음","제한 있음")){evidence=evidence.copy(restriction=it)}
    EvidenceChoice("낯선 로그인",evidence.unusualLogin,listOf("미확인","없음","있음")){evidence=evidence.copy(unusualLogin=it)}
    EvidenceChoice("2단계 인증",evidence.twoFactor,listOf("미확인","설정됨","미설정")){evidence=evidence.copy(twoFactor=it)}
    OutlinedTextField(value=evidence.loginCountry,onValueChange={evidence=evidence.copy(loginCountry=it.filter(Char::isLetter).uppercase().take(2))},label={Text("최근 로그인 국가 코드 (선택)")},supportingText={Text("거래소 로그인 기록의 국가 · 예: KR")},colors=fields(),modifier=Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(value=evidence.notice,onValueChange={evidence=evidence.copy(notice=it.take(2000))},label={Text("거래소 안내문 (선택)")},supportingText={Text("개인정보를 지운 안내문만 입력 · 원문은 저장 안 함")},minLines=3,colors=fields(),modifier=Modifier.fillMaxWidth())
    if(ex.infoUrl.isNotBlank())TextButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(ex.infoUrl)))}){Text("거래소 정보·공식 사이트 확인 ↗")}
   }}
   if(error.isNotBlank())Note(error,Red)
   Button(onClick={
    error=when{name.isBlank()->"거래소 이름을 입력해 주세요.";uid.length<3->"거래소 UID를 3자 이상 입력해 주세요.";country !in Locale.getISOCountries().toSet()->"유효한 KYC 국가 코드(KR, JP 등)를 입력해 주세요.";evidence.loginCountry.isNotBlank()&&evidence.loginCountry !in Locale.getISOCountries().toSet()->"로그인 국가 코드를 확인하세요.";else->""}
    if(error.isBlank()){val s=DeviceInspector(this@MainActivity).snapshot();val r=Record(ex,name,uid,country,AccountEvidenceEngine.combine(RiskEngine.evaluate(s,country,strict),evidence,country),s,SimpleDateFormat("yyyy.MM.dd  HH:mm",Locale.KOREA).format(Date()),worker);result=r;done(r)}
   },modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=Gold,contentColor=Bg)){Text(if(result==null)"계정 추가 및 점검" else "다시 스캔하기",fontWeight=FontWeight.Black,letterSpacing=1.sp)}
   Text("계정 기록은 Android 보안 키로 암호화해 저장합니다.",color=Muted,fontSize=11.sp)
  }
 }

 @Composable private fun InlineReport(r:Record){
  val c=levelColor(r.result.level)
  Panel(c.copy(.45f)){
   Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Label("SCAN COMPLETE");Text(r.result.level+" RISK",color=c,fontSize=26.sp,fontWeight=FontWeight.Black);Text(r.name+" · "+r.time,color=Muted,fontSize=10.sp)};Score(r.result.score,c)}
   Spacer(Modifier.height(12.dp));HorizontalDivider(color=Line)
   r.result.signals.forEach{Signal(it)}
  }
 }

 @Composable private fun History(items:List<Record>,open:(Record)->Unit,clear:()->Unit){
  Page{
   Row(verticalAlignment=Alignment.Bottom){Box(Modifier.weight(1f)){Title("SCAN HISTORY","저장된 보안 점검 기록")};if(items.isNotEmpty())TextButton(onClick=clear){Text("전체 삭제",color=Red)}}
   if(items.isEmpty())Box(Modifier.fillMaxWidth().height(290.dp).clip(RoundedCornerShape(24.dp)).background(Card).border(1.dp,Line,RoundedCornerShape(24.dp)),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text("00",color=Line,fontSize=52.sp,fontWeight=FontWeight.Black);Text("저장된 기록이 없습니다",fontWeight=FontWeight.Bold);Text("스캔 완료 후 여기에 표시됩니다",color=Muted,fontSize=11.sp)}}
   else{Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){Metric("SCANS",items.size.toString(),Cyan,Modifier.weight(1f));Metric("AVERAGE",items.map{it.result.score}.average().toInt().toString(),Gold,Modifier.weight(1f));Metric("HIGH",items.count{it.result.level=="HIGH"}.toString(),Red,Modifier.weight(1f))};items.forEach{r->HistoryRow(r){open(r)}}}
  }
 }

 @Composable private fun Settings(save:Boolean,strict:Boolean,tips:Boolean,privacy:Boolean,setSave:(Boolean)->Unit,setStrict:(Boolean)->Unit,setTips:(Boolean)->Unit,setPrivacy:(Boolean)->Unit){
  Page{
   Title("SETTINGS","ERS 작동 방식을 설정하세요")
   Label("SCAN PREFERENCES")
   Panel(){Setting("프라이버시 모드","화면 캡처 및 최근 앱 미리보기 차단",privacy,setPrivacy);Setting("기록 암호화 저장","기기에 암호화하여 최대 200개 보관",save,setSave);Setting("강화 분석 모드","민감한 보안 기준으로 표시",strict,setStrict);Setting("보안 도움말 표시","결과에 권장 조치 안내",tips,setTips)}
   Label("APP INFORMATION")
   Panel(){Info("Application","Exchange Risk Scanner");Info("Version","1.9");Info("Engine","ERS Device Guard");Info("Data Mode","기기 점검 + 선택적 AI 검토");Info("Exchange catalog","52 · Offline logos")}
   Panel(){TextButton(onClick={startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))}){Text("기기 보안 설정 열기")};TextButton(onClick={startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))}){Text("네트워크 설정 열기")}}
   Label("PRIVACY & SECURITY")
   Note("기기 스캔은 로컬에서 처리합니다. AI KYC 검토는 전송 동의 후에만 선택 이미지와 거래소·UID·국가를 지정 서버로 전송합니다.",Green)
  }
 }

 @Composable private fun ReportDialog(r:Record,persisted:Boolean,onReview:(KycReview)->Unit,onDiagnostic:(AppDiagnosticReport)->Unit,close:()->Unit){
  var showAi by remember{mutableStateOf(false)}
  var showDiagnostic by remember{mutableStateOf(false)}
  if(showDiagnostic)AppDiagnosticDialog(r,persisted,{onDiagnostic(it);showDiagnostic=false}){showDiagnostic=false}
  if(showAi)KycReviewDialog(r,persisted,{onReview(it);showAi=false}){showAi=false}
  val c=levelColor(r.result.level)
  androidx.compose.ui.window.Dialog(onDismissRequest=close,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
   Surface(modifier=Modifier.fillMaxSize(),color=Bg){Column(Modifier.fillMaxSize()){
    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=close){Icon(Icons.Outlined.ArrowBack,"결과 닫기",tint=Txt)};Text("스캔 결과",fontWeight=FontWeight.Bold,fontSize=20.sp)}
    Page{
     Row(verticalAlignment=Alignment.CenterVertically){Mark(r.exchange,58);Spacer(Modifier.width(16.dp));Column{Text(r.name,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("UID "+mask(r.uid),color=Muted,fontSize=13.sp)}}
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){Pill(r.result.level+" RISK · "+r.result.score,c)}
     Text("기기·입력 정보 기준 · 실제 계정 상태는 미검증",color=c,fontSize=12.sp,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
     OutlinedButton(onClick={showAi=true},modifier=Modifier.fillMaxWidth().testTag("open-kyc-review")){Text("AI KYC 검토 · 증빙 이미지")}
     OutlinedButton(onClick={showDiagnostic=true},modifier=Modifier.fillMaxWidth().testTag("open-app-diagnostic")){Text("거래소 앱 AI 진단 · 오류 화면")}
     r.diagnostics.forEach{review->Panel(){AppDiagnosticSummary(review)}}
     r.kycReviews.firstOrNull()?.let{review->Panel(){KycReviewSummary(review)}}
     Panel(){Info("점검 시간",r.time);Info("입력 KYC 국가",r.country);Info("작업자",r.worker.ifBlank{"미지정"});Info("접속 국가 / IP","미확인");Info("기기 정보",r.snapshot.deviceModel.ifBlank{r.snapshot.networkType})}
     Panel(){r.result.signals.forEach{Signal(it);HorizontalDivider(color=Line.copy(.4f))}}
     OutlinedButton(onClick=close,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(12.dp)){Text("확인",color=Gold2)}
    }
   }}
  }
 }
 @Composable private fun EvidenceChoice(label:String,value:String,options:List<String>,change:(String)->Unit){
  var expanded by remember{mutableStateOf(false)}
  Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(label+" · "+value,modifier=Modifier.weight(1f));Text("⌄")};DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){options.forEach{v->DropdownMenuItem(text={Text(v)},onClick={change(v);expanded=false})}}}
 }
 @Composable private fun Nav(active:Int,select:(Int)->Unit){
  val names=listOf("Home","Scan","Alerts","Settings")
  val icons=listOf(Icons.Outlined.Home,Icons.Outlined.Search,Icons.Outlined.Notifications,Icons.Outlined.Settings)
  Row(Modifier.fillMaxWidth().background(Bg).navigationBarsPadding().padding(8.dp)){names.forEachIndexed{i,n->Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable{select(i)}.testTag("nav-$i").padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(icons[i],null,tint=if(active==i)Gold2 else Muted,modifier=Modifier.size(24.dp));Spacer(Modifier.height(4.dp));Text(n,color=if(active==i)Gold2 else Muted,fontSize=10.sp)}}}
 }

 @Composable private fun Page(content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)}
 @Composable private fun Panel(border:Color=Line,content:@Composable ColumnScope.()->Unit){Surface(color=Card,contentColor=Txt,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().border(1.dp,border,RoundedCornerShape(14.dp))){Column(Modifier.padding(16.dp),content=content)}}
 @Composable private fun Title(a:String,b:String){Column{Label(a);Text(b,fontSize=21.sp,fontWeight=FontWeight.Black)}}
 @Composable private fun Label(s:String){Text(s,color=Gold2,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.25.sp)}
 @Composable private fun Pill(s:String,c:Color){Box(Modifier.clip(RoundedCornerShape(20.dp)).background(c.copy(.09f)).border(1.dp,c.copy(.28f),RoundedCornerShape(20.dp)).padding(9.dp,5.dp)){Text(s,color=c,fontSize=8.sp,fontWeight=FontWeight.Bold)}}
 @Composable private fun Score(n:Int?,c:Color){Box(Modifier.size(67.dp).clip(CircleShape).background(c.copy(.1f)).border(2.dp,c.copy(.55f),CircleShape),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(n?.toString()?:"—",color=c,fontSize=22.sp,fontWeight=FontWeight.Black);Text(if(n==null)"READY" else "SCORE",color=c,fontSize=7.sp)}}}
 @Composable private fun Metric(a:String,b:String,c:Color,m:Modifier){Column(m.height(78.dp).clip(RoundedCornerShape(12.dp)).background(Card).border(1.dp,Line,RoundedCornerShape(12.dp)).padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text(b,color=c,fontSize=23.sp,fontWeight=FontWeight.Bold,letterSpacing=0.sp,maxLines=1);Text(a,color=Muted,fontSize=8.sp,letterSpacing=0.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}}
 @Composable private fun Check(a:String,b:String,d:String){Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(8.dp).clip(CircleShape).background(Muted));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(a,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text(b,color=Muted,fontSize=10.sp)};Pill(d,Cyan)}}
 @Composable private fun Coverage(a:String,b:String,on:Boolean){Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(a,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text(b,color=Muted,fontSize=10.sp)};Text(if(on)"ON" else "OFF",color=if(on)Green else Muted,fontSize=10.sp,fontWeight=FontWeight.Bold)}}
 @Composable private fun Setting(a:String,b:String,on:Boolean,set:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(a,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text(b,color=Muted,fontSize=10.sp)};Switch(checked=on,onCheckedChange=set,colors=SwitchDefaults.colors(checkedTrackColor=Gold,checkedThumbColor=Bg))}}
 @Composable private fun Info(a:String,b:String){Row(Modifier.fillMaxWidth().padding(vertical=5.dp)){Text(a,color=Muted,fontSize=11.sp,modifier=Modifier.weight(1f));Text(b,fontSize=11.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.End,modifier=Modifier.weight(1.4f))}}
 @Composable private fun Signal(s:RiskSignal){
  val unknown=!s.checked||s.value.contains("확인 불가")||s.value.contains("미확인")
  val input=s.label.contains("입력")||s.label.contains("미검증")||s.label.contains("안내문")
  val color=if(unknown)Muted else if(s.triggered)Amber else if(input)Gold else Green
  Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
   Icon(if(unknown||input)Icons.Outlined.Info else if(s.triggered)Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,null,tint=color,modifier=Modifier.size(20.dp));Spacer(Modifier.width(10.dp))
   Column(Modifier.weight(1f)){Text(s.label,fontSize=12.sp);Text(if(s.value=="true")"감지됨" else if(s.value=="false")"감지되지 않음" else s.value,color=Muted,fontSize=11.sp);if(adviceEnabled&&(s.triggered||unknown))Text(s.advice.ifBlank{advice(s.label)},color=Gold2,fontSize=10.sp)}
   Text(if(unknown)"미확인" else if(s.triggered)"+"+s.points else if(input)"참고" else "확인",color=color,fontSize=10.sp)
  }
 }
 @Composable private fun Note(s:String,c:Color){Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.copy(.08f)).border(1.dp,c.copy(.25f),RoundedCornerShape(16.dp)).padding(14.dp)){Text(s,color=if(c==Red)Red else Muted,fontSize=11.sp,lineHeight=17.sp)}}
 @Composable private fun Steps(n:Int){Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){repeat(3){i->Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(if(i<n)Gold else Line))}}}
 @Composable private fun HistoryRow(r:Record,open:()->Unit){val c=levelColor(r.result.level);Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).border(1.dp,Line,RoundedCornerShape(12.dp)).clickable(onClick=open).padding(14.dp),verticalAlignment=Alignment.CenterVertically){Mark(r.exchange,42);Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text(r.name,fontWeight=FontWeight.Bold);Text("UID "+mask(r.uid),color=Muted,fontSize=11.sp);Text("KYC "+r.country+" · 입력값",color=Muted,fontSize=10.sp)};Column(horizontalAlignment=Alignment.End){Text(r.result.score.toString(),color=c,fontSize=20.sp,fontWeight=FontWeight.Black);Text(r.result.level,color=c,fontSize=9.sp)}}}
 @Composable private fun Mark(e:Exchange,n:Int){
  val bitmap=remember(e.logo){e.logo?.let{try{val bytes=Base64.decode(it,Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.asImageBitmap()}catch(_:Exception){null}}}
  Box(Modifier.size(n.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF080D0C)),contentAlignment=Alignment.Center){if(bitmap!=null)Image(bitmap=bitmap,contentDescription=e.name+" 로고",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().padding(4.dp)) else Text(e.mark,color=Gold2,fontSize=14.sp,fontWeight=FontWeight.Bold)}
 }
 @Composable private fun fields()=OutlinedTextFieldDefaults.colors(focusedBorderColor=Gold,unfocusedBorderColor=Line,focusedLabelColor=Gold2,unfocusedLabelColor=Muted,focusedTextColor=Txt,unfocusedTextColor=Txt,cursorColor=Gold,focusedContainerColor=Card2.copy(.5f),unfocusedContainerColor=Card2.copy(.28f),focusedSupportingTextColor=Muted,unfocusedSupportingTextColor=Muted,focusedPlaceholderColor=Muted,unfocusedPlaceholderColor=Muted,focusedLeadingIconColor=Txt,unfocusedLeadingIconColor=Txt,focusedTrailingIconColor=Txt,unfocusedTrailingIconColor=Txt)
 private fun advice(label:String):String=when{label.contains("루팅")->"공식 OS와 보안 업데이트 상태를 확인하세요.";label.contains("ADB")->"사용하지 않는 USB 디버깅을 끄세요.";label.contains("잠금")->"기기 잠금과 생체 인증을 설정하세요.";label.contains("패치")->"OS 보안 업데이트를 확인하세요.";label.contains("VPN")->"사용 중인 VPN의 신뢰성과 연결 필요성을 확인하세요.";else->"표시된 기기 설정을 확인한 뒤 다시 점검하세요."}
 private fun levelColor(s:String?)=when(s){"HIGH"->Red;"MEDIUM"->Amber;"LOW"->Green;else->Gold}
 private fun mask(s:String)=if(s.length<=4)"•".repeat(s.length) else s.take(2)+"•".repeat((s.length-4).coerceAtMost(6))+s.takeLast(2)
}

