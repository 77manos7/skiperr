package gr.accio.config

import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.runtime.QuarkusPrincipal
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.jwt.auth.principal.DefaultJWTCallerPrincipal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.util.*

@ApplicationScoped
class SecurityConfig {

    @Inject
    @ConfigProperty(name = "skiperr.jwt.secret")
    lateinit var jwtSecret: String

    companion object {
        const val USER_ROLE = "user"
        const val ISSUER = "skiperr"
    }

    fun createSecurityIdentity(username: String): SecurityIdentity {
        return QuarkusSecurityIdentity.builder()
            .setPrincipal(QuarkusPrincipal(username))
            .addRole(USER_ROLE)
            .build()
    }
}