package org.liquido;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.liquido.team.TeamDataResponse;
import org.liquido.util.Lson;

import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>Welcome mail after registering</h1>
 *
 * Covers POST /login/welcomeMail, which the frontend calls right after createNewTeam or joinTeam.
 *
 * The single most important thing asserted here is a <b>negative</b>: the welcome mail must not
 * contain a login token. The frontend's login page logs a visitor straight in when it gets both
 * {@code email} and {@code emailToken}, so a token in a welcome mail would silently turn it into a
 * magic-login mail that anyone with access to the inbox - or to a forwarded copy - could use.
 */
@Slf4j
@QuarkusTest
public class WelcomeMailTests {

	@Inject
	LiquidoTestUtils util;

	@Inject
	MockMailbox mockMailbox;

	private static final String WELCOME_MAIL_URI = TestFixtures.LIQUIDO_API + "/login/welcomeMail";

	@BeforeEach
	public void beforeEachTest(TestInfo testInfo) {
		log.info("==========> Starting: " + testInfo.getDisplayName());
		// MockMailbox is an application-scoped singleton shared by every test in this JVM.
		mockMailbox.clear();
	}

	/** Call POST /login/welcomeMail authenticated as the given JWT, expecting success. */
	private void requestWelcomeMail(String jwt) {
		RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
		given()
				.contentType(ContentType.JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
				.when()
				.post(WELCOME_MAIL_URI)
				.then()
				.statusCode(200);
	}

	/** The one mail sent to this address. Fails if there is not exactly one. */
	private Mail theMailSentTo(String email) {
		List<Mail> mails = mockMailbox.getMailsSentTo(email.toLowerCase());
		assertEquals(1, mails.size(), "Expected exactly one welcome mail to " + email);
		return mails.get(0);
	}

	/**
	 * Applies to BOTH variants: a welcome mail links to the login page, and carries nothing that
	 * could log anybody in.
	 */
	private void assertLinksToLoginButCarriesNoToken(Mail mail, String email) {
		String html = mail.getHtml();
		assertNotNull(html, "welcome mail must have an HTML part");
		assertNotNull(mail.getText(), "welcome mail must have a plain-text alternative part");

		assertTrue(html.contains("/login?email="),
				"welcome mail must link to the login page with the email prefilled");

		// The negative that matters. Any of these would make the mail log the reader in.
		assertFalse(html.contains("emailToken"),
				"welcome mail must NOT contain an emailToken - it is not a magic-login mail!");
		assertFalse(html.contains("resetPasswordToken"),
				"welcome mail must NOT contain a password reset token");
		assertFalse(html.contains("jwt"),
				"welcome mail must NOT contain a JWT");
	}

	@Test
	@DisplayName("Admin who created a team gets a welcome mail WITH the invite link")
	public void adminGetsWelcomeMailWithInviteLink() {
		TeamDataResponse admin = util.createFreshTeam("WelcomeAdmin");

		requestWelcomeMail(admin.jwt);

		Mail mail = theMailSentTo(admin.user.getEmail());
		assertLinksToLoginButCarriesNoToken(mail, admin.user.getEmail());

		// The whole point of the admin variant: something to forward to friends.
		assertTrue(mail.getHtml().contains("inviteCode=" + admin.team.getInviteCode()),
				"admin welcome mail must contain the team's invite link");
		assertTrue(mail.getHtml().contains(admin.team.getTeamName()),
				"admin welcome mail should name the team");
	}

	@Test
	@DisplayName("Member who joined a team gets a welcome mail WITHOUT any invite link")
	public void memberGetsWelcomeMailWithoutInviteLink() {
		TeamDataResponse admin = util.createFreshTeam("WelcomeMember");
		TeamDataResponse member = util.joinTeam(admin.team.getInviteCode(),
				"welcomemember" + new Date().getTime() + "@liquido.vote");

		requestWelcomeMail(member.jwt);

		Mail mail = theMailSentTo(member.user.getEmail());
		assertLinksToLoginButCarriesNoToken(mail, member.user.getEmail());

		// Distributing invites is the admin's job, so a member must not be handed the code.
		assertFalse(mail.getHtml().contains("inviteCode"),
				"member welcome mail must NOT contain an invite link");
		assertFalse(mail.getHtml().contains(admin.team.getInviteCode()),
				"member welcome mail must not leak the raw invite code either");
	}

	@Test
	@DisplayName("Recipient is taken from the JWT, so a caller can only ever mail themselves")
	public void recipientComesFromTheJwtOnly() {
		TeamDataResponse admin = util.createFreshTeam("WelcomeJwtOnly");
		TeamDataResponse other = util.createFreshTeam("WelcomeVictim");

		// A body is accepted but ignored - the endpoint takes no parameters at all.
		given()
				.contentType(ContentType.JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.jwt)
				.body("{\"email\":\"" + other.user.getEmail() + "\"}")
				.when()
				.post(WELCOME_MAIL_URI)
				.then()
				.statusCode(200);

		assertEquals(1, mockMailbox.getMailsSentTo(admin.user.getEmail().toLowerCase()).size(),
				"the mail must go to the JWT's user");
		assertEquals(0, mockMailbox.getMailsSentTo(other.user.getEmail().toLowerCase()).size(),
				"a caller-supplied address must be ignored - otherwise this is a spam relay");
	}

	@Test
	@DisplayName("Anonymous callers are rejected and no mail goes out")
	public void anonymousCallerIsRejected() {
		// Deliberately NOT asserting an exact status code. quarkus-security-webauthn puts a form
		// authentication mechanism on the classpath, and that answers a request carrying NO identity
		// at all with a 302 challenge towards its login page rather than a bare 401. Which of the two
		// comes back is a framework negotiation detail; the property this test exists to protect is
		// that the request is not served and nobody gets mailed.
		int status = given()
				.contentType(ContentType.JSON)
				.when()
				.post(WELCOME_MAIL_URI)
				.then()
				.extract().statusCode();

		assertNotEquals(200, status, "an unauthenticated call must not be served, but got HTTP " + status);
		assertEquals(0, mockMailbox.getTotalMessagesSent(),
				"an unauthenticated call must not send anything");
	}

	@Test
	@DisplayName("A bad JWT is rejected with 401 - the token really is validated")
	public void invalidJwtIsRejected() {
		// The counterpart to the test above: here an identity IS presented, just not a valid one, and
		// that path is unambiguously a 401. Without this, "rejected" above could pass even if the
		// endpoint accepted any garbage token.
		given()
				.contentType(ContentType.JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer this-is-not-a-jwt")
				.when()
				.post(WELCOME_MAIL_URI)
				.then()
				.statusCode(401);

		assertEquals(0, mockMailbox.getTotalMessagesSent(),
				"a call with an invalid token must not send anything");
	}

	@Test
	@DisplayName("Nickname and team name are HTML-escaped, not injected raw into the markup")
	public void userSuppliedValuesAreEscaped() {
		// Qute escapes {...} by default. The two older mails in UserService concatenate user values
		// into markup raw, so this asserts the new mails do not inherit that.
		// Note: cannot use createFreshTeam here - it derives the admin's email from the team name
		// prefix, and a team name containing markup would produce an invalid email address.
		TeamDataResponse admin = createTeamNamed("Welcome<script>alert(1)</script>");

		requestWelcomeMail(admin.jwt);

		String html = theMailSentTo(admin.user.getEmail()).getHtml();
		assertFalse(html.contains("<script>"),
				"a team name containing markup must be escaped, not rendered as an element");
		assertTrue(html.contains("&lt;script&gt;"),
				"the team name should still be shown to the reader, just escaped");
	}

	/**
	 * Register a team with an arbitrary team name and an unrelated, always-valid admin email.
	 * {@link LiquidoTestUtils#createFreshTeam(String)} builds the email out of the team name prefix,
	 * which breaks as soon as the name is anything but plain alphanumerics.
	 */
	private TeamDataResponse createTeamNamed(String teamName) {
		long unique = new Date().getTime();
		String adminEmail = "welcomeescape" + unique + "@liquido.vote";
		Lson admin = Lson.builder()
				.put("name", "Escape Admin")
				.put("email", adminEmail)
				.put("picture", "Avatar1.png");
		Lson variables = Lson.builder()
				.put("teamName", teamName + unique)
				.put("admin", admin)
				.put("password", adminEmail + TestFixtures.PASSWORD_SUFFIX);
		String query = "mutation createNewTeam($teamName: String!, $admin: UserEntityInput!, $password: String!) { " +
				" createNewTeam(teamName: $teamName, admin: $admin, password: $password) " +
				TestFixtures.CREATE_OR_JOIN_TEAM_RESULT + "}";
		return TestFixtures.sendGraphQL(query, variables)
				.extract().jsonPath().getObject("data.createNewTeam", TeamDataResponse.class);
	}
}
