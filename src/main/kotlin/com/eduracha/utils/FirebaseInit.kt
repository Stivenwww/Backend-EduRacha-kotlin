/*package com.eduracha.utils

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.auth.oauth2.GoogleCredentials
import java.io.FileInputStream

object FirebaseInit {

    fun initialize(credentialsPath: String?, databaseUrl: String?) {
        if (FirebaseApp.getApps().isNotEmpty()) return

        if (credentialsPath.isNullOrEmpty() || databaseUrl.isNullOrEmpty()) {
            throw IllegalStateException("No se encontró GOOGLE_APPLICATION_CREDENTIALS o FIREBASE_DATABASE_URL en las variables de entorno.")
        }

        val serviceAccount = FileInputStream(credentialsPath)

        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
            .setDatabaseUrl(databaseUrl)
            .build()

        FirebaseApp.initializeApp(options)
        println("Firebase inicializado correctamente con URL: $databaseUrl")
    }
}*/


package com.eduracha.utils

import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.auth.oauth2.GoogleCredentials

object FirebaseInit {

    fun initialize(credentials: GoogleCredentials, databaseUrl: String?) {
        if (FirebaseApp.getApps().isNotEmpty()) return

        if (databaseUrl.isNullOrEmpty()) {
            throw IllegalStateException("No se encontró FIREBASE_DATABASE_URL en las variables de entorno.")
        }

        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .setDatabaseUrl(databaseUrl)
            .build()

        FirebaseApp.initializeApp(options)
        println("Firebase inicializado correctamente con URL: $databaseUrl")
    }
}
