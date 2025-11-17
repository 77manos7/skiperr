package gr.accio.services

import gr.accio.models.*
import gr.accio.scheduler.TaskScheduler
import io.quarkus.logging.Log
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.util.*

@ApplicationScoped
class TaskService {

    @Inject
    lateinit var taskScheduler: TaskScheduler

    @Inject
    lateinit var taskUpdateBroadcaster: TaskUpdateBroadcaster

    /**
     * Create and schedule a new task
     */
    fun createTask(
        type: TaskType,
        priority: TaskPriority = TaskPriority.MEDIUM,
        videoFile: VideoFile? = null,
        subtitle: Subtitle? = null,
        parameters: Map<String, Any>? = null,
        createdBy: String = "system"
    ): Uni<Task> {
        val task = Task().apply {
            this.type = type
            this.status = TaskStatus.PENDING
            this.priority = priority
            this.video = videoFile
            this.subtitle = subtitle
            this.parameters = parameters?.let { encodeParameters(it) }
            this.createdBy = null
            this.createdAt = Instant.now()
            this.lastHeartbeat = Instant.now()
            this.progressPercentage = 0
            this.retryCount = 0
        }

        return task.persistAndFlush<Task>()
            .onItem().transformToUni { savedTask ->
                Log.info("Created task ${savedTask.id} of type ${savedTask.type}")
                
                // Schedule the task for execution
                taskScheduler.scheduleTask(savedTask)
                    .onItem().transform { scheduled ->
                        if (scheduled) {
                            Log.info("Successfully scheduled task ${savedTask.id}")
                        } else {
                            Log.error("Failed to schedule task ${savedTask.id}")
                        }
                        savedTask
                    }
            }
    }

    /**
     * Get task by ID
     */
    fun getTask(taskId: UUID): Uni<Task?> {
        return Task.findById(taskId)
    }

    /**
     * Get all tasks with optional filtering
     */
    fun getTasks(
        status: TaskStatus? = null,
        type: TaskType? = null,
        limit: Int = 50,
        offset: Int = 0
    ): Uni<List<Task>> {
        return when {
            status != null && type != null -> Task.findByStatusAndType(status, type, limit, offset)
            status != null -> Task.findByStatus(status, limit, offset)
            type != null -> Task.findByType(type, limit, offset)
            else -> Task.findAllPaged(limit, offset)
        }
    }

    /**
     * Get tasks for a specific video file
     */
    fun getTasksForVideo(videoFileId: UUID): Uni<List<Task>> {
        return VideoFile.findById(videoFileId)
            .onItem().transformToUni { videoFile ->
                if (videoFile != null) {
                    Task.findByVideoFile(videoFile)
                } else {
                    Uni.createFrom().item(emptyList())
                }
            }
    }

    /**
     * Cancel a task
     */
    fun cancelTask(taskId: UUID): Uni<Boolean> {
        return Task.findById(taskId)
            .onItem().transformToUni { task ->
                if (task == null) {
                    Log.warn("Attempted to cancel non-existent task: $taskId")
                    Uni.createFrom().item(false)
                } else if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.FAILED) {
                    Log.warn("Attempted to cancel already finished task: $taskId (status: ${task.status})")
                    Uni.createFrom().item(false)
                } else {
                    // Update task status to cancelled
                    task.status = TaskStatus.CANCELLED
                    task.completedAt = java.time.Instant.now()
                    task.lastHeartbeat = java.time.Instant.now()
                    
                    task.persistAndFlush<Task>()
                        .onItem().transformToUni {
                            // Try to interrupt the running job
                            taskScheduler.cancelTask(taskId)
                        }
                }
            }
    }

    /**
     * Retry a failed task
     */
    fun retryTask(taskId: UUID): Uni<Task?> {
        return Task.findById(taskId)
            .onItem().transformToUni { task ->
                if (task == null) {
                    Log.warn("Attempted to retry non-existent task: $taskId")
                    Uni.createFrom().item(null)
                } else if (task.status != TaskStatus.FAILED) {
                    Log.warn("Attempted to retry task that is not failed: $taskId (status: ${task.status})")
                    Uni.createFrom().item(null)
                } else {
                    // Reset task for retry
                    task.status = TaskStatus.PENDING
                    task.errorMessage = null
                    task.completedAt = null
                    task.startedAt = null
                    task.progressPercentage = 0
                    task.lastHeartbeat = java.time.Instant.now()
                    task.retryCount = (task.retryCount ?: 0) + 1
                    
                    task.persistAndFlush<Task>()
                        .onItem().transformToUni { updatedTask ->
                            Log.info("Retrying task ${updatedTask.id} (attempt ${updatedTask.retryCount})")
                            
                            // Schedule the task for execution
                            taskScheduler.scheduleTask(updatedTask)
                                .onItem().transform { scheduled ->
                                    if (scheduled) {
                                        Log.info("Successfully rescheduled task ${updatedTask.id}")
                                    } else {
                                        Log.error("Failed to reschedule task ${updatedTask.id}")
                                    }
                                    updatedTask
                                }
                        }
                }
            }
    }

    /**
     * Get task statistics
     */
    fun getTaskStatistics(): Uni<Map<String, Any>> {
        return Uni.combine().all().unis(
            Task.countByStatus(TaskStatus.PENDING),
            Task.countByStatus(TaskStatus.RUNNING),
            Task.countByStatus(TaskStatus.COMPLETED),
            Task.countByStatus(TaskStatus.FAILED),
            Task.countByStatus(TaskStatus.CANCELLED),
            taskScheduler.getRunningTasks()
        ).asTuple()
            .onItem().transform { tuple ->
                mapOf(
                    "pending" to tuple.item1,
                    "running" to tuple.item2,
                    "completed" to tuple.item3,
                    "failed" to tuple.item4,
                    "cancelled" to tuple.item5,
                    "activeJobs" to tuple.item6.size,
                    "timestamp" to System.currentTimeMillis()
                )
            }
    }

    /**
     * Stream task updates for real-time monitoring
     */
    fun streamTaskUpdates(): Multi<Task> {
        return taskUpdateBroadcaster.subscribeToTaskUpdates()
    }

    /**
     * Stream task updates with a filter for real-time monitoring
     */
    fun streamTaskUpdates(filter: (Task) -> Boolean): Multi<Task> {
        return taskUpdateBroadcaster.subscribeToTaskUpdates(filter)
    }

    /**
     * Clean up old completed tasks
     */
    fun cleanupOldTasks(olderThanDays: Int = 7): Uni<Int> {
        val cutoffDate = LocalDateTime.now().minusDays(olderThanDays.toLong())
        
        return Task.findCompletedTasksOlderThan(cutoffDate)
            .onItem().transformToUni { tasks ->
                if (tasks.isEmpty()) {
                    Uni.createFrom().item(0)
                } else {
                    Log.info("Cleaning up ${tasks.size} old tasks older than $olderThanDays days")
                    
                    // Delete tasks using Multi for better handling
                    Multi.createFrom().iterable(tasks)
                        .onItem().transformToUniAndMerge { task ->
                            task.delete()
                        }
                        .collect().asList()
                        .map { tasks.size }
                }
            }
    }

    /**
     * Create a scan library task
     */
    fun createScanTask(libraryPaths: List<String>, createdBy: String = "system"): Uni<Task> {
        val parameters = mapOf(
            "paths" to libraryPaths,
            "recursive" to true,
            "updateExisting" to true
        )
        
        return createTask(
            type = TaskType.SCAN_LIBRARY,
            priority = TaskPriority.HIGH,
            parameters = parameters,
            createdBy = createdBy
        )
    }

    /**
     * Create a sync subtitles task
     */
    fun createSyncTask(videoFile: VideoFile, createdBy: String = "system"): Uni<Task> {
        val parameters = mapOf(
            "videoFileId" to videoFile.id.toString(),
            "autoSync" to true
        )
        
        return createTask(
            type = TaskType.SYNC_SUBTITLES,
            priority = TaskPriority.MEDIUM,
            videoFile = videoFile,
            parameters = parameters,
            createdBy = createdBy
        )
    }

    /**
     * Create a translation task
     */
    fun createTranslationTask(
        subtitle: Subtitle,
        targetLanguage: String = "el",
        provider: String = "openai",
        createdBy: String = "system"
    ): Uni<Task> {
        val parameters = mapOf(
            "subtitleId" to subtitle.id.toString(),
            "targetLanguage" to targetLanguage,
            "provider" to provider,
            "preserveFormatting" to true
        )
        
        return createTask(
            type = TaskType.TRANSLATE_SUBTITLES,
            priority = TaskPriority.MEDIUM,
            subtitle = subtitle,
            parameters = parameters,
            createdBy = createdBy
        )
    }

    /**
     * Create a batch processing task
     */
    fun createBatchTask(
        operation: String,
        fileIds: List<UUID>,
        createdBy: String = "system"
    ): Uni<Task> {
        val parameters = mapOf(
            "operation" to operation,
            "fileIds" to fileIds.map { it.toString() },
            "batchSize" to 10
        )
        
        return createTask(
            type = TaskType.BATCH_PROCESS,
            priority = TaskPriority.LOW,
            parameters = parameters,
            createdBy = createdBy
        )
    }

    private fun encodeParameters(parameters: Map<String, Any>): String {
        return try {
            val jsonMap = parameters.mapValues { (_, value) ->
                when (value) {
                    is String -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    is List<*> -> JsonObject(value.mapIndexed { index, item -> 
                        index.toString() to JsonPrimitive(item.toString()) 
                    }.toMap())
                    else -> JsonPrimitive(value.toString())
                }
            }
            Json.encodeToString(JsonObject.serializer(), JsonObject(jsonMap))
        } catch (e: Exception) {
            Log.warn("Failed to encode task parameters", e)
            "{}"
        }
    }
}