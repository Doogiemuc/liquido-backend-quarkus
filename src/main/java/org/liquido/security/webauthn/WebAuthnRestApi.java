package org.liquido.security.webauthn;

import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import io.quarkus.security.webauthn.WebAuthnSecurity;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;
import org.liquido.security.JwtTokenUtils;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoException;

import static org.liquido.util.LiquidoException.Errors;

/**
 * JAX-RS endpoints for our <a href="https://quarkus.io/guides/security-webauthn#handling-login-and-registration-endpoints-yourself">
 * custom WebAuthn operations</a>.
 *
 * <p>This class handles:
 * <ul>
 *     <li><b>Registering</b> a new passkey/credential for a logged-in user.</li>
 *     <li><b>Authenticating</b> (logging in) a user with their existing passkey.</li>
 * </ul>
 * </p>
 */
@Slf4j
@Path("/liquido/v2/webauthn")
@Produces(MediaType.APPLICATION_JSON)
public class WebAuthnRestApi {

    private final WebAuthnSecurity webAuthnSecurity;
    private final JwtTokenUtils jwtTokenUtils;

    // Use constructor injection
    public WebAuthnRestApi(WebAuthnSecurity webAuthnSecurity, JwtTokenUtils jwtTokenUtils) {
        this.webAuthnSecurity = webAuthnSecurity;
        this.jwtTokenUtils = jwtTokenUtils;
    }

		@GET
		@Path("/testping")
		@PermitAll
		public String testping() {
			return "pong";
		}

    // =================================================================================================
    // Endpoints for REGISTERING a new passkey for an already authenticated user / ATTESTATION
    // =================================================================================================
    /**
		 * Get WebAuthn registration options (including a challenge) for the currently logged-in user.
		 * This is the first step of registering a new passkey/credential.
		 *
		 * @param ctx Vert.x RoutingContext
		 * @return 2FA registration options as JSON (challenge, rp, user, pubKeyCredParams)
		 */
    @GET
    @Path("/register-options-challenge")
    @RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
    @Blocking // This method performs a blocking calls
    public String registerOptions(RoutingContext ctx) throws LiquidoException {
        UserEntity currentUser = jwtTokenUtils.getCurrentUser()
                .orElseThrow(LiquidoException.supply(Errors.UNAUTHORIZED, "You must be logged in to get WebAuthn registration options."));
			log.info("============ WebAuthN GET /register-options-challenge for "+currentUser.toStringShort());
			PublicKeyCredentialCreationOptions creationOptions = webAuthnSecurity.getRegisterChallenge(currentUser.email, currentUser.name, ctx).await().indefinitely();
			//log.info(creationOptions.toString());
			return webAuthnSecurity.toJsonString(creationOptions);		//BUGFIX: Must use JSON serialization from  com.webauthn4j.converter.jackson.WebAuthnJSONModule

    }

    /**
     * Add a second factor for an <b>already registered</b> user.
     * This is the second step of registering a new passkey/credential.
		 *
     * @param webAuthnRegisterData The credential data from the browser.
     * @param ctx Vert.x RoutingContext
     * @return The updated UserEntity.
     * @throws LiquidoException if not logged in.
     */
    @POST
    @Path("/register")
    @RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
    @Blocking // This method performs a blocking DB call via getCurrentUser() and persist()
		@Transactional
    public Uni<UserEntity> register(JsonObject webAuthnRegisterData, RoutingContext ctx) throws LiquidoException {
			UserEntity currentUser = jwtTokenUtils.getCurrentUser()
					.orElseThrow(LiquidoException.supply(Errors.UNAUTHORIZED, "You must be logged in to add a WebAuthn credential."));
			log.info("======== WebAuthN POST /register new authenticator for {}", currentUser.toStringShort());
			// The `register` method returns a Uni<WebAuthnCredentialRecord>.
			// We chain it to persist our own WebAuthnCredential entity.
			return webAuthnSecurity.register(currentUser.email, webAuthnRegisterData, ctx)
				.onItem().invoke(credRecord -> {
					WebAuthnCredential cred = new WebAuthnCredential(credRecord, currentUser);
					cred.persist();
					log.info("Successfully added 2FA webAuthnCredential for user {}", currentUser.toStringShort());
				})
				.onItem().transform(credRecord -> currentUser); // Return the user entity on success
    }

    // =================================================================================================
    // Endpoints for AUTHENTICATING (logging in) a user with a passkey / ASSERTION
    // =================================================================================================

    /**
     * Get WebAuthn authentication options (including a challenge) for a user.
     * This is the first step of logging in with a passkey.
     *
     * @param email The user's email.
     * @param ctx Vert.x RoutingContext
     * @return 2FA authentication options.
     */
    @GET
    @Path("/login-options-challenge")
    public Uni<PublicKeyCredentialRequestOptions> authenticateOptions(@QueryParam("email") String email, RoutingContext ctx) {
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Email must be provided");
        }
				log.info("======== WebAuthN POST login-options-challenge new authenticator for {}", email);
        return webAuthnSecurity.getLoginChallenge(email, ctx);
    }

    /**
     * Authenticate a user with a passkey.
     * This is the second step of logging in. If successful, it returns a JWT.
     *
     * @param webAuthnLoginData The assertion data from the browser.
     * @param ctx Vert.x RoutingContext
     * @return A TeamDataResponse containing the user, team, and a new JWT.
		 */
    @POST
    @Path("/login")
    public Uni<TeamDataResponse> authenticate(JsonObject webAuthnLoginData, RoutingContext ctx) {
				log.info("======== WebAuthN POST /login");
        // The final part of the login (finding user, generating JWT) is blocking.
        // We run the transformation on a worker thread to avoid blocking the I/O thread.
        return webAuthnSecurity.login(webAuthnLoginData, ctx)
                .emitOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                .onItem().transformToUni(securityIdentity -> {
                    String email = securityIdentity.getUsername();
                    log.info("WebAuthn login successful for {}. Now finding user and creating JWT.", email);
                    return UserEntity.findByEmail(email)
                            // Be explicit with the lambda to resolve the method reference ambiguity
                            .map(userEntity -> Uni.createFrom().item(userEntity))
                            .orElse(Uni.createFrom().failure(new LiquidoException(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot login via WebAuthn. User with email '" + email + "' not found!")));
                })
                .onItem().transform(user -> {
                    try {
                        // This is a blocking call, but we are already on a worker thread thanks to emitOn()
                        return jwtTokenUtils.doLoginInternal(user, null);
                    } catch (LiquidoException e) {
                        // Propagate the business exception as a failure in the reactive stream
											//return Uni.createFrom().failure(new LiquidoException(Errors.CANNOT_LOGIN_INTERNAL_ERROR, "Cannot authenticate WebAuthn user."));
											throw new WebApplicationException(e.getMessage(), e, e.getError().getHttpResponseStatus());
                    }
                });
    }
}