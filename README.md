# RdChat — base Android consolidada

O projeto Android já existe no repositório. Abra a pasta raiz no Android Studio; não crie um projeto vazio por cima.

## Ambiente

- Android Studio compatível com Android Gradle Plugin 9.5.1.
- JDK 17.
- Android SDK Platform 37.
- Android SDK Build Tools 37.0.0.
- Gradle 9.6.0.

No Windows/PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

Este repositório inclui os scripts e o JAR oficiais do Gradle Wrapper 9.6.0. A distribuição é fixada por versão e protegida pela propriedade `distributionSha256Sum` com o SHA-256 oficial publicado pelo Gradle.

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
- P2P/Trystero/WebRTC foram bloqueados no HTML. Chamadas de saída e sinais de chamada recebidos são bloqueados; os botões visuais ainda podem aparecer, mas não iniciam captura de áudio/vídeo.

O código-fonte dos Workers não está neste repositório, portanto os endpoints remotos não foram alterados nem testados.

## Telegram (TDLib)

O modo **Com número** usa TDLib JNI Android. A dependência está fixada a um commit imutável e inclui bibliotecas nativas para arm64-v8a, armeabi-v7a, x86 e x86_64.

O `api_id` público já está configurado no projeto. O `api_hash` não é versionado: como o valor antigo apareceu exposto no histórico, use um hash rotacionado.

- GitHub Actions: cadastre somente `TELEGRAM_API_HASH` em Settings → Secrets and variables → Actions.
- Build local: adicione `telegramApiHash=...` ao arquivo local `local.properties` (já ignorado pelo Git).

Sem essas duas configurações, o APK continua compilando, mas o login por número informa que falta configurar a integração. Não envie credenciais por commit nem em logs.

O primeiro acesso prepara o banco TDLib no diretório privado do app. A chave do banco é aleatória e protegida pelo Android Keystore. O login por número foi conectado à ponte AndroidBridge; a validação final exige credenciais e teste real de recebimento do código Telegram/SMS.

## Implantação Firebase

O projeto já inclui `firebase.json` e `.firebaserc`. Consulte `docs/CONFIGURAR_FIREBASE.md` para publicar regras, configurar TTL e ativar App Check.

## Expiração da fila

Mensagens, inbox, convites e eventos de exclusão passam a receber `expiresAt` com janela de sete dias. É obrigatório ativar TTL no Firebase Console para o campo `expiresAt` nas coleções relevantes. Até a política estar ativa, os documentos não são removidos automaticamente.

## Pendências e limites

Leia `docs/ARQUITETURA_E_PENDENCIAS.md`. Em particular: integração TDLib depende de AAR/JNI .so e credenciais rotacionadas; Code.gs não é compatível com o contrato atual; E2EE real, App Check/rate limit, autenticação recuperável, processamento offline e testes de dispositivo ainda precisam ser feitos.
