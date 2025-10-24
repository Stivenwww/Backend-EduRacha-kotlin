package com.eduracha.services

import com.google.firebase.database.FirebaseDatabase

object ServicioProgreso {

    private val db = FirebaseDatabase.getInstance()

    fun inicializarDatosEstudiante(estudianteId: String, cursoId: String, nombre: String, email: String) {
        val refProgreso = db.getReference("progresos").child(cursoId).child(estudianteId)
        val refRacha = db.getReference("rachas").child(cursoId).child(estudianteId)

        val progresoInicial = mapOf(
            "porcentaje" to 0,
            "temasCompletados" to 0,
            "ultimaActividad" to System.currentTimeMillis()
        )

        val rachaInicial = mapOf(
            "diasConsecutivos" to 0,
            "ultimaFecha" to System.currentTimeMillis()
        )

        // Crear los nodos de progreso y racha correctamente
        refProgreso.setValue(progresoInicial) { error, _ ->
            if (error != null) {
                println("Error al crear nodo de progreso: ${error.message}")
            }
        }

        refRacha.setValue(rachaInicial) { error, _ ->
            if (error != null) {
                println("Error al crear nodo de racha: ${error.message}")
            }
        }

        // Guardar notificación de bienvenida
        val refNotif = db.getReference("notificaciones").child(estudianteId).push()
        val notificacion = mapOf(
            "titulo" to "¡Bienvenido al curso!",
            "mensaje" to "Hola $nombre, tu progreso en el curso $cursoId ha sido inicializado.",
            "fecha" to System.currentTimeMillis()
        )

        refNotif.setValue(notificacion) { error, _ ->
            if (error != null) {
                println("Error al guardar notificación: ${error.message}")
            }
        }
    }
}
