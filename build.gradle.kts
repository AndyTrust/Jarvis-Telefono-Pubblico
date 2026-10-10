plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // 0.6.1 (2026-10-08): sveglia FCM. Il plugin si applica in app/ solo se c'è app/google-services.json (fuori dal repo).
    id("com.google.gms.google-services") version "4.4.2" apply false
}
