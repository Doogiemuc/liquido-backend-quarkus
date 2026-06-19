package org.liquido.user;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.graphql.NonNull;
import org.liquido.security.JwtTokenUtils;
import org.liquido.security.OneTimeToken;
import org.liquido.security.PasswordServiceBcrypt;
import org.liquido.team.TeamDataResponse;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@ApplicationScoped
public class UserService {

	@Inject
	LiquidoConfig config;

	@Inject
	Mailer mailer;

	@Inject
	JwtTokenUtils jwtTokenUtils;

	/**
	 * Password Reset - Step 1
	 * Create a one time token for this user.
	 * Send a mail with a link to reset password. The link contains the OTT and is only valid once!
	 *
	 * @param email Must be a registered user
	 * @throws LiquidoException when email is unkown/not registered. Or email cannot be sent.
	 */
	@Transactional
	public void requestPasswordResetMail(String email) throws LiquidoException { // Changed return type to Uni<Void>
		log.info("Request password reset for email {}", email);
		String emailLowerCase = DoogiesUtil.cleanEmail(email);
		UserEntity user = UserEntity.findByEmail(emailLowerCase)
				.orElseThrow(() -> {
					log.warn("[Security] Requested password reset for <{}>, but this email is not registered.", email);
					return new LiquidoException(LiquidoException.Errors.WONT_RESET_PASSWORD, "Won't reset password!");   // No details to caller!
				});

		// Delete all old one time tokens of this user.
		OneTimeToken.deleteUsersOldTokens(user);

		// Create a one time token that allows to reset user's password exactly once.
		OneTimeToken ott = OneTimeToken.build(UUID.randomUUID().toString(), user, config.loginLinkExpirationMinutes());

		// This link is parsed in a cypress test case. You must also update that test if you change this.
		String resetPasswordLink = "<a id='resetPasswordLink' style='font-size: 20pt;' href='" + config.frontendUrl() + "/resetPassword?email=" + user.getEmail() + "&resetPasswordToken=" + ott.getNonce() + "'>Reset Password</a>";
		String body = String.join(
				System.lineSeparator(),
				"<html><h1>LIQUIDO - Reset Password</h1>",
				"<h3>Hello " + user.getName() + "</h3>",
				"<p>With this link you can reset your password.</p>",
				"<p>&nbsp;</p>",
				"<b>" + resetPasswordLink + "</b>",
				"<p>&nbsp;</p>",
				"<p>This link can only be used once!</p>",
				"<p style='color:grey; font-size:10pt;'>You received this email, because you used the reset password function in <a href='https://www.liquido.net'>LIQUIDO</a>.</p>",
				"</html>"
		);

		log.info("sending mail to {}", emailLowerCase);
		//BUG Reactive clients have problems inside GraphQL queries: https://github.com/quarkusio/quarkus/issues/29141
		//FIX: is on the way https://github.com/quarkusio/quarkus/pull/54927
		mailer.send(Mail.withHtml(emailLowerCase, "Reset Password for LIQUIDO", body).setFrom("info@liquido.vote"));
		log.info("mail sent successfully to {}", emailLowerCase);
	}

	/**
	 * Password reset - Step 2: set a new password (authenticated with OTT)
	 *
	 * @param email              must be a registered user
	 * @param resetPasswordToken one time token returned by #requestPasswordReset
	 * @param newPassword        set a new password
	 * @throws LiquidoException when email is unkown/not registered.
	 */
	@Transactional
	public void resetPassword(String email, String resetPasswordToken, String newPassword) throws LiquidoException {
		String emailLowerCase = email.toLowerCase();
		UserEntity user = UserEntity.findByEmail(emailLowerCase).orElseThrow(
				LiquidoException.supply(LiquidoException.Errors.WONT_RESET_PASSWORD, "Won't reset password for <" + emailLowerCase + ">: User is not registered.")
		);
		if (DoogiesUtil.isEqual(config.testPasswordResetTokenOpt(), resetPasswordToken) && LaunchMode.current() != LaunchMode.NORMAL) {
			log.info("[TEST/DEV] reset password of {} in LaunchMode={}", user.toStringShort(), LaunchMode.current());
			user.setPasswordHash(PasswordServiceBcrypt.hashPassword(newPassword));
			user.persist();
			return;
		}

		OneTimeToken ott = OneTimeToken.findByNonce(resetPasswordToken).orElseThrow(() -> {
			log.info("Won't reset password for <{}>. Invalid or expired one time token", emailLowerCase);
			return new LiquidoException(LiquidoException.Errors.WONT_RESET_PASSWORD, "Won't reset password for <" + emailLowerCase + ">: Invalid or expired one time token");
		});

		log.info("Resetting password of {}", user.toStringShort());
		ott.delete();
		user.setPasswordHash(PasswordServiceBcrypt.hashPassword(newPassword));
		user.persist();
	}


	/**
	 * Request an email that contains a login link.
	 * @param email must be an existing email
	 * @throws LiquidoException when email is unkown/not registered. Or email cannot be sent.
	 */
	@Transactional
	public void requestEmailLoginLink(String email) throws LiquidoException {
		String emailLowerCase = DoogiesUtil.cleanEmail(email);
		UserEntity user = UserEntity.findByEmail(emailLowerCase)
				.orElseThrow(() -> {
					log.warn("[Security] <{}> tried to login via email, but there is no registered user with that email.", emailLowerCase);
					return new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_EMAIL_NOT_FOUND, "Cannot login. There is no register user with that email.");
				});

		// If user already has a not used code, then delete it and create a new one
		OneTimeToken.deleteUsersOldTokens(user);

		// Create new email login link with a one time token in it.
		UUID tokenUUID = UUID.randomUUID();
		LocalDateTime validUntil = LocalDateTime.now().plusMinutes(config.loginLinkExpirationMinutes());
		OneTimeToken oneTimeToken = new OneTimeToken(tokenUUID.toString(), user, validUntil);
		oneTimeToken.persist();
		log.info("User " + user.getEmail() + " may login via email link.");

		// This link is parsed in a cypress test case. Must update test if you change this.
		String loginLink = "<a id='loginLink' style='font-size: 20pt;' href='" + config.frontendUrl() + "/login?email=" + user.getEmail() + "&emailToken=" + oneTimeToken.getNonce() + "'>Login " + user.getName() + "</a>";
		String body = String.join(
				System.lineSeparator(),
				"<html><h1>Liquido Login Token</h1>",
				"<h3>Hello " + user.getName() + "</h3>",
				"<p>With this link you can login to Liquido.</p>",
				"<p>&nbsp;</p>",
				"<b>" + loginLink + "</b>",
				"<p>&nbsp;</p>",
				"<p>This login link can only be used once!</p>",
				"<p style='color:grey; font-size:10pt;'>You received this email, because a login token for the <a href='https://www.liquido.net'>LIQUIDO</a> eVoting webapp was requested. If you did not request a login yourself, than you may simply ignore this message.</p>",
				"</html>"
		);

		try {
			log.info("Sending mail with login link to {}", emailLowerCase);
			mailer.send(Mail.withHtml(emailLowerCase, "Login Link for LIQUIDO", body).setFrom("info@liquido.vote"));
		} catch (Exception e) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_INTERNAL_ERROR, "Internal server error: Cannot send Email: " + e, e);
		}
	}

	/**
	 * Login with the token that was provided in a login email.
	 * @see #requestEmailLoginLink(String) requestEmailLink
	 * @param email must be a registered email
	 * @param emailToken the auth token from the email
	 * @return TeamDataResponse with team, user and JWT
	 * @throws LiquidoException when user is not registred or authToken invalid
	 */
	public TeamDataResponse loginWithEmailToken(@NonNull String email, @NonNull String emailToken) throws LiquidoException {
		UserEntity user = UserEntity.findByEmail(email)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_LOGIN_MOBILE_NOT_FOUND, "Cannot login via email token. No user with that email found!"));
		OneTimeToken.findByNonce(emailToken).orElseThrow(
				LiquidoException.supply(LiquidoException.Errors.CANNOT_LOGIN_TOKEN_INVALID, "Cannot login. Token from email is invalid")
		);
		return jwtTokenUtils.doLoginInternal(user, null);
	}
}