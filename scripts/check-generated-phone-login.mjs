import fs from "node:fs";

const htmlPath = process.argv[2] || "app/build/generated/rdchatAssets/index.html";
if (!fs.existsSync(htmlPath)) {
  throw new Error(`HTML gerado do RdChat não encontrado: ${htmlPath}`);
}
const html = fs.readFileSync(htmlPath, "utf8");
const checks = [
  ["campo de telefone usa teclado numérico", /id="rdTgPhone"[^>]*inputmode="numeric"/.test(html)],
  ["máscara brasileira DDD + número é aplicada", html.includes("var ddd=digits.slice(0,2),rest=digits.slice(2)")],
  ["número local recebe +55 antes de chamar TDLib", html.includes("var phone='+55'+digits")],
  ["validação aceita somente 10 ou 11 dígitos nacionais", html.includes("^[1-9][1-9][0-9]{8,9}$")],
  ["texto orienta digitar apenas DDD + número", html.includes("Digite DDD + número com 10 ou 11 dígitos")],
  ["mensagem antiga que exigia +55 foi removida", !html.includes("Digite o telefone com código do país")],
  ["ponte TDLib permanece conectada", html.includes("T.iniciarLogin(phone)")],
  ["tela Com número mantém identidade visual de referência", html.includes("rdchat-login-number-reference-v1")]
];
const generatedChecks = [
  ["formulário de idade abre quando um recurso é bloqueado", html.includes("window.rdEnsureBirthDate(true)")],
  ["Ajustes oferece a opção Sua idade", html.includes("Sua idade") && html.includes("rdSettingsAgeSubtitle")],
  ["permissão do microfone espera resposta do Android", html.includes("requestMicrophonePermissionWithCallback") && html.includes("window.onRdChatNativePermissionResult") && !html.includes("await sleep(1600)")],
  ["conversa ativa sincroniza UID e e-mail", html.includes("setActiveChatIdentity(uid && !String(uid).includes")],
  ["lista de contatos usa sincronização recíproca", html.includes("rdchat-reciprocal-contact-sync-v1") && html.includes("syncContactsFromWorker")],
  ["FCM de contato não vira alerta de mensagem", html.includes("rdchat_contact_added_v1")],
  ["notificação contém identidade para abrir a conversa", html.includes("senderEmail = normEmail") && html.includes("RdChatOpenConversationFromNotification")],
  ["recibos do Firestore são enviados em lote", html.includes("markTextReadBatch")],
  ["WebView não cria notificação duplicada no Android", html.includes("if (window.AndroidBridge) return;")],
  ["câmera e microfone ficam disponíveis sob demanda em Ajustes", html.includes("window.AndroidBridge&&window.AndroidBridge.requestAppPermissions") && !html.includes("native.requestAppPermissions()")]
];
checks.push(...generatedChecks);
const sendStart = html.indexOf("async function sendMsg() {");
let composerClearsBeforeAwait = false;
if (sendStart >= 0) {
  const sendBlock = html.slice(sendStart, sendStart + 2200);
  const clearAt = sendBlock.indexOf("input.value = '';");
  const awaitAt = sendBlock.indexOf("const sent = await operation;");
  composerClearsBeforeAwait = clearAt >= 0 && awaitAt > clearAt;
}
checks.push(["compositor limpa antes da confirmação remota", composerClearsBeforeAwait]);
for (const [name, ok] of checks) {
  console.log(`${ok ? "PASS" : "FAIL"} ${name}`);
}
const failures = checks.filter(([, ok]) => !ok).map(([name]) => name);
console.log(`PHONE_LOGIN_CHECKS=${checks.length - failures.length}/${checks.length}`);
if (failures.length) {
  throw new Error(`Falhas no login brasileiro: ${failures.join("; ")}`);
}
