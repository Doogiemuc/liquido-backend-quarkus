package org.liquido.user;

import io.quarkus.mailer.MailTemplate;
import io.quarkus.qute.CheckedTemplate;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.liquido.security.JwtTokenUtils;
import org.liquido.team.TeamEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * <h1>Welcome mail after registering</h1>
 *
 * Sends a one-off welcome mail to a user who has just registered, either by creating a team
 * (admin) or by joining one with an inviteCode (member). It explains what LIQUIDO is and how its
 * ranked pair voting works, and links to the login page. Admins additionally get the invite link
 * to forward to the people they want in their team.
 *
 * <h2>These mails deliberately contain NO authentication</h2>
 *
 * The login link carries only {@code ?email=...} and never a token. The frontend's login page
 * auto-logs a visitor in when it receives <b>both</b> {@code email} and {@code emailToken} - that
 * is what the existing magic-link mail in {@link UserService} is for. A welcome mail is not a
 * login mail: the user must still sign in through the app. Do not add a token here.
 *
 * <h2>Why this is triggered over REST and not from the GraphQL mutation</h2>
 *
 * The natural home would be inside {@code TeamGraphQL.createNewTeam/joinTeam}. It cannot go there
 * on this Quarkus version: a blocking GraphQL resolver that calls the mailer deadlocks, because the
 * executeBlocking dispatch serialises tasks per request and the mailer's event-loop callbacks
 * cannot complete while the ordered task slot is held. That is the same bug that made
 * {@code LoginRestAPI} a separate REST API in the first place. Fixed upstream in Quarkus 3.38.0
 * (quarkusio/quarkus#54927, resolving #29141); once this project is on that version, this could
 * move into the mutation and stop depending on the client to ask for it.
 */
@Slf4j
@ApplicationScoped
public class WelcomeMailService {

	@Inject
	LiquidoConfig config;

	@Inject
	JwtTokenUtils jwtTokenUtils;

	/**
	 * Qute mail templates. Each name resolves to BOTH templates/WelcomeMails/&lt;name&gt;.html and
	 * .txt, which become the HTML and plain-text parts of a multipart mail.
	 *
	 * Qute HTML-escapes every {...} expression, which matters here: nickname and team name are
	 * user-supplied. (The two older mails in UserService concatenate such values into markup raw.)
	 */
	@CheckedTemplate(basePath = "WelcomeMails")
	static class Templates {
		static native MailTemplate.MailTemplateInstance adminWelcome(String name, String teamName, String loginUrl, String inviteUrl);

		static native MailTemplate.MailTemplateInstance memberWelcome(String name, String teamName, String loginUrl);
	}

	/**
	 * Send the welcome mail to the currently authenticated user.
	 *
	 * Everything is taken from the JWT - recipient, team and role. Nothing comes from the caller, so
	 * this endpoint can only ever mail the caller themselves. That is what keeps a feature reachable
	 * right after an anonymous registration from being usable as a spam relay.
	 *
	 * @return a Uni that completes when the mail has been handed to the mailer
	 * @throws LiquidoException if there is no authenticated user or they are not in a team
	 */
	public Uni<Void> sendWelcomeMailToCurrentUser() throws LiquidoException {
		UserEntity user = jwtTokenUtils.getCurrentUser()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.UNAUTHORIZED,
						"Must be logged in to receive a welcome mail."));
		TeamEntity team = jwtTokenUtils.getCurrentTeam()
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.CANNOT_LOGIN_TEAM_NOT_FOUND,
						"Cannot send welcome mail: you are not logged into a team."));

		// Resolve everything to plain values HERE, while the Hibernate session of this request is
		// still open. team.isAdmin(user) walks the lazily loaded members collection, and the send
		// below is asynchronous - touching an entity from inside that Uni would hit a closed session.
		String name = user.getName();
		String email = user.getEmail();
		String teamName = team.getTeamName();
		String inviteCode = team.getInviteCode();
		boolean isAdmin = team.isAdmin(user);

		String loginUrl = config.frontendUrl() + "/login?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
		String inviteUrl = config.frontendUrl() + config.inviteLinkPath() + inviteCode;

		MailTemplate.MailTemplateInstance mail = isAdmin
				? Templates.adminWelcome(name, teamName, loginUrl, inviteUrl)
				: Templates.memberWelcome(name, teamName, loginUrl);

		String subject = isAdmin
				? "Willkommen bei LIQUIDO - dein Team " + teamName + " ist da"
				: "Willkommen bei LIQUIDO - du bist jetzt im Team " + teamName;

		log.info("Sending welcome mail ({}) to {}", isAdmin ? "admin" : "member", email);
		return mail.to(email)
				.subject(subject)
				.from(config.mailFrom())
				.send();
	}
}
