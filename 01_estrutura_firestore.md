# Estrutura Firestore do RdChat

Os códigos são armazenados com seis dígitos sem hífen (`123456`). A interface pode formatar como `123-456`, mas o ID do documento e o campo `code` são normalizados.

## Coleções em uso

- `users/{uid}`: perfil privado.
- `users/{uid}/fcmTokens/{tokenHash}`: tokens de cada dispositivo, privados ao dono.
- `users/{uid}/contacts/{contactUid}`: vínculos privados.
- `contactCodes/{123456}`: diretório de consulta exata, sem listagem pública e sem tokens FCM.
- `presence/{uid}`: estado de presença.
- `textConversations/{room}`: membros e metadados da conversa.
- `textConversations/{room}/messages/{messageId}`: texto, recibos e tombstones de exclusão.
- `messageInbox/{uid}/items/{itemId}`: ponteiros para mensagens e convites.

## Envio

O cliente resolve o UID pelo código, cria/atualiza o documento da sala, grava a mensagem e cria um item por destinatário em `messageInbox`. Em seguida chama o Worker FCM `/v1/push` para acordar o aparelho. O FCM é um aviso de sincronização; o conteúdo é recuperado do Firestore quando o HTML inicializa.

A exclusão para todos também grava um tombstone na sala e um ponteiro de inbox. A mídia depende do Worker HTTP de relay e precisa ser validada separadamente.

## TTL de sete dias

O HTML escreve `expiresAt` (Firestore Timestamp) nos documentos de mensagem, inbox, convites de grupo e exclusões. **A gravação do campo não ativa a exclusão automática.** No Firebase Console/Google Cloud Console, abra Firestore → Time-to-live → Create Policy e crie:
- Collection group: `messages`; campo: `expiresAt`.
- Collection group: `items`; campo: `expiresAt`.

Alternativa via Google Cloud CLI, no projeto `rdchat-firebasev1`:

```powershell
gcloud config set project rdchat-firebasev1
gcloud firestore fields ttls update expiresAt --collection-group=messages --enable-ttl
gcloud firestore fields ttls update expiresAt --collection-group=items --enable-ttl
gcloud firestore fields ttls list
```

A ativação pode levar dez minutos ou mais e documentos expirados normalmente são removidos em até 24 horas, não no instante exato. As exclusões por TTL são operações de delete e devem ser consideradas no consumo/limites do Firestore. Consulte a documentação oficial de TTL antes de ativar em produção.

## Segurança

As regras atualizadas fazem busca exata de seis dígitos, bloqueiam listagem de `contactCodes`, restringem tokens FCM à conta dona, verificam membros da sala e exigem que `toUid` corresponda ao UID no caminho de inbox.

Firestore Rules não aplicam rate limit. Um código de seis dígitos ainda pode ser enumerado por consultas exatas repetidas: habilite Firebase App Check com enforcement e adote rate limiting no servidor antes de disponibilizar ao público.

O conteúdo do campo `text` é gravado como texto no Firestore. O código atual não comprova E2EE. A criptografia do cofre local não equivale a criptografia ponta a ponta no transporte.
