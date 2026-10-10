import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 0.6.1 (2026-10-08): la sveglia FCM (sveglia/) legge il progetto Firebase da app/google-services.json, che NON sta
// nel repo (chi pubblica lo tiene fuori da git). Senza il file l'APK si costruisce lo
// stesso e la sveglia resta spenta (Firebase non parte, Sveglia.kt lo dice nel log).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Le credenziali di firma non stanno nel repo (jarvis-android/keystore.properties
// è in .gitignore): senza quel file la build di release fallisce con un
// messaggio chiaro invece di firmare con una chiave sbagliata o silenziosa.
val keystoreProperties = Properties()
// La firma della release si può tenere FUORI dal repo: JBOSS_KEYSTORE_PROPERTIES = percorso di un keystore.properties
// (storeFile relativo = accanto a quel file). Senza la variabile vale keystore.properties nella cartella del progetto.
val keystorePropertiesFile = System.getenv("JBOSS_KEYSTORE_PROPERTIES")?.takeIf { it.isNotBlank() }?.let { file(it) }
    ?: rootProject.file("keystore.properties")
val hasKeystore = keystorePropertiesFile.exists()
if (hasKeystore) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.jarvis.telefono"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.telefono"
        minSdk = 26
        targetSdk = 34
        // 0.4.1 (2026-10-07): cervello della VPS per le frasi non capite dalle regole. 0.4.0: procedura guidata, cassaforte, abbinamento QR. 0.3.3: Google dopo la parola.
        // 0.6.3 (2026-10-08): app unica: UI approvata, sveglia FCM dalla VPS (sveglia/), Postino completo, app Jarvis integrata.
        versionCode = 20
        versionName = "0.7.1"
        // Jarvis Telefono (passo A, 2026-10-07): nessun indirizzo, nessun token, nessun server.
        // Le preferenze personali di Boss arrivano da config-boss.json via ADB (scripts/configura-boss.sh).
        // 0.4.0: FLAG_SECURE sulle schermate con segreti. Spento SOLO nel pacchetto di prova (.prova, cassaforte
        // vuota e caselle finte) per guardare gli screenshot delle prove; l'APK vero lo ha sempre acceso.
        buildConfigField("boolean", "SCHERMO_SICURO", "true")
    }

    // L'AAR di sherpa-onnx porta le librerie native per quattro architetture. Un APK per architettura (più leggero)
    // e uno universale che va su tutti i telefoni:
    //   arm64-v8a   quasi tutti i telefoni dal 2017 in poi (Samsung, Pixel, Xiaomi, OnePlus, Motorola…)
    //   armeabi-v7a telefoni vecchi o economici a 32 bit (Android Go)
    //   x86_64      emulatore su PC Intel/AMD e alcuni Chromebook
    // x86 a 32 bit non si costruisce: nessun telefono in commercio lo usa più.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    // Le librerie x86 a 32 bit restano fuori anche dall'APK universale.
    packaging {
        jniLibs {
            excludes += "lib/x86/**"
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = (keystoreProperties["storeFile"] as String).let { sf ->
                    File(sf).takeIf { it.isAbsolute } ?: File(keystorePropertiesFile.parentFile, sf)
                }
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
        // MODIFICA LOCALE: build CI (GitHub Actions). Il default di Gradle
        // per "debug" cerca ~/.android/debug.keystore, ma il percorso
        // vero dipende da dove l'Android SDK del runner si aspetta di
        // trovarlo (varia, non è affidabile) -- scoperto perché ogni run
        // produceva una firma diversa, cioè una chiave rigenerata da zero
        // ogni volta, ignorando il file scritto dal workflow. Quando la
        // variabile d'ambiente JARVIS_DEBUG_KEYSTORE è impostata (solo in
        // CI), si punta esplicitamente a quel file, senza ambiguità sul
        // percorso di default.
        // Vuoto o file inesistente valgono come «non c'è»: una variabile
        // d'ambiente impostata a stringa vuota non è null, e puntare a un file
        // che non esiste faceva fallire la compilazione invece di ricadere
        // sulla chiave di debug normale.
        val ciDebugKeystorePath = System.getenv("JARVIS_DEBUG_KEYSTORE")
            ?.takeIf { it.isNotBlank() && file(it).exists() }
        if (ciDebugKeystorePath != null) {
            getByName("debug") {
                storeFile = file(ciDebugKeystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // 0.4.0: un secondo pacchetto (com.jarvis.telefono.prova, nome «JBoss prova») per provare la procedura guidata
        // con una cassaforte VUOTA senza toccare quella vera di Boss: ./gradlew :app:assembleProva
        create("prova") {
            initWith(getByName("release"))
            applicationIdSuffix = ".prova"
            versionNameSuffix = "-prova"
            buildConfigField("boolean", "SCHERMO_SICURO", "false")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Solo per scaricare i modelli della voce (Whisper, ERes2Net) da GitHub di k2-fsa, una volta.
    // Nessun collegamento a server di Jarvis.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Il cervello (nucleo/Cervello.kt) ha funzioni suspend: pronto per il cervello in cloud del passo B.
    // 1.8.1 è l'ultima per Kotlin 1.9.x.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // AAR ufficiale precompilato di sherpa-onnx (github.com/k2-fsa/sherpa-onnx,
    // release v1.13.8) per il risveglio vocale "Hey Jarvis" offline — nessun
    // account, nessuna chiave, Apache 2.0.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    // Per estrarre Whisper dal tar.bz2 di k2-fsa in streaming (voce/Modelli.kt). Apache 2.0.
    implementation("org.apache.commons:commons-compress:1.28.0")
    // 0.4.0: lettore di codici QR di Google Play Services per l'abbinamento con la VPS. Nessun permesso fotocamera
    // (la fotocamera la apre Play Services, non JBoss) e pochi KB nell'APK: il lettore si scarica da Play Services.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // 0.6.1 (2026-10-08): la sveglia della VPS (sveglia/). Solo firebase-messaging, niente analytics.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")

    // Prove che girano sulla JVM, senza telefono e senza emulatore: ./gradlew test
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // org.json vero sulla JVM (quello di Android nelle prove è solo uno stub).
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// postino-numeri (2026-10-07): la versione del solo APK di prova si sceglie da riga di comando,
// senza toccare versionCode/versionName di defaultConfig (li porta telefono-ui):
//   ./gradlew :app:assembleRelease -PversioneProva=0.2.0-postino -PcodiceProva=3
// Senza le due proprietà l'APK resta identico.
androidComponents {
    onVariants { variante ->
        val nome = project.findProperty("versioneProva") as String?
        val codice = (project.findProperty("codiceProva") as String?)?.toIntOrNull()
        variante.outputs.forEach { uscita ->
            if (nome != null) uscita.versionName.set(nome)
            if (codice != null) uscita.versionCode.set(codice)
        }
    }
}
