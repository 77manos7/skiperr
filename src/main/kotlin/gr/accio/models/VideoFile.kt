package gr.accio.models

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.smallrye.mutiny.Uni
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "videos",
    indexes = [
        Index(name = "idx_video_path", columnList = "path", unique = true),
        Index(name = "idx_video_type", columnList = "type"),
        Index(name = "idx_video_last_checked", columnList = "last_checked"),
        Index(name = "idx_video_title", columnList = "title")
    ]
)
class VideoFile: PanacheEntityBase {

    @Id @GeneratedValue(generator = "UUID")
    var id: UUID? = null

    @Column(nullable = false, unique = true, length = 1000)
    lateinit var path: String

    @Column(length = 500)
    var title: String? = null
    
    @Column(length = 100)
    var type: String? = null

    // Enhanced metadata
    var fileSize: Long? = null
    var duration: Long? = null // Duration in milliseconds
    var resolution: String? = null // e.g., "1920x1080"
    var codec: String? = null
    var bitrate: Long? = null

    // Subtitle status flags
    var hasEmbeddedEnglish: Boolean = false
    var hasGreekSubtitle: Boolean = false
    var generatedGreek: Boolean = false
    var synced: Boolean = false

    // Processing status
    var isProcessing: Boolean = false
    var processingError: String? = null

    // Timestamps
    var lastChecked: Instant? = null
    var createdAt: Instant = Instant.now()
    var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    companion object : PanacheCompanionBase<VideoFile, UUID> {
        fun findByPath(path: String): Uni<VideoFile?> = 
            find("path", path).firstResult()

        fun findUnprocessed(): Uni<List<VideoFile>> = 
            list("isProcessing = false AND lastChecked IS NULL")

        fun findByType(type: String): Uni<List<VideoFile>> = 
            list("type", type)

        fun findRecentlyAdded(limit: Int): Uni<List<VideoFile>> = 
            find("ORDER BY createdAt DESC").page(0, limit).list()

        fun findWithoutGreekSubtitles(): Uni<List<VideoFile>> = 
            list("hasGreekSubtitle = false AND hasEmbeddedEnglish = true")

        fun findOutOfSync(): Uni<List<VideoFile>> = 
            list("synced = false AND hasGreekSubtitle = true")

        fun countByType(type: String): Uni<Long> = 
            count("type", type)

        fun findProcessing(): Uni<List<VideoFile>> = 
            list("isProcessing = true")
    }
}