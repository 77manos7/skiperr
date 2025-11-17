package gr.accio.models

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.smallrye.mutiny.Uni
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "tasks",
    indexes = [
        Index(name = "idx_task_status", columnList = "status"),
        Index(name = "idx_task_type", columnList = "type"),
        Index(name = "idx_task_priority", columnList = "priority"),
        Index(name = "idx_task_video", columnList = "video_id"),
        Index(name = "idx_task_created_by", columnList = "created_by_id"),
        Index(name = "idx_task_scheduled", columnList = "scheduled_at"),
        Index(name = "idx_task_started", columnList = "started_at")
    ]
)
class Task : PanacheEntityBase {

    @Id @GeneratedValue(generator = "UUID")
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    lateinit var type: TaskType

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TaskStatus = TaskStatus.PENDING

    var progress: Int = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var priority: TaskPriority = TaskPriority.MEDIUM

    // Related entities
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id")
    var video: VideoFile? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subtitle_id")
    var subtitle: Subtitle? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    var createdBy: User? = null

    // Task configuration and results
    @Column(columnDefinition = "TEXT")
    var parameters: String? = null // JSON string with task parameters

    var progressPercentage: Int = 0
    var progressMessage: String? = null

    @Column(columnDefinition = "TEXT")
    var result: String? = null // JSON string with task results

    @Column(columnDefinition = "TEXT")
    var errorMessage: String? = null

    // Retry mechanism
    var retryCount: Int = 0
    var maxRetries: Int = 3

    // Timing
    var scheduledAt: Instant? = null
    var startedAt: Instant? = null
    var completedAt: Instant? = null
    var estimatedDuration: Long? = null // Estimated duration in milliseconds
    var actualDuration: Long? = null // Actual duration in milliseconds

    // Execution tracking
    var executorId: String? = null // ID of the worker/executor handling this task
    var lastHeartbeat: Instant? = null // Last heartbeat from executor

    var createdAt: Instant = Instant.now()
    var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun preUpdate() {
        updatedAt = Instant.now()
    }

    companion object : PanacheCompanionBase<Task, UUID> {
        fun findByStatus(status: TaskStatus): Uni<List<Task>> =
            list("status", status)

        fun findByType(type: TaskType): Uni<List<Task>> =
            list("type", type)

        fun findByVideo(video: VideoFile): Uni<List<Task>> =
            list("video", video)

        fun findByUser(user: User): Uni<List<Task>> =
            list("createdBy", user)

        fun findPending(): Uni<List<Task>> =
            list("status = ?1 ORDER BY priority DESC, createdAt ASC", TaskStatus.PENDING)

        fun findRunning(): Uni<List<Task>> =
            list("status", TaskStatus.RUNNING)

        fun findScheduled(): Uni<List<Task>> =
            list("status = ?1 AND scheduledAt <= ?2 ORDER BY priority DESC, scheduledAt ASC", 
                 TaskStatus.SCHEDULED, Instant.now())

        fun findStuckTasks(timeoutMinutes: Long = 30): Uni<List<Task>> {
            val cutoff = Instant.now().minusSeconds(timeoutMinutes * 60)
            return list("status = ?1 AND (lastHeartbeat IS NULL OR lastHeartbeat < ?2)", 
                       TaskStatus.RUNNING, cutoff)
        }

        fun findCompletedTasksOlderThan(cutoffDate: java.time.LocalDateTime): Uni<List<Task>> {
            val cutoffInstant = cutoffDate.atZone(java.time.ZoneId.systemDefault()).toInstant()
            return list("status IN (?1, ?2) AND completedAt < ?3", 
                       TaskStatus.COMPLETED, TaskStatus.FAILED, cutoffInstant)
        }

        fun findByStatusAndType(status: TaskStatus, type: TaskType, limit: Int, offset: Int): Uni<List<Task>> =
            find("status = ?1 AND type = ?2 ORDER BY createdAt DESC", status, type)
                .page(offset / limit, limit).list()

        fun findByStatus(status: TaskStatus, limit: Int, offset: Int): Uni<List<Task>> =
            find("status = ?1 ORDER BY createdAt DESC", status)
                .page(offset / limit, limit).list()

        fun findByType(type: TaskType, limit: Int, offset: Int): Uni<List<Task>> =
            find("type = ?1 ORDER BY createdAt DESC", type)
                .page(offset / limit, limit).list()

        fun findAllPaged(limit: Int, offset: Int): Uni<List<Task>> =
            find("ORDER BY createdAt DESC")
                .page(offset / limit, limit).list()

        fun findByVideoFile(videoFile: VideoFile): Uni<List<Task>> =
            list("video", videoFile)

        fun findByVideoFile(videoFile: VideoFile, limit: Int, offset: Int): Uni<List<Task>> =
            find("video = ?1 ORDER BY createdAt DESC", videoFile)
                .page(offset / limit, limit).list()

        fun findByVideoAndType(videoId: UUID, type: TaskType): Uni<List<Task>> =
            list("video.id = ?1 AND type = ?2 ORDER BY createdAt DESC", videoId, type)

        fun findStuckTasks(): Uni<List<Task>> = findStuckTasks(30)

        fun findRecentByType(type: TaskType, limit: Int): Uni<List<Task>> =
            find("type = ?1 ORDER BY createdAt DESC", type).page(0, limit).list()

        fun countByStatus(status: TaskStatus): Uni<Long> =
            count("status", status)

        fun countByTypeAndStatus(type: TaskType, status: TaskStatus): Uni<Long> =
            count("type = ?1 AND status = ?2", type, status)

        fun findFailedTasks(): Uni<List<Task>> =
            list("status = ?1 ORDER BY completedAt DESC", TaskStatus.FAILED)

        fun findRetryableTasks(): Uni<List<Task>> =
            list("status = ?1 AND retryCount < maxRetries", TaskStatus.FAILED)
    }
}

enum class TaskType {
    SCAN_LIBRARY,           // Scan video library for new files
    EXTRACT_SUBTITLES,      // Extract embedded subtitles from video
    TRANSLATE_SUBTITLES,    // Translate subtitles using AI
    SYNC_SUBTITLES,         // Synchronize subtitle timing
    GENERATE_SUBTITLES,     // Generate subtitles from audio using AI
    CLEANUP_FILES,          // Clean up temporary/orphaned files
    BACKUP_DATABASE,        // Backup database
    OPTIMIZE_DATABASE,      // Optimize database performance
    HEALTH_CHECK,           // System health check
    USER_EXPORT,            // Export user data
    BATCH_PROCESS           // Batch processing of multiple files
}

enum class TaskStatus {
    PENDING,        // Task created, waiting to be picked up
    SCHEDULED,      // Task scheduled for future execution
    RUNNING,        // Task currently being executed
    COMPLETED,      // Task completed successfully
    FAILED,         // Task failed with error
    CANCELLED,      // Task was cancelled by user/system
    PAUSED          // Task execution paused
}

enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT
}