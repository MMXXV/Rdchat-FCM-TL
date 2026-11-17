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
- Mensagens de texto, ponteiros, convites e tombstones recebem `expiresAt` (sete dias); anexos no Bot Telegram usam retenção remota de aproximadamente 40–41 horas.
- O HTML registra tokens FCM no campo legado `users/{uid}.fcmToken` e na subcoleção `users/{uid}/fcmTokens/{sha256}`.
- O Worker unificado foi adicionado em `rdchat-fcm-worker/`: reaproveita o gateway FCM do `Rdchat2` e implementa `/v1/media` e `/v1/profile` para anexos e mídia completa de perfil.
- O HTML fixa `RDCHAT_ZERO_P2P=true` e bloqueia criação de RTCPeerConnection.

## Contrato de transporte atual

O HTML atual:
1. grava o documento `textConversations/{room}`;
2. grava texto em `textConversations/{room}/messages/{id}` e cria um item `messageInbox/{recipientUid}/items/{id_senderUid}` por destinatário;
3. chama `POST /v1/push` com Firebase ID token para acordar os aparelhos destinatários;
4. para anexos, chama `POST /v1/media`; o Worker envia ao canal privado Telegram, grava a mensagem/mídia/inbox no Firestore e dispara FCM;
5. para mídia completa de perfil, chama `POST /v1/profile` e `GET /v1/profile/{uid}`. A prévia reduzida é replicada para `users` e `contactCodes`; o arquivo completo fica no canal privado Telegram.

O Android recebe data-only FCM, repassa ao callback HTML e o serviço confirma wake com `POST /v1/delivered`. O código do Worker está versionado em `rdchat-fcm-worker/`, mas a implantação no Cloudflare, os secrets e a resposta real dos endpoints ainda precisam ser verificados. O Worker possui rotinas de autenticação Firebase, FCM HTTP v1, Firestore REST e Telegram Bot API; CI comprova sintaxe, não operação de backend.

## Firestore: operação a realizar

No Firebase Console, habilitar Time-to-live para o campo Timestamp `expiresAt` nos collection groups `messages` e `items`. Esse campo já é escrito pelo HTML, mas não apaga nada até a política TTL estar ativa. Não criar TTL em `telegramMedia`: o Worker precisa do registro para apagar a cópia no canal. O Worker limpa anexos por volta de 40–41 horas porque o Bot API impõe limite de menos de 48 horas para `deleteMessage`; o destinatário precisa baixar dentro dessa janela. Confirmar quotas do plano Spark e regras no ambiente real.

O diretório `contactCodes` permite busca exata e bloqueia listagem. Isso não evita tentativa de enumerar seis dígitos: Rules não têm rate limit. Ativar App Check com enforcement e migrar resolução para um endpoint com limitação de taxa antes de abrir ao público.

### Limites de segurança existentes no HTML

O conteúdo de texto é enviado no campo Firestore `text`; o código atual não prova criptografia ponta a ponta no transporte. A criptografia do cofre local não basta. Não anunciar E2EE até implementar e testar criptografia antes do envio, troca/autenticação de chaves, grupos, multi-dispositivo e rotação.

Firebase Auth ainda é anônimo por instalação. Não existe mecanismo pronto de recuperação de conta/histórico em outro aparelho.

A fila local do FCM é SharedPreferences com até 50 eventos de wake, não um banco de mensagens. A sincronização da inbox acontece quando o HTML inicializa. Para garantir processamento autônomo com Activity fechada, falta banco nativo Room/SQLite, WorkManager e rotina autenticada de sincronização. FCM não é fila durável.

## Modo A e Modo B — contrato de compatibilidade

- A tela do Modo A (Usuário e senha) foi preservada sem mudança visual.
- A tela do Modo B usa a mesma estrutura de campos, na ordem Nome de usuário → PIN local de quatro dígitos → telefone. O botão ao lado do telefone usa o mesmo ícone de olho do PIN e abre a explicação de privacidade, que desaparece após 4,2 segundos. Código de autenticação e senha 2FA só aparecem na etapa em que são necessários.
- No Modo B, o botão azul original é a única ação principal: ele inicia o envio do código, confirma o código, confirma a senha 2FA e conclui a criação/entrada local. O link secundário de alternância login/cadastro fica oculto apenas no Modo B.
- O onboarding não contém mais a etapa de apresentação “Leve a privacidade com você”; ele abre diretamente em “Autorizar”. Notificações e contatos são solicitados nessa etapa; câmera e microfone permanecem sob demanda.
- Nome de usuário e PIN de quatro dígitos são credenciais locais do perfil/cofre RdChat, não autenticação remota Firebase. O número e o código recebido autenticam a sessão Telegram via TDLib; a senha 2FA é a senha da própria conta Telegram. A restauração do perfil/histórico RdChat em outro aparelho ainda não é automática.
- Os dois tipos de conta mantêm identidade de diretório pelo código de seis dígitos e UID Firebase. Assim, um usuário A e um usuário B podem localizar/adicionar um ao outro e conversar pelo transporte de compatibilidade Firestore/FCM.
- Quando remetente e todos os destinatários são contas B com username Telegram disponível, o roteador tenta TDLib. Em conversas mistas, o transporte cai para Firestore/FCM e, para anexos, Bot Telegram (limite de 20 MB).
- A implementação TDLib atual não é a API completa do Telegram: autentica contas, aplica tentativas de privacidade do número, envia texto e arquivos como documento genérico e recebe apenas `MessageText` e `MessageDocument`. Fotos/vídeos/áudios/stickers/reactions não têm tratamento nativo completo. Chamadas de voz/vídeo, chamadas recebidas, notificações TDLib em segundo plano e sincronização geral de chats ainda não estão implementadas.

## P2P e chamadas

O HTML define política imutável `RDCHAT_ZERO_P2P=true`; Trystero não é carregado e `makePC` lança erro antes da criação da PeerConnection. A chamada de saída agora falha antes de pedir microfone/câmera, a sinalização de chamada não é mais enviada e sinais antigos são descartados. Os elementos visuais de chamadas ainda existem; a versão atual deve ser tratada como sem chamadas até integrar um serviço compatível com a arquitetura desejada.

## TDLib — ponte Android integrada; credenciais e validação pendentes

- O módulo `:app` agora inclui o TDLib Java/JNI por dependência fixada a commit, com ABIs arm64-v8a, armeabi-v7a, x86 e x86_64.
- `MainActivity` registra os métodos esperados pelo HTML em `AndroidBridge` sem substituir a ponte nativa existente.
- O carregamento TDLib só é acionado após a página confiável carregar e confirmar `rdchat_profile.accountType === 'COM_NUMERO'`; o Modo A (`SEM_NUMERO`) não inicializa TDLib.
- A dependência TDLib JNI continua empacotada no APK único; ela ainda contribui para o tamanho do APK, embora não seja inicializada no Modo A.
- O início de autenticação aguarda o estado `AuthorizationStateWaitPhoneNumber`; o número não é enviado antes de `SetTdlibParameters` concluir.
- A privacidade de número ainda precisa de validação real: o código tenta ocultar o número e bloquear descoberta por telefone, mas hoje permite fallback para “somente contatos” se o bloqueio total falhar. Nesse caso, a frase “ninguém vê nem encontra” não está garantida; remover o fallback e testar o retorno da API antes de apresentar isso como proteção confirmada.
- A chave aleatória do banco TDLib é protegida com AES-GCM e Android Keystore.
- O usuário existente mantém o próprio username Telegram; um username técnico só é criado se não houver um.

Ainda é necessário:
- configurar um `TELEGRAM_API_HASH` novo/rotacionado como GitHub Actions Secret ou `telegramApiHash` em `local.properties` (o `api_id` já está definido no projeto);
- confirmar que as credenciais atuais não foram revogadas e, se expostas, criar um novo app em my.telegram.org;
- executar login real em aparelho, confirmar envio do código, senha de duas etapas e retomada após reiniciar;
- validar notificações e recebimento TDLib com o app encerrado; o serviço persistente/push receiver TDLib ainda não está integrado;
- testar upload/download de arquivos em aparelhos e limites reais; o recebimento interno por Base64 permanece limitado a 25 MB;
- validar política de username/privacidade e o fluxo entre duas contas.
## Code.gs não substitui o Worker

O Apps Script exige chave compartilhada, ações próprias, NIP-17 kind 1059 e fragmentos cifrados. O HTML atual não gera/decifra esse formato. Foi separado como alternativa de pesquisa; não publicar por cima do endpoint `/v1/push` atual.

## Antes de considerar o Modo A operacional

1. Cadastrar GitHub Actions Secrets `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `FIREBASE_SERVICE_ACCOUNT`, `TELEGRAM_BOT_TOKEN` e `TELEGRAM_CHANNEL_ID` no repositório `Rdchat-FCM-TL`.
2. Criar um canal privado exclusivo de mídia, adicionar o Bot como administrador com permissão de publicar/excluir e conferir o `TELEGRAM_CHANNEL_ID`.
3. Executar Actions → **Deploy RdChat FCM + Telegram Worker** e conferir `GET /health` com `mediaConfigured: true`.
4. Habilitar Authentication → Anonymous no Firebase e publicar as regras Firestore atualizadas.
5. Ativar TTL `expiresAt` para `messages` e `items`, sem TTL em `telegramMedia`.
6. Testar duas contas em dois aparelhos: texto, recibos, FCM com app aberto/minimizado/encerrado, anexo nos tipos suportados, download, perfil, exclusão e expiração.

O CI mais recente passa na verificação de sintaxe do Worker e na compilação/testes unitários Android. Isso não substitui a implantação Cloudflare, a validação de regras/TTL no Firebase e o teste real de ponta a ponta.

## Pendências para produção (Modo A e arquitetura geral)

- Publicar/testar as regras Firestore em duas contas e confirmar mensagens, convites, recibos e exclusões.
- Testar permissões, câmera, microfone, FileProvider, notificações e biometria.
- Implementar E2EE de ponta a ponta.
- Ativar App Check e rate limiting de código.
- Construir a arquitetura nativa de fila offline/WorkManager/Room.
- Rotacionar a credencial Telegram exposta e só depois integrar TDLib.
- Assinar release com keystore local, ignorada pelo Git.
