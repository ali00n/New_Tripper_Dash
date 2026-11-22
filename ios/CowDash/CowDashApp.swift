import SwiftUI

@main
struct CowDashApp: App {
    var body: some Scene { WindowGroup { ContentView() } }
}

struct ContentView: View {
    @StateObject private var link = DashLink()
    @AppStorage("ssid") private var ssid = ""
    @State private var broadcast = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Cow Dash · teste iF0").font(.title2.bold())
            Text("1) Em Ajustes > Wi-Fi, conecte no Wi-Fi do painel (RE_...). 2) Volte aqui, digite o mesmo nome e toque em Conectar.")
                .font(.footnote).foregroundStyle(.secondary)
            TextField("Wi-Fi do painel (ex.: RE_ABCD_123456)", text: $ssid)
                .textInputAutocapitalization(.characters).autocorrectionDisabled()
                .padding(10).background(Color.gray.opacity(0.15)).cornerRadius(10)
            Toggle("Enviar por broadcast (como no Android)", isOn: $broadcast).font(.footnote)
            HStack {
                Button("Conectar") {
                    link.ssid = ssid.trimmingCharacters(in: .whitespaces)
                    link.useBroadcast = broadcast
                    link.connect()
                }.buttonStyle(.borderedProminent).tint(.orange)
                Button("Mandar seta") { link.startTestArrow() }.buttonStyle(.bordered)
                    .disabled(!link.authenticated)
                Spacer()
                Text(link.authenticated ? "● conectado" : "○ desconectado")
                    .font(.footnote.bold()).foregroundColor(link.authenticated ? .green : .secondary)
            }
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(link.lines.enumerated()), id: \.offset) { i, l in
                            Text(l).font(.system(size: 10, design: .monospaced)).textSelection(.enabled).id(i)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                .onChange(of: link.lines.count) { n in proxy.scrollTo(n - 1, anchor: .bottom) }
            }
            .padding(8).background(Color.black.opacity(0.05)).cornerRadius(10)
            Button("Copiar log") { UIPasteboard.general.string = link.lines.joined(separator: "\n") }
                .font(.footnote)
        }
        .padding()
    }
}
