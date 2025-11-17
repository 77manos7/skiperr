package gr.accio.models

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.smallrye.mutiny.Uni
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "users",
    indexes = [
        Index(name = "idx_user_username", columnList = "username", unique = true),
        Index(name = "idx_user_email", columnList = "email", unique = true),
        Index(name = "idx_user_role", columnList = "role"),
        Index(name = "idx_user_active", columnList = "is_active")
    ]
)
class User : PanacheEntityBase {

    @Id @GeneratedValue(generator = "UUID")
    var id: UUID? = null

    @Column(nullable = false, unique = true, length = 50)
    lateinit var username: String

    @Column(nullable = false, length = 255)
    lateinit var passwordHash: String

    @Column(unique = true, length = 100)
    var email: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: UserRole = UserRole.USER

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    var lastLoginAt: Instant? = null
    var createdAt: Instant = Instant.now()
    var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    companion object : PanacheCompanionBase<User, UUID> {
        fun findByUsername(username: String): Uni<User?> =
            find("username", username).firstResult()

        fun findByEmail(email: String): Uni<User?> =
            find("email", email).firstResult()

        fun findActiveUsers(): Uni<List<User>> =
            list("isActive = true ORDER BY username ASC")

        fun findByRole(role: UserRole): Uni<List<User>> =
            list("role", role)

        fun countActiveUsers(): Uni<Long> =
            count("isActive = true")

        fun findRecentlyActive(days: Int): Uni<List<User>> {
            val cutoff = Instant.now().minusSeconds(days * 24 * 60 * 60L)
            return list("lastLoginAt >= ?1 ORDER BY lastLoginAt DESC", cutoff)
        }
    }
}

enum class UserRole {
    ADMIN,      // Full system access
    USER,       // Standard user access
    VIEWER      // Read-only access
}