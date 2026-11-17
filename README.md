# RdChat — base Android consolidada

O projeto Android já existe no repositório. Abra a pasta raiz no Android Studio; não crie um projeto vazio por cima.

## Ambiente

- Android Studio compatível com Android Gradle Plugin 9.2.0.
- JDK 17.
- Android SDK Platform 37.
- Android SDK Build Tools 36.0.0.
- Gradle 9.4.1.

No Windows/PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

Os arquivos `gradlew` e `gradlew.bat` são launchers bootstrap deste repositório: baixam o Gradle oficial 9.4.1 para o cache local e verificam SHA-256 antes de extrair. Não são o Wrapper JAR oficial. O arquivo `gradle/wrapper/gradle-wrapper.properties` registra a mesma distribuição para futura migração ao wrapper padrão (`gradle wrapper --gradle-version 9.4.1`).

## Estrutura ativa

- HTML: `app/src/main/assets/index.html`.
- Android: `app/src/main/java/com/example/rdchat/`.
- Manifesto: `app/src/main/AndroidManifest.xml`.
- Firebase: `app/google-services.json`.
- Regras: `firestore.rules`.

## Transporte atual

- Texto, diretório e presença: Firestore.
- Wake/push: Worker FCM `rdchat-fcm-gateway.edersonrgames.workers.dev`, endpoint esperado `POST /v1/push`.
- Confirmação de wake: o serviço Android chama `POST /v1/delivered`.
- Anexos: Worker `rdchat.edersonrgames.workers.dev`, endpoints `/v1/media`.
- P2P/Trystero/WebRTC foram bloqueados no HTML. O código antigo de chamadas ainda existe, mas a criação de PeerConnection está bloqueada.

O código-fonte dos Workers não está neste repositório, portanto os endpoints remotos não foram alterados nem testados.

## Expiração da fila

Mensagens, inbox, convites e eventos de exclusão passam a receber `expiresAt` com janela de sete dias. É obrigatório ativar TTL no Firebase Console para o campo `expiresAt` nas coleções relevantes. Até a política estar ativa, os documentos não são removidos automaticamente.

## Pendências e limites

Leia `docs/ARQUITETURA_E_PENDENCIAS.md`. Em particular: integração TDLib depende de AAR/JNI .so e credenciais rotacionadas; Code.gs não é compatível com o contrato atual; E2EE real, App Check/rate limit, autenticação recuperável, processamento offline e testes de dispositivo ainda precisam ser feitos.
