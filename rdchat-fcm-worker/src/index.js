const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8' };
const ALLOW_HEADERS = 'Authorization, Content-Type';
const ALLOW_METHODS = 'GET, POST, DELETE, OPTIONS';
let cachedGoogleToken = null;

function responseJson(data, status = 200, origin = '*') {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      ...JSON_HEADERS,
      'access-control-allow-origin': origin || '*',
      'access-control-allow-methods': ALLOW_METHODS,
      'access-control-allow-headers': ALLOW_HEADERS,
      'cache-control': 'no-store'
    }
  });
}

function corsOrigin(request) {
  const origin = request.headers.get('Origin') || '*';
  // The app's WebView origin is the deployed RdChat worker host. For this API,
  // wildcard is safe because every push request must carry a verified Firebase ID token.
  return origin;
}

function b64urlBytes(bytes) {
  let binary = '';
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  for (let i = 0; i < view.length; i += 0x8000) {
    binary += String.fromCharCode(...view.subarray(i, i + 0x8000));
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function b64urlText(value) {
  return b64urlBytes(new TextEncoder().encode(value));
}

function pemToBytes(pem) {
  const body = String(pem || '')
    .replace(/-----BEGIN PRIVATE KEY-----/g, '')
    .replace(/-----END PRIVATE KEY-----/g, '')
    .replace(/\s+/g, '');
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function getGoogleAccessToken(env) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedGoogleToken && cachedGoogleToken.expiresAt > now + 60) return cachedGoogleToken.value;

  let serviceAccount;
  try {
    serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT || '{}');
  } catch (_) {
    throw new Error('FIREBASE_SERVICE_ACCOUNT não contém JSON válido.');
  }
  if (!serviceAccount.client_email || !serviceAccount.private_key) {
    throw new Error('A conta de serviço precisa conter client_email e private_key.');
  }

  const header = b64urlText(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claim = b64urlText(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: 'https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3500
  }));
  const unsigned = `${header}.${claim}`;
  const key = await crypto.subtle.importKey(
    'pkcs8', pemToBytes(serviceAccount.private_key),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']
  );
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned));
  const assertion = `${unsigned}.${b64urlBytes(signature)}`;
  const tokenResponse = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion })
  });
  const tokenBody = await tokenResponse.json().catch(() => ({}));
  if (!tokenResponse.ok || !tokenBody.access_token) {
    throw new Error(`Google OAuth HTTP ${tokenResponse.status}: ${tokenBody.error_description || tokenBody.error || 'não foi possível obter token'}`);
  }
  cachedGoogleToken = { value: tokenBody.access_token, expiresAt: now + Math.min(Number(tokenBody.expires_in || 3600), 3400) }; // JWT exp = iat + 3500; renove antes de expirar.
  return cachedGoogleToken.value;
}

async function verifyFirebaseUser(idToken, env) {
  if (!idToken) throw new HttpError(401, 'Firebase ID token ausente.');
  if (!env.FIREBASE_API_KEY) throw new Error('FIREBASE_API_KEY não configurada no Worker.');
  const url = `https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${encodeURIComponent(env.FIREBASE_API_KEY)}`;
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ idToken })
  });
  const body = await response.json().catch(() => ({}));
  const uid = body && body.users && body.users[0] && body.users[0].localId;
  if (!response.ok || !uid) throw new HttpError(401, 'Firebase ID token inválido ou expirado.');
  return uid;
}

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function firestoreRoot(env) {
  const project = String(env.FIREBASE_PROJECT_ID || '').trim();
  if (!project) throw new Error('FIREBASE_PROJECT_ID não configurado.');
  return `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(project)}/databases/(default)/documents`;
}

async function firestoreRequest(env, path, options = {}) {
  const accessToken = await getGoogleAccessToken(env);
  const response = await fetch(firestoreRoot(env) + path, {
    ...options,
    headers: { authorization: `Bearer ${accessToken}`, ...(options.headers || {}) }
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = body && body.error && body.error.message ? body.error.message : `Firestore HTTP ${response.status}`;
    throw new HttpError(response.status, message);
  }
  return body;
}

function fieldString(fields, name) {
  const field = fields && fields[name];
  if (!field) return '';
  return String(field.stringValue ?? field.integerValue ?? field.doubleValue ?? '');
}

function fieldArrayStrings(fields, name) {
  const values = fields && fields[name] && fields[name].arrayValue && fields[name].arrayValue.values;
  return Array.isArray(values) ? values.map(item => String(item.stringValue || '')).filter(Boolean) : [];
}

function validDocId(value) {
  const id = String(value || '').trim();
  return id.length > 0 && id.length <= 1500 && !id.includes('/') && id !== '.' && id !== '..';
}

async function loadMessage(env, roomId, messageId) {
  if (!validDocId(roomId) || !validDocId(messageId)) throw new HttpError(400, 'roomId/messageId inválido.');
  const path = `/textConversations/${encodeURIComponent(roomId)}/messages/${encodeURIComponent(messageId)}`;
  return firestoreRequest(env, path);
}

function validFcmToken(token) {
  const value = String(token || '').trim();
  return value.length >= 50 && value.length <= 4096 && /^[A-Za-z0-9:_-]+$/.test(value) ? value : '';
}

async function loadUserTokens(env, uid) {
  const safeUid = encodeURIComponent(uid);
  const tokens = new Set();
  try {
    const user = await firestoreRequest(env, `/users/${safeUid}`);
    const parentToken = validFcmToken(fieldString(user.fields, 'fcmToken'));
    if (parentToken) tokens.add(parentToken);
  } catch (error) {
    if (!(error instanceof HttpError && error.status === 404)) throw error;
  }
  try {
    const collection = await firestoreRequest(env, `/users/${safeUid}/fcmTokens?pageSize=100`);
    for (const doc of collection.documents || []) {
      const token = validFcmToken(fieldString(doc.fields, 'token'));
      if (token) tokens.add(token);
    }
  } catch (error) {
    // The legacy parent field remains supported if the optional multi-device collection is absent.
    if (!(error instanceof HttpError && error.status === 404)) throw error;
  }
  return [...tokens];
}

async function sendFcm(env, token, data) {
  const accessToken = await getGoogleAccessToken(env);
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(env.FIREBASE_PROJECT_ID)}/messages:send`, {
    method: 'POST',
    headers: { authorization: `Bearer ${accessToken}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      message: {
        token,
        data,
        android: { priority: 'HIGH', ttl: '604800s' }
      }
    })
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = body && body.error && body.error.message ? body.error.message : `FCM HTTP ${response.status}`;
    throw new Error(message);
  }
  return body;
}

async function handlePush(request, env) {
  const bearer = (request.headers.get('Authorization') || '').match(/^Bearer\s+(.+)$/i);
  const senderUid = await verifyFirebaseUser(bearer && bearer[1], env);
  const input = await request.json().catch(() => ({}));
  const roomId = String(input.roomId || '').trim();
  const messageId = String(input.messageId || '').trim();
  if (!validDocId(roomId) || !validDocId(messageId)) throw new HttpError(400, 'roomId/messageId inválido.');

  const message = await loadMessage(env, roomId, messageId);
  const fields = message.fields || {};
  if (fieldString(fields, 'fromUid') !== senderUid) throw new HttpError(403, 'O remetente não é o autor desta mensagem.');
  if (fieldString(fields, 'kind') !== 'text' && fieldString(fields, 'kind') !== 'deleted') {
    throw new HttpError(400, 'Este endpoint de push aceita mensagens de texto.');
  }
  const recipients = fieldArrayStrings(fields, 'toUids').filter(uid => uid !== senderUid);
  if (!recipients.length) return { ok: true, sent: 0, reason: 'no_recipients' };

  const senderName = fieldString(fields, 'fromName').slice(0, 60) || 'Novo contato';
  const text = fieldString(fields, 'text').replace(/\s+/g, ' ').trim();
  const preview = (text || 'Você recebeu uma mensagem.').slice(0, 120);
  let sent = 0;
  const failures = [];
  for (const recipientUid of recipients) {
    const tokens = await loadUserTokens(env, recipientUid);
    for (const token of tokens) {
      try {
        await sendFcm(env, token, {
          type: 'rdchat_wake_v1',
          queueId: `wake:${messageId}:${recipientUid}`.slice(0, 160),
          id: messageId,
          roomId,
          messageId,
          recipientUid,
          senderId: senderUid,
          senderName,
          title: `RdChat · ${senderName}`,
          body: preview
        });
        sent++;
      } catch (error) {
        failures.push({ uid: recipientUid, code: String(error.message || error).slice(0, 180) });
      }
    }
  }
  return { ok: true, sent, recipients: recipients.length, failures };
}

async function handleDelivered(request, env) {
  const input = await request.json().catch(() => ({}));
  const roomId = String(input.roomId || '').trim();
  const messageId = String(input.messageId || '').trim();
  const recipientUid = String(input.recipientUid || '').trim();
  const fcmToken = validFcmToken(input.token);
  if (!validDocId(roomId) || !validDocId(messageId) || !recipientUid || !fcmToken) {
    throw new HttpError(400, 'Dados do recibo de entrega inválidos.');
  }
  const message = await loadMessage(env, roomId, messageId);
  const fields = message.fields || {};
  const recipients = fieldArrayStrings(fields, 'toUids');
  if (!recipients.includes(recipientUid)) throw new HttpError(403, 'Este aparelho não é destinatário da mensagem.');
  const knownTokens = await loadUserTokens(env, recipientUid);
  if (!knownTokens.includes(fcmToken)) throw new HttpError(403, 'O token FCM não pertence ao destinatário.');

  const documentName = `projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents/textConversations/${roomId}/messages/${messageId}`;
  await firestoreRequest(env, ':commit', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      writes: [{
        transform: {
          document: documentName,
          fieldTransforms: [{
            fieldPath: 'deliveredBy',
            appendMissingElements: { values: [{ stringValue: recipientUid }] }
          }]
        }
      }]
    })
  });
  return { ok: true, delivered: true };
}


function fieldBoolean(fields, name) {
  return !!(fields && fields[name] && fields[name].booleanValue === true);
}

function encodeFirestoreValue(value) {
  if (value === null) return { nullValue: null };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (typeof value === 'string') return { stringValue: value };
  if (typeof value === 'boolean') return { booleanValue: value };
  if (typeof value === 'number') return Number.isInteger(value) ? { integerValue: String(value) } : { doubleValue: value };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(encodeFirestoreValue) } };
  if (typeof value === 'object') return { mapValue: { fields: encodeFirestoreFields(value) } };
  return { nullValue: null };
}

function encodeFirestoreFields(data) {
  const fields = {};
  for (const [key, value] of Object.entries(data || {})) {
    if (value !== undefined) fields[key] = encodeFirestoreValue(value);
  }
  return fields;
}

async function commitFirestoreDocuments(env, documents) {
  const root = 'projects/' + env.FIREBASE_PROJECT_ID + '/databases/(default)/documents';
  const writes = documents.map(item => ({
    update: { name: root + item.path, fields: encodeFirestoreFields(item.data) },
    updateMask: { fieldPaths: Object.keys(item.data) }
  }));
  return firestoreRequest(env, ':commit', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ writes })
  });
}

async function loadUserProfile(env, uid) {
  const user = await firestoreRequest(env, '/users/' + encodeURIComponent(uid));
  const fields = user.fields || {};
  return {
    uid,
    email: fieldString(fields, 'email'),
    name: fieldString(fields, 'displayName') || fieldString(fields, 'name') || 'Contato',
    code: fieldString(fields, 'code')
  };
}

function safeMediaName(value) {
  const name = String(value || 'arquivo')
    .replace(/[\r\n\/\\"]/g, '_')
    .replace(/[^\w .()-]/g, '_')
    .trim();
  return (name || 'arquivo').slice(0, 120);
}

function mediaNotificationText(kind, name) {
  const label = ({
    image: 'Enviou uma foto',
    video: 'Enviou um vídeo',
    audio: 'Enviou um áudio',
    gif: 'Enviou um GIF',
    file: 'Enviou um arquivo'
  })[kind] || 'Enviou um arquivo';
  return name ? (label + ': ' + name).slice(0, 120) : label;
}

async function telegramPost(env, method, form) {
  const token = String(env.TELEGRAM_BOT_TOKEN || '').trim();
  if (!token || !String(env.TELEGRAM_CHANNEL_ID || '').trim()) {
    throw new Error('Configure os secrets TELEGRAM_BOT_TOKEN e TELEGRAM_CHANNEL_ID no Worker.');
  }
  const response = await fetch('https://api.telegram.org/bot' + token + '/' + method, {
    method: 'POST',
    body: form
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.ok !== true) {
    throw new HttpError(502, 'Telegram Bot API: ' + String(payload.description || ('HTTP ' + response.status)).slice(0, 220));
  }
  return payload.result;
}

async function telegramGetFile(env, fileId) {
  const token = String(env.TELEGRAM_BOT_TOKEN || '').trim();
  if (!token) throw new Error('TELEGRAM_BOT_TOKEN não configurado no Worker.');
  const url = 'https://api.telegram.org/bot' + token + '/getFile?file_id=' + encodeURIComponent(fileId);
  const response = await fetch(url);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || payload.ok !== true || !payload.result || !payload.result.file_path) {
    throw new HttpError(502, 'Telegram não conseguiu localizar o arquivo: ' + String(payload.description || ('HTTP ' + response.status)).slice(0, 180));
  }
  return payload.result;
}

async function sendMediaWake(env, message) {
  const recipients = Array.isArray(message.toUids) ? message.toUids : [];
  const failures = [];
  let sent = 0;
  const preview = mediaNotificationText(message.kind, message.mediaName);
  for (const recipientUid of recipients) {
    let tokens = [];
    try {
      tokens = await loadUserTokens(env, recipientUid);
    } catch (error) {
      failures.push({ uid: recipientUid, code: String(error.message || error).slice(0, 180) });
      continue;
    }
    for (const token of tokens) {
      try {
        await sendFcm(env, token, {
          type: 'rdchat_wake_v1',
          queueId: ('wake:' + message.id + ':' + recipientUid).slice(0, 160),
          id: message.id,
          roomId: message.roomId,
          messageId: message.id,
          recipientUid,
          senderId: message.fromUid,
          senderName: message.fromName,
          title: 'RdChat · ' + message.fromName,
          body: preview,
          kind: message.kind
        });
        sent++;
      } catch (error) {
        failures.push({ uid: recipientUid, code: String(error.message || error).slice(0, 180) });
      }
    }
  }
  return { sent, failures };
}

async function handleMediaUpload(request, env) {
  const bearer = (request.headers.get('Authorization') || '').match(/^Bearer\s+(.+)$/i);
  const senderUid = await verifyFirebaseUser(bearer && bearer[1], env);
  const form = await request.formData();
  const file = form.get('file');
  const roomId = String(form.get('roomId') || '').trim();
  const messageId = String(form.get('messageId') || '').trim();
  const kind = String(form.get('kind') || 'file').trim().toLowerCase();
  if (!file || typeof file.arrayBuffer !== 'function' || !file.size) throw new HttpError(400, 'Arquivo ausente ou vazio.');
  if (file.size > 20_000_000) throw new HttpError(413, 'O limite desta integração Telegram é 20 MB por arquivo.');
  if (!validDocId(roomId) || !validDocId(messageId)) throw new HttpError(400, 'roomId/messageId inválido.');
  if (!['image', 'video', 'audio', 'gif', 'file'].includes(kind)) throw new HttpError(400, 'Tipo de mídia não suportado.');

  let previous = null;
  try {
    previous = await loadMessage(env, roomId, messageId);
  } catch (error) {
    if (!(error instanceof HttpError && error.status === 404)) throw error;
  }
  if (previous) {
    const priorFields = previous.fields || {};
    const priorMediaId = fieldString(priorFields, 'telegramMediaId');
    if (fieldString(priorFields, 'fromUid') === senderUid && priorMediaId) {
      return { ok: true, mediaId: priorMediaId, duplicate: true };
    }
    throw new HttpError(409, 'Este identificador de mensagem já foi utilizado.');
  }

  const room = await firestoreRequest(env, '/textConversations/' + encodeURIComponent(roomId));
  const roomFields = room.fields || {};
  const members = fieldArrayStrings(roomFields, 'members');
  if (!members.includes(senderUid)) throw new HttpError(403, 'O remetente não pertence a esta conversa.');
  const recipientUids = members.filter(uid => uid && uid !== senderUid);
  if (!recipientUids.length || recipientUids.length > 100) throw new HttpError(400, 'A conversa não possui destinatários válidos.');

  const profiles = await Promise.all(members.map(uid => loadUserProfile(env, uid)));
  const profileByUid = new Map(profiles.map(profile => [profile.uid, profile]));
  const sender = profileByUid.get(senderUid);
  const recipients = recipientUids.map(uid => profileByUid.get(uid)).filter(Boolean);
  if (!sender || !sender.email || recipients.length !== recipientUids.length || recipients.some(profile => !profile.email)) {
    throw new HttpError(409, 'O perfil Firebase de um participante está incompleto. Refaça o vínculo pelo código de contato.');
  }

  const mediaName = safeMediaName(file.name || form.get('fileName') || 'arquivo');
  const mediaMime = String(form.get('mimeType') || file.type || 'application/octet-stream').slice(0, 120);
  const caption = String(form.get('caption') || '').slice(0, 1000);
  const viewOnce = ['1', 'true', 'yes'].includes(String(form.get('viewOnce') || '').toLowerCase());
  let reply = null;
  const replyRaw = String(form.get('reply') || '');
  if (replyRaw) {
    try {
      const parsed = JSON.parse(replyRaw);
      reply = {
        id: String(parsed.id || '').slice(0, 150),
        text: String(parsed.text || '').slice(0, 500),
        from: String(parsed.from || '').slice(0, 80)
      };
    } catch (_) {
      throw new HttpError(400, 'Metadados de resposta inválidos.');
    }
  }

  const telegramForm = new FormData();
  telegramForm.append('chat_id', String(env.TELEGRAM_CHANNEL_ID));
  telegramForm.append('document', file, mediaName);
  telegramForm.append('protect_content', 'true');
  if (caption) telegramForm.append('caption', caption);
  const telegramMessage = await telegramPost(env, 'sendDocument', telegramForm);
  const telegramDocument = telegramMessage && telegramMessage.document;
  const telegramFileId = String(telegramDocument && telegramDocument.file_id || '');
  const telegramMessageId = Number(telegramMessage && telegramMessage.message_id || 0);
  if (!telegramFileId || !telegramMessageId) throw new HttpError(502, 'Telegram não retornou os identificadores do arquivo.');

  const mediaId = crypto.randomUUID();
  const now = Date.now();
  const expiresAt = new Date(now + 40 * 60 * 60 * 1000); // Telegram Bot API só permite excluir mensagens antes de 48 horas.
  const group = fieldBoolean(roomFields, 'group');
  const groupKey = fieldString(roomFields, 'groupKey');
  const groupName = fieldString(roomFields, 'groupName');
  const toEmails = recipients.map(profile => profile.email);
  const participantEmails = [sender.email, ...toEmails];
  const message = {
    id: messageId,
    roomId,
    kind,
    fromUid: senderUid,
    toUid: recipientUids[0],
    toUids: recipientUids,
    fromEmail: sender.email,
    toEmail: toEmails[0] || '',
    toEmails,
    fromName: String(sender.name || 'Contato').slice(0, 40),
    fromCode: String(sender.code || '').replace(/[^0-9]/g, '').slice(0, 6),
    ts: now,
    expiresAt,
    deliveredBy: [],
    readBy: [],
    group,
    groupKey,
    groupName,
    participantEmails,
    telegramMediaId: mediaId,
    mediaName,
    mediaMime,
    mediaSize: Number(file.size),
    viewOnce,
    caption
  };
  if (reply) message.reply = reply;

  const mediaRecord = {
    mediaId,
    roomId,
    messageId,
    fromUid: senderUid,
    recipientUids,
    telegramFileId,
    telegramChatId: String(env.TELEGRAM_CHANNEL_ID),
    telegramMessageId,
    mediaName,
    mediaMime,
    mediaSize: Number(file.size),
    kind,
    viewOnce,
    createdAt: new Date(now),
    expiresAt
  };
  if (caption) mediaRecord.caption = caption;

  const writes = [
    { path: '/textConversations/' + encodeURIComponent(roomId) + '/messages/' + encodeURIComponent(messageId), data: message },
    { path: '/telegramMedia/' + mediaId, data: mediaRecord }
  ];
  for (const profile of recipients) {
    writes.push({
      path: '/messageInbox/' + encodeURIComponent(profile.uid) + '/items/' + encodeURIComponent(messageId + '_' + senderUid),
      data: {
        roomId,
        messageId,
        fromUid: senderUid,
        toUid: profile.uid,
        fromEmail: sender.email,
        fromName: String(sender.name || 'Contato').slice(0, 40),
        fromCode: String(sender.code || '').replace(/[^0-9]/g, '').slice(0, 6),
        group,
        groupKey,
        groupName,
        participantEmails,
        ts: now,
        expiresAt,
        media: true
      }
    });
  }

  try {
    await commitFirestoreDocuments(env, writes);
  } catch (error) {
    const cleanup = new FormData();
    cleanup.append('chat_id', String(env.TELEGRAM_CHANNEL_ID));
    cleanup.append('message_id', String(telegramMessageId));
    try { await telegramPost(env, 'deleteMessage', cleanup); } catch (_) {}
    throw error;
  }

  const push = await sendMediaWake(env, message);
  return { ok: true, mediaId, fcmSent: push.sent, fcmFailures: push.failures };
}

async function handleMediaDownload(request, env, mediaId) {
  const bearer = (request.headers.get('Authorization') || '').match(/^Bearer\s+(.+)$/i);
  const uid = await verifyFirebaseUser(bearer && bearer[1], env);
  if (!validDocId(mediaId)) throw new HttpError(400, 'Identificador de mídia inválido.');
  const record = await firestoreRequest(env, '/telegramMedia/' + encodeURIComponent(mediaId));
  const fields = record.fields || {};
  const allowed = uid === fieldString(fields, 'fromUid') || fieldArrayStrings(fields, 'recipientUids').includes(uid);
  if (!allowed) throw new HttpError(403, 'Esta conta não participa da conversa que contém a mídia.');

  const fileId = fieldString(fields, 'telegramFileId');
  if (!fileId) throw new HttpError(404, 'Arquivo não localizado no Telegram.');
  const fileInfo = await telegramGetFile(env, fileId);
  const fileUrl = 'https://api.telegram.org/file/bot' + String(env.TELEGRAM_BOT_TOKEN).trim() + '/' + fileInfo.file_path;
  const fileResponse = await fetch(fileUrl);
  if (!fileResponse.ok || !fileResponse.body) throw new HttpError(502, 'Não foi possível baixar o arquivo do Telegram.');

  const rawMime = fieldString(fields, 'mediaMime') || 'application/octet-stream';
  const mime = /^[a-z0-9.+-]+\/[a-z0-9.+-]+$/i.test(rawMime) ? rawMime : 'application/octet-stream';
  const fileName = safeMediaName(fieldString(fields, 'mediaName') || 'arquivo');
  return new Response(fileResponse.body, {
    status: 200,
    headers: {
      'content-type': mime,
      'content-disposition': "attachment; filename*=UTF-8''" + encodeURIComponent(fileName),
      'cache-control': 'private, no-store',
      'x-content-type-options': 'nosniff',
      'access-control-allow-origin': corsOrigin(request),
      'access-control-allow-methods': ALLOW_METHODS,
      'access-control-allow-headers': ALLOW_HEADERS
    }
  });
}

async function handleMediaDelete(request, env, mediaId) {
  const bearer = (request.headers.get('Authorization') || '').match(/^Bearer\s+(.+)$/i);
  const uid = await verifyFirebaseUser(bearer && bearer[1], env);
  if (!validDocId(mediaId)) throw new HttpError(400, 'Identificador de mídia inválido.');
  const record = await firestoreRequest(env, '/telegramMedia/' + encodeURIComponent(mediaId));
  const fields = record.fields || {};
  if (uid !== fieldString(fields, 'fromUid')) throw new HttpError(403, 'Somente o autor pode remover esta mídia para todos.');

  const cleanup = new FormData();
  cleanup.append('chat_id', fieldString(fields, 'telegramChatId'));
  cleanup.append('message_id', fieldString(fields, 'telegramMessageId'));
  await telegramPost(env, 'deleteMessage', cleanup);
  await firestoreRequest(env, '/telegramMedia/' + encodeURIComponent(mediaId), { method: 'DELETE' });
  return { ok: true, deleted: true };
}

async function cleanupExpiredTelegramMedia(env) {
  if (!env.TELEGRAM_BOT_TOKEN || !env.TELEGRAM_CHANNEL_ID) return;
  const query = {
    structuredQuery: {
      from: [{ collectionId: 'telegramMedia' }],
      where: {
        fieldFilter: {
          field: { fieldPath: 'expiresAt' },
          op: 'LESS_THAN_OR_EQUAL',
          value: { timestampValue: new Date().toISOString() }
        }
      },
      limit: 100
    }
  };
  const results = await firestoreRequest(env, ':runQuery', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(query)
  });
  for (const item of Array.isArray(results) ? results : []) {
    const document = item && item.document;
    if (!document || !document.name) continue;
    const fields = document.fields || {};
    const chatId = fieldString(fields, 'telegramChatId');
    const messageId = fieldString(fields, 'telegramMessageId');
    const marker = '/documents';
    const markerIndex = document.name.indexOf(marker);
    if (!chatId || !messageId || markerIndex < 0) continue;
    const documentPath = document.name.slice(markerIndex + marker.length);
    try {
      const cleanup = new FormData();
      cleanup.append('chat_id', chatId);
      cleanup.append('message_id', messageId);
      await telegramPost(env, 'deleteMessage', cleanup);

      // Depois de remover o arquivo no Telegram, apaga os metadados e os ponteiros remotos.
      const roomId = fieldString(fields, 'roomId');
      const messageIdField = fieldString(fields, 'messageId');
      const senderUid = fieldString(fields, 'fromUid');
      const recipientUids = fieldArrayStrings(fields, 'recipientUids');
      if (roomId && messageIdField) {
        try {
          await firestoreRequest(env, '/textConversations/' + encodeURIComponent(roomId) + '/messages/' + encodeURIComponent(messageIdField), { method: 'DELETE' });
        } catch (deleteError) {
          if (!(deleteError instanceof HttpError && deleteError.status === 404)) throw deleteError;
        }
      }
      if (messageIdField && senderUid) {
        for (const recipientUid of recipientUids) {
          try {
            await firestoreRequest(env, '/messageInbox/' + encodeURIComponent(recipientUid) + '/items/' + encodeURIComponent(messageIdField + '_' + senderUid), { method: 'DELETE' });
          } catch (deleteError) {
            if (!(deleteError instanceof HttpError && deleteError.status === 404)) {
              console.warn('RDCHAT_MEDIA_INBOX_CLEANUP_FAILED', String(deleteError.message || deleteError));
            }
          }
        }
      }
      await firestoreRequest(env, documentPath, { method: 'DELETE' });
    } catch (error) {
      console.warn('RDCHAT_MEDIA_TTL_CLEANUP_FAILED', String(error.message || error));
    }
  }
}

export default {
  async fetch(request, env) {
    const origin = corsOrigin(request);
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'access-control-allow-origin': origin,
          'access-control-allow-methods': ALLOW_METHODS,
          'access-control-allow-headers': ALLOW_HEADERS,
          'access-control-max-age': '86400'
        }
      });
    }
    const url = new URL(request.url);
    if (request.method === 'GET' && (url.pathname === '/' || url.pathname === '/health' || url.pathname === '/v1/health')) {
      return responseJson({ ok: true, service: 'RdChat FCM + Telegram Media Gateway', project: env.FIREBASE_PROJECT_ID, mediaConfigured: !!(env.TELEGRAM_BOT_TOKEN && env.TELEGRAM_CHANNEL_ID), version: 2 }, 200, origin);
    }
    try {
      if (request.method === 'GET' && url.pathname.startsWith('/v1/media/')) {
        const mediaId = decodeURIComponent(url.pathname.slice('/v1/media/'.length));
        return await handleMediaDownload(request, env, mediaId);
      }
      if (request.method === 'DELETE' && url.pathname.startsWith('/v1/media/')) {
        const mediaId = decodeURIComponent(url.pathname.slice('/v1/media/'.length));
        const result = await handleMediaDelete(request, env, mediaId);
        return responseJson(result, 200, origin);
      }
      if (request.method !== 'POST') return responseJson({ ok: false, error: 'method_not_allowed' }, 405, origin);
      let result;
      if (url.pathname === '/v1/push') result = await handlePush(request, env);
      else if (url.pathname === '/v1/delivered') result = await handleDelivered(request, env);
      else if (url.pathname === '/v1/media') result = await handleMediaUpload(request, env);
      else return responseJson({ ok: false, error: 'not_found' }, 404, origin);
      return responseJson(result, 200, origin);
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      console.error('RDCHAT_FCM_GATEWAY_ERROR', error && error.stack ? error.stack : error);
      return responseJson({ ok: false, error: String(error.message || error) }, status, origin);
    }
  },
  async scheduled(_controller, env, ctx) {
    ctx.waitUntil(cleanupExpiredTelegramMedia(env).catch(error => {
      console.error('RDCHAT_MEDIA_TTL_CLEANUP_ERROR', error && error.stack ? error.stack : error);
    }));
  }
};
