package gr.accio.scheduler

import gr.accio.models.Task
import gr.accio.models.TaskStatus
import gr.accio.services.TaskUpdateBroadcaster
import io.quarkus.logging.Log
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.*

@ApplicationScoped
class TaskExecutionJob : Job {

    @Inject
    lateinit var taskScheduler: TaskScheduler

    @Inject
    lateinit var taskUpdateBroadcaster: TaskUpdateBroadcaster

    override fun execute(context: JobExecutionContext) {
        val jobData = context.jobDetail.jobDataMap
        val taskIdStr = jobData.getString("taskId")
        val taskType = jobData.getString("taskType")
        
        if (taskIdStr == null) {
            Log.error("Task ID not found in job data")
            throw JobExecutionException("Task ID not found in job data")
        }

        val taskId = try {
            UUID.fromString(taskIdStr)
        } catch (e: IllegalArgumentException) {
            Log.error("Invalid task ID format: $taskIdStr")
            throw JobExecutionException("Invalid task ID format: $taskIdStr", e)
        }

        Log.info("Starting execution of task $taskId (type: $taskType)")
        
        // Register this job as running
        taskScheduler.registerRunningTask(taskId, context)
        
        try {
            val timeout = Duration.ofHours(24)
            
            Task.findById(taskId)
                .onItem().transformToUni { task ->
                    if (task == null) {
                        Log.error("Task $taskId not found in database")
                        Uni.createFrom().failure<Void>(IllegalArgumentException("Task not found: $taskId"))
                    } else {
                        executeTaskWithTracking(task, context)
                    }
                }
                .await().atMost(timeout)
                
        } catch (e: Exception) {
            Log.error("Task execution failed for task $taskId", e)
            
            val timeout = Duration.ofMinutes(5)
            
            try {
                Task.findById(taskId)
                    .onItem().transformToUni { task ->
                        if (task != null) {
                            task.status = TaskStatus.FAILED
                            task.errorMessage = e.message ?: "Unknown error"
                            task.completedAt = Instant.now()
                            task.persistAndFlush<Task>()
                                .onItem().invoke { updatedTask ->
                                    taskUpdateBroadcaster.broadcastTaskUpdate(updatedTask)
                                }
                                .replaceWithVoid()
                        } else {
                            Uni.createFrom().voidItem()
                        }
                    }
                    .await().atMost(timeout)
            } catch (updateException: Exception) {
                Log.error("Failed to update task status after failure: $taskId", updateException)
            }
                
            throw JobExecutionException("Task execution failed", e)
        } finally {
            // Unregister the running task
            taskScheduler.unregisterRunningTask(taskId)
        }
    }

    private fun executeTaskWithTracking(task: Task, context: JobExecutionContext): Uni<Void> {
        return updateTaskStatus(task, TaskStatus.RUNNING)
            .onItem().transformToUni {
                // Execute the actual task
                taskScheduler.executeTask(task)
                    .onItem().transformToUni { 
                        // Task completed successfully
                        Log.info("Task ${task.id} completed successfully")
                        Uni.createFrom().voidItem()
                    }
                    .onFailure().recoverWithUni { error ->
                        // Task failed
                        Log.error("Task ${task.id} failed", error)
                        updateTaskError(task, error)
                    }
            }
            .onItem().transformToUni {
                // Update heartbeat one final time
                updateHeartbeat(task)
            }
    }

    private fun updateTaskStatus(task: Task, status: TaskStatus): Uni<Task> {
        task.status = status
        task.lastHeartbeat = Instant.now()
        
        // Update additional fields based on status
        when (status) {
            TaskStatus.RUNNING -> {
                task.startedAt = Instant.now()
            }
            TaskStatus.COMPLETED -> {
                task.completedAt = Instant.now()
                task.progress = 100
            }
            TaskStatus.FAILED -> {
                task.completedAt = Instant.now()
            }
            else -> {
                // No additional fields to update for other statuses
            }
        }
        
        return task.persistAndFlush<Task>()
            .onItem().invoke { updatedTask ->
                // Broadcast task update for real-time monitoring
                taskUpdateBroadcaster.broadcastTaskUpdate(updatedTask)
            }
            .onItem().transform { it }
    }

    private fun updateTaskError(task: Task, error: Throwable): Uni<Void> {
        task.status = TaskStatus.FAILED
        task.errorMessage = error.message ?: "Unknown error occurred"
        task.completedAt = Instant.now()
        task.lastHeartbeat = Instant.now()
        
        // Increment retry count
        task.retryCount = (task.retryCount ?: 0) + 1
        
        return task.persistAndFlush<Task>()
            .onItem().invoke { updatedTask ->
                // Broadcast task update for real-time monitoring
                taskUpdateBroadcaster.broadcastTaskUpdate(updatedTask)
            }
            .replaceWithVoid()
    }

    private fun updateHeartbeat(task: Task): Uni<Void> {
        task.lastHeartbeat = Instant.now()
        return task.persistAndFlush<Task>()
            .onItem().transformToUni { Uni.createFrom().voidItem() }
    }
}