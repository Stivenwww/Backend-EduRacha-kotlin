package com.eduracha.utils

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.io.FileInputStream

object FirebaseInit {

    fun initialize() {
        if (FirebaseApp.getApps().isNotEmpty()) return

        val serviceAccount = FileInputStream("src/main/resources/serviceAccountKey.json")

        val options = FirebaseOptions.builder()
            .setCredentials(com.google.auth.oauth2.GoogleCredentials.fromStream(serviceAccount))
            .setDatabaseUrl("https://eduracha-41314-default-rtdb.firebaseio.com/") 
            .build()

        FirebaseApp.initializeApp(options)
        println("✅ Firebase inicializado correctamente.")
    }
}
