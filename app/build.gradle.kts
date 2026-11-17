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
