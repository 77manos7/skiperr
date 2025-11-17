package gr.accio.resources

import gr.accio.models.VideoFile
import gr.accio.services.ScanService
import gr.accio.services.SyncService
import gr.accio.services.TranslateService
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.Produces
import jakarta.ws.rs.GET
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.POST
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import java.util.UUID

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
class SubtitleController @Inject constructor(
    private val scanService: ScanService,
    private val translateService: TranslateService,
    private val syncService: SyncService,
) {

    @GET
    @Path("/videos")
    fun getAllVideos(): Uni<List<VideoFile>> {
        return VideoFile.listAll()
    }

    @GET
    @Path("/videos/{id}")
    fun getVideo(@PathParam("id") id: UUID): Uni<Response> {
        return VideoFile.findById(id)
            .map { video ->
                if (video != null) {
                    Response.ok(video).build()
                } else {
                    Response.status(Response.Status.NOT_FOUND).build()
                }
            }
    }

    @GET
    @Path("/videos/type/{type}")
    fun getVideosByType(@PathParam("type") type: String): Uni<List<VideoFile>> {
        return VideoFile.findByType(type)
    }

    @GET
    @Path("/videos/unprocessed")
    fun getUnprocessedVideos(): Uni<List<VideoFile>> {
        return VideoFile.findUnprocessed()
    }

    @GET
    @Path("/videos/without-greek")
    fun getVideosWithoutGreekSubtitles(): Uni<List<VideoFile>> {
        return VideoFile.findWithoutGreekSubtitles()
    }

    @GET
    @Path("/videos/out-of-sync")
    fun getOutOfSyncVideos(): Uni<List<VideoFile>> {
        return VideoFile.findOutOfSync()
    }

    @GET
    @Path("/videos/recent")
    fun getRecentVideos(@QueryParam("limit") @DefaultValue("10") limit: Int): Uni<List<VideoFile>> {
        return VideoFile.findRecentlyAdded(limit)
    }

    @GET
    @Path("/videos/count/{type}")
    fun getVideoCountByType(@PathParam("type") type: String): Uni<Long> {
        return VideoFile.countByType(type)
    }

    @GET
    @Path("/scan")
    fun scan(): Uni<Response> {
        return scanService.scanLibrary()
            .map { result ->
                Response.ok(mapOf("scanned" to result.size, "files" to result)).build()
            }
            .onFailure().recoverWithItem { throwable ->
                Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(mapOf("error" to throwable.message))
                    .build()
            }
    }

    @POST
    @Path("/translate/{id}")
    fun translate(@PathParam("id") id: UUID): Uni<Response> {
        return VideoFile.findById(id)
            .flatMap { video ->
                if (video != null) {
                    translateService.translate(id)
                        .map { result ->
                            Response.ok(mapOf("message" to "Translation completed", "result" to result)).build()
                        }
                } else {
                    Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build())
                }
            }
            .onFailure().recoverWithItem { throwable ->
                Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(mapOf("error" to throwable.message))
                    .build()
            }
    }

    @POST
    @Path("/sync/{id}")
    fun sync(@PathParam("id") id: UUID): Uni<Response> {
        return VideoFile.findById(id)
            .flatMap { video ->
                if (video != null) {
                    syncService.sync(id)
                        .map { result ->
                            Response.ok(mapOf("message" to "Sync completed", "result" to result)).build()
                        }
                } else {
                    Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build())
                }
            }
            .onFailure().recoverWithItem { throwable ->
                Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(mapOf("error" to throwable.message))
                    .build()
            }
    }

    @GET
    @Path("/health")
    @PermitAll
    fun health(): Uni<Map<String, String>> {
        return Uni.createFrom().item(mapOf("status" to "UP", "timestamp" to System.currentTimeMillis().toString()))
    }
}

