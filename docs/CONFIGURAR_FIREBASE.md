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

O app recebe FCM nativo e usa o Worker existente como gateway. O Worker FCM precisa continuar aceitando:
- `POST /v1/push` — recebe o wake enviado pelo HTML após a gravação no Firestore;
- `POST /v1/delivered` — confirmação do serviço Android.

O código-fonte desse Worker não está no repositório; não foi alterado. Verifique no ambiente Worker os bindings/segredos, Firebase project ID, service account e autorização para FCM HTTP v1. Não coloque service account, chave privada ou chave de gateway no HTML, APK ou Git.

## 4. App Check e enumeração de códigos

O diretório `contactCodes` permite apenas leitura por ID exato e bloqueia listagem, mas códigos numéricos de seis dígitos ainda podem ser tentados sequencialmente. Regras Firestore não fazem rate limiting.

Antes de impor App Check, registre o app Android e configure o provider suportado, teste em debug e produção, depois ative enforcement para Firestore. Para reduzir enumeração, migre a busca de código para endpoint autenticado com limitação de taxa e monitoramento.

## 5. Teste mínimo após publicar

Use duas instalações Android e duas contas:
1. criar conta em ambos os aparelhos e confirmar que cada conta tem `users/{uid}` e `contactCodes/{seisDigitos}`;
2. adicionar contato pelo código e conferir o UID resolvido;
3. enviar texto e verificar sala, mensagem, item de inbox e wake FCM;
4. conferir recebimento com app em primeiro plano, segundo plano e processo encerrado;
5. confirmar recibos, exclusão para todos e convites de grupo;
6. confirmar arquivos no Worker `/v1/media` e notificações;
7. esperar o TTL e conferir remoção automática na nuvem, preservando histórico local.

Não considerar o backend validado até passar esses testes. Este repositório não contém os fontes dos Workers.
