import fs from "node:fs";
import crypto from "node:crypto";

const google = JSON.parse(fs.readFileSync("app/google-services.json", "utf8"));
const projectId = google.project_info?.project_id;
const androidClient = (google.client || []).find(
  (client) => client.client_info?.android_client_info?.package_name === "com.example.rdchat"
);
const apiKey = androidClient?.api_key?.[0]?.current_key;
if (!projectId || !apiKey) {
  throw new Error("Não foi possível ler project_id/API key do app/google-services.json.");
}

const authBase = "https://identitytoolkit.googleapis.com/v1";
const firestoreBase = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/databases/(default)/documents`;

async function requestJson(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    signal: AbortSignal.timeout(20000),
    headers: {
      ...(options.body ? { "content-type": "application/json" } : {}),
      ...(options.headers || {})
    }
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = body?.error?.message || body?.error?.status || response.statusText || "Erro desconhecido";
    const error = new Error(`HTTP ${response.status}: ${message}`);
    error.status = response.status;
    error.firebaseCode = body?.error?.message || body?.error?.status || "";
    throw error;
  }
  return body;
}

function fields(values) {
  const result = {};
  for (const [key, value] of Object.entries(values)) {
    if (typeof value === "boolean") result[key] = { booleanValue: value };
    else if (typeof value === "number") result[key] = { integerValue: String(Math.trunc(value)) };
    else result[key] = { stringValue: String(value) };
  }
  return { fields: result };
}

const result = {
  projectId,
  anonymousAuth: "not-tested",
  firestoreUsersRead: "not-tested",
  firestoreContactCodesRead: "not-tested",
  firestoreContactCodesCreate: "not-tested",
  firestoreUsersCreate: "not-tested",
  firestorePresenceCreate: "not-tested",
  profileMetadataRules: "not-tested",
  cleanup: { firestore: "not-needed", anonymousUser: "not-needed" }
};

let idToken = "";
let uid = "";
let userDocCreated = false;
let contactDocCreated = false;
let presenceDocCreated = false;
let testCode = "";
let stage = "Firebase Auth anonymous";

try {
  const account = await requestJson(`${authBase}/accounts:signUp?key=${encodeURIComponent(apiKey)}`, {
    method: "POST",
    body: JSON.stringify({ returnSecureToken: true })
  });
  idToken = String(account.idToken || "");
  uid = String(account.localId || "");
  if (!idToken || !uid) throw new Error("Firebase Auth não devolveu idToken/localId.");
  result.anonymousAuth = "enabled";
  console.log("FIREBASE_ANONYMOUS_AUTH=enabled");

  const authHeaders = { Authorization: `Bearer ${idToken}` };
  const smokeEmail = `rdchat-smoke-${uid.toLowerCase()}@rdchat.local`;
  const now = Date.now();

  stage = "Firestore users read authorization";
  try {
    await requestJson(`${firestoreBase}/users/${encodeURIComponent(uid)}`, {
      headers: authHeaders
    });
    result.firestoreUsersRead = "read-existing";
  } catch (error) {
    if (error.status === 404) result.firestoreUsersRead = "allowed-empty";
    else throw error;
  }
  console.log("FIRESTORE_USERS_READ=" + result.firestoreUsersRead);

  stage = "Firestore contactCodes read authorization";
  let foundFreeCode = false;
  for (let attempt = 0; attempt < 12; attempt++) {
    testCode = String(crypto.randomInt(100000, 1000000));
    try {
      await requestJson(`${firestoreBase}/contactCodes/${testCode}`, {
        headers: authHeaders
      });
    } catch (error) {
      if (error.status === 404) {
        foundFreeCode = true;
        result.firestoreContactCodesRead = "allowed-empty";
        break;
      }
      throw error;
    }
  }
  if (!foundFreeCode) throw new Error("Não foi possível reservar um código de contato temporário.");
  console.log("FIRESTORE_CONTACT_CODES_READ=allowed");

  stage = "Firestore contactCodes create (campos essenciais do login)";
  const contactUrl = `${firestoreBase}/contactCodes?documentId=${encodeURIComponent(testCode)}`;
  await requestJson(contactUrl, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify(fields({
      uid,
      email: smokeEmail,
      name: "RdChat Firebase Smoke Test",
      code: testCode,
      accountType: "SEM_NUMERO",
      tgUsername: "",
      is_adult: true,
      updatedAt: now
    }))
  });
  contactDocCreated = true;
  result.firestoreContactCodesCreate = "allowed";
  console.log("FIRESTORE_CONTACT_CODES_CREATE=allowed");

  stage = "Firestore users create (campos essenciais do login)";
  const userUrl = `${firestoreBase}/users?documentId=${encodeURIComponent(uid)}`;
  await requestJson(userUrl, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify(fields({
      uid,
      email: smokeEmail,
      displayName: "RdChat Firebase Smoke Test",
      code: testCode,
      accountType: "SEM_NUMERO",
      tgUserId: "",
      tgUsername: "",
      is_adult: true,
      updatedAt: now
    }))
  });
  userDocCreated = true;
  result.firestoreUsersCreate = "allowed";
  console.log("FIRESTORE_USERS_CREATE=allowed");

  stage = "Firestore presence create";
  const presenceUrl = `${firestoreBase}/presence?documentId=${encodeURIComponent(uid)}`;
  await requestJson(presenceUrl, {
    method: "POST",
    headers: authHeaders,
    body: JSON.stringify(fields({ uid, email: smokeEmail, online: true, updatedAt: now }))
  });
  presenceDocCreated = true;
  result.firestorePresenceCreate = "allowed";
  console.log("FIRESTORE_PRESENCE_CREATE=allowed");

  stage = "Firestore contactCodes profile metadata update";
  const metadataQuery = new URLSearchParams();
  for (const field of ["profileMediaVersion", "profileMediaType", "profileMediaName"]) {
    metadataQuery.append("updateMask.fieldPaths", field);
  }
  await requestJson(
    `${firestoreBase}/contactCodes/${encodeURIComponent(testCode)}?${metadataQuery.toString()}`,
    {
      method: "PATCH",
      headers: authHeaders,
      body: JSON.stringify(fields({
        profileMediaVersion: "smoke-test-v1",
        profileMediaType: "image/jpeg",
        profileMediaName: "smoke-test.jpg"
      }))
    }
  );
  result.profileMetadataRules = "allowed";
  console.log("FIRESTORE_PROFILE_METADATA_RULES=allowed");
} catch (error) {
  const message = String(error.message || error);
  result.failureStage = stage;
  result.failure = message;
  if (stage.startsWith("Firestore contactCodes profile metadata")) {
    result.profileMetadataRules = "denied";
    result.profileMetadataFailure = message;
  }
  console.error("FIREBASE_CLIENT_SMOKE_FAILED stage=" + stage + " error=" + message);
} finally {
  if (uid && idToken) {
    const headers = { Authorization: `Bearer ${idToken}` };
    const cleanupErrors = [];
    const deletions = [
      presenceDocCreated ? `/presence/${encodeURIComponent(uid)}` : "",
      userDocCreated ? `/users/${encodeURIComponent(uid)}` : "",
      contactDocCreated && testCode ? `/contactCodes/${encodeURIComponent(testCode)}` : ""
    ].filter(Boolean);
    for (const path of deletions) {
      try {
        await requestJson(`${firestoreBase}${path}`, {
          method: "DELETE",
          headers
        });
      } catch (error) {
        cleanupErrors.push(`${path}: ${error.message}`);
      }
    }
    result.cleanup.firestore = cleanupErrors.length ? cleanupErrors.join(" | ") : "removed";
    try {
      await requestJson(`${authBase}/accounts:delete?key=${encodeURIComponent(apiKey)}`, {
        method: "POST",
        body: JSON.stringify({ idToken })
      });
      result.cleanup.anonymousUser = "removed";
    } catch (error) {
      result.cleanup.anonymousUser = `cleanup-failed: ${error.message}`;
    }
    console.log("FIREBASE_SMOKE_CLEANUP=" + JSON.stringify(result.cleanup));
  }
}

console.log(JSON.stringify(result, null, 2));
if (result.failure) process.exitCode = 1;
if (result.cleanup.firestore !== "not-needed" && result.cleanup.firestore !== "removed") process.exitCode = 1;
if (result.cleanup.anonymousUser.startsWith("cleanup-failed")) process.exitCode = 1;
