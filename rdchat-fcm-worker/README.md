# RdChat FCM + Telegram Media Worker

Este Worker reaproveita o gateway FCM do repositório Rdchat2 e atende os contratos de texto, anexos e mídia de perfil do HTML atual.

## Rotas

- `GET /health`: saúde básica e indicação de credenciais Telegram configuradas.
- `POST /v1/push`: valida Firebase ID token, lê mensagem de texto no Firestore e envia FCM data-only.
- `POST /v1/delivered`: valida destinatário e token FCM e grava recibo de entrega.
- `POST /v1/media`: autentica remetente, envia anexo ao canal privado Telegram, grava mensagem/inbox no Firestore e envia FCM.
- `GET /v1/media/{mediaId}`: baixa anexo apenas para participantes da conversa.
- `DELETE /v1/media/{mediaId}`: autor remove a cópia do canal e registro de mídia.
- `POST /v1/profile`: grava prévia compacta em `users`/`contactCodes` e mídia completa no canal privado.
- `GET /v1/profile/{uid}`: próprio usuário ou contato adicionado baixa a mídia completa do perfil.

O cliente limita anexos e mídia de perfil a 20.000.000 bytes. O Bot API oficial limita downloads a 20 MB e só permite apagar mensagens antes de 48 horas. Por isso anexos de conversa expiram remotamente em cerca de 40–41 horas e precisam ser baixados nessa janela. Mídia completa de perfil permanece enquanto for a versão ativa.

## Secrets obrigatórios no Cloudflare

- `FIREBASE_SERVICE_ACCOUNT`: JSON completo de uma conta de serviço do projeto Firebase com acesso ao Firestore e Firebase Cloud Messaging.
- `TELEGRAM_BOT_TOKEN`: token criado pelo BotFather.
- `TELEGRAM_CHANNEL_ID`: ID do canal privado de armazenamento (normalmente começa por `-100`).

O bot precisa ser administrador do canal privado com permissão para publicar e excluir mensagens. Use um canal privado dedicado ao RdChat.

## Publicação pelo GitHub Actions

Cadastre os seguintes GitHub Actions Secrets:

- `CLOUDFLARE_API_TOKEN`: token com permissão para editar Workers na conta.
- `CLOUDFLARE_ACCOUNT_ID`: ID da conta Cloudflare.
- `FIREBASE_SERVICE_ACCOUNT`
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHANNEL_ID`

Depois abra Actions → **Deploy RdChat FCM + Telegram Worker** → Run workflow. Ele publica o Worker `rdchat-fcm-gateway`, já referenciado pelo HTML e serviço Android.

## Retenção e exclusão

O Worker verifica `telegramMedia` a cada hora e remove anexos de conversa após cerca de 40–41 horas. Não habilite TTL automático em `telegramMedia`, pois a rotina precisa do documento para localizar a mensagem no canal. Mantenha TTL para `messages` e `items`; o prazo efetivo do anexo é controlado pelo Worker.

Os documentos `telegramProfiles` não expiram automaticamente. Ao substituir mídia de perfil, o Worker tenta apagar a anterior; o Bot API não permite excluir mensagens com mais de 48 horas, então versões antigas podem permanecer no canal privado. Mantenha o canal privado e restrinja o acesso.

A exclusão para todos também chama `DELETE /v1/media/{mediaId}`. Se Telegram estiver indisponível, a remoção remota permanece pendente e deve ser verificada antes de considerar o arquivo fisicamente apagado.

## Verificação

O workflow **Verify RdChat FCM Worker** executa `node --check`. Isso verifica sintaxe; não substitui implantação, configuração dos secrets ou teste real entre aparelhos.
