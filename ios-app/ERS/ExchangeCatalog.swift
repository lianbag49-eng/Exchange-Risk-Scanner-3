import Foundation
import UIKit
struct CatalogEntry: Decodable { let name:String; let rank:Int; let logoBase64:String }
struct CatalogData: Decodable { let exchanges:[CatalogEntry] }
enum ExchangeCatalog {
 static let entries:[CatalogEntry] = {
  guard let url=Bundle.main.url(forResource:"exchanges",withExtension:"json"),let data=try? Data(contentsOf:url),let catalog=try? JSONDecoder().decode(CatalogData.self,from:data) else { return [] }
  return catalog.exchanges
 }()
 static let images:[String:UIImage] = Dictionary(uniqueKeysWithValues:entries.compactMap { e in
  guard let data=Data(base64Encoded:e.logoBase64),let image=UIImage(data:data) else {return nil}
  return (e.name.lowercased(),image)
 })
 static func image(_ name:String)->UIImage? { images[(name=="Gate.io" ? "Gate":name).lowercased()] }
}
struct AccountEvidence {
 var kyc="미확인",restriction="미확인",unusualLogin="미확인",twoFactor="미확인",loginCountry="",notice=""
 func signals(country:String)->[RiskSignal] {
  func item(_ title:String,_ value:String,_ points:Int=0)->RiskSignal { RiskSignal(title:title,value:value,points:points,triggered:points>0) }
  var result=[item("KYC 상태 · 사용자 입력",kyc,kyc=="거절" ? 20:0),item("계정 제한 · 사용자 입력",restriction,restriction=="제한 있음" ? 30:0),item("낯선 로그인 · 사용자 입력",unusualLogin,unusualLogin=="있음" ? 30:0),item("2단계 인증 · 사용자 입력",twoFactor,twoFactor=="미설정" ? 10:0),item("최근 로그인 국가 · 사용자 입력",loginCountry.isEmpty ? "미확인":loginCountry)]
  if !loginCountry.isEmpty && loginCountry.uppercased() != country.uppercased() { result.append(item("로그인/KYC 국가 차이 · 입력값 비교",loginCountry+" / "+country+" · 정상 사유 확인 필요",10)) }
  let words=["withdrawal suspended","withdrawals suspended","account restricted","account frozen","verification failed","kyc rejected","unusual login","출금 제한","출금 정지","계정 제한","계정 동결","인증 실패","인증 거절","비정상 로그인"].filter{notice.lowercased().contains($0)}
  result.append(item("거래소 안내문 분석 · 진위 미검증",notice.isEmpty ? "미확인":words.isEmpty ? "위험 문구 미검출 · 안전·진위 확인 아님":"주의 문구: "+words.joined(separator:", "),words.isEmpty ? 0:15))
  result.append(item("KYC 진위·실제 제한 여부","미확인 · 거래소 공식 응답 필요"))
  return result
 }
}
