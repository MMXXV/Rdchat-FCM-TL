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
- Anexos e mídia completa de perfil: `rdchat-fcm-gateway.edersonrgames.workers.dev`, endpoints `/v1/media` e `/v1/profile` (Bot Telegram + Firestore + FCM). O Worker está versionado em `rdchat-fcm-worker/`.
- P2P/Trystero/WebRTC foram bloqueados no HTML. Chamadas estão desativadas nesta versão.

O código local do Worker foi integrado, mas só estará ativo após publicar pelo workflow manual e configurar os secrets indicados em `rdchat-fcm-worker/README.md`.

## Telegram (TDLib)

O modo **Com número** usa TDLib JNI Android. A dependência está fixada a um commit imutável e inclui bibliotecas nativas para arm64-v8a, armeabi-v7a, x86 e x86_64.

O `api_id` público já está configurado no projeto. O `api_hash` não é versionado: como o valor antigo apareceu exposto no histórico, use um hash rotacionado.

- GitHub Actions: cadastre somente `TELEGRAM_API_HASH` em Settings → Secrets and variables → Actions.
- Build local: adicione `telegramApiHash=...` ao arquivo local `local.properties` (já ignorado pelo Git).

Sem essas duas configurações, o APK continua compilando, mas o login por número informa que falta configurar a integração. Não envie credenciais por commit nem em logs.

O primeiro acesso prepara o banco TDLib no diretório privado do app. A chave do banco é aleatória e protegida pelo Android Keystore. O login por número foi conectado à ponte AndroidBridge; a validação final exige credenciais e teste real de recebimento do código Telegram/SMS.

## Implantação Firebase

O projeto já inclui `firebase.json` e `.firebaserc`. Consulte `docs/CONFIGURAR_FIREBASE.md` para publicar regras, configurar TTL, habilitar Auth anônima e implantar o Worker FCM + Telegram.

## Expiração da fila

Mensagens de texto, inbox, convites e exclusões usam o prazo definido no cliente (sete dias). Anexos do Bot Telegram têm janela remota de 40 horas, necessária para que o Bot API consiga removê-los fisicamente. Ative TTL em `messages` e `items`; o Worker limpa `telegramMedia`, o canal privado e os ponteiros relacionados.

## Pendências e limites

Leia `docs/ARQUITETURA_E_PENDENCIAS.md`. Em particular: integração TDLib depende de AAR/JNI .so e credenciais rotacionadas; Code.gs não é compatível com o contrato atual; E2EE real, App Check/rate limit, autenticação recuperável, processamento offline e testes de dispositivo ainda precisam ser feitos.
