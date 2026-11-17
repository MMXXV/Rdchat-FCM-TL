/** RdChat FCM gateway: encaminhamento temporário. Não armazena mensagens nem tokens de destinatários. */
const RDCHAT_FCM_ENDPOINT = 'https://fcm.googleapis.com/v1/projects/';
const RDCHAT_MAX_REQUEST_BYTES = 3800;

function doGet() {
  return json_({ ok: true, service: 'RdChat FCM relay', status: 'ready', version: 4 });
}

/**
 * Execute uma vez no editor do Apps Script para apagar o registro legado de tokens.
 * Preserva RDCHAT_PUSH_KEY e FCM_PROJECT_ID, que são configuração do gateway.
 */
function purgeLegacyFcmTokenRegistry() {
  const properties = PropertiesService.getScriptProperties();
  const all = properties.getProperties();
  const prefix = 'RDCHAT_FCM_TOKEN_';
  const keys = Object.keys(all).filter(key => key.indexOf(prefix) === 0);
  keys.forEach(key => properties.deleteProperty(key));
  console.log('[RDCHAT_PUSH] LEGACY_TOKEN_REGISTRY_PURGED count=' + keys.length);
  return { deleted: keys.length };
}

function doPost(e) {
  try {
    const cfg = PropertiesService.getScriptProperties().getProperties();
    const input = JSON.parse(e && e.postData && e.postData.contents ? e.postData.contents : '{}');
    if (!cfg.RDCHAT_PUSH_KEY || !safeEqual_(String(input.key || ''), cfg.RDCHAT_PUSH_KEY)) {
      console.error('[RDCHAT_PUSH] REQUEST_BLOCKED unauthorized_or_missing_gateway_key');
      return json_({ ok: false, error: 'unauthorized' });
    }

    const action = String(input.action || '');
    const token = validFcmToken_(input.token);
    if (!token) return json_({ ok: false, error: 'invalid_fcm_token' });

    if (action === 'send_text') {
      const result = sendText_(token, input.message || {}, cfg);
      return json_({ ok: true, status: 'accepted', chunks: result.chunks });
    }
    if (action === 'send_ack') {
      const result = sendAck_(token, input.ack || {}, cfg);
      return json_({ ok: true, status: 'accepted', message: result });
    }
    if (action === 'wake') {
      const result = sendWake_(token, input.wake || {}, cfg);
      return json_({ ok: true, status: 'accepted', message: result });
    }

    return json_({ ok: false, error: 'invalid_action' });
  } catch (err) {
    console.error('[RDCHAT_PUSH] GATEWAY_ERROR ' + (err && err.stack ? err.stack : err));
    return json_({ ok: false, error: String(err && err.message ? err.message : err) });
  }
}

function authorizeFcm() {
  const token = ScriptApp.getOAuthToken();
  if (!token) throw new Error('O Google não forneceu token OAuth.');
  return 'Autorização Google disponível.';
}

function testFcmToDevice() {
  const cfg = PropertiesService.getScriptProperties().getProperties();
  const token = validFcmToken_(cfg.FCM_TEST_TOKEN);
  if (!token) throw new Error('Defina FCM_TEST_TOKEN nas Script Properties.');
  return sendWake_(token, { id: 'rdchat-test-' + Date.now(), title: 'RdChat', body: 'Notificação de teste' }, cfg);
}

function sendText_(token, message, cfg) {
  const id = String(message.id || '').trim().slice(0, 80);
  if (!/^[A-Za-z0-9_-]{1,80}$/.test(id)) throw new Error('Identificador de mensagem inválido.');
  const sealedChunks = Array.isArray(message.sealedChunks) ? message.sealedChunks.slice(0, 32) : [];
  if (!sealedChunks.length) throw new Error('Mensagem sem partes cifradas.');
  const total = sealedChunks.length;
  let accepted = 0;
  sealedChunks.forEach((chunk, index) => {
    const chunkIndex = Number(chunk && chunk.index);
    const chunkTotal = Number(chunk && chunk.total);
    const sealedEvent = String(chunk && chunk.sealedEvent || '');
    if (chunkIndex !== index || chunkTotal !== total || sealedEvent.length < 100 || sealedEvent.length > 2500) {
      throw new Error('Parte cifrada inválida.');
    }
    let parsed;
    try { parsed = JSON.parse(sealedEvent); } catch (_) { throw new Error('Evento NIP-17 inválido.'); }
    if (!parsed || parsed.kind !== 1059 || !parsed.pubkey || !parsed.sig || !parsed.content) {
      throw new Error('Evento NIP-17 incompleto.');
    }
    const data = {
      type: 'rdchat_text_sealed_v1',
      queueId: id + ':' + index,
      id: id,
      chunkIndex: String(index),
      chunkTotal: String(total),
      sealedEvent: sealedEvent
    };
    const request = buildFcmRequest_(token, data, '604800s');
    const bytes = byteLength_(JSON.stringify(request));
    if (bytes > RDCHAT_MAX_REQUEST_BYTES) throw new Error('Parte cifrada acima do limite FCM (' + bytes + ' bytes).');
    sendFcmRequest_(request, cfg);
    accepted++;
  });
  console.log('[RDCHAT_PUSH] SEALED_TEXT_ACCEPTED id=' + id + ' chunks=' + accepted);
  return { chunks: accepted };
}

function sendAck_(token, ack, cfg) {
  const id = String(ack.id || '').trim().slice(0, 80);
  const sealedEvent = String(ack.sealedEvent || '');
  if (!/^[A-Za-z0-9_-]{1,80}$/.test(id) || sealedEvent.length < 100 || sealedEvent.length > 2500) {
    throw new Error('Recibo cifrado inválido.');
  }
  let parsed;
  try { parsed = JSON.parse(sealedEvent); } catch (_) { throw new Error('Evento NIP-17 inválido.'); }
  if (!parsed || parsed.kind !== 1059 || !parsed.pubkey || !parsed.sig || !parsed.content) throw new Error('Evento NIP-17 incompleto.');
  const data = {
    type: 'rdchat_ack_sealed_v1',
    queueId: 'ack:' + id,
    id: id,
    sealedEvent: sealedEvent
  };
  const result = sendFcmRequest_(buildFcmRequest_(token, data, '604800s', 'NORMAL'), cfg);
  console.log('[RDCHAT_PUSH] SEALED_ACK_ACCEPTED id=' + id);
  return result;
}

function sendWake_(token, wake, cfg) {
  const id = String(wake.id || ('wake-' + Date.now())).slice(0, 80);
  const data = {
    type: 'rdchat_wake_v1',
    queueId: 'wake:' + id,
    id: id,
    senderName: String(wake.senderName || '').slice(0, 60),
    title: 'RdChat',
    body: String(wake.body || 'Há uma mensagem ou mídia aguardando.').slice(0, 120)
  };
  const result = sendFcmRequest_(buildFcmRequest_(token, data, '86400s'), cfg);
  console.log('[RDCHAT_PUSH] WAKE_ACCEPTED message=' + id);
  return result;
}

function buildFcmRequest_(token, data, ttl, priority) {
  const safeData = {};
  Object.keys(data || {}).forEach(key => { safeData[key] = String(data[key] == null ? '' : data[key]); });
  return {
    message: {
      token: token,
      data: safeData,
      android: { priority: priority || 'HIGH', ttl: ttl || '604800s' }
    }
  };
}

function sendFcmRequest_(request, cfg) {
  const projectId = String(cfg.FCM_PROJECT_ID || '').trim();
  if (!projectId) throw new Error('Script Property FCM_PROJECT_ID não configurada.');
  const accessToken = ScriptApp.getOAuthToken();
  if (!accessToken) throw new Error('Não foi possível obter token OAuth.');
  const response = UrlFetchApp.fetch(RDCHAT_FCM_ENDPOINT + encodeURIComponent(projectId) + '/messages:send', {
    method: 'post',
    contentType: 'application/json; charset=UTF-8',
    headers: { Authorization: 'Bearer ' + accessToken },
    payload: JSON.stringify(request),
    muteHttpExceptions: true
  });
  const status = response.getResponseCode();
  const body = response.getContentText();
  if (status < 200 || status >= 300) throw new Error('FCM HTTP ' + status + ': ' + body.slice(0, 500));
  return body;
}

function byteLength_(value) {
  return Utilities.newBlob(String(value), 'text/plain').getBytes().length;
}

function validFcmToken_(value) {
  const token = String(value || '').trim();
  return token.length >= 50 && token.length <= 4096 && /^[A-Za-z0-9:_-]+$/.test(token) ? token : '';
}

function safeEqual_(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function json_(value) {
  return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);
}
