# RdChat — decisões e pendências técnicas

## O que foi consolidado

- O módulo Gradle compilado é `:app`, namespace/applicationId `com.example.rdchat`.
- HTML preservado na mesma origem HTTPS/AssetLoader para não trocar a origem do IndexedDB/localStorage que já pode existir nos aparelhos.
- FCM Android agora drena fila e token após o HTML confiável carregar; o token que chegava antes do carregamento também é drenado.
- Métodos nativos usados pelo HTML foram adicionados à ponte: biometria, notificações, eventos de segurança locais, armazenamento, perfil, speakerphone e callback de GIF.
- Métodos de revisão de mídia respondem negando (fail-closed) enquanto não houver motor real; não aprovam mídia sem análise.
- Navegação de primeiro plano fora de `/assets/` abre no app externo; pop-ups automáticos desativados.
- Removidas permissões de LAN/Wi-Fi multicast, já que o transporte P2P foi desativado.
- FileProvider restrito a `cache/midia_privada/`.
- Gradle version catalog, JDK 17 e versões alinhadas a AGP 9.2.0 / Gradle 9.4.1 / API 37.
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

## TDLib — não ativada

A raiz antiga tinha código em pacote diferente, API_HASH hardcoded já exposto publicamente, sem AAR/JNI .so no módulo e sem dependência Gradle. Isso não pode ser ligado sem binários.

Faltam:
- revogar/rotacionar o API_HASH exposto; não reutilizar a credencial anterior;
- receber/compilar TDLib Android, AAR e JNI `.so` para arm64-v8a (e x86_64, se usar emulador);
- mover a implementação sanitizada da pasta `integration-staging/tdlib` para o pacote da aplicação apenas após revisão;
- implementar sessão singleton de processo/serviço, persistência do estado de autenticação, recuperação/2FA e push receiver TDLib;
- implementar download/upload por URI/streaming; data URL/Base64 não atende 2 GB;
- ligar callbacks `window.RdChatTdlib` e testar recebimento com app fechado;
- então ligar o roteador COM_NUMERO no HTML.

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
