import Foundation
import Security

/// Cifra RSA PKCS#1 com a chave pública que o painel manda (módulo + expoente).
enum RSAKey {
    static func encrypt(modulus: [UInt8], exponent: [UInt8], plain: [UInt8]) throws -> [UInt8] {
        let der = sequence(integer(modulus) + integer(exponent))
        let attrs: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass: kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits: stripZeros(modulus).count * 8,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(Data(der) as CFData, attrs as CFDictionary, &error) else {
            throw error!.takeRetainedValue() as Error
        }
        guard let out = SecKeyCreateEncryptedData(key, .rsaEncryptionPKCS1, Data(plain) as CFData, &error) else {
            throw error!.takeRetainedValue() as Error
        }
        return [UInt8](out as Data)
    }

    private static func stripZeros(_ b: [UInt8]) -> [UInt8] {
        let s = Array(b.drop(while: { $0 == 0 }))
        return s.isEmpty ? [0] : s
    }

    private static func length(_ n: Int) -> [UInt8] {
        if n < 0x80 { return [UInt8(n)] }
        var bytes: [UInt8] = []
        var v = n
        while v > 0 { bytes.insert(UInt8(v & 0xFF), at: 0); v >>= 8 }
        return [0x80 | UInt8(bytes.count)] + bytes
    }

    private static func integer(_ raw: [UInt8]) -> [UInt8] {
        var v = stripZeros(raw)
        if v[0] & 0x80 != 0 { v.insert(0, at: 0) }
        return [0x02] + length(v.count) + v
    }

    private static func sequence(_ body: [UInt8]) -> [UInt8] { [0x30] + length(body.count) + body }
}
