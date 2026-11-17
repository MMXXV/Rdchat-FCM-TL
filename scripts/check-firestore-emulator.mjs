import crypto from "node:crypto";

const projectId = "demo-rdchat-rules";
const fakeApiKey = "fake-api-key";
const authBase = "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1";
const firestoreBase = `http://127.0.0.1:8080/v1/projects/${projectId}/databases/(default)/documents`;

async function json(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    signal: AbortSignal.timeout(15000),
    headers: {
      ...(options.body ? { "content-type": "application/json" } : {}),
      ...(options.headers || {})
    }
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(`${options.method || "GET"} ${url} -> HTTP ${response.status}: ${body.error?.message || response.statusText}`);
  }
  return body;
}

function fields(values) {
  const encoded = {};
  for (const [key, value] of Object.entries(values)) {
    if (typeof value === "boolean") encoded[key] = { booleanValue: value };
    else if (typeof value === "number") encoded[key] = { integerValue: String(Math.trunc(value)) };
    else encoded[key] = { stringValue: String(value) };
  }
  return { fields: encoded };
}

const account = await json(`${authBase}/accounts:signUp?key=${fakeApiKey}`, {
  method: "POST",
  body: JSON.stringify({ returnSecureToken: true })
});
const uid = String(account.localId || "");
const idToken = String(account.idToken || "");
if (!uid || !idToken) throw new Error("Auth Emulator did not return UID/token.");
const headers = { Authorization: `Bearer ${idToken}` };
const code = String(crypto.randomInt(100000, 1000000));
const email = `rdchat-emulator-${uid}@rdchat.local`;
const now = Date.now();
let created = { contactCodes: false, users: false, presence: false };

try {
  await json(`${firestoreBase}/contactCodes?documentId=${code}`, {
    method: "POST",
    headers,
    body: JSON.stringify(fields({
      uid, email, name: "RdChat Emulator Test", code,
      accountType: "SEM_NUMERO", tgUsername: "", is_adult: true, updatedAt: now
    }))
  });
  created.contactCodes = true;

  await json(`${firestoreBase}/users?documentId=${uid}`, {
    method: "POST",
    headers,
    body: JSON.stringify(fields({
      uid, email, displayName: "RdChat Emulator Test", code,
      accountType: "SEM_NUMERO", tgUserId: "", tgUsername: "", is_adult: true, updatedAt: now
    }))
  });
  created.users = true;

  await json(`${firestoreBase}/presence?documentId=${uid}`, {
    method: "POST",
    headers,
    body: JSON.stringify(fields({ uid, email, online: true, updatedAt: now }))
  });
  created.presence = true;

  const mask = new URLSearchParams();
  for (const field of ["profileMediaVersion", "profileMediaType", "profileMediaName"]) {
    mask.append("updateMask.fieldPaths", field);
  }
  await json(`${firestoreBase}/contactCodes/${code}?${mask.toString()}`, {
    method: "PATCH",
    headers,
    body: JSON.stringify(fields({
      profileMediaVersion: "emulator-v1",
      profileMediaType: "image/jpeg",
      profileMediaName: "emulator.jpg"
    }))
  });

  console.log(JSON.stringify({
    anonymousAuth: "enabled",
    contactCodesCreate: "allowed",
    usersCreate: "allowed",
    presenceCreate: "allowed",
    profileMetadataUpdate: "allowed"
  }, null, 2));
} finally {
  for (const [collection, docId] of [
    ["presence", uid],
    ["users", uid],
    ["contactCodes", code]
  ]) {
    if (!created[collection]) continue;
    await json(`${firestoreBase}/${collection}/${encodeURIComponent(docId)}`, {
      method: "DELETE", headers
    }).catch(error => console.warn("Emulator cleanup failed:", error.message));
  }
  await json(`${authBase}/accounts:delete?key=${fakeApiKey}`, {
    method: "POST",
    body: JSON.stringify({ idToken })
  }).catch(error => console.warn("Auth emulator cleanup failed:", error.message));
}
