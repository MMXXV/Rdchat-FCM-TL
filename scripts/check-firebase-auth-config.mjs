import crypto from "node:crypto";

const PROJECT_ID = "rdchat-firebasev1";
const serviceJson = process.env.FIREBASE_SERVICE_ACCOUNT || "";
if (!serviceJson) {
  console.error("FIREBASE_SERVICE_ACCOUNT não está configurado nos secrets do repositório.");
  process.exit(1);
}

let service;
try {
  service = JSON.parse(serviceJson);
} catch {
  console.error("FIREBASE_SERVICE_ACCOUNT não contém JSON válido.");
  process.exit(1);
}
if (service.type !== "service_account" || service.project_id !== PROJECT_ID ||
    !service.client_email || !service.private_key) {
  console.error("A credencial de serviço não corresponde ao projeto esperado ou está incompleta.");
  process.exit(1);
}

const base64url = (value) => Buffer.from(value).toString("base64url");
const now = Math.floor(Date.now() / 1000);
const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
const claims = base64url(JSON.stringify({
  iss: service.client_email,
  sub: service.client_email,
  aud: "https://oauth2.googleapis.com/token",
  iat: now,
  exp: now + 3300,
  scope: "https://www.googleapis.com/auth/cloud-platform"
}));
const unsigned = header + "." + claims;
const signer = crypto.createSign("RSA-SHA256");
signer.update(unsigned);
signer.end();
const assertion = unsigned + "." + signer.sign(service.private_key, "base64url");

const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
  method: "POST",
  headers: { "Content-Type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion
  })
});
const tokenPayload = await tokenResponse.json().catch(() => ({}));
if (!tokenResponse.ok || !tokenPayload.access_token) {
  console.error("Não foi possível autenticar a conta de serviço no Google OAuth.",
    tokenPayload.error_description || tokenPayload.error || tokenResponse.status);
  process.exit(1);
}

const configResponse = await fetch(
  "https://identitytoolkit.googleapis.com/admin/v2/projects/" + PROJECT_ID + "/config",
  { headers: { Authorization: "Bearer " + tokenPayload.access_token } }
);
const configPayload = await configResponse.json().catch(() => ({}));
if (!configResponse.ok) {
  const status = configResponse.status;
  if (status === 403) {
    console.error("A conta de serviço não tem permissão para ler Firebase Authentication. Conceda a permissão firebaseauth.configs.get (por exemplo, Firebase Authentication Admin) e habilite a Identity Toolkit API.");
  } else if (status === 404) {
    console.error("O projeto ou a configuração do Firebase Authentication não foi encontrado. Confirme se Authentication está inicializado no projeto.");
  } else {
    console.error("Falha ao consultar a configuração real do Firebase Authentication.",
      configPayload.error?.message || configResponse.statusText || status);
  }
  process.exit(1);
}

const anonymousEnabled = configPayload.signIn?.anonymous?.enabled === true;
console.log(JSON.stringify({
  projectId: PROJECT_ID,
  anonymousSignInEnabled: anonymousEnabled,
  phoneSignInEnabled: configPayload.signIn?.phoneNumber?.enabled === true,
  authorizedDomainsCount: Array.isArray(configPayload.authorizedDomains) ? configPayload.authorizedDomains.length : 0
}, null, 2));

if (!anonymousEnabled) {
  console.error("CAUSA CONFIRMADA: o provedor de autenticação anônima está DESATIVADO no Firebase. Ative em Firebase Console → Authentication → Sign-in method → Anonymous.");
  process.exit(1);
}
