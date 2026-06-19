package org.liquido.security;

import io.quarkus.mailer.Mailer;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.constraints.Length;
import org.jboss.resteasy.reactive.RestQuery;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserService;
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
public class LoginRestAPI {

	@Inject
	UserService userService;

	@Inject
	Mailer mailer;

	//================== Password reset =====================

	/** Step 1: Request a password reset. The app will send you a link via mail. There you can define a new password. */
	@GET
	@Path("/requestPasswordResetEmail")  // The URL is "...Email" not "...Mail" !
	@Produces(MediaType.APPLICATION_JSON)
	public Response requestPasswordResetEmail(
			@RestQuery @NotNull @Length(max = 100) String email
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
			@RestQuery @NotNull @Length(max = 100) String email,
			@RestQuery @NotNull String resetPasswordToken,
			@RestQuery @NotNull String newPassword
	) throws LiquidoException {
		userService.resetPassword(email, resetPasswordToken, newPassword);
		return Response.ok(Lson.builder("message", "Your new password has been set successfully.")).build();
	}

	// =============== Login via Link in Email ================

	@GET
	@Path("/requestEmailLoginLink")
	public String requestEmailLoginLink(@RestQuery @NotNull @Length(max = 100) String email) throws LiquidoException {
		userService.requestEmailLoginLink(email);
		return "mail sent";
	}

	@GET
	@Path("/loginWithEmailToken")
	public TeamDataResponse loginWithEmailToken(
			@RestQuery @NotNull @Length(max = 100)  String email,
			@RestQuery @NotNull @Length(max = 100)  String emailToken
	) throws LiquidoException {
		return userService.loginWithEmailToken(email, emailToken);
	}

}