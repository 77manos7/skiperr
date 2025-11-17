package gr.accio.models

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.smallrye.mutiny.Uni
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "user_configurations",
    indexes = [
        Index(name = "idx_user_config_user", columnList = "user_id", unique = true)
    ]
)
class UserConfiguration : PanacheEntityBase {

    @Id @GeneratedValue(generator = "UUID")
    var id: UUID? = null

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    lateinit var user: User

    // Language preferences
    @Column(length = 500)
    var preferredLanguages: String? = null // Comma-separated list: "en,el,fr"

    @Column(length = 10)
    var defaultSourceLanguage: String = "en" // Default source language for translation

    @Column(length = 10)
    var defaultTargetLanguage: String = "el" // Default target language

    // AI Provider settings
    @Enumerated(EnumType.STRING)
    var aiProvider: AIProvider = AIProvider.OPENAI

    @Column(length = 255)
    var openaiApiKey: String? = null

    @Column(length = 50)
    var openaiModel: String = "gpt-4o-mini"

    @Column(length = 255)
    var anthropicApiKey: String? = null

    @Column(length = 50)
    var anthropicModel: String = "claude-3-haiku-20240307"

    @Column(length = 255)
    var geminiApiKey: String? = null

    @Column(length = 50)
    var geminiModel: String = "gemini-1.5-flash"

    // Sync preferences
    @Enumerated(EnumType.STRING)
    var syncTool: SyncTool = SyncTool.FFSUBSYNC

    var autoSyncThreshold: Double = 0.8 // Auto-sync if confidence > threshold

    var maxSyncOffset: Int = 5000 // Maximum sync offset in milliseconds

    // Processing preferences
    var enableAutoTranslation: Boolean = true
    var enableAutoSync: Boolean = true
    var enableBackgroundProcessing: Boolean = true
    var maxConcurrentTasks: Int = 3

    // Quality settings
    var minConfidenceScore: Double = 0.7 // Minimum AI confidence to accept
    var enableQualityCheck: Boolean = true
    var retryFailedTasks: Boolean = true

    // Notification preferences
    var enableEmailNotifications: Boolean = false
    var enableWebNotifications: Boolean = true
    var notifyOnTaskCompletion: Boolean = true
    var notifyOnErrors: Boolean = true

    // Library settings
    @Column(length = 1000)
    var customLibraryPaths: String? = null // Additional library paths

    var scanInterval: Int = 24 // Hours between automatic scans

    var enablePlexIntegration: Boolean = false

    @Column(length = 255)
    var plexServerUrl: String? = null

    @Column(length = 255)
    var plexToken: String? = null

    // Backup settings
    var enableAutoBackup: Boolean = true
    var backupRetentionDays: Int = 30

    @Column(length = 1000)
    var backupPath: String? = null

    var createdAt: Instant = Instant.now()
    var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    companion object : PanacheCompanionBase<UserConfiguration, UUID> {
        fun findByUser(user: User): Uni<UserConfiguration?> =
            find("user", user).firstResult()

        fun findByUserId(userId: UUID): Uni<UserConfiguration?> =
            find("user.id", userId).firstResult()

        fun createDefaultForUser(user: User): Uni<UserConfiguration> {
            val config = UserConfiguration().apply {
                this.user = user
            }
            return config.persist()
        }

        fun findByAiProvider(provider: AIProvider): Uni<List<UserConfiguration>> =
            list("aiProvider", provider)

        fun findWithPlexEnabled(): Uni<List<UserConfiguration>> =
            list("enablePlexIntegration = true")

        fun findWithAutoProcessingEnabled(): Uni<List<UserConfiguration>> =
            list("enableBackgroundProcessing = true")
    }
}

enum class AIProvider {
    OPENAI,
    ANTHROPIC,
    GEMINI,
    LOCAL      // For future local AI models
}

enum class SyncTool {
    FFSUBSYNC,
    WHISPERX,
    MANUAL
}