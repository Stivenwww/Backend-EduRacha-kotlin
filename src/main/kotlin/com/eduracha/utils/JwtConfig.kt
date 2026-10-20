package com.eduracha.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import java.util.*

object JwtConfig {
    // En producción debe venir de JWT_SECRET. Fallback solo para desarrollo local.
    private val secret: String = System.getenv("JWT_SECRET")
        ?: System.getProperty("JWT_SECRET")
        ?: "EduRachaSuperSecretKey123!"
    private const val issuer = "eduracha-server"
    val isDefaultSecret: Boolean get() = secret == "EduRachaSuperSecretKey123!"
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(issuer).build()

    fun jwtSecret(): String = secret

    // Genera token JWT con uid, email y rol
    fun generateToken(uid: String, email: String, rol: String = "estudiante"): String {
        val now = System.currentTimeMillis()
        val expiration = Date(now + 24 * 60 * 60 * 1000)

        return JWT.create()
            .withIssuer(issuer)
            .withClaim("uid", uid)
            .withClaim("email", email)
            .withClaim("rol", rol)
            .withIssuedAt(Date(now))
            .withExpiresAt(expiration)
            .sign(algorithm)
    }

    fun generateToken(email: String): String {
        return generateToken("", email, "estudiante")
    }

    fun verifyToken(token: String): DecodedJWT? {
        return try {
            verifier.verify(token)
        } catch (e: Exception) {
            null
        }
    }
}

