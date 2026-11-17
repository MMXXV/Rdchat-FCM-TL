import fs from "node:fs";
import path from "node:path";

const file = path.resolve("app/src/main/assets/index.html");
const html = fs.readFileSync(file, "utf8");
const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)];
let checked = 0;
const failures = [];

for (let index = 0; index < scripts.length; index++) {
  const attrs = scripts[index][1] || "";
  const source = scripts[index][2] || "";
  if (!source.trim() || /\bsrc\s*=/i.test(attrs)) continue;
  const type = (attrs.match(/\btype\s*=\s*["']([^"']+)["']/i) || [])[1] || "";
  if (type && !/(?:text|application)\/(?:java|ecma)script/i.test(type) && type.toLowerCase() !== "module") continue;
  if (type.toLowerCase() === "module") continue;
  try {
    new Function(source);
    checked++;
  } catch (error) {
    failures.push({ script: index + 1, message: error.message });
  }
}

const has = (value) => html.includes(value);
const checks = {
  singlePrimaryButton: (html.match(/id=["']authSubmit["']/g) || []).length === 1,
  noSeparateTelegramButtons: !has('id="rdTgSend"') && !has('id="rdTgCodeBtn"') && !has('id="rdTgPassBtn"'),
  phoneFieldAfterPin: html.indexOf('id="regPass"') < html.indexOf('id="rdTgPhoneF"'),
  sameButtonConfirmsCode: has("submit.textContent='Confirmar código'"),
  sameButtonConfirmsTwoStepPassword: has("submit.textContent='Confirmar senha'"),
  privacyInfoIcon: has('id="rdTgPhoneInfo"'),
  privacyInfoExactText: has('Seu número fica oculto: ninguém vê nem encontra você por ele. O RdChat usa só o código de 6 dígitos.'),
  onboardingStartsAtAuthorize: has("    s2();\n  })};\n})();\n</script>\n\n<!-- RDCHAT_V106_ARQUITETURA -->")
};
for (const [name, ok] of Object.entries(checks)) {
  if (!ok) failures.push({ check: name, message: "falhou" });
}
console.log(JSON.stringify({ inlineScriptsChecked: checked, checks }, null, 2));
if (failures.length) {
  console.error(JSON.stringify(failures, null, 2));
  process.exitCode = 1;
}
