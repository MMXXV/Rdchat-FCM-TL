# Apps Script NIP-17 — alternativa não ativa

Código de referência separado do serviço em produção. Não publique por cima do Worker FCM atual.

O script espera chave compartilhada, ações `send_text/send_ack/wake`, eventos NIP-17 kind 1059 e fragmentos cifrados `sealedChunks`. O HTML atualmente grava texto no Firestore e chama Worker `/v1/push` com Firebase ID token e `roomId/messageId/kind/body`. O HTML não implementa geração, decriptação ou remontagem NIP-17.

Para adotar esta alternativa será necessário substituir/implementar o protocolo completo no cliente e no serviço. Não coloque a chave do Apps Script no HTML nem APK.
