package dniel.forwardauth.infrastructure.endpoints

import dniel.forwardauth.AuthProperties
import dniel.forwardauth.domain.State
import dniel.forwardauth.domain.service.TokenService
import dniel.forwardauth.infrastructure.auth0.Auth0Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import javax.ws.rs.*
import javax.ws.rs.core.*


/**
 * Callback Endpoint for Auth0 signin to retrieve JWT token from code.
 * TODO rename to signin
 */
@Path("signin")
@Component
class SigninEndpoint(val properties: AuthProperties, val auth0Client: Auth0Service, val verifyTokenService: TokenService) {
    private val LOGGER = LoggerFactory.getLogger(this.javaClass)
    private val DOMAIN = properties.domain

    /**
     * Callback Endpoint
     * Use Code from signin query parameter to retrieve Token from Auth0 and decode and verify it.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun signin(@Context headers: HttpHeaders,
               @QueryParam("code") code: String?,
               @QueryParam("error_description") errorDescription: String?,
               @QueryParam("error") error: String?,
               @QueryParam("state") state: String,
               @HeaderParam("x-forwarded-host") forwardedHost: String,
               @CookieParam("AUTH_NONCE") nonceCookie: Cookie): Response {
        printHeaders(headers)

        if (error != null && error == "unauthorized") {
            return Response.status(Response.Status.FORBIDDEN).entity(errorDescription).build()
        } else if (error != null) {
            throw WebApplicationException(errorDescription)
        } else if (code != null) {
            LOGGER.debug("SignIn with code=$code")
            val app = properties.findApplicationOrDefault(forwardedHost)
            val audience = app.audience
            val tokenCookieDomain = app.tokenCookieDomain

            // TODO move into NonceService and add proper errorhandling if nnonce check fails.
            val decodedState = State.decode(state)
            val receivedNonce = decodedState.nonce.value
            val sentNonce = nonceCookie.value
            if (receivedNonce != sentNonce) {
                LOGGER.error("SignInFailedNonce received=$receivedNonce sent=$sentNonce")
            }

            val response = auth0Client.authorizationCodeExchange(code, app.clientId, app.clientSecret, app.redirectUri)
            val accessToken = response.get("access_token") as String
            val idToken = response.get("id_token") as String

            if (shouldVerifyAccessToken(app)) {
                verifyTokenService.verify(accessToken, audience, DOMAIN)
            }
            val accessTokenCookie = NewCookie("ACCESS_TOKEN", accessToken, "/", tokenCookieDomain, null, -1, false, true)
            val jwtCookie = NewCookie("JWT_TOKEN", idToken, "/", tokenCookieDomain, null, -1, false, true)
            val nonceCookie = NewCookie("AUTH_NONCE", "deleted", "/", tokenCookieDomain, null, 0, false, true)

            LOGGER.info("SignInSuccessful, redirect to originUrl originUrl=${decodedState.originUrl}")
            return Response
                    .temporaryRedirect(decodedState.originUrl.uri())
                    .cookie(jwtCookie)
                    .cookie(accessTokenCookie)
                    .cookie(nonceCookie)
                    .build()
        } else {
            throw WebApplicationException("Missing field. One of the fields 'code' or 'error' must be filled out.")
        }
    }

    private fun shouldVerifyAccessToken(app: AuthProperties.Application): Boolean = !app.audience.equals("${DOMAIN}userinfo")

    private fun printHeaders(headers: HttpHeaders) {
        if (LOGGER.isTraceEnabled) {
            for (requestHeader in headers.requestHeaders) {
                LOGGER.trace("Header ${requestHeader.key} = ${requestHeader.value}")
            }
        }
    }
}