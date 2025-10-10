package com.eduracha.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import java.util.*

object JwtConfig {
    private const val secret = "EduRachaSuperSecretKey123!" 
    private const val issuer = "eduracha-server"
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(issuer).build()

    fun generateToken(email: String): String {
        val now = System.currentTimeMillis()
        val expiration = Date(now + 24 * 60 * 60 * 1000) // 24 horas de validez

        return JWT.create()
            .withIssuer(issuer)
            .withClaim("email", email)
            .withIssuedAt(Date(now))
            .withExpiresAt(expiration)
            .sign(algorithm)
    }

    fun verifyToken(token: String): DecodedJWT? {
        return try {
            verifier.verify(token)
        } catch (e: Exception) {
            null
        }
    }
}
