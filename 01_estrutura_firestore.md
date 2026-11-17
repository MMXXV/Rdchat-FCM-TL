# RdChat – Estrutura do Firestore (arquitetura V1.0.6)

Premissas: zero P2P/WebRTC · histórico 100% local · nuvem = fila temporária de transporte.
Código de contato: 6 dígitos no formato `123-456` (único). O telefone NUNCA é gravado no Firestore.

## Coleções

### `users/{uid}`  (PRIVADO – só o dono lê/escreve)
```json
{
  "uid": "firebaseUid",
  "email": "usuario@rdchat.local",
  "displayName": "Binho",
  "code": "123-456",
  "accountType": "SEM_NUMERO | COM_NUMERO",
  "tgUserId": "",                 // só COM_NUMERO: id interno do Telegram (oculto na interface)
  "tgUsername": "",               // só COM_NUMERO: usuário técnico automático, ex.: rdc_483920_ab12
  "fcmToken": "token principal",
  "fcmUpdatedAt": 1763300000000,
  "is_adult": true,
  "updatedAt": 1763300000000
}
```
Subcoleções: `users/{uid}/fcmTokens/{sha256(token)}` `{token, platform:"android", updatedAt}` (multiaparelho) e `users/{uid}/contacts/{uid}`.

### `contactCodes/{123-456}`  (PÚBLICO p/ busca por código – só `get`, sem listagem)
Somente o que a busca precisa. Nada sensível.
```json
{
  "uid": "firebaseUid",
  "email": "usuario@rdchat.local",
  "name": "Binho",                  // Nome
  "code": "123-456",
  "accountType": "COM_NUMERO",      // Tipo de conta
  "tgUsername": "rdc_123456_ab12",  // usado só pela rota TDLib (veja nota abaixo)
  "fcmToken": "…",                  // Token FCM (conforme pedido; ver nota de segurança)
  "avatarData": "",
  "is_adult": true,
  "updatedAt": 1763300000000
}
```

### `presence/{uid}`
`{ uid, online:true, away:false, ts, awayAt }` – leitura para logados, escrita só do dono.

### `textConversations/{room}/messages/{messageId}`  (fila temporária – Cenário 1)
```json
{
  "id": "msgId", "roomId": "room", "kind": "text|image|video|audio|gif|file|deleted",
  "fromUid": "…", "fromEmail": "…", "fromName": "…", "fromCode": "123-456",
  "toUids": ["…"], "toEmails": ["…"],
  "text": "texto cifrado/ou claro",
  "telegramMediaId": "file_id do Bot Gateway (mídias ≤ 20 MB)",
  "mediaName": "foto.jpg", "mediaMime": "image/jpeg", "mediaSize": 123456,
  "viewOnce": true, "caption": "legenda",
  "groupKey": "", "groupName": "", "participantEmails": [],
  "deliveredBy": [], "readBy": [], "ts": 1763300000000
}
```
O destinatário grava no IndexedDB e **apaga** o documento (regra permite `delete` pelo destinatário).

### `messageInbox/{uid}/items/{messageId_uid}`
Ponteiro por destinatário (aciona sincronização). Dono lê/apaga; o remetente cria.

## Rotas de envio (decididas no JS por `RdChatRouter`/`enviarMensagem()`)
| Remetente | Destinatário(s) | Rota |
|---|---|---|
| SEM_NUMERO | qualquer | Cenário 1: texto → Firestore + FCM · mídia ≤ 20 MB → Bot Gateway |
| qualquer | SEM_NUMERO (ou algum SEM_NUMERO no grupo) | Cenário 1 |
| COM_NUMERO | todos COM_NUMERO | Cenário 2: `AndroidBridge.enviarViaTelegram` (TDLib, até 2 GB) |
Se a rota TDLib falhar, o JS cai automaticamente no Cenário 1 (limite de 20 MB para mídia).

## Mensagens pela TDLib
O corpo enviado pela TDLib é `RDC1` + JSON (id, kind, text, fromEmail, fromName, fromCode, ts, viewOnce, caption, grupo…).
Mídia vai como documento com esse JSON na legenda. Quem recebe lê o JSON e injeta na mesma rotina do Firestore
(`RdChatReceiveFirebaseText`), então histórico, leitura, aceitar/recusar download e visualização única continuam iguais.
Após gravar localmente o app confirma (`tdlibConfirmarRecebimento`) e a mensagem é apagada do Telegram.

## Notas importantes (leia)
1. **Resolver o destinatário na TDLib:** a TDLib NÃO consegue enviar para um `user_id` que ela nunca “viu”
   (precisa do access_hash; sem agenda/telefone isso não existe). Por isso cada conta COM_NUMERO recebe,
   no login, um usuário técnico automático (`rdc_<aleatório>_<rand>`, `SetUsername`) guardado em `tgUsername`;
   o remetente usa `SearchPublicChat(tgUsername)`. O telefone continua oculto; o `tgUserId` fica só em `users/{uid}`.
2. **Privacidade do número:** `ShowPhoneNumber` aceita “Ninguém”. Já `AllowFindingByPhoneNumber` no Telegram só
   aceita “Todos” ou “Meus contatos”; o código tenta “Ninguém” e, se o servidor recusar, usa “Meus contatos”
   (como o app nunca importa contatos, na prática ninguém encontra você pelo número).
3. **Token FCM público:** o token sozinho não permite enviar push (precisa de credencial de servidor), mas é um
   identificador de aparelho. Mais seguro: não expor `fcmToken` em `contactCodes` e deixar o gateway FCM
   (Worker/Cloud Function) buscar o token em `users/{uid}` com credencial de admin. A regra aceita o campo
   porque você pediu, mas o app atual já funciona sem ele.
4. **Envio de FCM** (data-only, `priority:"high"`) deve sair do servidor (Worker/Cloud Function), nunca do cliente.
5. **api_id/api_hash** da TDLib: crie em https://my.telegram.org e compile a TDLib para Android (td/example/android).
