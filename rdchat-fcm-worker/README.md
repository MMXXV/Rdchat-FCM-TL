# RdChat FCM + Telegram Media Worker

Este Worker usa o gateway FCM recuperado do repositório Rdchat2 e fecha o contrato de anexos exigido pelo HTML atual.

## Rotas

- `GET /health`: estado básico do Worker.
- `POST /v1/push`: valida Firebase ID token, lê texto no Firestore e envia FCM data-only.
- `POST /v1/delivered`: valida destinatário e token FCM e grava o recibo.
- `POST /v1/media`: autentica remetente, envia o arquivo ao canal privado do Telegram, publica a mensagem no Firestore, cria inbox e envia FCM.
- `GET /v1/media/{mediaId}`: baixa o arquivo somente para participantes da conversa.
- `DELETE /v1/media/{mediaId}`: o autor remove a cópia do canal e o registro de mídia.

O cliente limita anexos a 20 MiB. Os arquivos são enviados como documentos para preservar o conteúdo original e seu MIME; após baixar, o aplicativo renderiza o arquivo localmente.

## Secrets obrigatórios no Cloudflare

- `FIREBASE_SERVICE_ACCOUNT`: JSON completo de uma conta de serviço do projeto Firebase com acesso ao Firestore e Firebase Cloud Messaging.
- `TELEGRAM_BOT_TOKEN`: token criado pelo BotFather.
- `TELEGRAM_CHANNEL_ID`: ID do canal privado de armazenamento (normalmente começa por `-100`).

O bot precisa ser administrador do canal privado com permissão para publicar e excluir mensagens. O canal deve ser dedicado ao armazenamento do RdChat.

## Publicação pelo GitHub Actions

Cadastre os seguintes GitHub Actions Secrets:

- `CLOUDFLARE_API_TOKEN`: token com permissão de edição de Workers na conta.
- `CLOUDFLARE_ACCOUNT_ID`: ID da conta Cloudflare.
- `FIREBASE_SERVICE_ACCOUNT`
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHANNEL_ID`

Depois abra Actions → **Deploy RdChat FCM + Telegram Worker** → Run workflow. Ele publica o Worker `rdchat-fcm-gateway`, cujo endereço já está referenciado pelo HTML e pelo serviço Android.

## Retenção

O Worker verifica a coleção `telegramMedia` a cada hora e apaga do canal arquivos com mais de sete dias. Não habilite TTL automático para `telegramMedia`, pois a rotina precisa do documento para localizar a mensagem no canal. Mantenha o TTL de sete dias para `messages` e `items`.

A exclusão para todos também chama `DELETE /v1/media/{mediaId}`. Se Telegram estiver indisponível, a remoção remota permanece pendente e deve ser verificada antes de considerar o arquivo fisicamente apagado.

## Verificação

O workflow **Verify RdChat FCM Worker** executa `node --check`. Isso verifica sintaxe; não substitui implantação, configuração dos secrets ou teste real entre aparelhos.
