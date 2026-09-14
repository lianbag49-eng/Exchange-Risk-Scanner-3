import SwiftUI

private let bg=Color(red:00.02,green:00.04,blue:00.065)
private let panel=Color(red:00.045,green:00.082,blue:00.12)
private let panel2=Color(red:00.07,green:00.12,blue:00.17)
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
                TabView(selection:$tab) {
                    HomeView(start:{ tab=1 }).tag(0)
                    ScanView().tag(1)
                    HistoryView().tag(2)
                    SettingsView().tag(3)
                }.tabViewStyle(.page(indexDisplayMode:.never))
                HStack {
                    Nav("HOME",0);Nav("SCAN",1);Nav("HISTORY",2);Nav("SETTINGS",3)
                }.padding(8).background(panel.overlay(Rectangle().frame(height:1).foregroundStyle(line),alignment:.top))
            }
        }.tint(gold).foregroundStyle(.white)
    }
    @ViewBuilder func Nav(_ title:String,_ index:Int)->some View {
        Button { tab=index } label: {
            VStack(spacing:5) {
                Circle().fill(tab==index ? gold:line).frame(width:tab==index ? 7:5,height:tab==index ? 7:5)
                Text(title).font(.system(size:9,weight:tab==index ? .bold:.regular)).foregroundStyle(tab==index ? gold2:muted)
            }.frame(maxWidth:.infinity).padding(.vertical,7).background(tab==index ? gold.opacity(00.1):.clear).clipShape(RoundedRectangle(cornerRadius:13))
        }
    }
}

struct Header: View {
    var body: some View {
        HStack {
            ZStack { RoundedRectangle(cornerRadius:15).fill(LinearGradient(colors:[gold.opacity(0.25),panel2],startPoint:.topLeading,endPoint:.bottomTrailing));RoundedRectangle(cornerRadius:15).stroke(gold.opacity(0.7));Text("E").font(.system(size:24,weight:.black)).foregroundStyle(gold2) }.frame(width:48,height:48)
            VStack(alignment:.leading,spacing:1){Text("ERS").font(.system(size:21,weight:.black)).tracking(2).foregroundStyle(gold2);Text("EXCHANGE RISK SCANNER").font(.system(size:9)).tracking(10.2).foregroundStyle(muted)}
            Spacer()
            Text("SYSTEM SECURE").font(.system(size:8,weight:.bold)).foregroundStyle(green).padding(.horizontal,9).padding(.vertical,6).background(green.opacity(0.09)).clipShape(Capsule()).overlay(Capsule().stroke(green.opacity(0.3)))
        }.padding(.horizontal,18).padding(.vertical,12)
    }
}

struct HomeView: View {
    @EnvironmentObject var store:ERSStore
    let start:()->Void
    @State private var selected:ScanRecord?
    var latest:ScanRecord?{store.records.first}
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:14) {
                Text("보안 상태를 한눈에 확인하세요").font(.system(size:24,weight:.black))
                Text("거래소 계정과 현재 기기 환경을 점검합니다.").font(.system(size:13)).foregroundStyle(muted)
                CardBox {
                    HStack {
                        VStack(alignment:.leading){LabelText("CURRENT STATUS");Text(latest?.level ?? "READY").font(.system(size:34,weight:.black)).foregroundStyle(levelColor(latest?.level));Text(latest.map{$0.exchange.name+" · UID "+maskUid($0.uid)} ?? "첫 번째 보안 스캔을 시작하세요").font(.caption).foregroundStyle(muted)}
                        Spacer();ScoreView(score:latest?.score,color:levelColor(latest?.level))
                    }
                    Divider().background(line)
                    Button(latest==nil ? "START SECURITY SCAN":"VIEW LATEST REPORT"){if let latest{selected=latest}else{start()}}.font(.system(size:14,weight:.black)).foregroundStyle(bg).frame(maxWidth:.infinity).padding().background(gold).clipShape(RoundedRectangle(cornerRadius:14))
                }
                LabelText("OVERVIEW")
                HStack { Metric("SCANS","\(store.records.count)",cyan);Metric("SAFE","\(store.records.filter{$0.level=="LOW"}.count)",green);Metric("RISK","\(store.records.filter{$0.level != "LOW"}.count)",amber)}
                LabelText("QUICK CHECK")
                CardBox { CheckRow("Network Integrity","연결·네트워크 상태");CheckRow("Device Integrity","탈옥·시뮬레이터");CheckRow("Identity Region","KYC·기기 국가");CheckRow("Security State","데이터 보호 상태") }
                if !store.records.isEmpty { LabelText("RECENT ACTIVITY");ForEach(store.records.prefix(3)){r in RecordRow(r){selected=r}} }
                Notice("ERS는 기기·계정 환경 자가 점검 도구입니다. 거래소 비공개 규칙을 추정하거나 우회하지 않습니다.",gold)
            }.padding(.horizontal,18).padding(.bottom,16)
        }.sheet(item:$selected){ReportView(record:$0)}
    }
}

struct ScanView: View {
    @EnvironmentObject var store:ERSStore
    @State private var exchange=Exchange(name:"Binance",mark:"BI")
    @State private var uid=""
    @State private var country="KR"
    @State private var custom=""
    @State private var error=""
    @State private var result:ScanRecord?
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:14) {
                PageTitle("RISK SCAN","스캔 대상과 기준을 설정하세요")
                HStack(spacing:7){ForEach(0..<3){i in Capsule().fill(i < (result==nil ? 1:3) ? gold:line).frame(height:3)}}
                LabelText("SCAN TARGET")
                CardBox {
                    Picker("거래소 선택",selection:$exchange){ForEach(store.exchanges){Text($0.name).tag($0)}}.pickerStyle(.menu).foregroundStyle(.white)
                    Divider().background(line)
                    if exchange.name=="기타 거래소" { DarkField("거래소 이름",text:$custom) }
                    DarkField("거래소 UID",text:$uid)
                    DarkField("KYC 국가 코드",text:$country)
                    Text("이메일이 아닌 거래소 UID와 두 자리 국가 코드를 입력하세요.").font(.caption2).foregroundStyle(muted)
                }
                LabelText("SCAN COVERAGE")
                CardBox { Coverage("VPN 및 네트워크",true);Coverage("기기 무결성",true);Coverage("개발 환경",true);Coverage("국가 일치",true);Coverage("강화 분석",store.strictMode) }
                if !error.isEmpty { Notice(error,red) }
                Button(result==nil ? "RUN FULL SCAN":"SCAN AGAIN") {
                    let name=exchange.name=="기타 거래소" ? custom.trimmingCharacters(in:.whitespaces):exchange.name
                    if name.isEmpty {error="거래소 이름을 입력해 주세요."}
                    else if uid.count<3 {error="거래소 UID를 3자 이상 입력해 주세요."}
                    else if country.count != 2 {error="KYC 국가 코드를 두 자리로 입력해 주세요."}
                    else {error="";result=store.scan(exchange:exchange,uid:uid,country:country)}
                }.font(.system(size:14,weight:.black)).foregroundStyle(bg).frame(maxWidth:.infinity).padding().background(gold).clipShape(RoundedRectangle(cornerRadius:16))
                if let result { InlineReport(result) }
            }.padding(.horizontal,18).padding(.bottom,16)
        }.onAppear{if let first=store.exchanges.first,exchange.name=="Binance"{exchange=first}}
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
        }.sheet(item:$selected){ReportView(record:$0)}
    }
}

struct SettingsView:View {
    @EnvironmentObject var store:ERSStore
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:14) {
                PageTitle("SETTINGS","ERS 작동 방식을 설정하세요")
                LabelText("SCAN PREFERENCES")
                CardBox {ToggleRow("스캔 기록 자동 저장","완료 결과를 기록에 보관",$store.autoSave);ToggleRow("강화 분석 모드","민감한 보안 기준",$store.strictMode);ToggleRow("보안 도움말 표시","결과에 권장 조치",$store.showTips)}
                LabelText("APP INFORMATION")
                CardBox {InfoRow("Application","Exchange Risk Scanner");InfoRow("Version","10.4 iOS");InfoRow("Engine","ERS Device Guard");InfoRow("Data Mode","On-device only")}
                LabelText("PRIVACY & SECURITY")
                Notice("스캔 데이터는 기기에서만 분석되며 입력한 UID 원문을 외부 서버로 전송하지 않습니다.",green)
            }.padding(.horizontal,18).padding(.bottom,16)
        }
    }
}

struct ReportView:View {
    let record:ScanRecord
    @Environment(\.dismiss) var dismiss
    var body:some View {
        NavigationStack {
            ScrollView {VStack(alignment:.leading,spacing:12){LabelText("SECURITY REPORT");Text(record.level+" · \(record.score)/100").font(.system(size:28,weight:.black)).foregroundStyle(levelColor(record.level));CardBox{InfoRow("Exchange",record.exchange.name);InfoRow("UID",maskUid(record.uid));InfoRow("KYC Country",record.country);InfoRow("Device Country",record.deviceCountry.isEmpty ? "-":record.deviceCountry)};LabelText("ANALYSIS");CardBox{ForEach(record.signals){SignalRow($0)}}}.padding(18)}
            .background(bg).toolbar{ToolbarItem(placement:.topBarTrailing){Button("완료"){dismiss()}}}
        }.preferredColorScheme(.dark)
    }
}

struct InlineReport:View {let r:ScanRecord;init(_ r:ScanRecord){self.r=r};var body:some View{CardBox{HStack{VStack(alignment:.leading){LabelText("SCAN COMPLETE");Text(r.level+" RISK").font(.system(size:26,weight:.black)).foregroundStyle(levelColor(r.level));Text(r.exchange.name).font(.caption).foregroundStyle(muted)};Spacer();ScoreView(score:r.score,color:levelColor(r.level))};Divider().background(line);ForEach(r.signals){SignalRow($0)}}}}
struct ScoreView:View{let score:Int?;let color:Color;var body:some View{ZStack{Circle().fill(color.opacity(0.1));Circle().stroke(color.opacity(0.55),lineWidth:2);VStack{Text(score.map(String.init) ?? "—").font(.system(size:22,weight:.black));Text(score==nil ? "READY":"SCORE").font(.system(size:7,weight:.bold))}.foregroundStyle(color)}.frame(width:67,height:67)}}
struct LabelText:View{let text:String;init(_ t:String){text=t};var body:some View{Text(text).font(.system(size:10,weight:.bold)).tracking(10.2).foregroundStyle(gold2)}}
struct PageTitle:View{let a:String;let b:String;init(_ a:String,_ b:String){self.a=a;self.b=b};var body:some View{VStack(alignment:.leading){LabelText(a);Text(b).font(.system(size:21,weight:.black))}}}
struct CardBox<Content:View>:View{let content:Content;init(@ViewBuilder content:()->Content){self.content=content()};var body:some View{VStack(alignment:.leading,spacing:12){content}.padding(16).frame(maxWidth:.infinity,alignment:.leading).background(panel).clipShape(RoundedRectangle(cornerRadius:22)).overlay(RoundedRectangle(cornerRadius:22).stroke(line))}}
struct Metric:View{let a:String;let b:String;let c:Color;init(_ a:String,_ b:String,_ c:Color){self.a=a;self.b=b;self.c=c};var body:some View{VStack(alignment:.leading){Text(b).font(.system(size:21,weight:.black)).foregroundStyle(c);Text(a).font(.system(size:8)).foregroundStyle(muted)}.padding(12).frame(maxWidth:.infinity,alignment:.leading).background(panel).clipShape(RoundedRectangle(cornerRadius:17)).overlay(RoundedRectangle(cornerRadius:17).stroke(line))}}
struct CheckRow:View{let a:String;let b:String;init(_ a:String,_ b:String){self.a=a;self.b=b};var body:some View{HStack{Circle().fill(green).frame(width:8,height:8);VStack(alignment:.leading){Text(a).font(.caption).fontWeight(.semibold);Text(b).font(.caption2).foregroundStyle(muted)};Spacer();Text("SECURE").font(.system(size:8,weight:.bold)).foregroundStyle(cyan)}}}
struct Coverage:View{let s:String;let on:Bool;init(_ s:String,_ on:Bool){self.s=s;self.on=on};var body:some View{HStack{Text(s).font(.caption);Spacer();Text(on ? "ON":"OFF").font(.caption2).fontWeight(.bold).foregroundStyle(on ? green:muted)}}}
struct DarkField:View{let label:String;@Binding var text:String;init(_ l:String,text:Binding<String>){label=l;_text=text};var body:some View{TextField(label,text:$text).textInputAutocapitalization(.never).autocorrectionDisabled().padding(13).background(panel2).clipShape(RoundedRectangle(cornerRadius:12)).overlay(RoundedRectangle(cornerRadius:12).stroke(line)).foregroundStyle(.white)}}
struct ToggleRow:View{let a:String;let b:String;@Binding var on:Bool;init(_ a:String,_ b:String,_ on:Binding<Bool>){self.a=a;self.b=b;_on=on};var body:some View{Toggle(isOn:$on){VStack(alignment:.leading){Text(a).font(.caption).fontWeight(.semibold);Text(b).font(.caption2).foregroundStyle(muted)}}}}
struct InfoRow:View{let a:String;let b:String;init(_ a:String,_ b:String){self.a=a;self.b=b};var body:some View{HStack{Text(a).font(.caption).foregroundStyle(muted);Spacer();Text(b).font(.caption).fontWeight(.semibold)}}}
struct SignalRow:View{let s:RiskSignal;init(_ s:RiskSignal){self.s=s};var body:some View{HStack{Circle().fill(s.triggered ? red:green).frame(width:7,height:7);VStack(alignment:.leading){Text(s.title).font(.caption);Text(s.value).font(.caption2).foregroundStyle(muted)};Spacer();Text(s.triggered ? "+\(s.points)":"PASS").font(.caption2).fontWeight(.bold).foregroundStyle(s.triggered ? red:green)}}}
struct RecordRow:View{let r:ScanRecord;let tap:()->Void;init(_ r:ScanRecord,_ tap:@escaping()->Void){self.r=r;self.tap=tap};var body:some View{Button(action:tap){HStack{ZStack{RoundedRectangle(cornerRadius:12).fill(gold.opacity(0.1));Text(r.exchange.mark).font(.caption).fontWeight(.black).foregroundStyle(gold2)}.frame(width:42,height:42);VStack(alignment:.leading){Text(r.exchange.name).fontWeight(.bold);Text("UID "+maskUid(r.uid)).font(.caption2).foregroundStyle(muted)};Spacer();VStack{Text("\(r.score)").font(.system(size:20,weight:.black));Text(r.level).font(.system(size:9,weight:.bold))}.foregroundStyle(levelColor(r.level))}}.buttonStyle(.plain).padding(14).background(panel).clipShape(RoundedRectangle(cornerRadius:18)).overlay(RoundedRectangle(cornerRadius:18).stroke(line))}}
func Notice(_ s:String,_ c:Color)->some View{Text(s).font(.caption).foregroundStyle(c==red ? red:muted).padding(14).frame(maxWidth:.infinity,alignment:.leading).background(c.opacity(0.08)).clipShape(RoundedRectangle(cornerRadius:16)).overlay(RoundedRectangle(cornerRadius:16).stroke(c.opacity(0.25)))}
func levelColor(_ s:String?)->Color{s=="HIGH" ? red:s=="MEDIUM" ? amber:s=="LOW" ? green:gold}
func maskUid(_ s:String)->String{s.count<=4 ? s:String(s.prefix(2))+String(repeating:"•",count:min(6,s.count-4))+String(s.suffix(2))}
