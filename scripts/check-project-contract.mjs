import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const read = (file) => fs.readFileSync(path.join(root, file), "utf8");
const parseJson = (file) => {
  try { return JSON.parse(read(file)); }
  catch (error) { throw new Error(file + " não contém JSON válido: " + error.message); }
};
const failures = [];
const checks = {};
const check = (name, condition, detail = "") => {
  checks[name] = Boolean(condition);
  if (!condition) failures.push({ check: name, detail });
};
const contains = (source, fragment) => source.includes(fragment);

const firebaseRc = parseJson(".firebaserc");
const firebaseConfig = parseJson("firebase.json");
const googleServices = parseJson("app/google-services.json");
const firestoreIndexes = parseJson("firestore.indexes.json");
const workerPackage = parseJson("rdchat-fcm-worker/package.json");

const html = read("app/src/main/assets/index.html");
const gradle = read("app/build.gradle.kts");
const manifest = read("app/src/main/AndroidManifest.xml");
const main = read("app/src/main/java/com/example/rdchat/MainActivity.kt");
const webInterface = read("app/src/main/java/com/example/rdchat/telegram/WebAppInterface.kt");
const fcmService = read("app/src/main/java/com/example/rdchat/MyFirebaseMessagingService.kt");
const worker = read("rdchat-fcm-worker/src/index.js");
const rules = read("firestore.rules");
const firebaseDeployWorkflow = read(".github/workflows/deploy-firestore-rules.yml");
const firebaseWorkerWorkflow = read(".github/workflows/deploy-fcm-worker.yml");
const androidWorkflow = read(".github/workflows/android-build.yml");

const expectedProject = "rdchat-firebasev1";
const applicationId = "com.example.rdchat";
const projectId = googleServices.project_info?.project_id || "";
const senderId = String(googleServices.project_info?.project_number || "");
const androidClients = (googleServices.client || [])
  .map((client) => client.client_info?.android_client_info?.package_name);
const webConfig = {
  projectId: (html.match(/projectId\s*:\s*['"]([^'"]+)['"]/) || [])[1] || "",
  authDomain: (html.match(/authDomain\s*:\s*['"]([^'"]+)['"]/) || [])[1] || "",
  senderId: (html.match(/messagingSenderId\s*:\s*['"]([^'"]+)['"]/) || [])[1] || ""
};
const domain = (main.match(/WORKER_DOMAIN\s*=\s*"([^"]+)"/) || [])[1] || "";
const appUrl = (main.match(/APP_URL\s*=\s*"https:\/\/([^/]+)\/assets\/index\.html"/) || [])[1] || "";
const assetPrefix = (main.match(/ASSET_PREFIX\s*=\s*"https:\/\/([^/]+)\/assets\/"/) || [])[1] || "";
const ruleMetadataFields = ["profileMediaVersion", "profileMediaType", "profileMediaName"];

check("firebase_project_alias_matches_google_services", firebaseRc.projects?.default === projectId && projectId === expectedProject, String(projectId));
check("firebase_json_declares_rules_and_indexes", firebaseConfig.firestore?.rules === "firestore.rules" && firebaseConfig.firestore?.indexes === "firestore.indexes.json");
check("web_sdk_project_matches_android_config", webConfig.projectId === projectId);
check("web_auth_domain_matches_project", webConfig.authDomain === projectId + ".firebaseapp.com");
check("web_messaging_sender_matches_android", webConfig.senderId === senderId);
check("android_application_id_matches_registered_client", contains(gradle, 'applicationId = "' + applicationId + '"') && androidClients.includes(applicationId));
check("webview_asset_loader_domain_matches_loaded_url", Boolean(domain) && domain === appUrl && appUrl === assetPrefix);
check("firebase_sdk_urls_are_13_3_0", (html.match(/firebasejs\/13\.3\.0\/firebase-(?:app|auth|firestore)-compat\.js/g) || []).length === 3);
check("firestore_indexes_json_is_well_formed", Array.isArray(firestoreIndexes.indexes) && Array.isArray(firestoreIndexes.fieldOverrides));
check("firestore_rules_limit_contact_code_fields", ruleMetadataFields.every((field) => rules.includes("'" + field + "'")));
check("worker_writes_same_profile_metadata", ruleMetadataFields.every((field) => worker.includes(field)));
check("firebase_rules_deploy_workflow_uses_project_secret", firebaseDeployWorkflow.includes("FIREBASE_SERVICE_ACCOUNT") && firebaseDeployWorkflow.includes("firestore:rules"));
check("firebase_worker_workflow_keeps_service_account", firebaseWorkerWorkflow.includes("FIREBASE_SERVICE_ACCOUNT"));
check("android_build_checks_telegram_hash_secret", androidWorkflow.includes("TELEGRAM_API_HASH"));
check("manifest_has_internet_and_network", manifest.includes('android.permission.INTERNET') && manifest.includes('android.permission.ACCESS_NETWORK_STATE'));
check("manifest_has_camera_microphone_and_notifications", manifest.includes('android.permission.CAMERA') && manifest.includes('android.permission.RECORD_AUDIO') && manifest.includes('android.permission.POST_NOTIFICATIONS'));
check("manifest_has_foreground_microphone_camera", manifest.includes('android:foregroundServiceType="microphone|camera"'));
check("manifest_disables_cleartext", manifest.includes('android:usesCleartextTraffic="false"'));
check("manifest_uses_resizable_keyboard", manifest.includes('android:windowSoftInputMode="adjustResize"'));
check("gradle_uses_firebase_messaging", gradle.includes("libs.firebase.messaging"));
check("gradle_uses_java_17", gradle.includes("JavaVersion.VERSION_17"));
check("gradle_targets_sdk_37", gradle.includes("targetSdk = 37"));
check("native_bridge_has_camera_mic_method", main.includes("fun requestCameraAndMicrophonePermissions()") && webInterface.includes("requestCameraAndMicrophonePermissions"). === false);
check("native_fcm_service_exists", fcmService.includes("FirebaseMessagingService"));
check("worker_has_syntax_check_script", typeof workerPackage.scripts?.check === "string");
check("no_open_all_firestore_rules", !rules.includes("allow read, write: if true") && !rules.includes("allow write: if true"));

console.log(JSON.stringify({ checks, failures }, null, 2));
if (failures.length) process.exitCode = 1;
