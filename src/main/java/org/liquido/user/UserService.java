package org.liquido.user;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.liquido.security.OneTimeToken;
import org.liquido.security.PasswordServiceBcrypt;
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

	public String requestPasswordReset(String email) throws LiquidoException {
		String emailLowerCase = DoogiesUtil.cleanEmail(email);
		UserEntity user = UserEntity.findByEmail(emailLowerCase)
				.orElseThrow(() -> {
					log.warn("[Security] Requested password reset for <{}>, but this email is not registered.", email);
					return new LiquidoException(LiquidoException.Errors.CANNOT_RESET_PASSWORD, "Won't reset password!");   // No details to caller!
				});

		// Delete old one time tokens of this user. Also others that might have been generated for login via email.
		OneTimeToken.deleteUsersOldTokens(user);

		// Create a one time token that allows to reset user's password exactly once.
		UUID tokenUUID = UUID.randomUUID();
		LocalDateTime validUntil = LocalDateTime.now().plusHours(config.loginLinkExpirationHours());
		OneTimeToken oneTimeToken = new OneTimeToken(tokenUUID.toString(), user, validUntil);
		oneTimeToken.persist();

		// This link is parsed in a cypress test case. You must also update that test if you change this.
		String resetPasswordLink = "<a id='resetPasswordLink' style='font-size: 20pt;' href='" + config.frontendUrl() + "/resetPassword?email=" + user.getEmail() + "&resetPasswordToken=" + oneTimeToken.getNonce() + "'>Reset Password</a>";
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

		try {
			mailer.send(Mail.withHtml(emailLowerCase, "Reset Password for LIQUIDO", body).setFrom("info@liquido.vote"));
		} catch (Exception e) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_RESET_PASSWORD, "Internal server error: Cannot send Email: " + e, e);
		}

		return "{ \"message\": \"Reset password email sent successfully.\" }";
	}


	public String resetPassword(String email, String nonce, String newPassword) throws LiquidoException {
		email = email.toLowerCase();
		OneTimeToken ott = OneTimeToken.findByNonce(nonce).orElseThrow(
				LiquidoException.supplyAndLog(LiquidoException.Errors.CANNOT_RESET_PASSWORD, "Won't reset password for <" + email + ">. Invalid or expired one time token")
		);
		UserEntity user = UserEntity.findByEmail(email).orElseThrow(
				LiquidoException.supplyAndLog(LiquidoException.Errors.CANNOT_RESET_PASSWORD, "Won't reset password for <" + email + ">. Cannot find user.")
		);
		log.info("Resetting password of {}", user.toStringShort());
		ott.delete();
		user.setPasswordHash(PasswordServiceBcrypt.hashPassword(newPassword));
		user.persist();

		return "{ \"message\": \"Your password has been reset.\" }";
	}



	public String requestEmailLoginLink(String email) throws LiquidoException {
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
		LocalDateTime validUntil = LocalDateTime.now().plusHours(config.loginLinkExpirationHours());
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
			mailer.send(Mail.withHtml(emailLowerCase, "Login Link for LIQUIDO", body).setFrom("info@liquido.vote"));
		} catch (Exception e) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_LOGIN_INTERNAL_ERROR, "Internal server error: Cannot send Email: " + e, e);
		}
		return "{ \"message\": \"Email successfully sent.\" }";
	}


}