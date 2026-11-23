# New Tripper Dash 🐄

App **gratuito e de código aberto** que leva o mapa de navegação ao painel **Tripper Dash (TFT)** da
**Royal Enfield Himalayan 450**, sem depender do app oficial.

> Projeto independente, feito por um motociclista. **Sem nenhum vínculo com a Royal Enfield.**
> Use por sua conta e risco, configure a rota com a moto parada e mantenha a atenção no trânsito.

**Baixar o app (Android):** https://claude.ai/artifact/6B1T4QCLjCPfNmV2AW5np4

## O que ele faz

- Mapa em tela cheia no painel, com seta, distância, rua e horário de chegada (navegação própria, rotas OSRM)
- Conexão automática: ligou a moto, o app acha o Wi-Fi do painel e conecta
- Mapa sem internet: baixa o mapa do estado (MapLibre + OpenFreeMap) e funciona sem sinal
- Espelhamento do Waze no painel (botão "Espelhar Waze")
- Nome de quem liga no painel, atender e desligar pelo joystick
- Música no painel (título, artista, capa)

## Como funciona (resumo do protocolo)

O celular entra no Wi-Fi do painel (`RE_...`) e conversa por UDP nas portas 2000 e 2002, em quadros
TLV com o cabeçalho `K1G `. O painel manda a chave pública RSA dele; o app responde com uma chave de
sessão AES cifrada (PKCS#1). O mapa vai como vídeo H.264 por RTP, em broadcast na porta 5000
(526x300, 12 quadros/s). Detalhes em `DashProtocol.java`, `DashLink.java` e `VideoProjector.java`.

Nenhuma chave ou segredo da Royal Enfield está neste repositório: a chave do painel é recebida
a cada conexão e a chave de sessão é gerada na hora.

## Estrutura

| Pasta | O quê |
|---|---|
| `app/` | Android (Java, Gradle). Edição `publico` (a distribuída), `evandro` (desenvolvimento) e `doom` (vídeo de demonstração) |
| `ios/` | iPhone (Swift), fase de teste: conecta, autentica e manda uma seta |
| `ferramentas/` | Ferramentas da edição DOOM |
| `.github/workflows/` | Builds no GitHub Actions (IPA sem assinatura e a edição DOOM) |

## Compilar (Android)

```
./gradlew assemblePublicoDebug
```

Para gerar o release assinado, crie a sua própria chave e o arquivo `chave/keystore.properties`
(fora do repositório). APKs assinados por outras pessoas **não são o app oficial**: o oficial é só o do link acima.

## Contribuir

Issues e pull requests são bem-vindos: bugs, celulares que não conectam, melhorias de mapa.
Mande sempre o modelo do celular e, se puder, o log do app (aba Log).

## Licença

GPL-3.0. Pode usar, estudar, modificar e redistribuir, desde que as versões redistribuídas
continuem com o código aberto sob a mesma licença.

A edição `doom` usa o [doomgeneric](https://github.com/ozkl/doomgeneric) (GPL-2.0) e o DOOM
shareware (episódio 1), baixados só na hora da compilação.
