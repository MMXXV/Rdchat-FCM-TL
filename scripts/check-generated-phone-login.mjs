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
for (const [name, ok] of checks) {
  console.log(`${ok ? "PASS" : "FAIL"} ${name}`);
}
const failures = checks.filter(([, ok]) => !ok).map(([name]) => name);
console.log(`PHONE_LOGIN_CHECKS=${checks.length - failures.length}/${checks.length}`);
if (failures.length) {
  throw new Error(`Falhas no login brasileiro: ${failures.join("; ")}`);
}
