# Relatório técnico RdChat — integração TDLib / Modo B
**Data:** 18/11/2026  
**Repositório:** `MMXXV/Rdchat-FCM-TL`  
**Escopo:** reaproveitar e corrigir a implementação existente; manter a interface HTML/WebView e o Modo A. Não foi criado um cliente Telegram separado.

## O que foi alterado

### 1. TDLib / sessão do Modo B
- Explicitado `useTestDc = false` para usar o ambiente de produção.
- Preparação e validação de escrita dos diretórios locais de banco e arquivos antes de enviar os parâmetros.
- A versão de aplicação enviada ao TDLib passou a acompanhar `BuildConfig.VERSION_NAME`, evitando versão divergente fixa.
- Mantida a chave de criptografia local protegida pelo Android Keystore.
- As operações expostas pela ponte (código da conta, download de arquivo e recibo) passaram a usar o executor serial já existente. A autenticação, envio, push e chamadas continuam nessa mesma sessão gerenciada.

### 2. Recibos e preservação das mensagens
- Corrigida a confirmação nativa: em vez de `DeleteMessages(..., revoke=true)`, agora usa `ViewMessages(..., forceRead=true)`. O recibo marca a mensagem como lida e não solicita apagá-la para todos.
- Texto só confirma ao Telegram depois que `RdChatReceiveFirebaseText` termina e devolve confirmação explícita de persistência.
- Mídia também aguarda o download e a gravação local antes de confirmar. Falha de download ou de gravação mantém a mensagem sem recibo, para nova tentativa.
- A repetição do histórico verifica o IndexedDB antes de inserir novamente a mensagem, reduzindo duplicatas após reconexão ou reinício.

### 3. Push Telegram e recuperação de histórico
- Push recebido pelo fluxo já existente agora é colocado em fila persistente local (até 100 payloads) até a sessão TDLib ficar pronta; falhas de processamento não descartam o payload.
- Os chats Telegram já usados pelo transporte RdChat são lembrados localmente.
- Após a autenticação, o app carrega a lista TDLib e consulta até 100 mensagens recentes por chat conhecido, filtrando apenas envelopes `RDC1` de entrada. Conversas pessoais do Telegram fora desse contrato não são importadas para a interface RdChat.
- O limite atual é intencional: histórico recente e chats conhecidos, não uma sincronização completa de toda a conta Telegram.

### 4. HTML e Gradle
- O HTML final que antes era produzido por 33 substituições no Gradle foi materializado em `app/src/main/assets/index.html`.
- Gradle deixou de copiar, reescrever e selecionar uma pasta de assets gerada; agora usa a fonte de assets padrão do Android. A tarefa de validação das credenciais TDLib permanece.
- O Gradle caiu de aproximadamente 940 linhas para cerca de 110 linhas. Os patches `replaceExactlyOnce`, `prepareRdchatAssets` e `generatedRdchatAssets` foram removidos.
- Corrigido o caso em que `sendMsg()` limpava o campo e não restaurava o texto quando o envio retornava sem mensagem.
- Atualizada a verificação estática do telefone para o contrato efetivamente usado pelo RdChat: número nacional brasileiro de 10/11 dígitos, com `+55` aplicado antes da chamada à TDLib.

### 5. Limpeza cautelosa
- Removidas da árvore ativa as cópias antigas em `integration-staging/tdlib` e o protótipo incompatível `integration-staging/google-apps-script-nip17`. Os conteúdos continuam recuperáveis pelo histórico Git.
- Mantidos `TelegramCallEngine`, `TelegramCallForegroundService`, Worker FCM/Telegram, `RdChatMediaHandler` e a política que mantém P2P/Trystero desativado. Esses componentes ainda têm referências/contratos ativos; não foram apagados por aparência de código antigo.
- O workflow de verificação passou a ser acionado também por mudanças Kotlin e Gradle.

## Referências utilizadas
- **TGX-Android/Telegram-X:** referência para o ciclo de autorização, atualizações, arquivos, chamadas e notificações; não foi importado como aplicativo.
- **TelegramExample:** referência direta para `GetChats`, `GetChatHistory`, envio e transições de autorização.
- **Mategram:** referência conceitual para sessão centralizada, execução serial e separação de responsabilidades; não foi copiado código GPL-3.0.
- Não foram substituídas as bibliotecas TDLib atuais por um fork do Telegram X, nem foi importada a UI desses projetos.

## Validação automatizada e ressalvas

- O workflow de materialização do HTML terminou com sucesso: [run 44232133666](https://github.com/MMXXV/Rdchat-FCM-TL/actions/runs/44232133666).
- Um build Android com as alterações Kotlin iniciais passou, incluindo testes unitários e verificação das quatro ABIs: [run 44232133710](https://github.com/MMXXV/Rdchat-FCM-TL/actions/runs/44232133710). Esse build antecedeu a última correção do recibo de mídia e a adoção final do HTML canônico; portanto não é considerado a validação final deste relatório.
- Na primeira execução sobre o HTML canônico, XML/JSON, emulator Firestore rules e live Firebase smoke passaram; a validação de JavaScript interrompeu o job por uma asserção antiga que esperava caracteres internacionais no campo, enquanto o fluxo atual normaliza o número brasileiro. Essa asserção foi atualizada para testar a normalização real; a nova execução está sendo validada separadamente.
- Compilação automatizada não substitui teste físico. Não foi possível confirmar login por SMS/2FA, push com processo encerrado, mídia, chamadas ou recuperação de rede em dois aparelhos reais.

## O que ainda falta

1. **Teste com duas contas e dois aparelhos:** login telefônico real, código, senha 2FA, reiniciar app, histórico, texto, mídia, recibos, perda e retorno de internet.
2. **Autonomia de push em segundo plano:** o serviço FCM nativo ainda persiste o payload quando não há WebView; a ponte o entrega à TDLib quando o app/WebView volta. A fila nova do gerenciador não equivale a um serviço TDLib independente da Activity.
3. **Histórico completo:** a recuperação atual cobre até 100 mensagens recentes por chat RdChat previamente conhecido; não importa conversas desconhecidas nem histórico ilimitado.
4. **Mídia e chamadas:** confirmar ponta a ponta fotos, vídeos, áudio/voz, GIFs, documentos, limites de tamanho, permissão de câmera/microfone e chamadas reais em duas contas. A implementação de chamadas foi mantida, mas não testada fisicamente.
5. **Publicação do backend:** a atualização mais recente do Worker não foi confirmada em produção porque o workflow anterior não tinha secrets Cloudflare/Telegram necessários. A publicação de regras Firestore também falhou por permissão IAM 403 (`serviceusage.services.get`). Os testes emulador e smoke do cliente não provam que esses deployments estejam atualizados.
6. **Segurança e privacidade:** revisar App Check, limites antiabuso e estratégia E2EE. O Modo B atualmente usa username Telegram para localizar a conta; o username técnico criado automaticamente é público e isso é diferente de ocultar o número de telefone.
7. **Desempenho:** ainda é necessário medir tempo de inicialização, memória, CPU, jank e ANR em aparelhos reais. CSS legado e regras `!important` não foram consolidados nesta etapa; a redução de patches Gradle melhora manutenção, mas não prova ganho de FPS ou ausência de lag.
8. **Distribuição:** validar assinatura de release e instalar o APK final nos aparelhos de teste.

**Conclusão:** as correções de baixo risco e a primeira etapa de recuperação/sincronização TDLib foram incorporadas sem refazer a aplicação. O Modo B está mais preparado para retomar sessão, reprocessar push, recuperar histórico RdChat conhecido e confirmar mensagens sem apagá-las. Ainda não deve ser declarado “concluído” até os testes reais de duas contas e a validação do push independente da WebView.
