# TDLib Android — integração pendente

Este material está isolado e **não faz parte do módulo :app nem do APK atual**.

A versão antiga continha API_HASH hardcoded e usava outro package. A cópia foi sanitizada: o manager e a ponte recebem a credencial por parâmetro e usam o package correto. Não preencha, commite ou imprima credenciais aqui.

Antes de integrar:
1. Revogar/rotacionar o API_HASH que apareceu na história pública do repositório.
2. Obter/buildar TDLib Android e fornecer AAR + JNI .so para arm64-v8a (e x86_64 para emulador).
3. Criar a configuração local segura das credenciais.
4. Implementar singleton de processo/serviço, persistência de auth e registro push TDLib.
5. Substituir Base64/data URL por URI/streaming para arquivos grandes.
6. Validar callbacks de `window.RdChatTdlib` e entrega com processo encerrado.

O constructor agora exige `apiHash`; nenhuma credencial foi incluída nesta pasta.
