import fs from "node:fs";
import path from "node:path";

const file = path.resolve("app/src/main/assets/index.html");
const html = fs.readFileSync(file, "utf8");
const failures = [];
let inlineScriptsChecked = 0;

const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)];
for (let index = 0; index < scripts.length; index++) {
  const attrs = scripts[index][1] || "";
  const source = scripts[index][2] || "";
  if (!source.trim() || /\bsrc\s*=/i.test(attrs)) continue;
  const type = (attrs.match(/\btype\s*=\s*["']([^"']+)["']/i) || [])[1] || "";
  if (type.toLowerCase() === "module") continue;
  if (type && !/(?:text|application)\/(?:java|ecma)script/i.test(type)) continue;
  try {
    new Function(source);
    inlineScriptsChecked++;
  } catch (error) {
    failures.push({ check: "inline-script-" + (index + 1), detail: error.message });
  }
}

const has = (value) => html.includes(value);
const count = (value) => html.split(value).length - 1;
const loginStart = html.indexOf('<div class="screen active no-pad" id="screen-login">');
const loginEnd = html.indexOf("<!-- ABA ONLINE -->", loginStart);
const login = loginStart >= 0 && loginEnd > loginStart ? html.slice(loginStart, loginEnd) : "";
const phoneEyeStart = login.indexOf('id="rdTgPhoneEye"');
const phoneEyeEnd = login.indexOf("</button>", phoneEyeStart);
const phoneEyeMarkup = phoneEyeStart >= 0 && phoneEyeEnd >= 0 ? login.slice(phoneEyeStart, phoneEyeEnd) : "";
const firebaseScripts = [...html.matchAll(/<script\b[^>]*\bsrc=["']([^"']*\/firebasejs\/([0-9.]+)\/firebase-(app|auth|firestore)-compat\.js)["'][^>]*>/gi)]
  .map((match) => ({ url: match[1], version: match[2], module: match[3] }));
const firebaseModules = new Set(firebaseScripts.map((entry) => entry.module));
const sdkVersion = firebaseScripts[0]?.version || "";

const checks = {
  loginSectionExists: login.length > 0,
  singlePrimaryButton: count('id="authSubmit"') === 1,
  noExtraTelegramSubmitButtons: !has('id="rdTgSend"') && !has('id="rdTgCodeBtn"') && !has('id="rdTgPassBtn"'),
  staticModeSelector: login.includes('<div class="rd-modes" id="rdModes"'),
  staticPhoneField: login.includes('<div class="field rd-tg-step" id="rdTgPhoneF" hidden>'),
  staticPhoneInput: login.includes('id="rdTgPhone"'),
  staticPhoneEye: login.includes('id="rdTgPhoneEye"'),
  staticTelegramCodeField: login.includes('id="rdTgCodeF"') && login.includes('id="rdTgCode"'),
  staticTelegramTwoStepField: login.includes('id="rdTgPassF"') && login.includes('id="rdTgPass"'),
  phoneFieldImmediatelyAfterPin: login.indexOf('id="regPass"') >= 0 &&
    login.indexOf('id="rdTgPhoneF"') > login.indexOf('id="regPass"') &&
    login.indexOf('id="rdTgPhoneF"') < login.indexOf('id="authSubmit"'),
  codeAndPasswordFollowPhone: login.indexOf('id="rdTgPhoneF"') < login.indexOf('id="rdTgCodeF"') &&
    login.indexOf('id="rdTgCodeF"') < login.indexOf('id="rdTgPassF"'),
  uniquePhoneField: count('id="rdTgPhoneF"') === 1,
  noRuntimePhoneFieldConstruction: !has("'<div class=\"field rd-tg-step\" id=\"rdTgPhoneF\" hidden>'"),
  modeButtonChangesMode: has("mode=button.getAttribute('data-m')==='B'?'B':'A'"),
  phoneFieldShownInModeB: has("phoneField.hidden=!isPhoneMode"),
  phoneFieldHiddenInModeA: has("phoneField.hidden=!isPhoneMode"),
  oneButtonConfirmsTelegramCode: has("submit.textContent='Confirmar código'"),
  oneButtonConfirmsTelegramTwoStep: has("submit.textContent='Confirmar senha'"),
  phoneEyeTogglesMask: has("phone.type=showing?'tel':'password'"),
  phoneEyeHasAccessibleLabel: has("phoneEye.setAttribute('aria-label',showing?'Ocultar número':'Mostrar número')"),
  phoneEyeUsesEyeIcon: phoneEyeMarkup.includes("M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"),
  phoneLoginNormalizesBrazilianNationalNumber: has("var digits=String($('rdTgPhone').value||'').replace(/\\\\D/g,'')") &&
    has("digits.length===12||digits.length===13") &&
    has("var phone='+55'+digits") &&
    has("O +55 será adicionado automaticamente."),
  pinEnterDoesNotSubmitLocalRegisterInModeB: has("event.stopImmediatePropagation();"),
  preservesOriginalUserPasswordFlow: has("return originalSubmitAuthMode&&originalSubmitAuthMode.apply(this,arguments)"),
  nativeCameraMicPermissionBridge: has("requestCameraAndMicrophonePermissions"),
  firebaseCompatHasAppAuthFirestore: firebaseModules.has("app") && firebaseModules.has("auth") && firebaseModules.has("firestore"),
  firebaseCompatUsesSinglePatchedVersion: firebaseScripts.length === 3 &&
    firebaseModules.size === 3 && firebaseScripts.every((entry) => entry.version === "13.3.0"),
  firebaseSdkLoadErrorsAreCaptured: has("window.__RDCHAT_FIREBASE_SDK_ERROR="),
  firebaseErrorMapsAnonymousAuthDisabled: has("code === 'auth/operation-not-allowed'"),
  firebaseErrorMapsFirestorePermissionDenied: has("code === 'permission-denied'"),
  firebaseFailureIncludesStageAndCode: has("safeToast('Firebase · ' + stage + ' · ' + detail + ' [' + code + ']')"),
  firebaseReadyEventOnlyAfterCoreStartup: has("window.dispatchEvent(new CustomEvent('rdchat-firebase-ready'))"),
  phonePrivacyFailureWarnsUser: has("Atenção: o Telegram não confirmou todas as restrições do número."),
  noOldPrivacyIntro: !has("Leve a privacidade com você.") && !has("Seja você em cada mensagem.")
};

for (const [name, ok] of Object.entries(checks)) {
  if (!ok) failures.push({ check: name, detail: "falhou" });
}

console.log(JSON.stringify({ inlineScriptsChecked, checks, failures }, null, 2));
if (failures.length) process.exitCode = 1;
