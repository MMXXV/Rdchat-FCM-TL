# Configuração Firebase do RdChat

O projeto já está vinculado ao alias `default = rdchat-firebasev1`. O arquivo `app/google-services.json` contém o cliente Android `com.example.rdchat`.

## 1. Publicar as regras

Instale/atualize Firebase CLI, abra PowerShell na pasta raiz do repositório e execute:

```powershell
firebase login
firebase use default
firebase deploy --only firestore:rules
```

Confira no Console Firebase que o deploy terminou no projeto `rdchat-firebasev1`. O arquivo de fonte é `firestore.rules`; não cole regras antigas por cima.

## 2. Ativar TTL (sete dias)

O HTML já grava um Timestamp `expiresAt`. Para a exclusão ocorrer, habilite TTL nos grupos de coleção `messages` e `items`.

Com Google Cloud CLI instalado e autenticado:

```powershell
gcloud config set project rdchat-firebasev1
gcloud firestore fields ttls update expiresAt --collection-group=messages --enable-ttl
gcloud firestore fields ttls update expiresAt --collection-group=items --enable-ttl
gcloud firestore fields ttls list
```

Ou pelo Console Google Cloud: Firestore → Time-to-live → Create Policy, criando uma política para cada collection group com o campo `expiresAt`.

A criação da política pode demorar dez minutos ou mais. O TTL remove documentos normalmente dentro de até 24 horas após a expiração, não necessariamente no segundo exato. As exclusões TTL são cobradas como operações de exclusão e devem ser consideradas nos limites/custos do Firestore.

## 3. Conferir autenticação anônima e FCM

O HTML usa Firebase Authentication anônimo para obter o UID local da instalação. No Console Firebase, habilite o provedor **Anonymous** em Authentication → Sign-in method.

O app recebe FCM nativo. O Worker unificado está em `rdchat-fcm-worker/` e expõe:
- `POST /v1/push` — envia wake FCM após a gravação de texto no Firestore;
- `POST /v1/delivered` — grava confirmação de entrega enviada pelo Android;
- `POST /v1/media` — envia arquivo ao canal privado Telegram, cria o documento da mensagem/inbox e envia FCM;
- `GET /v1/media/{mediaId}` — baixa a mídia com autenticação e checagem de participante;
- `DELETE /v1/media/{mediaId}` — remove o arquivo do canal quando permitido pelo Telegram;
- `POST /v1/profile` — atualiza prévia compacta em `users`/`contactCodes` e armazena mídia completa no canal privado;
- `GET /v1/profile/{uid}` — permite ao próprio usuário ou a um contato adicionado baixar a mídia completa.

Para publicar, configure os GitHub Actions Secrets `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `FIREBASE_SERVICE_ACCOUNT`, `TELEGRAM_BOT_TOKEN` e `TELEGRAM_CHANNEL_ID`. Execute Actions → **Deploy RdChat FCM + Telegram Worker**. O workflow não revela os valores dos secrets. Não coloque service account, token do BotFather nem chave privada no HTML/APK/Git.

## 4. Configurar o Bot Telegram e a retenção de mídia

Crie um bot pelo BotFather e um canal **privado** exclusivo para os anexos. Adicione o bot como administrador com permissão para publicar e excluir mensagens. Cadastre `TELEGRAM_BOT_TOKEN` e `TELEGRAM_CHANNEL_ID` nos GitHub Actions Secrets; não os envie no chat nem os grave no repositório.

O Bot API oficial limita o download a 20 MB e a exclusão de mensagens a menos de 48 horas. Por isso o app limita anexos a 20.000.000 bytes e o Worker programa a limpeza remota por volta de 40–41 horas. O destinatário precisa baixar anexos de conversa dentro dessa janela. Mídia completa de perfil permanece disponível enquanto for a versão ativa; versões antigas que não puderem ser apagadas antes de 48 horas podem ficar como arquivos órfãos no canal privado. O texto continua com seu prazo próprio de sete dias. Consulte `rdchat-fcm-worker/README.md` antes de implantar.

## 5. App Check e enumeração de códigos

O diretório `contactCodes` permite apenas leitura por ID exato e bloqueia listagem, mas códigos numéricos de seis dígitos ainda podem ser tentados sequencialmente. Regras Firestore não fazem rate limiting.

Antes de impor App Check, registre o app Android e configure o provider suportado, teste em debug e produção, depois ative enforcement para Firestore. Para reduzir enumeração, migre a busca de código para endpoint autenticado com limitação de taxa e monitoramento.

## 6. Teste mínimo após publicar

Use duas instalações Android e duas contas:
1. criar conta em ambos os aparelhos e confirmar que cada conta tem `users/{uid}` e `contactCodes/{seisDigitos}`;
2. adicionar contato pelo código e conferir o UID resolvido;
3. enviar texto e verificar sala, mensagem, item de inbox e wake FCM;
4. conferir recebimento com app em primeiro plano, segundo plano e processo encerrado;
5. confirmar recibos, exclusão para todos e convites de grupo;
6. enviar e baixar imagem, vídeo, áudio, GIF e documento, sempre dentro de 20 MB, e verificar o recebimento FCM;
7. trocar foto/vídeo do perfil em uma conta e confirmar que o contato recebe a prévia Firestore e baixa a mídia completa pelo Worker;
8. excluir para todos e conferir que o arquivo é removido do canal privado;
9. conferir a limpeza automática de anexos após a janela de aproximadamente 40 horas;
10. testar notificações com app aberto, minimizado e processo encerrado.

O repositório agora contém o código do Worker, mas o estado da implantação Cloudflare e dos secrets não pode ser inferido do Git. Não considerar Modo A operacional até publicar o Worker e passar os testes com duas contas e dois aparelhos.
