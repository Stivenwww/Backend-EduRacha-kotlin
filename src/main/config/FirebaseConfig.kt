package com.eduracha.config

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.FirebaseDatabase
import java.io.FileInputStream

object FirebaseConfig {
    private val databaseUrl = "https://console.firebase.google.com/project/eduracha-41314/database/eduracha-41314-default-rtdb/data/~2F?hl=es-419"

    fun init() {
        val base64 = System.getenv("FIREBASE_CREDENTIALS_BASE64")
        ?: throw IllegalStateException("Firebase credentials not found")

        val bytes = Base64.getDecoder().decode(base64)
        val credentials = GoogleCredentials.fromStream(bytes.inputStream())
        
        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .setDatabaseUrl(databaseUrl)
            .build()

        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(options)
        }
    }

    fun getDatabase(): FirebaseDatabase {
        return FirebaseDatabase.getInstance()
    }
}
