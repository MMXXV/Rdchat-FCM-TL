import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

val telegramProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

val telegramApiIdText =
    providers.environmentVariable("TELEGRAM_API_ID").orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("telegramApiId").orNull?.takeIf { it.isNotBlank() }
        ?: telegramProperties.getProperty("telegramApiId")?.takeIf { it.isNotBlank() }
        ?: "34968764" // api_id já usado pelo projeto; não é segredo.

val telegramApiHash =
    providers.environmentVariable("TELEGRAM_API_HASH").orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("telegramApiHash").orNull?.takeIf { it.isNotBlank() }
        ?: telegramProperties.getProperty("telegramApiHash")?.takeIf { it.isNotBlank() }
        ?: ""
val telegramApiId = telegramApiIdText.toIntOrNull() ?: 0
val escapedTelegramApiHash = telegramApiHash.replace("\\", "\\\\").replace("\"", "\\\"")

val generatedRdchatAssets = layout.buildDirectory.dir("generated/rdchatAssets")
val prepareRdchatAssets = tasks.register("prepareRdchatAssets") {
    val sourceAssets = layout.projectDirectory.dir("src/main/assets")
    inputs.dir(sourceAssets)
    outputs.dir(generatedRdchatAssets)

    doLast {
        val outputDir = generatedRdchatAssets.get().asFile
        project.delete(outputDir)
        project.copy {
            from(sourceAssets)
            into(outputDir)
        }

        val htmlFile = File(outputDir, "index.html")
        require(htmlFile.isFile) { "app/src/main/assets/index.html não foi encontrado." }
        var html = htmlFile.readText(Charsets.UTF_8)

        fun replaceExactlyOnce(old: String, replacement: String, description: String) {
            val count = Regex(Regex.escape(old)).findAll(html).count()
            require(count == 1) { "Patch do login abortado: '$description' apareceu $count vez(es)." }
            html = html.replace(old, replacement)
        }

        // Marca visual do usuário, cadeado do PIN e telefone ficam à esquerda dos campos.
        replaceExactlyOnce(
            """<input type="text" id="regName""",
            """<span class="rd-login-field-icon rd-icon-user" aria-hidden="true"></span><input type="text" id="regName""",
            "ícone do nome"
        )
        replaceExactlyOnce(
            """<input type="password" id="regPass""",
            """<span class="rd-login-field-icon rd-icon-lock" aria-hidden="true"></span><input type="password" id="regPass""",
            "ícone do PIN"
        )
        replaceExactlyOnce(
            """<input id="rdTgPhone" type="password" inputmode="tel" placeholder="+55 11 99999-9999"""",
            """<span class="rd-login-field-icon rd-icon-phone" aria-hidden="true"></span><input id="rdTgPhone" type="tel" inputmode="numeric" placeholder="Digite seu telefone"""",
            "campo de telefone e ícone"
        )
        replaceExactlyOnce(
            """<label for="rdTgPhone">Número de telefone</label>""",
            """<label for="rdTgPhone">Telefone</label>""",
            "rótulo do telefone"
        )

        replaceExactlyOnce(
            """phone.addEventListener('input',function(){
    this.value=this.value.replace(/[^\d+()\s.-]/g,'');
  });""",
            """phone.addEventListener('input',function(){
    var digits=this.value.replace(/\D/g,'');
    if((digits.length===12||digits.length===13)&&digits.indexOf('55')===0)digits=digits.slice(2);
    digits=digits.slice(0,11);
    var ddd=digits.slice(0,2),rest=digits.slice(2);
    if(digits.length<=2){this.value=digits;return}
    if(rest.length<=4)this.value='('+ddd+') '+rest;
    else if(digits.length<=10)this.value='('+ddd+') '+rest.slice(0,4)+'-'+rest.slice(4);
    else this.value='('+ddd+') '+rest.slice(0,5)+'-'+rest.slice(5);
  });""",
            "máscara brasileira DDD + número"
        )

        replaceExactlyOnce(
            """function startTelegramPhoneLogin(){
  if(!validatePhoneModeIdentity())return;
  var phone=String($('rdTgPhone').value||'').trim();
  var digits=phone.replace(/\D/g,'');
  if(digits.length<8||digits.length>15||phone.replace(/[^\d+]/g,'').charAt(0)!=='+'){
    tt('Digite o telefone com código do país. Exemplo: +55 11 99999-9999.');
    $('rdTgPhone').focus();return;
  }
  phoneRequested=true;
  markPhoneRequestStarted();
  if(!T.iniciarLogin(phone)){
    phoneRequested=false;
    markPhoneRequestFinished();
    render();
    return;
  }
  render();
}""",
            """function startTelegramPhoneLogin(){
  if(!validatePhoneModeIdentity())return;
  var digits=String($('rdTgPhone').value||'').replace(/\D/g,'');
  if((digits.length===12||digits.length===13)&&digits.indexOf('55')===0)digits=digits.slice(2);
  if(!/^[1-9][1-9][0-9]{8,9}$/.test(digits)){
    tt('Digite DDD + número com 10 ou 11 dígitos. O +55 será adicionado automaticamente.');
    $('rdTgPhone').focus();return;
  }
  var phone='+55'+digits;
  phoneRequested=true;
  markPhoneRequestStarted();
  try{
    if(!T.iniciarLogin(phone)){
      phoneRequested=false;
      markPhoneRequestFinished();
      render();
      return;
    }
  }catch(error){
    phoneRequested=false;
    markPhoneRequestFinished();
    tt('Não foi possível iniciar o Telegram. Tente novamente.');
    console.error('RdChat TDLib: falha ao iniciar autenticação',error);
    render();
    return;
  }
  render();
}""",
            "envio automático do DDI +55 ao TDLib"
        )

        replaceExactlyOnce(
            """else if(T.ready){submit.textContent=profile&&profile.email?'Entrar':'Criar conta e entrar';submit.disabled=false}""",
            """else if(T.ready){submit.textContent='Entrar';submit.disabled=false}""",
            "rótulo Entrar no modo Com número"
        )

        val referenceCss = """
<style id="rdchat-login-number-reference-v1">
/* Réplica do formulário Com número enviado como referência. O modo A não recebe estes estilos. */
#screen-login.rd-login-phone-mode { background:#101216 !important; color:#e2e7f1 !important; }
#screen-login.rd-login-phone-mode .auth {
  width:100% !important; max-width:362px !important; min-height:100% !important;
  box-sizing:border-box !important; padding:24px 24px max(24px,var(--safe-bottom)) !important;
  justify-content:safe center !important;
}
#screen-login.rd-login-phone-mode .hero { gap:36px !important; margin:0 0 36px !important; }
#screen-login.rd-login-phone-mode .hero-logo {
  width:72px !important; height:72px !important; margin-bottom:0 !important; border-radius:22px !important;
  background:#2f78f7 !important; color:#fff !important; box-shadow:none !important;
}
#screen-login.rd-login-phone-mode .hero-logo svg { width:36px !important; height:36px !important; stroke:#fff !important; }
#screen-login.rd-login-phone-mode .rd-wordmark,
#screen-login.rd-login-phone-mode .rd-wm-line { display:none !important; }
#screen-login.rd-login-phone-mode .hero h1 {
  margin:0 !important; color:#c4cada !important; font-size:22px !important;
  line-height:1.2 !important; font-weight:750 !important; letter-spacing:-.45px !important;
  text-transform:none !important;
}
#screen-login.rd-login-phone-mode .rd-modes {
  display:flex !important; gap:5px !important; padding:0 !important; margin:0 0 16px !important;
  border:0 !important; border-radius:0 !important; background:transparent !important;
  -webkit-backdrop-filter:none !important; backdrop-filter:none !important;
}
#screen-login.rd-login-phone-mode .rd-modes button {
  flex:1 1 0 !important; min-width:0 !important; min-height:48px !important; padding:8px 8px !important;
  border:1px solid #303642 !important; border-radius:16px !important;
  background:#1d2028 !important; color:#d9dfeb !important;
  font-size:13px !important; font-weight:700 !important; white-space:nowrap !important;
}
#screen-login.rd-login-phone-mode .rd-modes button.on {
  border-color:#2f78f7 !important; background:#2f78f7 !important; color:#fff !important;
}
#screen-login.rd-login-phone-mode .field { margin:0 0 18px !important; }
#screen-login.rd-login-phone-mode #rdTgPhoneF.field { margin-bottom:14px !important; }
#screen-login.rd-login-phone-mode .field label {
  display:block !important; margin:0 0 8px 1px !important; color:#c9d0df !important;
  font-size:12px !important; line-height:1.2 !important; font-weight:700 !important;
  text-transform:uppercase !important;
}
#screen-login.rd-login-phone-mode .input-wrap {
  display:flex !important; align-items:center !important; gap:0 !important;
  min-height:54px !important; padding:0 16px !important; border:1px solid #2c2f39 !important;
  border-radius:16px !important; background:#1d2028 !important; box-shadow:none !important;
}
#screen-login.rd-login-phone-mode .rd-login-field-icon {
  display:block !important; flex:0 0 24px !important; width:24px !important; height:24px !important;
  margin:0 16px 0 0 !important; background-color:#cbd3e2 !important;
  background-repeat:no-repeat !important; background-position:center !important;
  background-size:contain !important;
}
#screen-login.rd-login-phone-mode .rd-icon-user {
  -webkit-mask:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23fff' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'%3E%3Ccircle cx='12' cy='8' r='3.5'/%3E%3Cpath d='M4 21c0-4.1 3.6-7 8-7s8 2.9 8 7'/%3E%3C/svg%3E") center/contain no-repeat !important;
  mask:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23fff' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'%3E%3Ccircle cx='12' cy='8' r='3.5'/%3E%3Cpath d='M4 21c0-4.1 3.6-7 8-7s8 2.9 8 7'/%3E%3C/svg%3E") center/contain no-repeat !important;
}
#screen-login.rd-login-phone-mode .rd-icon-lock {
  -webkit-mask:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23fff' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'%3E%3Crect x='4' y='10' width='16' height='11' rx='2'/%3E%3Cpath d='M8 10V7a4 4 0 0 1 8 0v3'/%3E%3Ccircle cx='12' cy='15.5' r='1'/%3E%3C/svg%3E") center/contain no-repeat !important;
  mask:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23fff' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'%3E%3Crect x='4' y='10' width='16' height='11' rx='2'/%3E%3Cpath d='M8 10V7a4 4 0 0 1 8 0v3'/%3E%3Ccircle cx='12' cy='15.5' r='1'/%3E%3C/svg%3E") center/contain no-repeat !important;
}
#screen-login.rd-login-phone-mode .rd-icon-phone {
  -webkit-mask:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23fff' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M7 3.5 10 8 8 10c1.4 3.1 3.9 5.6 7 7l2-2 4.5 3c.7.5.9 1.2.6 2.1l-.7 1.6c-.4.8-1.3 1.2-2.2 1.1C9.6 21.6 2.4 14.4 1.2 4.8 1.1 3.9 1.5 3 2.3 2.6l1.6-.7c.9-.4 1.6-.2 2.1.6z'/%3E%3C/svg%3E") center/contain no-repeat !important;
  mask:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23fff' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M7 3.5 10 8 8 10c1.4 3.1 3.9 5.6 7 7l2-2 4.5 3c.7.5.9 1.2.6 2.1l-.7 1.6c-.4.8-1.3 1.2-2.2 1.1C9.6 21.6 2.4 14.4 1.2 4.8 1.1 3.9 1.5 3 2.3 2.6l1.6-.7c.9-.4 1.6-.2 2.1.6z'/%3E%3C/svg%3E") center/contain no-repeat !important;
}
#screen-login.rd-login-phone-mode input {
  flex:1 1 auto !important; min-width:0 !important; height:52px !important; padding:0 !important;
  border:0 !important; outline:0 !important; background:transparent !important;
  color:#e8edf7 !important; font-size:15px !important; box-shadow:none !important;
}
#screen-login.rd-login-phone-mode input::placeholder { color:#7b808a !important; opacity:1 !important; }
#screen-login.rd-login-phone-mode .rd-phone-eye { display:none !important; }
#screen-login.rd-login-phone-mode .toggle-pass:not(.rd-phone-eye) {
  display:grid !important; flex:0 0 38px !important; width:38px !important; height:42px !important;
  color:#cbd3e2 !important;
}
#screen-login.rd-login-phone-mode .toggle-pass:not(.rd-phone-eye) svg { width:24px !important; height:24px !important; }
#screen-login.rd-login-phone-mode #authSubmit {
  min-height:54px !important; margin-top:8px !important; border:0 !important;
  border-radius:999px !important; background:#2f78f7 !important; color:#fff !important;
  font-size:14px !important; font-weight:750 !important; box-shadow:none !important;
}
#screen-login.rd-login-phone-mode .login-footer {
  margin-top:22px !important; color:#c5ccdb !important; font-size:12px !important;
  font-weight:500 !important; text-align:center !important;
}
#screen-login.rd-login-phone-mode #authModeSwitch { display:none !important; }
#screen-login:not(.rd-login-phone-mode) .rd-login-field-icon { display:none !important; }
@media (max-height:700px) {
  #screen-login.rd-login-phone-mode .auth { padding-top:16px !important; padding-bottom:16px !important; }
  #screen-login.rd-login-phone-mode .hero { gap:20px !important; margin-bottom:20px !important; }
  #screen-login.rd-login-phone-mode .field { margin-bottom:12px !important; }
}
</style>
""".trimIndent()

        replaceExactlyOnce("</head>", "$referenceCss\n</head>", "inserção do estilo da referência")
        require(html.contains("id=\"rdTgPhone\" type=\"tel\"") &&
            html.contains("inputmode=\"numeric\"") &&
            html.contains("placeholder=\"Digite seu telefone\"") &&
            html.contains("O +55 será adicionado automaticamente") &&
            html.contains("rdchat-login-number-reference-v1")) {
            "O HTML gerado não contém todas as alterações visuais do modo Com número."
        }

        replaceExactlyOnce(
            """async function sendMsg() {
    if (!cur() || blockedGuard()) return;
    const input = $('msgInput'), text = input.value.trim();
    if (!text) return;
    const activeMeta = cur(), outgoingId = uid();
    if (activeMeta.isGroup && (!Array.isArray(activeMeta.members) || !activeMeta.members.length)) {
      /* v1.0.5: grupo envia com qualquer número de membros */
    }
    if ((!activeMeta || !activeMeta.self) && typeof window.rdSecurityValidateText === 'function' && !window.rdSecurityValidateText(text, outgoingId)) return;
    try {
      const sent = await postBubble(currentKey, { id: outgoingId, securityValidated: true, kind: 'text', text: text.slice(0, 5000), reply: replyTo });
      if (!sent) return;
      cancelReply(); input.value = ''; toggleSendButton(); resizeMsgInput();
    } catch (error) {
      console.error('RdChat: não foi possível salvar a mensagem.', error);
      toast('Não foi possível salvar a mensagem. O texto foi mantido para você tentar novamente.');
    }
  }""",
            """async function sendMsg() {
    if (!cur() || blockedGuard()) return;
    const input = $('msgInput'), text = input.value.trim();
    if (!text) return;
    const activeMeta = cur(), outgoingId = uid(), sendingKey = currentKey, reply = replyTo;
    if (activeMeta.isGroup && (!Array.isArray(activeMeta.members) || !activeMeta.members.length)) {
      /* v1.0.5: grupo envia com qualquer número de membros */
    }
    if ((!activeMeta || !activeMeta.self) && typeof window.rdSecurityValidateText === 'function' && !window.rdSecurityValidateText(text, outgoingId)) return;
    let operation;
    try {
      operation = postBubble(sendingKey, { id: outgoingId, securityValidated: true, kind: 'text', text: text.slice(0, 5000), reply });
      cancelReply();
      input.value = '';
      toggleSendButton();
      resizeMsgInput();
      const sent = await operation;
      if (!sent) return;
    } catch (error) {
      console.error('RdChat: não foi possível salvar a mensagem.', error);
      if (!input.value.trim()) input.value = text;
      toggleSendButton(); resizeMsgInput();
      toast('Não foi possível salvar a mensagem. O texto foi restaurado para você tentar novamente.');
    }
  }""",
            "limpeza imediata do compositor após envio"
        )

        replaceExactlyOnce(
            """    // Mensagens de texto do Firebase usam recibo de leitura no Firestore; mídias continuam com recibo P2P.
    by && Object.values(by).flat().forEach(x => {
      if (x && x.transport === 'firebase' && x.firebaseRoomId && window.RdChatFirebase && window.RdChatFirebase.markTextRead) {
        window.RdChatFirebase.markTextRead(x.firebaseRoomId, x.id).catch(error => console.warn('RdChat: recibo de leitura Firebase pendente.', error));
      }
    });
""",
            "",
            "remoção do segundo envio de recibos de leitura"
        )

        replaceExactlyOnce(
            """window.rdRequireBirthDate=function(feature){
    if(!me||window.rdHasBirthDate())return false;
    try{var __c=window.__rd&&window.__rd.cur&&window.__rd.cur();if(__c&&(__c.self||__c.isGroup))return false;}catch(_){}
    say((feature?String(feature)+': ':'')+'para proteger menores e liberar mídias, GIFs, arquivos, foto de perfil e chamadas, informe sua data de nascimento em Perfil. Sem a data, mensagens de texto continuam disponíveis com filtro reforçado.');
    return true;
  };""",
            """window.rdRequireBirthDate=function(feature){
    if(!me||window.rdHasBirthDate())return false;
    try{var __c=window.__rd&&window.__rd.cur&&window.__rd.cur();if(__c&&(__c.self||__c.isGroup))return false;}catch(_){}
    Promise.resolve(window.rdEnsureBirthDate(true)).then(function(saved){
      if(saved){try{if(typeof toast==='function')toast('Idade salva. Tente enviar novamente.');}catch(_){}}
    }).catch(function(error){console.error('RdChat: não foi possível abrir o formulário de idade.',error);});
    return true;
  };""",
            "abertura real do formulário de idade ao bloquear mídia"
        )

        replaceExactlyOnce(
            """  // Sem banner nem diálogo automático: a data é solicitada exclusivamente ao tocar em Perfil.
""",
            """  // A data fica disponível em Ajustes > Sua idade e é aberta quando um recurso exigir.
""",
            "texto da política de idade"
        )

        replaceExactlyOnce(
            """    const ageNotice=$('rdBirthGateBanner');
    if(!window.rdHasBirthDate || !window.rdHasBirthDate()){
      if(!ageNotice){ageNotice=document.createElement('div');ageNotice.id='rdBirthGateBanner';var ph=document.querySelector('#screen-profile .profile-header');if(ph&&ph.parentNode)ph.parentNode.insertBefore(ageNotice,ph.nextSibling);}
      if(ageNotice)ageNotice.textContent='Informe sua data de nascimento para liberar fotos, vídeos, GIFs, arquivos, foto de perfil e chamadas. Mensagens de texto continuam disponíveis com proteção reforçada.';
    }else if(ageNotice)ageNotice.remove();
""",
            """    var ageNotice=$('rdBirthGateBanner'); if(ageNotice)ageNotice.remove();
""",
            "remoção do banner de idade do Perfil"
        )

        replaceExactlyOnce(
            """    /* A data de nascimento é solicitada na primeira abertura do perfil, não na tela de login. */
    setTimeout(function(){ try { if (typeof window.rdEnsureBirthDate === 'function') window.rdEnsureBirthDate(); } catch(e) { console.warn('RdChat: não foi possível abrir o diálogo de nascimento.', e); } }, 260);
""",
            "",
            "remoção do prompt de idade do Perfil"
        )

        replaceExactlyOnce(
            """var explanatory=oldYear ? ('O cadastro antigo guardou apenas o ano '+oldYear+'. Informe a data completa para classificar a conta.') : 'Sua data é usada para aplicar as proteções adequadas à idade. Sem informar, você ainda poderá ler e enviar mensagens de texto, mas fotos, vídeos, GIFs, arquivos, foto de perfil e chamadas ficarão limitados. O pedido aparece ao tocar em Perfil até salvar uma data válida.';""",
            """var explanatory=oldYear ? ('O cadastro antigo guardou apenas o ano '+oldYear+'. Informe a data completa para classificar a conta.') : 'Sua data é usada para aplicar as proteções adequadas à idade. Sem informar, você ainda poderá ler e enviar mensagens de texto, mas fotos, vídeos, GIFs, arquivos, foto de perfil e chamadas ficarão limitados. Você também pode abrir este formulário em Ajustes > Sua idade.';""",
            "orientação para abrir Sua idade em Ajustes"
        )

        replaceExactlyOnce(
            """if(!r||r.value!=='save') { try{me.birthDateGatePending=true;saveProfile();}catch(_){} say('Você pode continuar usando mensagens de texto. Recursos de mídia e chamadas ficam limitados até informar a data em Perfil.'); return false; }""",
            """if(!r||r.value!=='save') { try{me.birthDateGatePending=true;saveProfile();}catch(_){} say('Você pode continuar usando mensagens de texto. Recursos de mídia e chamadas ficam limitados até informar a data em Ajustes > Sua idade.'); return false; }""",
            "orientação após adiar idade"
        )

        replaceExactlyOnce(
            """  async function recBegin(e) {
    if (!cur() || blockedGuard() || micBusy) return;
    skipLock(30000);""",
            """  async function recBegin(e) {
    if (!cur() || blockedGuard() || micBusy) return;
    if (me && window.rdHasBirthDate && !window.rdHasBirthDate()) { window.rdRequireBirthDate('Enviar áudio'); return; }
    skipLock(30000);""",
            "bloqueio de áudio com abertura do formulário de idade"
        )

        replaceExactlyOnce(
            """  async function askNativeMic() {
    const b = window.AndroidBridge; if (!b) return false;
    for (const fn of ['requestMicrophonePermission', 'requestMicPermission', 'requestAudioPermission', 'requestRecordAudioPermission']) {
      if (typeof b[fn] === 'function') { try { b[fn](); await sleep(1600); return true; } catch (e) {} }
    }
    return false;
  }""",
            """  async function askNativeMic() {
    const b = window.AndroidBridge;
    if (!b || typeof b.requestMicrophonePermissionWithCallback !== 'function') return false;
    if (!window.__rdNativePermissionResolvers) {
      window.__rdNativePermissionResolvers = Object.create(null);
      window.onRdChatNativePermissionResult = function(id, granted) {
        const resolve = window.__rdNativePermissionResolvers[String(id || '')];
        if (!resolve) return;
        delete window.__rdNativePermissionResolvers[String(id || '')];
        resolve(granted === true);
      };
    }
    const id = 'mic-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
    return await new Promise(resolve => {
      const timer = setTimeout(() => {
        delete window.__rdNativePermissionResolvers[id];
        resolve(false);
      }, 30000);
      window.__rdNativePermissionResolvers[id] = granted => { clearTimeout(timer); resolve(!!granted); };
      try { b.requestMicrophonePermissionWithCallback(id); }
      catch (error) { clearTimeout(timer); delete window.__rdNativePermissionResolvers[id]; resolve(false); }
    });
  }""",
            "espera real pela resposta da permissão do microfone"
        )

        replaceExactlyOnce(
            """    if (window.AndroidBridge && typeof window.AndroidBridge.showNotification === 'function') {
      try { window.AndroidBridge.showNotification(String(m.name || 'RdChat'), body); return; } catch (e) {}
    }""",
            """    // No APK Android as notificações são centralizadas no FCM nativo para evitar duplicidade.
    if (window.AndroidBridge) return;""",
            "notificações Android centralizadas no FCM"
        )

        replaceExactlyOnce(
            """                if (uid && !String(uid).includes("@")) {
                    window.AndroidBridge.setActiveChatUid(String(uid));
                } else {
                    window.AndroidBridge.clearActiveChat();
                    console.warn("[RdChat] UID do contato nao encontrado; notificacoes permanecem habilitadas.");
                }""",
            """                if (typeof window.AndroidBridge.setActiveChatIdentity === "function") {
                    window.AndroidBridge.setActiveChatIdentity(uid && !String(uid).includes("@") ? String(uid) : "", key);
                } else if (uid && !String(uid).includes("@")) {
                    window.AndroidBridge.setActiveChatUid(String(uid));
                } else {
                    window.AndroidBridge.clearActiveChat();
                }""",
            "sincronização da conversa ativa por UID e email"
        )

        replaceExactlyOnce(
            """      const senderUid = String(payload && (payload.senderId || payload.fromUid) || '');
      const roomId = String(payload && payload.roomId || '');
      let key = '';
      const contact = contacts.find(item => senderUid && String(item.firebaseUid || '') === senderUid);
      if (contact) key = contact.email;
      if (!key && roomId) {
        const match = Object.keys(meta).find(candidate => String((meta[candidate] || {}).firebaseRoomId || '') === roomId);
        if (match) key = match;
      }
      if (!key) { toast('Notificação recebida. Atualize sua lista de conversas para localizar o contato.'); return false; }""",
            """      const senderUid = String(payload && (payload.senderId || payload.fromUid) || '');
      const senderEmail = normEmail(payload && (payload.senderEmail || payload.fromEmail) || '');
      const senderName = String(payload && (payload.senderName || payload.title || '') || 'Contato').replace(/^RdChat\\s*[·-]\\s*/,'').slice(0,40);
      const senderCode = String(payload && payload.senderCode || '').replace(/\\D/g,'').slice(0,6);
      const roomId = String(payload && payload.roomId || '');
      let key = '';
      let contact = contacts.find(item => senderUid && String(item.firebaseUid || '') === senderUid);
      if (!contact && senderEmail) contact = contacts.find(item => normEmail(item.email) === senderEmail);
      if (contact) key = contact.email;
      if (!key && roomId) {
        const match = Object.keys(meta).find(candidate => String((meta[candidate] || {}).firebaseRoomId || '') === roomId);
        if (match) key = match;
      }
      if (!key && senderEmail && /^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(senderEmail)) {
        contact = await addContact(senderEmail, senderName, { code: senderCode, confirmed: true });
        if (contact) {
          contact.firebaseUid = senderUid;
          if (senderCode) contact.code = senderCode;
          try { await dbPut('contacts', { email: contact.email }, contact); } catch (_) {}
          key = contact.email;
        }
      }
      if (!key) {
        try { if (typeof window.rdChatSyncReciprocalContacts === 'function') await window.rdChatSyncReciprocalContacts(); } catch (_) {}
        contact = contacts.find(item => (senderUid && String(item.firebaseUid || '') === senderUid) || (senderEmail && normEmail(item.email) === senderEmail));
        if (contact) key = contact.email;
      }
      if (!key) { toast('Não foi possível localizar a conversa desta notificação. Atualize os contatos e tente novamente.'); return false; }""",
            "abertura direta do contato remetente ao tocar notificação"
        )

        val ageSettings = """
<div class="settings-section rd-age-settings-section">
  <div class="label">CONTA</div>
  <div class="settings-list">
    <div class="settings-item" role="button" tabindex="0" onclick="window.rdEnsureBirthDate&&window.rdEnsureBirthDate(true)" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();window.rdEnsureBirthDate&&window.rdEnsureBirthDate(true);}">
      <div class="si-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="4" width="18" height="17" rx="2"/><path d="M8 2v4M16 2v4M3 9h18M8 13h3M8 17h7"/></svg></div>
      <div class="si-body"><div class="si-title">Sua idade</div><div class="si-sub" id="rdSettingsAgeSubtitle">Informe sua data de nascimento e ajuste a proteção da conta</div></div>
      <div class="chevron"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18l6-6-6-6"/></svg></div>
    </div>
    <div class="settings-item" role="button" tabindex="0" onclick="window.AndroidBridge&&window.AndroidBridge.requestAppPermissions&&window.AndroidBridge.requestAppPermissions()" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();window.AndroidBridge&&window.AndroidBridge.requestAppPermissions&&window.AndroidBridge.requestAppPermissions();}">
      <div class="si-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7l8-4z"/><path d="M9 12l2 2 4-4"/></svg></div>
      <div class="si-body"><div class="si-title">Permissões do dispositivo</div><div class="si-sub">Microfone, câmera e notificações</div></div>
      <div class="chevron"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18l6-6-6-6"/></svg></div>
    </div>
  </div>
</div>
""".trimIndent()
        replaceExactlyOnce(
            """<div class="settings-section">
              <div class="label">SOBRE</div>""",
            """$ageSettings
            <div class="settings-section">
              <div class="label">SOBRE</div>""",
            "opções Sua idade e permissões em Ajustes"
        )

        replaceExactlyOnce(
            """      if (native && typeof native.requestNotificationPermission === 'function') await native.requestNotificationPermission();
      const plugins = window.Capacitor && window.Capacitor.Plugins;""",
            """      if (native && typeof native.requestNotificationPermission === 'function') await native.requestNotificationPermission();
      if (native && typeof native.requestAppPermissions === 'function') native.requestAppPermissions();
      const plugins = window.Capacitor && window.Capacitor.Plugins;""",
            "solicitação nativa das permissões na inicialização"
        )

        replaceExactlyOnce(
            """    var ageNotice=$('rdBirthGateBanner');
    if(!window.rdHasBirthDate || !window.rdHasBirthDate()){
      if(!ageNotice){ageNotice=document.createElement('div');ageNotice.id='rdBirthGateBanner';var ph=document.querySelector('#screen-profile .profile-header');if(ph&&ph.parentNode)ph.parentNode.insertBefore(ageNotice,ph.nextSibling);}
      if(ageNotice)ageNotice.textContent='Informe sua data de nascimento para liberar fotos, vídeos, GIFs, arquivos, foto de perfil e chamadas. Mensagens de texto continuam disponíveis com proteção reforçada.';
    }else if(ageNotice)ageNotice.remove();""",
            """    var ageNotice=$('rdBirthGateBanner'); if(ageNotice)ageNotice.remove();""",
            "remoção do aviso etário dentro do Perfil"
        )

        replaceExactlyOnce(
            """window.AndroidBridge.setActiveChatUid(String(uid));""",
            """window.AndroidBridge.setActiveChatIdentity(String(uid), key);""",
            "sincronização da conversa ativa com email"
        )

        replaceExactlyOnce(
            """window.AndroidBridge.clearActiveChat();
                    console.warn("[RdChat] UID do contato nao encontrado; notificacoes permanecem habilitadas.");""",
            """window.AndroidBridge.setActiveChatIdentity("", key);""",
            "sincronização por email quando UID ainda não foi resolvido"
        )


        replaceExactlyOnce(
            """  async function registerOwnIdentity() {
    if (!currentUid || !me || !me.email) return;
    let code = codeNorm(me.code);
    if (!codePathSafe(code) && typeof createContactCode === 'function') code = codeNorm(createContactCode());
    if (!codePathSafe(code)) throw new Error('Código de contato inválido.');
    let reserved = false;
    for (let attempt = 0; attempt < 10; attempt++) {
      const ref = fs.collection('contactCodes').doc(code);
      const available = await fs.runTransaction(async tx => {
        const snap = await tx.get(ref);
        if (snap.exists && snap.data().uid !== currentUid) return false;
        tx.set(ref, { uid: currentUid, email: String(me.email), name: String(me.name || 'Contato').slice(0, 40), code, accountType: me.accountType === 'COM_NUMERO' ? 'COM_NUMERO' : 'SEM_NUMERO', tgUsername: String(me.tgUsername || ''), is_adult: (typeof me.is_adult === 'boolean') ? me.is_adult : null, updatedAt: Date.now() }, { merge: true });
        return true;
      });
      if (available) { reserved = true; break; }
      if (typeof createContactCode !== 'function') break;
      code = codeNorm(createContactCode());
    }
    if (!reserved) throw new Error('Não foi possível reservar um código de contato exclusivo.');
    if (codeNorm(me.code) !== code) { me.code = code; try { if (typeof saveProfile === 'function') saveProfile(); } catch (_) {} }
    await fs.collection('users').doc(currentUid).set({ uid: currentUid, email: String(me.email), displayName: String(me.name || 'Contato').slice(0, 40), code, accountType: me.accountType === 'COM_NUMERO' ? 'COM_NUMERO' : 'SEM_NUMERO', tgUserId: String(me.tgUserId || ''), tgUsername: String(me.tgUsername || ''), is_adult: (typeof me.is_adult === 'boolean') ? me.is_adult : null, updatedAt: Date.now() }, { merge: true });
  }""",
            """  async function workerRequestDuringInitialization(path, body) {
    if (!MEDIA_API) throw new Error('Gateway de contatos indisponível. Publique o Worker RdChat FCM/Telegram.');
    if (!auth || !auth.currentUser) throw new Error('Sessão Firebase indisponível para registrar a identidade.');
    const idToken = await auth.currentUser.getIdToken(true);
    const response = await fetch(MEDIA_API + path, {
      method: 'POST',
      headers: { 'content-type': 'application/json', Authorization: 'Bearer ' + idToken },
      body: JSON.stringify(body || {})
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.ok === false) throw new Error(payload.error || ('Gateway de contatos respondeu HTTP ' + response.status));
    return payload;
  }

  async function registerOwnIdentity() {
    if (!currentUid || !me || !me.email) return;
    let code = codeNorm(me.code);
    if (!codePathSafe(code) && typeof createContactCode === 'function') code = codeNorm(createContactCode());
    if (!codePathSafe(code)) throw new Error('Código de contato inválido.');
    let registered = null, lastError = null;
    for (let attempt = 0; attempt < 10; attempt++) {
      try {
        registered = await workerRequestDuringInitialization('/v1/identity/register', {
          code,
          email: String(me.email),
          name: String(me.name || 'Contato').slice(0, 40),
          accountType: me.accountType === 'COM_NUMERO' ? 'COM_NUMERO' : 'SEM_NUMERO',
          tgUserId: String(me.tgUserId || ''),
          tgUsername: String(me.tgUsername || ''),
          is_adult: typeof me.is_adult === 'boolean' ? me.is_adult : null
        });
        break;
      } catch (error) {
        lastError = error;
        if (!/já pertence a outra conta|already belongs|HTTP 409/i.test(String(error && error.message || error)) ||
            typeof createContactCode !== 'function') throw error;
        code = codeNorm(createContactCode());
        if (!codePathSafe(code)) throw new Error('Não foi possível gerar um novo código de contato.');
      }
    }
    if (!registered || !registered.code) throw lastError || new Error('O Worker não confirmou o registro da identidade.');
    code = codeNorm(registered.code);
    if (codeNorm(me.code) !== code) {
      me.code = code;
      try { if (typeof saveProfile === 'function') saveProfile(); } catch (_) {}
    }
  }

  async function addContactByCode(codeValue) {
    if (!initialized) await initializeFirebase();
    const code = codeNorm(codeValue);
    if (!codePathSafe(code)) throw new Error('Digite os seis dígitos do código.');
    return mediaGatewayRequest('/v1/contacts/add', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code })
    });
  }

  async function lookupContactByCode(codeValue) {
    if (!initialized) await initializeFirebase();
    const code = codeNorm(codeValue);
    if (!codePathSafe(code)) throw new Error('Digite os seis dígitos do código.');
    const result = await mediaGatewayRequest('/v1/contacts/lookup', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code })
    });
    return result && result.profile ? result.profile : null;
  }

  async function syncContactsFromWorker() {
    if (!initialized) await initializeFirebase();
    const response = await mediaGatewayRequest('/v1/contacts', { method: 'GET' });
    const result = await response.json();
    return Array.isArray(result && result.contacts) ? result.contacts : [];
  }""",
            "registro de identidade e contatos pelo Worker autenticado"
        )

        replaceExactlyOnce(
            """    publishProfilePhoto,
    get relayConfigured() { return !!MEDIA_API; },""",
            """    publishProfilePhoto,
    addContactByCode,
    lookupContactByCode,
    syncContactsFromWorker,
    get relayConfigured() { return !!MEDIA_API; },""",
            "exposição de API de contatos do Worker"
        )
        htmlFile.writeText(html, Charsets.UTF_8)
    }
}

android {
    namespace = "com.example.rdchat"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    sourceSets.getByName("main").assets.setSrcDirs(listOf(generatedRdchatAssets))

    defaultConfig {
        applicationId = "com.example.rdchat"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$escapedTelegramApiHash\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Mantém o APK universal menor ao comprimir as bibliotecas .so.
    // O Android as extrai na instalação, preservando o carregamento nativo.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // AGP 9.x compila Kotlin com o suporte Kotlin integrado; não aplicar kotlin-android.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Gera um único APK com todas as bibliotecas nativas entregues pelas dependências.
    // A verificação no CI exige as quatro ABIs Android: ARM 32/64 e x86 32/64.

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    testOptions { animationsDisabled = true }
}

tasks.named("preBuild").configure {
    dependsOn(prepareRdchatAssets)
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // TDLib JNI + Java API, pinned to an immutable commit; includes arm64-v8a and x86_64.
    implementation("com.github.capullo-tech:lib-tdlib-android:7dede3f1a716b7398caf79acd413ad9d5331f079")

    // Native WebRTC media engine used together with TDLib call signaling.
    implementation("io.github.pytgcalls:ntgcalls:3.0.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
