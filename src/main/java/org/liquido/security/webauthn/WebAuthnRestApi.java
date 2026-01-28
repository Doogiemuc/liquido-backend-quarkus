package org.liquido.security.webauthn;

import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import io.quarkus.security.webauthn.WebAuthnSecurity;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.liquido.security.JwtTokenUtils;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoException;
import org.liquido.util.LiquidoException.Errors;

import java.util.Optional;


//Remark about imports
//for REST: import jakarta.validation.constraints.NotNull;   // this is standard for REST
//for GraphQL: import org.eclipse.microprofile.graphql.NonNull; //  <= use this for GraphQL!


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


	/**
	 * Check if a user with the given email has registered WebAuthn authenticators.
	 * This is used in the login flow to determine if we should show the "Login with WebAuthn" button.
	 * No authentication required for this endpoint.
	 *
	 * @param email The user's email address
	 * @return JSON response { "webauthn": true/false, email: "email_in_lowercase" }
	 */
	@GET
	//@Description("Check if a user with the given email exists and has a registered WebAuthn authenticator.")
	@Path("/check-login-email")
	public Response checkLoginEmail(@NotBlank @Email @Size(max = 255) @QueryParam("email") String email) {
		// Check if user exists
		Optional<UserEntity> userOpt = UserEntity.findByEmail(email);
		if (userOpt.isEmpty()) {
			log.warn("[SECURITY] invalid login attempt for unknown email {}", email);
			return Response.status(Response.Status.NOT_FOUND).build();
		}

		// Check if user has WebAuthn credentials
		boolean hasWebAuthn = !userOpt.get().webAuthnCredentials.isEmpty();
		log.debug("check-login-email: User {}, hasWebAuthn={}", email, hasWebAuthn);
		JsonObject response = new JsonObject()
				.put("webauthn", hasWebAuthn)
				.put("email", email.toLowerCase());

		return Response.ok(response).build();
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
	@Blocking // This method performs blocking calls
	public String registerOptions(RoutingContext ctx) throws LiquidoException {
		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(Errors.UNAUTHORIZED, "You must be logged in to get WebAuthn registration options."));
		log.debug("WebAuthN GET /register-options-challenge for {}", currentUser.toStringShort());
		PublicKeyCredentialCreationOptions creationOptions = webAuthnSecurity.getRegisterChallenge(currentUser.email, currentUser.name, ctx).await().indefinitely();
		//log.info(creationOptions.toString());
		return webAuthnSecurity.toJsonString(creationOptions);    //BUGFIX: Must use JSON serialization from  com.webauthn4j.converter.jackson.WebAuthnJSONModule
	}

	/**
	 * Add a second factor for an <b>already registered</b> user.
	 * This is the second step of registering a new passkey/credential.
	 *
	 * @param webAuthnRegisterData The credential data from the browser.
	 * @param ctx Vert.x RoutingContext
	 * @return HTTP 204 No Content on success
	 * @throws LiquidoException if not logged in.
	 */
	@POST
	@Path("/register")
	@RolesAllowed(JwtTokenUtils.LIQUIDO_USER_ROLE)
	@Blocking
	@Transactional
	public Uni<Response> register(
			@NotNull @QueryParam("label") String label,   //remark: org.eclipse.microprofile.graphql.NonNull  is the official one!
			JsonObject webAuthnRegisterData,
			RoutingContext ctx) throws LiquidoException
	{
		if (label == null || label.isEmpty() || label.length() > 100)
			throw new LiquidoException(Errors.WEBAUTHN_ERROR, "You must provide a label for your authenticator (max 100 chars)");
		UserEntity currentUser = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(Errors.WEBAUTHN_ERROR,"You must be logged in to add a WebAuthn credential."));

		log.debug("WebAuthN POST /register new authenticator '{}' for {}", label, currentUser.toStringShort());
		return webAuthnSecurity
			.register(currentUser.email, webAuthnRegisterData, ctx)
			.onFailure().transform(err -> {
				log.warn("WebAuthn registration failed for user {}: {}", currentUser.toStringShort(), err.getMessage());
				if ("Missing challenge".equals(err.getMessage())) {
					log.info("=========> Is your frontend served on the same domain as the backend?");
				}
				return new LiquidoException(Errors.WEBAUTHN_ERROR, "WebAuthn registration failed for user: " + currentUser.toStringShort() + ": " + err.getMessage(), err);
			})
			.map(credRecord -> {
				WebAuthnCredential cred = new WebAuthnCredential(credRecord, currentUser, label);
				cred.persist();
				log.info("Successfully added 2FA webAuthnCredential {} for user {}", label, currentUser.toStringShort());
				return Response.noContent().build();
			});
	}


	// =================================================================================================
	// Endpoints for AUTHENTICATING (logging in) a user with a passkey / ASSERTION
	// =================================================================================================

	/**
	 * Get WebAuthn authentication options (including a challenge) for a user.
	 * This is the first step of logging in with a passkey.
	 *
	 * @param email The user's email.
	 * @param ctx   Vert.x RoutingContext
	 * @return 2FA authentication options.
	 */
	@GET
	@Path("/login-options-challenge")
	public Uni<String> authenticateOptions(
			@NotNull @QueryParam("email") String email, RoutingContext ctx)
	{
		log.debug("WebAuthN POST /login-options-challenge new authenticator for {}", email);
		return webAuthnSecurity.getLoginChallenge(email, ctx)
				.map(this.webAuthnSecurity::toJsonString);
	}

	/**
	 * Authenticate/Login a user with their existing passkey.
	 * This is the second step of logging in. If successful, it returns a JWT.
	 *
	 * @param webAuthnLoginData The assertion data from the browser must be sent as JSON body.
	 * @param ctx Vert.x RoutingContext
	 * @return A TeamDataResponse containing the user, team, and a new JWT.
	 */
	@POST
	@Path("/login")
	@Blocking
	@Produces(MediaType.APPLICATION_JSON)
	public TeamDataResponse authenticate(
			@NotNull JsonObject webAuthnLoginData,
			RoutingContext ctx
	) throws LiquidoException {
		log.debug("WebAuthN POST /login");

		// Perform WebAuthn login
		var securityIdentity = webAuthnSecurity.login(webAuthnLoginData, ctx)
				.await().indefinitely();  // Wait for the login to complete

		String email = securityIdentity.getUsername();
		log.debug("WebAuthn login successful for {}. Now finding user and creating JWT.", email);

		// Find the user entity
		var user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot login via WebAuthn. User with email '" + email + "' not found!"));

		// Generate JWT
		return jwtTokenUtils.doLoginInternal(user, null);
	}
}