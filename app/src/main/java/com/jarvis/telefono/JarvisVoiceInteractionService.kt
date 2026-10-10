package com.jarvis.telefono

import android.service.voice.VoiceInteractionService

// Non fa altro che esistere: è la sua presenza (dichiarata nel Manifest, con
// il file XML che punta a JarvisVoiceInteractionSessionService) che rende
// Jarvis selezionabile in Impostazioni > App predefinite > App assistente
// digitale. Una volta scelto lì, tenere premuto il tasto laterale apre
// JarvisVoiceInteractionSession invece di Gemini.
class JarvisVoiceInteractionService : VoiceInteractionService()
