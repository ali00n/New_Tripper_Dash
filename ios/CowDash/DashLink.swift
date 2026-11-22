import Foundation
import UIKit
import Combine

/// Conversa com o painel pelas portas UDP 2000 (controle) e 2002 (auxiliar).
/// Fase iF0: descobrir se o iPhone, sem permissões especiais da Apple, consegue autenticar e mandar seta.
final class DashLink: ObservableObject {
    @Published var lines: [String] = []
    @Published var authenticated = false

    /// Para onde mandar: "broadcast" (como no Android) ou "direto" (192.168.1.1). O iPhone pode barrar o broadcast.
    var useBroadcast = false
    var ssid = ""

    private var controlFd: Int32 = -1
    private var auxFd: Int32 = -1
    private var seq = 0
    private let queue = DispatchQueue(label: "dash.send")
    private var modulus: [UInt8]?
    private var exponent: [UInt8]?
    private var aesKey: [UInt8]?
    private var arrowTimer: Timer?

    func log(_ s: String) {
        let t = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        DispatchQueue.main.async {
            self.lines.append("\(t) \(s)")
            if self.lines.count > 400 { self.lines.removeFirst(self.lines.count - 400) }
        }
    }

    func connect() {
        stop()
        guard !ssid.isEmpty else { log("Digite o nome do Wi-Fi do painel (RE_...)"); return }
        controlFd = openSocket(port: 2000)
        auxFd = openSocket(port: 2002)
        guard controlFd >= 0 else { log("ERRO: não abriu a porta 2000"); return }
        log("Portas abertas. Enviando para \(useBroadcast ? "192.168.1.255 (broadcast)" : "192.168.1.1 (direto)")")
        receive(controlFd, "2000")
        if auxFd >= 0 { receive(auxFd, "2002") }
        for f in DashProtocol.openingBurst(deviceName: UIDevice.current.name) { send("abertura", f) }
    }

    func stop() {
        arrowTimer?.invalidate()
        arrowTimer = nil
        if controlFd >= 0 { close(controlFd) }
        if auxFd >= 0 { close(auxFd) }
        controlFd = -1
        auxFd = -1
        modulus = nil; exponent = nil; aesKey = nil
        DispatchQueue.main.async { self.authenticated = false }
    }

    func startTestArrow() {
        send("início da navegação", DashProtocol.navStart())
        arrowTimer?.invalidate()
        arrowTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.send("seta", DashProtocol.testArrow(), quiet: true)
        }
        log("Mandando seta de teste a cada 1 s")
    }

    // MARK: - Envio e recepção

    func send(_ label: String, _ tlvs: [Tlv], quiet: Bool = false) {
        queue.async {
            guard self.controlFd >= 0 else { self.log("(sem conexão) \(label)"); return }
            self.seq = (self.seq + 1) & 0xFF
            let bytes = DashProtocol.frame(seq: self.seq, tlvs)
            var addr = sockaddr_in()
            addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = UInt16(2000).bigEndian
            inet_pton(AF_INET, self.useBroadcast ? "192.168.1.255" : "192.168.1.1", &addr.sin_addr)
            let sent = bytes.withUnsafeBytes { buf in
                withUnsafePointer(to: &addr) { p in
                    p.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(self.controlFd, buf.baseAddress, buf.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            if sent < 0 {
                self.log("ERRO envio \(label): \(String(cString: strerror(errno))) (errno \(errno))")
            } else if !quiet {
                self.log("→ \(label): \(DashProtocol.hex(bytes))")
            }
            usleep(20_000)
        }
    }

    private func openSocket(port: UInt16) -> Int32 {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return -1 }
        var yes: Int32 = 1
        let size = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, size)
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &yes, size)
        if setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, size) != 0 {
            log("Aviso: SO_BROADCAST recusado (\(String(cString: strerror(errno))))")
        }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr = in_addr(s_addr: 0)
        let r = withUnsafePointer(to: &addr) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        if r != 0 {
            log("ERRO bind \(port): \(String(cString: strerror(errno)))")
            close(fd)
            return -1
        }
        return fd
    }

    private func receive(_ fd: Int32, _ port: String) {
        Thread.detachNewThread { [weak self] in
            var buf = [UInt8](repeating: 0, count: 8192)
            let cap = buf.count
            while let self = self, fd == self.controlFd || fd == self.auxFd {
                var from = sockaddr_in()
                var len = socklen_t(MemoryLayout<sockaddr_in>.size)
                let n = withUnsafeMutablePointer(to: &from) { p in
                    p.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &buf, cap, 0, $0, &len) }
                }
                if n <= 0 { break }
                let data = Array(buf[0..<n])
                let ip = String(cString: inet_ntoa(from.sin_addr))
                // eco dos nossos próprios pacotes (broadcast volta pra gente)
                if ip != "192.168.1.1" && data.count > 16 && data[12] == 0x4B && data[13] == 0x31 { continue }
                let tlvs = DashProtocol.parse(data)
                if tlvs.allSatisfy({ $0.group == 1 || $0.group == 12 }) { continue } // telemetria
                self.log("← [\(port) de \(ip)] \(DashProtocol.hex(data))")
                for t in tlvs { self.handle(t) }
            }
            self?.log("Recepção \(port) encerrada")
        }
    }

    // MARK: - Autenticação (igual ao Android)

    private func handle(_ t: Tlv) {
        guard t.group == 7 else { return }
        if t.id == 1 {
            if t.firstByte != 1 {
                log("✖ Painel RECUSOU a autenticação (código \(t.firstByte))")
                modulus = nil; exponent = nil; aesKey = nil
            } else if aesKey != nil && !authenticated {
                log("✔ AUTENTICADO no painel \(ssid)")
                DispatchQueue.main.async { self.authenticated = true }
                send("relógio", [DashProtocol.clock()])
            }
            return
        }
        guard !t.data.isEmpty else { return }
        if t.data.count > 8 { modulus = t.data; log("   chave RSA: módulo \(t.data.count * 8) bits") }
        else { exponent = t.data; log("   chave RSA: expoente") }
        if let m = modulus, let e = exponent, aesKey == nil { sendSessionKey(m, e) }
    }

    private func sendSessionKey(_ m: [UInt8], _ e: [UInt8]) {
        var key = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, key.count, &key)
        do {
            let enc = try RSAKey.encrypt(modulus: m, exponent: e, plain: Array(ssid.utf8) + key)
            aesKey = key
            send("chave de sessão", [Tlv(group: 8, id: 0, data: enc)])
        } catch {
            log("ERRO ao cifrar a chave: \(error)")
        }
    }
}
