package gr.accio.auth

import io.smallrye.mutiny.Uni
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.Serializable

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AuthResource @Inject constructor(
    private val authService: AuthService
) {

    @POST
    @Path("/login")
    @PermitAll
    fun login(request: LoginRequest): Uni<Response> {
        return authService.authenticate(request.password)
            .map { authResponse ->
                if (authResponse.success) {
                    Response.ok(authResponse).build()
                } else {
                    Response.status(Response.Status.UNAUTHORIZED)
                        .entity(authResponse)
                        .build()
                }
            }
    }

    @POST
    @Path("/validate")
    @RolesAllowed("user")
    fun validateToken(): Uni<Response> {
        // If we reach here, the token is valid (handled by @RolesAllowed)
        return Uni.createFrom().item(
            Response.ok(mapOf("valid" to true, "message" to "Token is valid")).build()
        )
    }

    @POST
    @Path("/change-password")
    @RolesAllowed("admin")
    fun changePassword(request: ChangePasswordRequest): Uni<Response> {
        return authService.changePassword(request.currentPassword, request.newPassword)
            .map { response ->
                if (response.success) {
                    Response.ok(response).build()
                } else {
                    Response.status(Response.Status.BAD_REQUEST)
                        .entity(response)
                        .build()
                }
            }
    }

    @POST
    @Path("/logout")
    @RolesAllowed("user")
    fun logout(): Uni<Response> {
        // With JWT, logout is handled client-side by removing the token
        return Uni.createFrom().item(
            Response.ok(mapOf("message" to "Logged out successfully")).build()
        )
    }

    @GET
    @Path("/status")
    @RolesAllowed("user")
    fun getAuthStatus(): Uni<Response> {
        return Uni.createFrom().item(
            Response.ok(mapOf(
                "authenticated" to true,
                "user" to "admin",
                "timestamp" to System.currentTimeMillis()
            )).build()
        )
    }
}

@Serializable
data class LoginRequest(
    val password: String
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)