import SwiftUI
import PhotosUI
import ImageIO
import CryptoKit

let kycLabels:[String:String] = ["insufficient":"판독 근거 부족","review_required":"추가 확인 필요","no_mismatch_detected":"대조 항목 불일치 미검출","approved":"승인 표시","pending":"심사 중 표시","rejected":"거절 표시","not_verified":"미인증 표시","unknown":"판독 불가","match":"일치","mismatch":"불일치","unreadable":"판독 불가","blurry":"이미지가 흐림","cropped":"화면 일부가 잘림","glare":"빛 반사","small_text":"글자가 너무 작음","possible_edit":"편집 의심 단서 · 위조 확정 아님","conflicting_text":"화면 내 문구 충돌","exchange_mismatch":"거래소 이름 불일치","exchange_unreadable":"거래소 이름 판독 불가","uid_mismatch":"UID 불일치","uid_unreadable":"UID 판독 불가","country_mismatch":"KYC 국가 불일치","country_unreadable":"KYC 국가 판독 불가","unsupported_document":"KYC 상태 화면 필요 · 신분증 진위 검사 미지원","readability_limited":"읽을 수 있는 정보 부족","status_requires_review":"인증 상태 추가 확인 필요","unreviewed":"담당자 미검토","follow_up":"추가 증빙 요청","official_check_requested":"공식 확인 요청"]
func kycText(_ code:String)->String { kycLabels[code] ?? "확인 필요" }
enum KycError:LocalizedError { case message(String);var errorDescription:String? {if case .message(let text)=self{return text};return nil} }
struct KycReview:Codable,Identifiable {
 var id:String {requestId}
 let requestId:String
 let imageSha256:String
 let reviewedAt:String
 let model:String
 let assessment:String
 let displayedStatus:String
 let matches:[String:String]
 let issues:[String]
 let officialVerified:Bool
 let authenticity:String
 var humanDecision:String
 func validate() throws {
  guard !officialVerified,authenticity=="not_verified",["insufficient","review_required","no_mismatch_detected"].contains(assessment),["approved","pending","rejected","not_verified","unknown"].contains(displayedStatus),Set(matches.keys)==Set(["exchange","uid","country"]),matches.values.allSatisfy({["match","mismatch","unreadable"].contains($0)}),issues.count<=20,issues.allSatisfy({kycLabels[$0] != nil}),["unreviewed","follow_up","official_check_requested"].contains(humanDecision),imageSha256.range(of:"^[0-9a-f]{64}$",options:.regularExpression) != nil else {throw KycError.message("검토 응답 형식을 확인할 수 없습니다")}
  if assessment=="no_mismatch_detected" && (!issues.isEmpty || matches.values.contains(where:{$0 != "match"}) || displayedStatus != "approved") {throw KycError.message("검토 응답이 일치하지 않습니다")}
 }
}
func kycEndpoint(_ base:String)->URL? {
 guard let u=URLComponents(string:base.trimmingCharacters(in:.whitespacesAndNewlines)),u.scheme=="https",let host=u.host,!host.isEmpty,u.user==nil,u.password==nil,u.query==nil,u.fragment==nil else{return nil}
 var c=u;c.path=c.path.trimmingCharacters(in:CharacterSet(charactersIn:"/"));c.path="/"+(c.path.isEmpty ? "":c.path+"/")+"v1/kyc/reviews";return c.url
}
private final class NoKycRedirect:NSObject,URLSessionTaskDelegate {
 func urlSession(_ session:URLSession,task:URLSessionTask,willPerformHTTPRedirection response:HTTPURLResponse,newRequest request:URLRequest,completionHandler:@escaping(URLRequest?)->Void){completionHandler(nil)}
}
func kycImage(_ data:Data)throws->Data {
 guard data.count<=12*1024*1024,let source=CGImageSourceCreateWithData(data as CFData,nil),let image=CGImageSourceCreateThumbnailAtIndex(source,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceThumbnailMaxPixelSize:2048,kCGImageSourceCreateThumbnailWithTransform:true] as CFDictionary),let jpeg=UIImage(cgImage:image).jpegData(compressionQuality:0.88),jpeg.count<=4*1024*1024 else {throw KycError.message("12 MB 이하의 읽을 수 있는 이미지를 선택하세요")};return jpeg
}
func requestKycReview(base:String,token:String,image:Data,record:ScanRecord)async throws->KycReview {
 guard let url=kycEndpoint(base),token.count>=32 else{throw KycError.message("HTTPS 검토 서버와 접속 토큰을 확인하세요")}
 let id=UUID().uuidString,hash=SHA256.hash(data:image).map{String(format:"%02x",$0)}.joined()
 let body:[String:Any] = ["requestId":id,"consent":true,"expected":["exchange":record.exchange.name,"uid":record.uid,"country":record.country],"image":["mimeType":"image/jpeg","data":image.base64EncodedString()]]
 var req=URLRequest(url:url);req.httpMethod="POST";req.timeoutInterval=60;req.setValue("application/json",forHTTPHeaderField:"Content-Type");req.setValue("Bearer "+token,forHTTPHeaderField:"Authorization");req.httpBody=try JSONSerialization.data(withJSONObject:body)
 let config=URLSessionConfiguration.ephemeral;config.urlCache=nil;config.httpCookieStorage=nil;config.timeoutIntervalForResource=60
 let session=URLSession(configuration:config,delegate:NoKycRedirect(),delegateQueue:nil);defer{session.invalidateAndCancel()}
 let (data,response)=try await session.data(for:req)
 guard let http=response as? HTTPURLResponse else{throw KycError.message("검토 서버 응답을 확인하세요")}
 guard http.statusCode==200 else{throw KycError.message(http.statusCode==401 ? "검토 서버 접속 토큰을 확인하세요":http.statusCode==429 ? "요청이 많습니다. 잠시 후 직접 다시 시도하세요":"AI 검토를 완료하지 못했습니다 · HTTP \(http.statusCode)")}
 guard data.count<=65536 else{throw KycError.message("검토 응답이 너무 큽니다")}
 let r=try JSONDecoder().decode(KycReview.self,from:data);try r.validate();guard r.requestId==id,r.imageSha256==hash else{throw KycError.message("첨부 이미지와 응답이 일치하지 않습니다")};return r
}
struct KycReviewSummary:View {
 let review:KycReview
 var body:some View {VStack(alignment:.leading,spacing:8){
  Text(kycText(review.assessment)).font(.headline)
  Text("AI 화면 판독 · 진위 및 공식 승인 미검증").font(.caption).foregroundStyle(.orange)
  Text("표시된 상태: "+kycText(review.displayedStatus))
  ForEach(["exchange","uid","country"],id:\.self){key in Text((["exchange":"거래소","uid":"UID","country":"KYC 국가"][key] ?? key)+": "+kycText(review.matches[key] ?? "unreadable"))}
  ForEach(review.issues,id:\.self){Text("• "+kycText($0))}
  Text(kycText(review.humanDecision)+" · "+review.reviewedAt.prefix(19).replacingOccurrences(of:"T",with:" ")+" UTC").font(.caption)
  Text("이미지 지문 "+review.imageSha256.prefix(12)+" · "+review.model).font(.caption2)
 }}
}
struct KycReviewSheet:View {
 let record:ScanRecord
 let persisted:Bool
 let save:(KycReview)->Void
 @Environment(\.dismiss) var dismiss
 @AppStorage("ers_kyc_endpoint") private var endpoint=""
 @State private var token=""
 @State private var selection:PhotosPickerItem?
 @State private var image:Data?
 @State private var consent=false
 @State private var busy=false
 @State private var error=""
 @State private var result:KycReview?
 @State private var job:Task<Void,Never>?
 var body:some View {
  NavigationStack {ScrollView{VStack(alignment:.leading,spacing:16){
   Text(record.exchange.name+" · UID "+maskUid(record.uid)).font(.headline)
   Text("KYC 상태 화면을 선택하세요. AI는 표시 문구와 UID·국가를 대조합니다. 신분증 진위·얼굴 인증·거래소 실제 승인은 확인하지 않습니다.").font(.caption)
   TextField("HTTPS 검토 서버 주소",text:$endpoint).textInputAutocapitalization(.never).autocorrectionDisabled().disabled(busy)
   SecureField("검토 서버 접속 토큰",text:$token).textInputAutocapitalization(.never).autocorrectionDisabled().disabled(busy)
   Text("서버 연결 필요 · 접속 토큰은 저장하지 않습니다. OpenAI API 키를 입력하지 마세요.").font(.caption)
   PhotosPicker(selection:$selection,matching:.images){Label(image==nil ? "증빙 이미지 선택":"이미지 다시 선택",systemImage:"photo")}.disabled(busy)
   if let data=image,let ui=UIImage(data:data){Image(uiImage:ui).resizable().scaledToFit().frame(maxHeight:280)}
   Text("전송 항목: 선택 이미지, 거래소, UID, KYC 국가. 지정 서버가 이미지 내용을 OpenAI에 전달하고 계정 입력값은 서버에서 대조합니다. 불필요한 개인정보는 선택 전에 가려 주세요.").font(.caption)
   Toggle("이미지와 위 정보를 검토 서버에 전송하는 데 동의합니다",isOn:$consent).disabled(busy)
   Text("ERS와 검토 서버는 원본 이미지를 저장하지 않습니다. AI 제공자 보관 정책은 서버 운영자가 확인해야 합니다.").font(.caption)
   if !error.isEmpty{Text(error).foregroundStyle(.red)}
   Button(busy ? "처리 중…":"AI 검토 요청"){
    guard let image else{return};busy=true;error="";result=nil;let server=endpoint,key=token.trimmingCharacters(in:.whitespacesAndNewlines)
    job=Task { @MainActor in defer{busy=false};do{let review=try await requestKycReview(base:server,token:key,image:image,record:record);if !Task.isCancelled{result=review}}catch{if !Task.isCancelled{self.error=error.localizedDescription}} }
   }.buttonStyle(.borderedProminent).disabled(busy || !consent || image==nil || token.count<32 || kycEndpoint(endpoint)==nil)
   if let r=result{Divider();KycReviewSummary(review:r);Picker("담당자 검토",selection:Binding(get:{result?.humanDecision ?? "unreviewed"},set:{result?.humanDecision=$0})){Text("미검토").tag("unreviewed");Text("추가 증빙 요청").tag("follow_up");Text("공식 확인 요청").tag("official_check_requested")};Button(persisted ? "검토 결과 저장":"현재 세션에 보관"){if let result{save(result);dismiss()}}.buttonStyle(.borderedProminent)}
   if !(record.kycReviews ?? []).isEmpty{Divider();Text("검토 이력 · 최근 10건").font(.headline);ForEach(record.kycReviews ?? []){KycReviewSummary(review:$0);Divider()}}
   if !persisted{Text("계정 저장이 꺼져 있어 검토 결과는 현재 세션에만 보관됩니다.").font(.caption)}
   Text("저장 시 결과·시간·이미지 지문만 보호된 계정 기록에 보관합니다. 계정 기록 삭제 시 검토 이력도 삭제됩니다.").font(.caption)
  }.textFieldStyle(.roundedBorder).padding()}.navigationTitle("AI KYC 검토").toolbar{ToolbarItem(placement:.topBarTrailing){Button("닫기"){job?.cancel();dismiss()}}}}
  .preferredColorScheme(.dark)
  .onChange(of:endpoint){_,_ in consent=false;result=nil}
  .onChange(of:token){_,_ in consent=false;result=nil}
  .onChange(of:selection){_,item in
   job?.cancel();consent=false;result=nil;image=nil;error="";busy=true
   job=Task { @MainActor in defer{busy=false};do{if let raw=try await item?.loadTransferable(type:Data.self){let jpeg=try kycImage(raw);if !Task.isCancelled{image=jpeg}}}catch{if !Task.isCancelled{self.error="이미지를 읽지 못했습니다. 12 MB 이하 이미지를 선택하세요."}} }
  }.onDisappear{job?.cancel();image=nil;token=""}
 }
}
