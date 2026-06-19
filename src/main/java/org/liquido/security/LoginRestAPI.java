package org.liquido.security;

import io.quarkus.mailer.Mailer;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.constraints.Length;
import org.jboss.resteasy.reactive.RestQuery;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserService;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoException;
import org.liquido.util.Lson;

/**
 * This API handles public endpoints related to login and password reset.
 * We have this as a seperate REST API, next our normal LIQUIDO /graphql endpoint, because
 *  - these methods are public
 *  - sending mails in GraphQL query resolvers <a href="https://github.com/quarkusio/quarkus/pull/54927#issuecomment-4748001641">does not work</a>
 *  - we may want to split this into an own microservice
 */
@Slf4j
@Path("/login")
@ApplicationScoped
public class LoginRestAPI {

	@Inject
	UserService userService;

	@Inject
	Mailer mailer;

	@Inject
	EntityManager entityManager;

	/*
	 * Check if a user with the given email has registered WebAuthn authenticators.
	 * This is used in the login flow to determine if we should show the "Login with WebAuthn" button.
	 * No authentication required for this endpoint.
	 *
	 * @param email The user's email address
	 * @return JSON response { "webauthn": true/false, email: "email_in_lowercase" }

	@GET
	@Path("/check-login-email")
	public Response checkLoginEmail(@NotBlank @Email @Length(max = 255) @QueryParam("email") String email) {
		// Normalize email
		email = DoogiesUtil.cleanEmail(email);
		// Check if user exists
		Optional<UserEntity> userOpt = UserEntity.findByEmail(email);
		if (userOpt.isEmpty()) {
			log.debug("[SECURITY] Login attempt of unknown user: {}", email);
			JsonObject response = new JsonObject()
					.put("status", "UNKNOWN");
			// If user is not registered, we return HTTP 200 but with status: "UNKNOWN" in body.
			// HTTP 404 would be too harsh. Client did not do anything wrong. Monitoring tools would complain about this response status.
			return Response.status(Response.Status.OK).entity(response).build();
		}

		// Check if user has WebAuthn credentials
		boolean hasWebAuthn = !userOpt.get().webAuthnCredentials.isEmpty();
		log.debug("check-login-email: User {}, hasWebAuthn={}", email, hasWebAuthn);
		JsonObject response = new JsonObject()
				.put("status", "REGISTERED")
				.put("webauthn", hasWebAuthn)
				.put("email", email.toLowerCase());

		return Response.ok(response).build();
	}

	 */

	// V2 by Claude himself :-)

	public EmailExistsRec doesEmailExist(String email) {
		return entityManager
				.createQuery("""
            SELECT new org.liquido.security.EmailExistsRec(
                true,
                CASE WHEN SIZE(u.webAuthnCredentials) > 0 THEN true ELSE false END
            )
            FROM UserEntity u WHERE u.email = :email
            """, EmailExistsRec.class)
				.setParameter("email", email)
				.getResultStream()
				.findFirst()
				.orElse(new EmailExistsRec(false, false));
	}

	@GET
	@Path("/check-login-email")
	public Response checkLoginEmail(@NotBlank @Email @Length(max = 255) @QueryParam("email") String email) {
		email = DoogiesUtil.cleanEmail(email);

		EmailExistsRec check = doesEmailExist(email);

		JsonObject response = new JsonObject()
				.put("status", check.exists() ? "REGISTERED" : "UNKNOWN")
				.put("webauthn", check.hasWebAuthn());  // false if not registered

		log.debug("[SECURITY] check-login-email: {}", response.toString());

		return Response.ok(response).build();
	}


	//================== Password reset =====================

	/** Step 1: Request a password reset. The app will send you a link via mail. There you can define a new password. */
	@GET
	@Path("/requestPasswordResetEmail")  // The URL is "...Email" not "...Mail" !
	@Produces(MediaType.APPLICATION_JSON)
	public Response requestPasswordResetEmail(
			@RestQuery @NotNull @Email @Length(max = 255) String email
	) {
		try {
			userService.requestPasswordResetMail(email);
			return Response.ok(Lson.builder("message", "Reset password email sent.")).build();
		} catch (LiquidoException lqe) {
			// Don't just throw exception from REST endpoint, but return a good error to the client.
			return Response.status(Response.Status.BAD_REQUEST)
							.entity(lqe)
							.build();
		}
	}

	/** Step 2: Reset a user's password. Needs valid one time token." */
	@GET
	@Path("/resetPassword")
	@Produces(MediaType.APPLICATION_JSON)
	public Response resetPassword(
			@RestQuery @NotNull @Email @Length(max = 100) String email,
			@RestQuery @NotNull String resetPasswordToken,
			@RestQuery @NotNull String newPassword
	) throws LiquidoException {
		userService.resetPassword(email, resetPasswordToken, newPassword);
		return Response.ok(Lson.builder("message", "Your new password has been set successfully.")).build();
	}

	// =============== Login via Link in Email ================

	@GET
	@Path("/requestEmailLoginLink")
	@Produces(MediaType.APPLICATION_JSON)
	public Response requestEmailLoginLink(
			@RestQuery @NotNull @Email @Length(max = 100) String email
	) throws LiquidoException {
		userService.requestEmailLoginLink(email);
		return Response.ok(Lson.builder("message", "Login email sent.")).build();
	}

	@GET
	@Path("/loginWithEmailToken")
	@Produces(MediaType.APPLICATION_JSON)
	public TeamDataResponse loginWithEmailToken(
			@RestQuery @NotNull @Email @Length(max = 100)  String email,
			@RestQuery @NotNull @Length(max = 100)  String emailToken
	) throws LiquidoException {
		return userService.loginWithEmailToken(email, emailToken);
	}

}