import SwiftUI

private let bg=Color(red:00.02,green:00.04,blue:00.065)
private let panel=Color(red:0.06,green:0.09,blue:0.075)
private let panel2=Color(red:0.085,green:0.13,blue:0.105)
private let line=Color(red:00.14,green:00.22,blue:00.29)
private let gold=Color(red:00.85,green:00.71,blue:00.43)
private let gold2=Color(red:00.95,green:00.85,blue:00.62)
private let cyan=Color(red:00.33,green:00.85,blue:1)
private let muted=Color(red:00.57,green:00.65,blue:00.71)
private let green=Color(red:00.34,green:00.84,blue:00.60)
private let amber=Color(red:00.96,green:00.71,blue:00.30)
private let red=Color(red:1,green:00.43,blue:00.46)

struct ContentView: View {
    @EnvironmentObject var store: ERSStore
    @State private var tab=0
    var body: some View {
        ZStack {
            LinearGradient(colors:[Color(red:00.035,green:00.08,blue:00.12),bg,Color.black],startPoint:.top,endPoint:.bottom).ignoresSafeArea()
            VStack(spacing:0) {
                Header()
                if !store.storageMessage.isEmpty { Notice(store.storageMessage,red) }
                TabView(selection:$tab) {
                    HomeView(start:{ tab=1 }).tag(0)
                    ScanView().tag(1)
                    HistoryView().tag(2)
                    SettingsView().tag(3)
                }.tabViewStyle(.page(indexDisplayMode:.never))
                HStack {
                    Nav("HOME",0);Nav("SCAN",1);Nav("ALERTS",2);Nav("SETTINGS",3)
                }.padding(8).background(panel.overlay(Rectangle().frame(height:1).foregroundStyle(line),alignment:.top))
            }
        }.tint(gold).foregroundStyle(.white)
    }
    @ViewBuilder func Nav(_ title:String,_ index:Int)->some View {
        Button { tab=index } label: {
            VStack(spacing:5) {
                Image(systemName:["house","magnifyingglass","bell","gearshape"][index]).font(.system(size:22)).foregroundStyle(tab==index ? gold:muted)
                Text(title).font(.system(size:9,weight:tab==index ? .bold:.regular)).foregroundStyle(tab==index ? gold2:muted)
            }.frame(maxWidth:.infinity).padding(.vertical,7).background(tab==index ? gold.opacity(00.1):.clear).clipShape(RoundedRectangle(cornerRadius:13))
        }
    }
}

struct Header: View {
 var body:some View { VStack(spacing:3){Text("ERS").font(.system(size:30,weight:.black)).tracking(3).foregroundStyle(gold2);Text("EXCHANGE RISK SCANNER").font(.system(size:9)).tracking(1.5).foregroundStyle(gold2)}.frame(maxWidth:.infinity).padding(.vertical,18) }
}

struct HomeView: View {
    @EnvironmentObject var store:ERSStore
    let start:()->Void
    @State private var selected:ScanRecord?
    var accounts:[ScanRecord] { var seen=Set<String>();return store.records.filter{seen.insert($0.exchange.name+":"+$0.uid).inserted} }
    var latest:ScanRecord?{accounts.max{$0.score < $1.score}}
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:14) {
                CardBox {
                    HStack {
                        VStack(alignment:.leading){LabelText("Overall Risk Level");Text(latest?.level ?? "READY").font(.system(size:34,weight:.black)).foregroundStyle(levelColor(latest?.level));Text(latest.map{$0.exchange.name+" · UID "+maskUid($0.uid)} ?? "첫 번째 보안 스캔을 시작하세요").font(.caption).foregroundStyle(muted)}
                        Spacer();Image(systemName:"checkmark.shield").font(.system(size:60)).foregroundStyle(levelColor(latest?.level))
                    }
                    Divider().background(line)
                    Button(latest==nil ? "START SECURITY SCAN":"VIEW LATEST REPORT"){if let latest{selected=latest}else{start()}}.font(.system(size:14,weight:.black)).foregroundStyle(bg).frame(maxWidth:.infinity).padding().background(gold).clipShape(RoundedRectangle(cornerRadius:14))
                }
                HStack {Metric("Exchanges","\(Set(accounts.map{$0.exchange.name}).count)",gold);Metric("High","\(accounts.filter{$0.level=="HIGH"}.count)",red);Metric("Medium","\(accounts.filter{$0.level=="MEDIUM"}.count)",amber);Metric("Low","\(accounts.filter{$0.level=="LOW"}.count)",green)}
                HStack {Text("Monitored Accounts").font(.headline);Spacer();Button("+ Add",action:start)}
                ForEach(accounts){r in RecordRow(r){selected=r}}
                Text("52개 거래소 · CMC 상위 50 + Tapbit · BitMart").font(.caption).foregroundStyle(gold2)
                Notice("기기·입력 정보 기준이며 계정 안전·KYC 진위를 보증하지 않습니다. 목록 기준 2026.09.14",gold)
            }.padding(.horizontal,18).padding(.bottom,16)
        }.fullScreenCover(item:$selected){ReportView(record:$0)}
    }
}

struct ScanView: View {
    @EnvironmentObject var store:ERSStore
    @State private var exchange=Exchange(name:"Binance",mark:"BI")
    @State private var uid=""
    @State private var country="KR"
    @State private var custom=""
    @State private var worker=""
    @State private var showExchanges=false
    @State private var search=""
    @State private var error=""
    @State private var evidence=AccountEvidence()
    @State private var result:ScanRecord?
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:14) {
                PageTitle("계정 추가","계정 정보를 입력해 점검을 시작하세요")
                HStack(spacing:7){ForEach(0..<3){i in Capsule().fill(i < (result==nil ? 1:3) ? gold:line).frame(height:3)}}
                LabelText("SCAN TARGET")
                CardBox {
                    Button { showExchanges=true } label: {
                        HStack { ExchangeMark(exchange.name); Text(exchange.name); Spacer(); Image(systemName:"chevron.right") }
                    }
                    .sheet(isPresented:$showExchanges) {
                        NavigationStack {
                            List(store.exchanges.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }) { item in
                                Button { exchange=item; result=nil; showExchanges=false } label:{HStack{ExchangeMark(item.name);Text(item.name)}}
                            }
                            .searchable(text:$search,prompt:"거래소 검색")
                            .navigationTitle("거래소 선택")
                            .toolbar { ToolbarItem(placement:.topBarTrailing) { Button("닫기") { showExchanges=false } } }
                        }.preferredColorScheme(.dark)
                    }
                    Divider().background(line)
                    if exchange.name=="기타 거래소" { DarkField("거래소 이름",text:$custom) }
                    DarkField("거래소 UID",text:$uid)
                    DarkField("작업자 (선택)",text:$worker)
                    DarkField("KYC 국가 코드",text:$country)
                    Text("이메일이 아닌 거래소 UID와 두 자리 국가 코드를 입력하세요.").font(.caption2).foregroundStyle(muted)
                }
                DisclosureGroup("로그인·KYC 정보 추가") {
                 VStack(spacing:12){
                  Text("직접 확인한 정보 · 비밀번호·OTP·API 비밀키 입력 금지").font(.caption).foregroundStyle(muted)
                  EvidencePicker("KYC 상태",value:$evidence.kyc,options:["미확인","승인","심사 중","거절"])
                  EvidencePicker("계정 제한",value:$evidence.restriction,options:["미확인","제한 없음","제한 있음"])
                  EvidencePicker("낯선 로그인",value:$evidence.unusualLogin,options:["미확인","없음","있음"])
                  EvidencePicker("2단계 인증",value:$evidence.twoFactor,options:["미확인","설정됨","미설정"])
                  DarkField("최근 로그인 국가 코드 (선택)",text:$evidence.loginCountry)
                  DarkField("개인정보를 지운 거래소 안내문 (선택)",text:$evidence.notice)
                  Text("입력한 안내문 원문은 저장하지 않습니다.").font(.caption2).foregroundStyle(muted)
                 }.padding(.vertical,12)
                }
                Notice("UID만으로 거래소 내부 로그인·KYC 정보를 조회할 수 없습니다. 입력 근거와 미확인 항목을 구분합니다.",gold)
                if !error.isEmpty { Notice(error,red) }
                Button("계정 추가 및 점검") {
                    let name=exchange.name=="기타 거래소" ? custom.trimmingCharacters(in:.whitespaces):exchange.name
                    if name.isEmpty {error="거래소 이름을 입력해 주세요."}
                    else if uid.count<3 {error="거래소 UID를 3자 이상 입력해 주세요."}
                    else if !Locale.isoRegionCodes.contains(country.uppercased()) {error="KYC 국가 코드를 두 자리로 입력해 주세요."}
                    else if !evidence.loginCountry.isEmpty && !Locale.isoRegionCodes.contains(evidence.loginCountry.uppercased()) {error="로그인 국가 코드를 확인하세요."}
                    else {error="";result=store.scan(exchange:Exchange(name:name,mark:exchange.mark),uid:uid,country:country,worker:worker,evidence:evidence)}
                }.font(.system(size:14,weight:.black)).foregroundStyle(bg).frame(maxWidth:.infinity).padding().background(gold).clipShape(RoundedRectangle(cornerRadius:16))

            }.padding(.horizontal,18).padding(.bottom,16)
        }.fullScreenCover(item:$result){ReportView(record:$0)}.onAppear{if let first=store.exchanges.first,exchange.name=="Binance"{exchange=first}}
    }
}

struct HistoryView:View {
    @EnvironmentObject var store:ERSStore
    @State private var selected:ScanRecord?
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:14) {
                HStack{PageTitle("SCAN HISTORY","저장된 보안 점검 기록");Spacer();if !store.records.isEmpty{Button("전체 삭제"){store.records.removeAll()}.foregroundStyle(red).font(.caption)}}
                if store.records.isEmpty {
                    VStack(spacing:7){Text("00").font(.system(size:52,weight:.black)).foregroundStyle(line);Text("저장된 기록이 없습니다").fontWeight(.bold);Text("스캔 완료 후 여기에 표시됩니다").font(.caption).foregroundStyle(muted)}.frame(maxWidth:.infinity,minHeight:290).background(panel).clipShape(RoundedRectangle(cornerRadius:24)).overlay(RoundedRectangle(cornerRadius:24).stroke(line))
                } else {
                    HStack {Metric("SCANS","\(store.records.count)",cyan);Metric("AVERAGE","\(store.records.map{$0.score}.reduce(0,+)/store.records.count)",gold);Metric("HIGH","\(store.records.filter{$0.level=="HIGH"}.count)",red)}
                    ForEach(store.records){r in RecordRow(r){selected=r}}
                }
            }.padding(.horizontal,18).padding(.bottom,16)
        }.fullScreenCover(item:$selected){ReportView(record:$0)}
    }
}

struct SettingsView:View {
    @EnvironmentObject var store:ERSStore
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:14) {
                PageTitle("SETTINGS","ERS 작동 방식을 설정하세요")
                LabelText("SCAN PREFERENCES")
                CardBox {ToggleRow("보호된 저장소에 기록 보관","이 기기에 최대 200개 보관",$store.autoSave);ToggleRow("강화 분석 모드","민감한 보안 기준",$store.strictMode);ToggleRow("보안 도움말 표시","결과에 권장 조치",$store.showTips)}
                LabelText("APP INFORMATION")
                CardBox {InfoRow("Application","Exchange Risk Scanner");InfoRow("Version","1.9 iOS");InfoRow("Engine","ERS Device Guard");InfoRow("Data Mode","기기 점검 + 선택적 AI 검토")}
                LabelText("PRIVACY & SECURITY")
                Notice("기기 스캔은 로컬에서 처리합니다. AI KYC 검토는 전송 동의 후에만 이미지와 거래소·UID·국가를 지정 서버에 전송합니다.",green)
            }.padding(.horizontal,18).padding(.bottom,16)
        }
    }
}

struct ReportView:View {
    let record:ScanRecord
    @EnvironmentObject var store:ERSStore
    @Environment(\.dismiss) var dismiss
    @State private var showAi=false
    @State private var sessionRecord:ScanRecord?
    var current:ScanRecord {sessionRecord ?? store.records.first(where:{$0.id==record.id}) ?? record}
    var body:some View {
        NavigationStack {
            ScrollView {VStack(alignment:.leading,spacing:12){
                HStack{ExchangeMark(current.exchange.name);Text(current.exchange.name).font(.title2)}
                LabelText("스캔 결과 · 기기·입력 정보 기준")
                Text(current.level+" · \(current.score)/100").font(.system(size:28,weight:.black)).foregroundStyle(levelColor(current.level))
                CardBox{InfoRow("Exchange",current.exchange.name);InfoRow("UID",maskUid(current.uid));InfoRow("작업자",current.worker.isEmpty ? "미지정":current.worker);InfoRow("KYC Country",current.country);InfoRow("Device Country",current.deviceCountry.isEmpty ? "-":current.deviceCountry)}
                Button("AI KYC 검토 · 증빙 이미지"){showAi=true}.buttonStyle(.borderedProminent)
                if let review=current.kycReviews?.first{CardBox{KycReviewSummary(review:review)}}
                LabelText("ANALYSIS");CardBox{ForEach(current.signals){SignalRow($0)}}
            }.padding(18)}
            .background(bg).toolbar{ToolbarItem(placement:.topBarTrailing){Button("완료"){dismiss()}}}
            .sheet(isPresented:$showAi){KycReviewSheet(record:current,persisted:store.records.contains(where:{$0.id==record.id})){review in sessionRecord=store.saveKyc(current,review:review)}}
        }.preferredColorScheme(.dark)
    }
}

struct InlineReport:View {let r:ScanRecord;init(_ r:ScanRecord){self.r=r};var body:some View{CardBox{HStack{VStack(alignment:.leading){LabelText("SCAN COMPLETE");Text(r.level+" RISK").font(.system(size:26,weight:.black)).foregroundStyle(levelColor(r.level));Text(r.exchange.name).font(.caption).foregroundStyle(muted)};Spacer();ScoreView(score:r.score,color:levelColor(r.level))};Divider().background(line);ForEach(r.signals){SignalRow($0)}}}}
struct ScoreView:View{let score:Int?;let color:Color;var body:some View{ZStack{Circle().fill(color.opacity(0.1));Circle().stroke(color.opacity(0.55),lineWidth:2);VStack{Text(score.map(String.init) ?? "—").font(.system(size:22,weight:.black));Text(score==nil ? "READY":"SCORE").font(.system(size:7,weight:.bold))}.foregroundStyle(color)}.frame(width:67,height:67)}}
struct LabelText:View{let text:String;init(_ t:String){text=t};var body:some View{Text(text).font(.system(size:10,weight:.bold)).tracking(1.2).foregroundStyle(gold2)}}
struct PageTitle:View{let a:String;let b:String;init(_ a:String,_ b:String){self.a=a;self.b=b};var body:some View{VStack(alignment:.leading){LabelText(a);Text(b).font(.system(size:21,weight:.black))}}}
struct CardBox<Content:View>:View{let content:Content;init(@ViewBuilder content:()->Content){self.content=content()};var body:some View{VStack(alignment:.leading,spacing:12){content}.padding(16).frame(maxWidth:.infinity,alignment:.leading).background(panel).clipShape(RoundedRectangle(cornerRadius:14)).overlay(RoundedRectangle(cornerRadius:14).stroke(line))}}
struct Metric:View{let a:String;let b:String;let c:Color;init(_ a:String,_ b:String,_ c:Color){self.a=a;self.b=b;self.c=c};var body:some View{VStack(alignment:.leading){Text(b).font(.system(size:21,weight:.black)).foregroundStyle(c);Text(a).font(.system(size:8)).foregroundStyle(muted)}.padding(12).frame(maxWidth:.infinity,alignment:.leading).background(panel).clipShape(RoundedRectangle(cornerRadius:17)).overlay(RoundedRectangle(cornerRadius:17).stroke(line))}}
struct CheckRow:View{let a:String;let b:String;init(_ a:String,_ b:String){self.a=a;self.b=b};var body:some View{HStack{Circle().fill(green).frame(width:8,height:8);VStack(alignment:.leading){Text(a).font(.caption).fontWeight(.semibold);Text(b).font(.caption2).foregroundStyle(muted)};Spacer();Text("점검 항목").font(.system(size:8,weight:.bold)).foregroundStyle(cyan)}}}
struct Coverage:View{let s:String;let on:Bool;init(_ s:String,_ on:Bool){self.s=s;self.on=on};var body:some View{HStack{Text(s).font(.caption);Spacer();Text(on ? "ON":"OFF").font(.caption2).fontWeight(.bold).foregroundStyle(on ? green:muted)}}}
struct DarkField:View{let label:String;@Binding var text:String;init(_ l:String,text:Binding<String>){label=l;_text=text};var body:some View{TextField(label,text:$text).textInputAutocapitalization(.never).autocorrectionDisabled().padding(13).background(panel2).clipShape(RoundedRectangle(cornerRadius:12)).overlay(RoundedRectangle(cornerRadius:12).stroke(line)).foregroundStyle(.white)}}
struct ToggleRow:View{let a:String;let b:String;@Binding var on:Bool;init(_ a:String,_ b:String,_ on:Binding<Bool>){self.a=a;self.b=b;_on=on};var body:some View{Toggle(isOn:$on){VStack(alignment:.leading){Text(a).font(.caption).fontWeight(.semibold);Text(b).font(.caption2).foregroundStyle(muted)}}}}
struct InfoRow:View{let a:String;let b:String;init(_ a:String,_ b:String){self.a=a;self.b=b};var body:some View{HStack{Text(a).font(.caption).foregroundStyle(muted);Spacer();Text(b).font(.caption).fontWeight(.semibold)}}}
struct SignalRow:View {
 let s:RiskSignal;init(_ s:RiskSignal){self.s=s}
 var unknown:Bool{s.value.contains("미확인")||s.value.contains("확인 불가")}
 var reference:Bool{s.title.contains("입력")||s.title.contains("미검증")}
 var tint:Color{unknown ? muted:s.triggered ? amber:reference ? gold:green}
 var body:some View{HStack{Image(systemName:unknown||reference ? "info.circle":s.triggered ? "exclamationmark.circle":"checkmark.circle").foregroundStyle(tint);VStack(alignment:.leading){Text(s.title).font(.caption);Text(s.value).font(.caption2).foregroundStyle(muted)};Spacer();Text(unknown ? "미확인":s.triggered ? "+\(s.points)":reference ? "참고":"확인").font(.caption2).foregroundStyle(tint)}}
}
struct ExchangeMark:View {let name:String;init(_ name:String){self.name=name};var body:some View{Group{if let image=ExchangeCatalog.image(name){Image(uiImage:image).resizable().scaledToFit()}else{Text(String(name.prefix(2))).foregroundStyle(gold)}}.frame(width:42,height:42).padding(4).background(Color.black.opacity(0.4)).clipShape(RoundedRectangle(cornerRadius:10))}}
struct EvidencePicker:View {let label:String;@Binding var value:String;let options:[String];init(_ label:String,value:Binding<String>,options:[String]){self.label=label;_value=value;self.options=options};var body:some View{Picker(label,selection:$value){ForEach(options,id:\.self){Text($0)}}}}
struct RecordRow:View{let r:ScanRecord;let tap:()->Void;init(_ r:ScanRecord,_ tap:@escaping()->Void){self.r=r;self.tap=tap};var body:some View{Button(action:tap){HStack{ExchangeMark(r.exchange.name);VStack(alignment:.leading){Text(r.exchange.name).fontWeight(.bold);Text("UID "+maskUid(r.uid)).font(.caption2).foregroundStyle(muted)};Spacer();VStack{Text("\(r.score)").font(.system(size:20,weight:.black));Text(r.level).font(.system(size:9,weight:.bold))}.foregroundStyle(levelColor(r.level))}}.buttonStyle(.plain).padding(14).background(panel).clipShape(RoundedRectangle(cornerRadius:18)).overlay(RoundedRectangle(cornerRadius:18).stroke(line))}}
func Notice(_ s:String,_ c:Color)->some View{Text(s).font(.caption).foregroundStyle(c==red ? red:muted).padding(14).frame(maxWidth:.infinity,alignment:.leading).background(c.opacity(0.08)).clipShape(RoundedRectangle(cornerRadius:16)).overlay(RoundedRectangle(cornerRadius:16).stroke(c.opacity(0.25)))}
func levelColor(_ s:String?)->Color{s=="HIGH" ? red:s=="MEDIUM" ? amber:s=="LOW" ? green:gold}
func maskUid(_ s:String)->String{s.count<=4 ? String(repeating:"•",count:s.count):String(s.prefix(2))+String(repeating:"•",count:min(6,s.count-4))+String(s.suffix(2))}

