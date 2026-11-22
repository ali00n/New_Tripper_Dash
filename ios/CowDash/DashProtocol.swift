import Foundation

/// Formato das mensagens do painel Tripper Dash (quadro + TLVs), igual ao app Android.
struct Tlv: CustomStringConvertible {
    let group: UInt8
    let id: UInt8
    let data: [UInt8]

    static func byte(_ g: UInt8, _ i: UInt8, _ v: Int) -> Tlv { Tlv(group: g, id: i, data: [UInt8(truncatingIfNeeded: v)]) }
    static func short(_ g: UInt8, _ i: UInt8, _ v: Int) -> Tlv {
        Tlv(group: g, id: i, data: [UInt8(truncatingIfNeeded: v >> 8), UInt8(truncatingIfNeeded: v)])
    }
    static func text(_ g: UInt8, _ i: UInt8, _ s: String, max: Int) -> Tlv {
        Tlv(group: g, id: i, data: DashProtocol.latin1(String(s.prefix(max))) + [0])
    }
    var firstByte: Int { data.first.map { Int($0) } ?? -1 }
    var description: String { String(format: "%02X %02X [%@]", group, id, DashProtocol.hex(data)) }
}

enum DashProtocol {
    static let yes = 0x55
    static let no = 0xAA
    private static let headerTlv: [UInt8] = [0, 0, 0, 0, 2, 1, 0, 5, 0x4B, 0x31, 0x47, 0x20] // ... "K1G "

    static func latin1(_ s: String) -> [UInt8] {
        s.unicodeScalars.map { $0.value <= 255 ? UInt8($0.value) : UInt8(ascii: "?") }
    }

    static func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02X", $0) }.joined(separator: " ") }

    static func frame(seq: Int, _ tlvs: [Tlv]) -> [UInt8] {
        let startsWithBrightness = tlvs.first.map { $0.group == 6 && $0.id == 8 } ?? false
        let count = tlvs.count + (startsWithBrightness ? 0 : 1)
        var out: [UInt8] = [0, 0, UInt8((count >> 8) & 0xFF), UInt8(count & 0xFF)]
        out += headerTlv
        out.append(UInt8(seq & 0xFF))
        for t in tlvs {
            out += [t.group, t.id, UInt8((t.data.count >> 8) & 0xFF), UInt8(t.data.count & 0xFF)]
            out += t.data
        }
        out[0] = UInt8((out.count >> 8) & 0xFF)
        out[1] = UInt8(out.count & 0xFF)
        return out
    }

    static func parse(_ f: [UInt8]) -> [Tlv] {
        var result: [Tlv] = []
        guard f.count >= 8 else { return result }
        var expected = (Int(f[2]) << 8) | Int(f[3])
        if expected <= 0 { expected = Int.max }
        var pos = 8
        while result.count < expected && pos + 4 <= f.count {
            let len = (Int(f[pos + 2]) << 8) | Int(f[pos + 3])
            let end = min(pos + 4 + len, f.count)
            result.append(Tlv(group: f[pos], id: f[pos + 1], data: Array(f[(pos + 4)..<end])))
            pos = end
        }
        return result
    }

    static func distance(_ meters: Int) -> (Int, Int) {
        let m = max(meters, 0)
        let r = m <= 100 ? ((m + 9) / 10) * 10 : ((m + 49) / 50) * 50
        if r <= 999 { return (r, 48) }
        return (min(65535, Int((Double(r) / 100).rounded())), 16)
    }

    static func clock() -> Tlv {
        let c = Calendar.current.dateComponents([.hour, .minute, .second], from: Date())
        return Tlv(group: 6, id: 6, data: [UInt8(c.hour ?? 0), UInt8(c.minute ?? 0), UInt8(c.second ?? 0)])
    }

    static func openingBurst(deviceName: String) -> [[Tlv]] {
        [
            [.byte(8, 4, 1)],
            [.text(6, 11, deviceName, max: 19)],
            [clock()],
            [.byte(5, 87, yes)],
            [.byte(5, 86, no)],
            [.byte(6, 5, no)],
            [.byte(5, 23, no)],
            [Tlv(group: 10, id: 2, data: [0xAA, 0x55, 0, 0, 0, 0, 0, 0])],
            [.byte(6, 8, 255), .byte(6, 3, yes), .byte(6, 4, 162), .byte(6, 15, no), .byte(6, 1, 1),
             .byte(5, 76, 19), .short(5, 45, 0), .byte(5, 27, 25), .byte(5, 33, 50), .byte(5, 77, 50)],
        ]
    }

    static func navStart() -> [Tlv] { [.byte(5, 86, yes)] }

    /// Seta de teste: manobra 21, 150 m até a curva, 2,5 km até o destino.
    static func testArrow() -> [Tlv] {
        let turn = distance(150), left = distance(2500)
        return [
            .byte(5, 2, 21), .byte(5, 3, 255), .short(5, 4, turn.0), .byte(5, 6, turn.1),
            Tlv(group: 5, id: 8, data: latin1("1830")), .byte(5, 84, 48),
            .short(5, 9, left.0), .byte(5, 70, left.1), .byte(5, 10, yes),
            Tlv(group: 5, id: 11, data: latin1("001500")), .byte(5, 85, 32),
            .text(5, 1, "Teste do iPhone", max: 19), .byte(6, 5, no), .byte(6, 13, no),
        ]
    }
}
