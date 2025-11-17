package gr.accio.resources

import gr.accio.models.*
import gr.accio.services.TaskService
import io.smallrye.mutiny.Uni
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.Serializable
import java.util.*

@Path("/api/tasks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
class TaskResource {

    @Inject
    lateinit var taskService: TaskService

    @GET
    fun getTasks(
        @QueryParam("status") status: String?,
        @QueryParam("type") type: String?,
        @QueryParam("limit") @DefaultValue("50") limit: Int,
        @QueryParam("offset") @DefaultValue("0") offset: Int
    ): Uni<List<Task>> {
        val taskStatus = status?.let { 
            try { TaskStatus.valueOf(it.uppercase()) } 
            catch (e: IllegalArgumentException) { null }
        }
        val taskType = type?.let { 
            try { TaskType.valueOf(it.uppercase()) } 
            catch (e: IllegalArgumentException) { null }
        }
        
        return taskService.getTasks(taskStatus, taskType, limit, offset)
    }

    @GET
    @Path("/{id}")
    fun getTask(@PathParam("id") taskId: UUID): Uni<Response> {
        return taskService.getTask(taskId)
            .onItem().transform { task ->
                if (task != null) {
                    Response.ok(task).build()
                } else {
                    Response.status(Response.Status.NOT_FOUND).build()
                }
            }
    }

    @GET
    @Path("/video/{videoId}")
    fun getTasksForVideo(@PathParam("videoId") videoId: UUID): Uni<List<Task>> {
        return taskService.getTasksForVideo(videoId)
    }

    @GET
    @Path("/statistics")
    fun getStatistics(): Uni<Map<String, Any>> {
        return taskService.getTaskStatistics()
    }

    @POST
    @Path("/scan")
    fun createScanTask(request: CreateScanTaskRequest): Uni<Response> {
        return taskService.createScanTask(request.libraryPaths, request.createdBy ?: "api")
            .onItem().transform { task ->
                Response.status(Response.Status.CREATED).entity(task).build()
            }
    }

    @POST
    @Path("/sync/{videoId}")
    fun createSyncTask(
        @PathParam("videoId") videoId: UUID,
        request: CreateTaskRequest?
    ): Uni<Response> {
        return VideoFile.findById(videoId)
            .onItem().transformToUni { videoFile ->
                if (videoFile == null) {
                    Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build())
                } else {
                    taskService.createSyncTask(videoFile, request?.createdBy ?: "api")
                        .onItem().transform { task ->
                            Response.status(Response.Status.CREATED).entity(task).build()
                        }
                }
            }
    }

    @POST
    @Path("/translate/{subtitleId}")
    fun createTranslationTask(
        @PathParam("subtitleId") subtitleId: UUID,
        request: CreateTranslationTaskRequest
    ): Uni<Response> {
        return Subtitle.findById(subtitleId)
            .onItem().transformToUni { subtitle ->
                if (subtitle == null) {
                    Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build())
                } else {
                    taskService.createTranslationTask(
                        subtitle,
                        request.targetLanguage,
                        request.provider,
                        request.createdBy ?: "api"
                    ).onItem().transform { task ->
                        Response.status(Response.Status.CREATED).entity(task).build()
                    }
                }
            }
    }

    @POST
    @Path("/batch")
    fun createBatchTask(request: CreateBatchTaskRequest): Uni<Response> {
        val fileUuids = request.fileIds.mapNotNull { idStr ->
            try {
                UUID.fromString(idStr)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        
        if (fileUuids.size != request.fileIds.size) {
            return Uni.createFrom().item(
                Response.status(Response.Status.BAD_REQUEST)
                    .entity(mapOf("error" to "One or more invalid UUIDs provided"))
                    .build()
            )
        }
        
        return taskService.createBatchTask(
            request.operation,
            fileUuids,
            request.createdBy ?: "api"
        ).onItem().transform { task ->
            Response.status(Response.Status.CREATED).entity(task).build()
        }
    }

    @POST
    @Path("/{id}/cancel")
    fun cancelTask(@PathParam("id") taskId: UUID): Uni<Response> {
        return taskService.cancelTask(taskId)
            .onItem().transform { cancelled ->
                if (cancelled) {
                    Response.ok(mapOf("message" to "Task cancelled successfully")).build()
                } else {
                    Response.status(Response.Status.BAD_REQUEST)
                        .entity(mapOf("error" to "Task could not be cancelled"))
                        .build()
                }
            }
    }

    @POST
    @Path("/{id}/retry")
    fun retryTask(@PathParam("id") taskId: UUID): Uni<Response> {
        return taskService.retryTask(taskId)
            .onItem().transform { task ->
                if (task != null) {
                    Response.ok(task).build()
                } else {
                    Response.status(Response.Status.BAD_REQUEST)
                        .entity(mapOf("error" to "Task could not be retried"))
                        .build()
                }
            }
    }

    @DELETE
    @Path("/cleanup")
    fun cleanupOldTasks(@QueryParam("days") @DefaultValue("7") days: Int): Uni<Response> {
        return taskService.cleanupOldTasks(days)
            .onItem().transform { deletedCount ->
                Response.ok(mapOf(
                    "message" to "Cleanup completed",
                    "deletedTasks" to deletedCount
                )).build()
            }
    }

    // Data classes for request bodies
    @Serializable
    data class CreateTaskRequest(
        val createdBy: String? = null
    )

    @Serializable
    data class CreateScanTaskRequest(
        val libraryPaths: List<String>,
        val createdBy: String? = null
    )

    @Serializable
    data class CreateTranslationTaskRequest(
        val targetLanguage: String = "el",
        val provider: String = "openai",
        val createdBy: String? = null
    )

    @Serializable
    data class CreateBatchTaskRequest(
        val operation: String,
        val fileIds: List<String>,
        val createdBy: String? = null
    )
}