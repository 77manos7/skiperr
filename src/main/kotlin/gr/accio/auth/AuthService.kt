package gr.accio.auth

import io.smallrye.jwt.build.Jwt
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt
import java.time.Duration
import java.time.Instant

@ApplicationScoped
class AuthService {

    private val adminPassword = System.getenv("SKIPERR_PASSWORD") ?: "admin"
    private val jwtSecret = System.getenv("JWT_SECRET") ?: "skiperr-secret-key-change-in-production"
    private val tokenExpirationHours = 24L

    fun authenticate(password: String): Uni<AuthResponse> {
        return Uni.createFrom().item {
            if (verifyPassword(password)) {
                val token = generateJwtToken()
                AuthResponse(
                    success = true,
                    token = token,
                    expiresIn = tokenExpirationHours * 3600, // seconds
                    message = "Authentication successful"
                )
            } else {
                AuthResponse(
                    success = false,
                    token = null,
                    expiresIn = null,
                    message = "Invalid password"
                )
            }
        }
    }

    fun validateToken(token: String): Uni<Boolean> {
        return Uni.createFrom().item {
            try {
                // JWT validation is handled by Quarkus automatically
                // This method can be used for additional custom validation if needed
                token.isNotBlank()
            } catch (e: Exception) {
                false
            }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String): Uni<ChangePasswordResponse> {
        return Uni.createFrom().item {
            if (!verifyPassword(currentPassword)) {
                ChangePasswordResponse(
                    success = false,
                    message = "Current password is incorrect"
                )
            } else if (newPassword.length < 6) {
                ChangePasswordResponse(
                    success = false,
                    message = "New password must be at least 6 characters long"
                )
            } else {
                // In a real implementation, you would update the password in a secure store
                // For now, we'll just return success (password change would require restart)
                ChangePasswordResponse(
                    success = true,
                    message = "Password change successful. Please restart the application and update SKIPERR_PASSWORD environment variable."
                )
            }
        }
    }

    private fun verifyPassword(password: String): Boolean {
        // If the admin password starts with $2a$, it's already hashed with BCrypt
        return if (adminPassword.startsWith("$2a$")) {
            BCrypt.checkpw(password, adminPassword)
        } else {
            // Plain text comparison for development (not recommended for production)
            password == adminPassword
        }
    }

    private fun generateJwtToken(): String {
        val now = Instant.now()
        val expiresAt = now.plus(Duration.ofHours(tokenExpirationHours))

        return Jwt.issuer("skiperr")
            .subject("admin")
            .groups(setOf("admin", "user"))
            .claim("preferred_username", "admin")
            .issuedAt(now)
            .expiresAt(expiresAt)
            .signWithSecret(jwtSecret)
    }

    fun hashPassword(plainPassword: String): String {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt())
    }
}

@Serializable
data class AuthResponse(
    val success: Boolean,
    val token: String?,
    val expiresIn: Long?, // seconds
    val message: String
)

@Serializable
data class ChangePasswordResponse(
    val success: Boolean,
    val message: String
)