# RdChat — decisões e pendências técnicas

## O que foi consolidado

- O módulo Gradle compilado é `:app`, namespace/applicationId `com.example.rdchat`.
- HTML preservado na mesma origem HTTPS/AssetLoader para não trocar a origem do IndexedDB/localStorage que já pode existir nos aparelhos.
- FCM Android agora drena fila e token após o HTML confiável carregar; o token que chegava antes do carregamento também é drenado.
- Métodos nativos usados pelo HTML foram adicionados à ponte: biometria, notificações, eventos de segurança locais, armazenamento, perfil, speakerphone e callback de GIF.
- Métodos de revisão de mídia respondem negando (fail-closed) enquanto não houver motor real; não aprovam mídia sem análise.
- Navegação de primeiro plano fora de `/assets/` abre no app externo; pop-ups automáticos desativados.
- Removidas permissões de LAN/Wi-Fi multicast, já que o transporte P2P foi desativado.
- Câmera/microfone deixaram de ser solicitados automaticamente ao abrir a página; serão pedidos no contexto de uso. Backup Android e cookies de terceiros foram desativados.
- FileProvider restrito a `cache/midia_privada/`.
- Gradle version catalog, JDK 17 e versões alinhadas a AGP 9.5.1 / Gradle 9.6.0 / API 37.
- Regras Firestore atualizadas para IDs de contato de seis dígitos, documento pai de sala, membros autorizados e inbox com `toUid` correspondente ao UID no caminho.
- Mensagens, ponteiros, convites e tombstones recebem `expiresAt` (sete dias).
- O HTML fixa `RDCHAT_ZERO_P2P=true` e bloqueia criação de RTCPeerConnection.

## Contrato de transporte atual

O HTML atual:
1. grava o documento `textConversations/{room}`;
2. grava `textConversations/{room}/messages/{id}`;
3. cria um item `messageInbox/{recipientUid}/items/{id_senderUid}` por destinatário;
4. chama o Worker FCM `POST /v1/push` com Firebase ID token e os campos `roomId/messageId/kind/body`;
5. chama o Worker HTTP de mídia `POST /v1/media` para anexos.

O Android recebe data-only FCM, repassa ao callback HTML e o serviço tenta confirmar wake com `POST /v1/delivered`. O código dos Workers não foi incluído neste repositório; não foi possível validar sua implantação, autenticação, TTL de fila ou resposta.

## Firestore: operação a realizar

No Firebase Console, habilitar Time-to-live para o campo Timestamp `expiresAt` nas coleções de mensagens e inbox. Esse campo já é escrito pelo HTML, mas não apaga nada até a política TTL estar ativa. Confirmar também as quotas do plano Spark e regras no ambiente real.

O diretório `contactCodes` permite busca exata e bloqueia listagem. Isso não evita tentativa de enumerar seis dígitos: Rules não têm rate limit. Ativar App Check com enforcement e migrar resolução para um endpoint com limitação de taxa antes de abrir ao público.

### Limites de segurança existentes no HTML

O conteúdo de texto é enviado no campo Firestore `text`; o código atual não prova criptografia ponta a ponta no transporte. A criptografia do cofre local não basta. Não anunciar E2EE até implementar e testar criptografia antes do envio, troca/autenticação de chaves, grupos, multi-dispositivo e rotação.

Firebase Auth ainda é anônimo por instalação. Não existe mecanismo pronto de recuperação de conta/histórico em outro aparelho.

A fila local do FCM é SharedPreferences com até 50 eventos de wake, não um banco de mensagens. A sincronização da inbox acontece quando o HTML inicializa. Para garantir processamento autônomo com Activity fechada, falta banco nativo Room/SQLite, WorkManager e rotina autenticada de sincronização. FCM não é fila durável.

## P2P e chamadas

O HTML define política imutável `RDCHAT_ZERO_P2P=true`; Trystero não é carregado e `makePC` lança erro antes da criação da PeerConnection. A chamada de saída agora falha antes de pedir microfone/câmera, a sinalização de chamada não é mais enviada e sinais antigos são descartados. Os elementos visuais de chamadas ainda existem; a versão atual deve ser tratada como sem chamadas até integrar um serviço compatível com a arquitetura desejada.

## TDLib — ponte Android integrada; credenciais e validação pendentes

- O módulo `:app` agora inclui o TDLib Java/JNI por dependência fixada a commit, com ABIs arm64-v8a, armeabi-v7a, x86 e x86_64.
- `MainActivity` registra os métodos esperados pelo HTML em `AndroidBridge` sem substituir a ponte nativa existente.
- O carregamento TDLib é acionado após a página confiável carregar, permitindo retomar a sessão persistida.
- O início de autenticação aguarda o estado `AuthorizationStateWaitPhoneNumber`; o número não é enviado antes de `SetTdlibParameters` concluir.
- A chave aleatória do banco TDLib é protegida com AES-GCM e Android Keystore.
- O usuário existente mantém o próprio username Telegram; um username técnico só é criado se não houver um.

Ainda é necessário:
- configurar um par válido `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` como GitHub Actions Secrets ou em `local.properties`;
- confirmar que as credenciais atuais não foram revogadas e, se expostas, criar um novo app em my.telegram.org;
- executar login real em aparelho, confirmar envio do código, senha de duas etapas e retomada após reiniciar;
- validar notificações e recebimento TDLib com o app encerrado; o serviço persistente/push receiver TDLib ainda não está integrado;
- testar upload/download de arquivos em aparelhos e limites reais; o recebimento interno por Base64 permanece limitado a 25 MB;
- validar política de username/privacidade e o fluxo entre duas contas.
## Code.gs não substitui o Worker

O Apps Script exige chave compartilhada, ações próprias, NIP-17 kind 1059 e fragmentos cifrados. O HTML atual não gera/decifra esse formato. Foi separado como alternativa de pesquisa; não publicar por cima do endpoint `/v1/push` atual.

## Antes de produção

- Compilar e instalar no Android Studio/um dispositivo real.
- Publicar/testar as regras Firestore em duas contas e confirmar mensagens, convites, recibos e exclusões.
- Habilitar TTL `expiresAt` e confirmar exclusão real.
- Verificar Workers `/v1/push`, `/v1/delivered`, `/v1/media` com app encerrado.
- Testar permissões, câmera, microfone, FileProvider, notificações e biometria.
- Implementar E2EE de ponta a ponta.
- Ativar App Check e rate limiting de código.
- Construir a arquitetura nativa de fila offline/WorkManager/Room.
- Rotacionar a credencial Telegram exposta e só depois integrar TDLib.
- Assinar release com keystore local, ignorada pelo Git.
