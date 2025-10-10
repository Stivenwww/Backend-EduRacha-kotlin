package com.eduracha.config

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.FirebaseDatabase
import java.io.FileInputStream

object FirebaseConfig {
    private val databaseUrl = "https://console.firebase.google.com/project/eduracha-41314/database/eduracha-41314-default-rtdb/data/~2F?hl=es-419"

    fun init() {
        val serviceAccount = FileInputStream("serviceAccountKey.json")

        val options = FirebaseOptions.builder()
            .setCredentials(com.google.auth.oauth2.GoogleCredentials.fromStream(serviceAccount))
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
