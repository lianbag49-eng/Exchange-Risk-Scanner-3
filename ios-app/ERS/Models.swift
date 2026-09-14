import Foundation
import UIKit
import LocalAuthentication

struct Exchange: Identifiable, Hashable {
    let id = UUID()
    let name: String
    let mark: String
}

struct RiskSignal: Identifiable {
    let id = UUID()
    let title: String
    let value: String
    let points: Int
    let triggered: Bool
}

struct ScanRecord: Identifiable {
    let id = UUID()
    let exchange: Exchange
    let uid: String
    let country: String
    let deviceCountry: String
    let score: Int
    let level: String
    let signals: [RiskSignal]
    let date: Date
}

final class ERSStore: ObservableObject {
    @Published var records: [ScanRecord] = []
    @Published var autoSave = true
    @Published var strictMode = false
    @Published var showTips = true

    let exchanges = ["Binance","Bybit","OKX","Bitget","BingX","Toobit","CoinW","Deepcoin","Gate.io","MEXC","KuCoin","LBank","OURBIT","Tapbit","MGBX","기타 거래소"]
        .map { Exchange(name: $0, mark: String($0.prefix(2)).uppercased()) }

    func scan(exchange: Exchange, uid: String, country: String) -> ScanRecord {
        let deviceCountry = Locale.current.region?.identifier ?? ""
        let jailbroken = Self.isJailbreakSuspected()
        let simulator = ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != nil
        let passcode = LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
        let mismatch = !country.isEmpty && !deviceCountry.isEmpty && country.uppercased() != deviceCountry.uppercased()
        let signals = [
            RiskSignal(title:"탈옥/시스템 변조 의심",value:jailbroken ? "감지됨":"감지되지 않음",points:jailbroken ? 40:0,triggered:jailbroken),
            RiskSignal(title:"시뮬레이터 환경",value:simulator ? "감지됨":"감지되지 않음",points:simulator ? 40:0,triggered:simulator),
            RiskSignal(title:"기기 보호 상태",value:passcode ? "정상":"확인 필요",points:passcode ? 0:10,triggered:!passcode),
            RiskSignal(title:"입력 국가 / 기기 언어 지역",value:country.uppercased()+" / "+deviceCountry,points:0,triggered:mismatch),
            RiskSignal(title:"보안 데이터 보호",value:"활성",points:0,triggered:false)
        ]
        let score = min(100, signals.reduce(0){$0+$1.points})
        let level = score >= 60 ? "HIGH" : score >= 30 ? "MEDIUM" : "LOW"
        let record = ScanRecord(exchange:exchange,uid:uid,country:country.uppercased(),deviceCountry:deviceCountry,score:score,level:level,signals:signals,date:Date())
        if autoSave { records.insert(record,at:0) }
        return record
    }

    static func isJailbreakSuspected() -> Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        return ["/Applications/Cydia.app","/Library/MobileSubstrate/MobileSubstrate.dylib","/bin/bash","/usr/sbin/sshd"].contains { FileManager.default.fileExists(atPath:$0) }
        #endif
    }
}
