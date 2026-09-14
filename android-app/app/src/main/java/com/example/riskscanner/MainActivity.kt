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
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg=Color(0xFF050A10)
private val Card=Color(0xFF0B151F)
private val Card2=Color(0xFF111F2C)
private val Line=Color(0xFF24394A)
private val Gold=Color(0xFFD9B56D)
private val Gold2=Color(0xFFF2D89E)
private val Cyan=Color(0xFF55D8FF)
private val Txt=Color(0xFFF4F7F9)
private val Muted=Color(0xFF91A5B6)
private val Green=Color(0xFF57D69A)
private val Amber=Color(0xFFF4B64D)
private val Red=Color(0xFFFF6D75)

data class Exchange(val name:String,val mark:String,val color:Color,val logo:String?=null)
data class Record(val exchange:Exchange,val name:String,val uid:String,val country:String,val result:RiskResult,val snapshot:DeviceSnapshot,val time:String)

private val exchanges=listOf(
 Exchange("Binance","BN",Color(0xFFF3BA2F),"https://www.binance.com/favicon.ico"),
 Exchange("Bybit","BY",Color(0xFFF7A600),"https://www.bybit.com/favicon.ico"),
 Exchange("OKX","OK",Color.White,"https://www.okx.com/favicon.ico"),
 Exchange("Bitget","BG",Color(0xFF00D3B7),"https://www.bitget.com/favicon.ico"),
 Exchange("BingX","BX",Color(0xFF2D7CFF),"https://bingx.com/favicon.ico"),
 Exchange("Toobit","TB",Color(0xFF19C7B5),"https://www.toobit.com/favicon.ico"),
 Exchange("CoinW","CW",Color(0xFF2A75FF),"https://www.coinw.com/favicon.ico"),
 Exchange("Deepcoin","DC",Color(0xFF7258FF),"https://www.deepcoin.com/favicon.ico"),
 Exchange("Gate.io","GT",Color(0xFF17C6B3),"https://www.gate.com/favicon.ico"),
 Exchange("MEXC","MX",Color(0xFF2F6BFF),"https://www.mexc.com/favicon.ico"),
 Exchange("KuCoin","KC",Color(0xFF23AF91),"https://www.kucoin.com/favicon.ico"),
 Exchange("LBank","LB",Color(0xFF2D74FF),"https://www.lbank.com/favicon.ico"),
 Exchange("OURBIT","OB",Color(0xFF7C5CFF),"https://www.ourbit.com/favicon.ico"),
 Exchange("Tabit","TA",Color(0xFF39B5FF)),
 Exchange("MGBX","MG",Color(0xFFFF8A3D)),
 Exchange("기타 거래소","+",Gold)
)

class MainActivity:ComponentActivity(){
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
  var tab by remember{mutableIntStateOf(0)}
  var ex by remember{mutableStateOf(exchanges[0])}
  var custom by remember{mutableStateOf("")}
  var uid by remember{mutableStateOf("")}
  var country by remember{mutableStateOf("KR")}
  var latest by remember{mutableStateOf<Record?>(null)}
  val records=remember{mutableStateListOf<Record>()}
  var detail by remember{mutableStateOf<Record?>(null)}
  var save by remember{mutableStateOf(true)}
  var strict by remember{mutableStateOf(false)}
  var tips by remember{mutableStateOf(true)}
  Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF091520),Bg,Color(0xFF03070B))))){
   Header()
   Box(Modifier.weight(1f)){
    when(tab){
     0->Home(latest,records,{tab=1},{detail=it})
     1->Scan(ex,custom,uid,country,strict,{ex=it},{custom=it},{uid=it},{country=it}){r->latest=r;if(save)records.add(0,r)}
     2->History(records,{detail=it},{records.clear()})
     else->Settings(save,strict,tips,{save=it},{strict=it},{tips=it})
    }
   }
   Nav(tab){tab=it}
  }
  detail?.let{ReportDialog(it){detail=null}}
 }

 @Composable private fun Header(){
  Row(Modifier.fillMaxWidth().padding(18.dp,14.dp),verticalAlignment=Alignment.CenterVertically){
   Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Color(0xFF251C0D),Color(0xFF0D202D)))).border(1.dp,Gold.copy(.7f),RoundedCornerShape(15.dp)),contentAlignment=Alignment.Center){Text("E",color=Gold2,fontSize=24.sp,fontWeight=FontWeight.Black)}
   Spacer(Modifier.width(11.dp))
   Column(Modifier.weight(1f)){Text("ERS",color=Gold2,fontSize=21.sp,fontWeight=FontWeight.Black,letterSpacing=2.sp);Text("EXCHANGE RISK SCANNER",color=Muted,fontSize=9.sp,letterSpacing=1.3.sp)}
   Pill("SYSTEM SECURE",Green)
  }
 }

 @Composable private fun Home(latest:Record?,history:List<Record>,start:()->Unit,open:(Record)->Unit){
  Page{
   Text("보안 상태를 한눈에 확인하세요",fontSize=24.sp,fontWeight=FontWeight.Black)
   Text("거래소 계정과 현재 기기 환경을 점검합니다.",color=Muted,fontSize=13.sp)
   val c=levelColor(latest?.result?.level)
   Panel(c.copy(.35f)){
    Row(verticalAlignment=Alignment.CenterVertically){
     Column(Modifier.weight(1f)){Label("CURRENT STATUS");Text(latest?.result?.level?:"READY",color=c,fontSize=34.sp,fontWeight=FontWeight.Black);Text(latest?.let{it.name+" · UID "+mask(it.uid)}?:"첫 번째 스캔을 시작하세요",color=Muted,fontSize=11.sp)}
     Score(latest?.result?.score,c)
    }
    Spacer(Modifier.height(14.dp));HorizontalDivider(color=Line);Spacer(Modifier.height(14.dp))
    Button(onClick={if(latest==null)start() else open(latest)},modifier=Modifier.fillMaxWidth().height(50.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=Gold,contentColor=Bg)){Text(if(latest==null)"START SECURITY SCAN" else "VIEW LATEST REPORT",fontWeight=FontWeight.Black)}
   }
   Label("OVERVIEW")
   Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){Metric("SCANS",history.size.toString(),Cyan,Modifier.weight(1f));Metric("SAFE",history.count{it.result.level=="LOW"}.toString(),Green,Modifier.weight(1f));Metric("RISK",history.count{it.result.level!="LOW"}.toString(),Amber,Modifier.weight(1f))}
   Label("QUICK CHECK")
   Panel(){Check("Network Integrity","VPN·연결 유형","NETWORK");Check("Device Integrity","루팅·에뮬레이터","DEVICE");Check("Identity Region","KYC·기기 국가","KYC");Check("Security State","잠금화면·보안 패치","SECURE")}
   if(history.isNotEmpty()){Label("RECENT ACTIVITY");history.take(3).forEach{r->HistoryRow(r){open(r)}}}
   Note("ERS는 기기·계정 환경 자가 점검 도구입니다. 거래소 비공개 규칙을 추정하거나 우회하지 않습니다.",Gold)
  }
 }

 @OptIn(ExperimentalMaterial3Api::class)
 @Composable private fun Scan(ex:Exchange,custom:String,uid:String,country:String,strict:Boolean,setEx:(Exchange)->Unit,setCustom:(String)->Unit,setUid:(String)->Unit,setCountry:(String)->Unit,done:(Record)->Unit){
  var menu by remember{mutableStateOf(false)}
  var result by remember{mutableStateOf<Record?>(null)}
  var error by remember{mutableStateOf("")}
  val name=if(ex.name=="기타 거래소")custom.trim() else ex.name
  Page{
   Title("RISK SCAN","스캔 대상과 기준을 설정하세요")
   Steps(if(result==null)1 else 3)
   Label("SCAN TARGET")
   Panel(){
    ExposedDropdownMenuBox(expanded=menu,onExpandedChange={menu=!menu}){
     OutlinedTextField(value=ex.name,onValueChange={},readOnly=true,label={Text("거래소 선택")},leadingIcon={Mark(ex,34)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(menu)},colors=fields(),modifier=Modifier.fillMaxWidth().menuAnchor())
     ExposedDropdownMenu(expanded=menu,onDismissRequest={menu=false},modifier=Modifier.background(Card2)){exchanges.forEach{o->DropdownMenuItem(text={Row(verticalAlignment=Alignment.CenterVertically){Mark(o,34);Spacer(Modifier.width(10.dp));Text(o.name,color=Txt)}},onClick={setEx(o);menu=false;result=null},colors=MenuDefaults.itemColors(textColor=Txt,leadingIconColor=Txt,trailingIconColor=Txt,disabledTextColor=Muted))}}
    }
    if(ex.name=="기타 거래소"){Spacer(Modifier.height(12.dp));OutlinedTextField(value=custom,onValueChange={setCustom(it);result=null},label={Text("거래소 이름")},singleLine=true,colors=fields(),modifier=Modifier.fillMaxWidth())}
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(value=uid,onValueChange={setUid(it.filterNot(Char::isWhitespace));result=null},label={Text("거래소 UID")},supportingText={Text("이메일이 아닌 거래소 UID를 입력하세요")},singleLine=true,colors=fields(),modifier=Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(value=country,onValueChange={setCountry(it.filter(Char::isLetter).uppercase().take(2));result=null},label={Text("KYC 국가 코드")},supportingText={Text("예: KR, JP, US")},singleLine=true,colors=fields(),modifier=Modifier.fillMaxWidth())
   }
   Label("SCAN COVERAGE")
   Panel(){Coverage("VPN 및 네트워크","연결 유형·터널 확인",true);Coverage("기기 무결성","루팅·에뮬레이터 확인",true);Coverage("개발 환경","ADB·개발자 옵션 확인",true);Coverage("국가 일치","KYC·기기 국가 비교",true);Coverage("강화 분석","엄격한 표시 기준",strict)}
   if(error.isNotBlank())Note(error,Red)
   Button(onClick={
    error=when{name.isBlank()->"거래소 이름을 입력해 주세요.";uid.length<3->"거래소 UID를 3자 이상 입력해 주세요.";country.length!=2->"KYC 국가 코드를 두 자리로 입력해 주세요.";else->""}
    if(error.isBlank()){val s=DeviceInspector(this@MainActivity).snapshot();val r=Record(ex,name,uid,country,RiskEngine.evaluate(s,country),s,SimpleDateFormat("yyyy.MM.dd  HH:mm",Locale.KOREA).format(Date()));result=r;done(r)}
   },modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=Gold,contentColor=Bg)){Text(if(result==null)"RUN FULL SCAN" else "SCAN AGAIN",fontWeight=FontWeight.Black,letterSpacing=1.sp)}
   result?.let{InlineReport(it)}
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

 @Composable private fun Settings(save:Boolean,strict:Boolean,tips:Boolean,setSave:(Boolean)->Unit,setStrict:(Boolean)->Unit,setTips:(Boolean)->Unit){
  Page{
   Title("SETTINGS","ERS 작동 방식을 설정하세요")
   Label("SCAN PREFERENCES")
   Panel(){Setting("스캔 기록 자동 저장","완료 결과를 기록에 보관",save,setSave);Setting("강화 분석 모드","민감한 보안 기준으로 표시",strict,setStrict);Setting("보안 도움말 표시","결과에 권장 조치 안내",tips,setTips)}
   Label("APP INFORMATION")
   Panel(){Info("Application","Exchange Risk Scanner");Info("Version","1.3");Info("Engine","ERS Device Guard");Info("Data Mode","On-device only");Info("Network","Logo assets only")}
   Label("PRIVACY & SECURITY")
   Note("스캔 데이터는 기기에서만 분석되며 입력한 UID 원문을 외부 서버로 전송하지 않습니다.",Green)
  }
 }

 @Composable private fun ReportDialog(r:Record,close:()->Unit){
  val c=levelColor(r.result.level)
  AlertDialog(onDismissRequest=close,containerColor=Card,title={Column{Label("SECURITY REPORT");Text(r.result.level+" · "+r.result.score+"/100",color=c,fontSize=24.sp,fontWeight=FontWeight.Black)}},text={Column(Modifier.verticalScroll(rememberScrollState())){Info("Exchange",r.name);Info("UID",mask(r.uid));Info("KYC Country",r.country);Info("Device Country",r.snapshot.deviceCountry.ifBlank{"-"});Info("Network",r.snapshot.networkType);HorizontalDivider(color=Line);r.result.signals.forEach{Signal(it)}}},confirmButton={Button(onClick=close,colors=ButtonDefaults.buttonColors(containerColor=Gold,contentColor=Bg)){Text("확인",fontWeight=FontWeight.Bold)}})
 }

 @Composable private fun Nav(active:Int,select:(Int)->Unit){
  val names=listOf("HOME","SCAN","HISTORY","SETTINGS")
  Row(Modifier.fillMaxWidth().background(Card).border(1.dp,Line).navigationBarsPadding().padding(8.dp)){names.forEachIndexed{i,n->Column(Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).clickable{select(i)}.background(if(active==i)Gold.copy(.1f) else Color.Transparent).padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(if(active==i)7.dp else 5.dp).clip(CircleShape).background(if(active==i)Gold else Line));Spacer(Modifier.height(5.dp));Text(n,color=if(active==i)Gold2 else Muted,fontSize=9.sp,fontWeight=if(active==i)FontWeight.Bold else FontWeight.Normal)}}}
 }

 @Composable private fun Page(content:@Composable ColumnScope.()->Unit){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)}
 @Composable private fun Panel(border:Color=Line,content:@Composable ColumnScope.()->Unit){Surface(color=Card,contentColor=Txt,shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth().border(1.dp,border,RoundedCornerShape(22.dp))){Column(Modifier.padding(16.dp),content=content)}}
 @Composable private fun Title(a:String,b:String){Column{Label(a);Text(b,fontSize=21.sp,fontWeight=FontWeight.Black)}}
 @Composable private fun Label(s:String){Text(s,color=Gold2,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.25.sp)}
 @Composable private fun Pill(s:String,c:Color){Box(Modifier.clip(RoundedCornerShape(20.dp)).background(c.copy(.09f)).border(1.dp,c.copy(.28f),RoundedCornerShape(20.dp)).padding(9.dp,5.dp)){Text(s,color=c,fontSize=8.sp,fontWeight=FontWeight.Bold)}}
 @Composable private fun Score(n:Int?,c:Color){Box(Modifier.size(67.dp).clip(CircleShape).background(c.copy(.1f)).border(2.dp,c.copy(.55f),CircleShape),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(n?.toString()?:"—",color=c,fontSize=22.sp,fontWeight=FontWeight.Black);Text(if(n==null)"READY" else "SCORE",color=c,fontSize=7.sp)}}}
 @Composable private fun Metric(a:String,b:String,c:Color,m:Modifier){Column(m.clip(RoundedCornerShape(17.dp)).background(Card).border(1.dp,Line,RoundedCornerShape(17.dp)).padding(12.dp)){Text(b,color=c,fontSize=21.sp,fontWeight=FontWeight.Black);Text(a,color=Muted,fontSize=8.sp)}}
 @Composable private fun Check(a:String,b:String,d:String){Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(8.dp).clip(CircleShape).background(Green));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(a,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text(b,color=Muted,fontSize=10.sp)};Pill(d,Cyan)}}
 @Composable private fun Coverage(a:String,b:String,on:Boolean){Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(a,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text(b,color=Muted,fontSize=10.sp)};Text(if(on)"ON" else "OFF",color=if(on)Green else Muted,fontSize=10.sp,fontWeight=FontWeight.Bold)}}
 @Composable private fun Setting(a:String,b:String,on:Boolean,set:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(a,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text(b,color=Muted,fontSize=10.sp)};Switch(checked=on,onCheckedChange=set,colors=SwitchDefaults.colors(checkedTrackColor=Gold,checkedThumbColor=Bg))}}
 @Composable private fun Info(a:String,b:String){Row(Modifier.fillMaxWidth().padding(vertical=5.dp)){Text(a,color=Muted,fontSize=11.sp,modifier=Modifier.weight(1f));Text(b,fontSize=11.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.End,modifier=Modifier.weight(1.4f))}}
 @Composable private fun Signal(s:RiskSignal){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).clip(CircleShape).background(if(s.triggered)Red else Green));Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f)){Text(s.label,fontSize=11.sp);Text(if(s.value=="true")"감지됨" else if(s.value=="false")"감지되지 않음" else s.value,color=Muted,fontSize=9.sp)};Text(if(s.triggered)"+"+s.points else "PASS",color=if(s.triggered)Red else Green,fontSize=9.sp,fontWeight=FontWeight.Bold)}}
 @Composable private fun Note(s:String,c:Color){Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.copy(.08f)).border(1.dp,c.copy(.25f),RoundedCornerShape(16.dp)).padding(14.dp)){Text(s,color=if(c==Red)Red else Muted,fontSize=11.sp,lineHeight=17.sp)}}
 @Composable private fun Steps(n:Int){Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){repeat(3){i->Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(if(i<n)Gold else Line))}}}
 @Composable private fun HistoryRow(r:Record,open:()->Unit){val c=levelColor(r.result.level);Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp,Line,RoundedCornerShape(18.dp)).clickable(onClick=open).padding(14.dp),verticalAlignment=Alignment.CenterVertically){Mark(r.exchange,42);Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text(r.name,fontWeight=FontWeight.Bold);Text("UID "+mask(r.uid)+" · "+r.time,color=Muted,fontSize=10.sp)};Column(horizontalAlignment=Alignment.End){Text(r.result.score.toString(),color=c,fontSize=20.sp,fontWeight=FontWeight.Black);Text(r.result.level,color=c,fontSize=9.sp)}}}
 @Composable private fun Mark(e:Exchange,n:Int){Box(Modifier.size(n.dp).clip(RoundedCornerShape((n/3).dp)).background(e.color.copy(.16f)).border(1.dp,e.color.copy(.32f),RoundedCornerShape((n/3).dp)),contentAlignment=Alignment.Center){Text(e.mark,color=e.color,fontSize=9.sp,fontWeight=FontWeight.Black);e.logo?.let{AsyncImage(model=it,contentDescription=e.name,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape((n/4).dp)))}}}
 @Composable private fun fields()=OutlinedTextFieldDefaults.colors(focusedBorderColor=Gold,unfocusedBorderColor=Line,focusedLabelColor=Gold2,unfocusedLabelColor=Muted,focusedTextColor=Txt,unfocusedTextColor=Txt,cursorColor=Gold,focusedContainerColor=Card2.copy(.5f),unfocusedContainerColor=Card2.copy(.28f),focusedSupportingTextColor=Muted,unfocusedSupportingTextColor=Muted,focusedPlaceholderColor=Muted,unfocusedPlaceholderColor=Muted,focusedLeadingIconColor=Txt,unfocusedLeadingIconColor=Txt,focusedTrailingIconColor=Txt,unfocusedTrailingIconColor=Txt)
 private fun levelColor(s:String?)=when(s){"HIGH"->Red;"MEDIUM"->Amber;"LOW"->Green;else->Gold}
 private fun mask(s:String)=if(s.length<=4)s else s.take(2)+"•".repeat((s.length-4).coerceAtMost(6))+s.takeLast(2)
}
