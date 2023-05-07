package org.liquido;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.liquido.graphql.TeamDataResponse;
import org.liquido.util.Lson;

import javax.inject.Inject;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@QuarkusTest
//@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HappyCaseTests {

	@Inject
	MockMailbox mailbox;

	@BeforeEach
	public void beforeEachTest(TestInfo testInfo) {
		log.info("==========> Starting: " + testInfo.getDisplayName());
		//mailbox.clear();
	}

	@AfterEach
	public void afterEachTest(TestInfo testInfo) {
		log.info("<========== Finished: " + testInfo.getDisplayName());
	}


	/**
	 * This is one big flow of the so-called "Happy Case".
	 * A full walk through the application. Without any special or error conditions.
	 * Just Happy.
	 */
	@Test
	//@Order(100)
	@TestTransaction   // run test with transaction but rollback after test
	public void HappyCase() {

		// =========== Create a new team

		// GIVEN a test team
		Long now = new Date().getTime();
		String teamName = "testTeam" + now;
		String adminEmail = "testadmin" + now + "@liquido.vote";
		Lson admin = Lson.builder()
				.put("name", "TestAdmin " + now)
				.put("email", adminEmail)
				.put("mobilephone", "0151 555 " + now);

		// WHEN creating a new team via GraphQL
		String query = "mutation createNewTeam($teamName: String, $admin: UserEntityInput) { " +
				" createNewTeam(teamName: $teamName, admin: $admin) " + TestFixtures.CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson variables = Lson.builder()
				.put("teamName", teamName)
				.put("admin", admin);

		// THEN a new team with correct name and admin user is created
		TeamDataResponse res = TestFixtures.sendGraphQL(query, variables)
				.body("errors", nullValue())
				.body("data.createNewTeam.team.teamName", is(teamName))
				.body("data.createNewTeam.user.id", greaterThan(0))
				.body("data.createNewTeam.user.email", is(adminEmail))
				.extract().jsonPath().getObject("data.createNewTeam", TeamDataResponse.class);

		log.info("======================== Successfully created " + res.team);
		log.info("TOTP Factor URI = " + res.user.totpFactorUri);




		// =============== another user joins that team

		// GIVEN a new member
		now = new Date().getTime();
		String memberEmail = "testmember" + now + "@liquido.vote";
		Lson member = Lson.builder()
				.put("name", "TestMember " + now)
				.put("email", memberEmail)
				.put("mobilephone", "0151 555 " + now);

		// WHEN joining the team that was created above
		String joinQuery = "mutation joinTeam($inviteCode: String, $member: UserEntityInput) { " +
				" joinTeam(inviteCode: $inviteCode, member: $member) " + TestFixtures.CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson joinVars = Lson.builder()
				.put("inviteCode", res.team.inviteCode)
				.put("member", member);

		// THEN the team with that new member is returned
		TeamDataResponse joinRes = TestFixtures.sendGraphQL(joinQuery, joinVars)
				.body("data.joinTeam.team.teamName", is(teamName))
				.body("data.joinTeam.user.id", greaterThan(0))
				.body("data.joinTeam.user.email", is(memberEmail))
				.extract().jsonPath().getObject("data.createNewTeam", TeamDataResponse.class);

		log.info("===========" + joinRes.user.toStringShort() + " successfully joined " + joinRes.team);


		// ========== Admin logs in via email

		//  WHEN requesting and email token for this user
		String loginQuery = "query reqEmail($email: String) { requestEmailToken(email: $email) }";
		ValidatableResponse loginRes = TestFixtures.sendGraphQL(loginQuery, Lson.builder("email", adminEmail));

		// THEN login link is sent via email
		loginRes.body(containsString("successfully"));
		log.info("Successfully sent login email.");

		//  AND an email with a one time password (nonce) is received
		List<Mail> mails = mailbox.getMessagesSentTo(adminEmail.toLowerCase());
		assertEquals(1, mails.size());
		String html = mails.get(0).getHtml();
		assertNotNull(html);
		log.info("Received (mock) login email.");
		log.info(html);

		// AND the email contains a login link with a one time password ("nonce")
		// Format of login link in HTML:   "<a id='loginLink' style='font-size: 20pt;' href='http://localhost:3001/login?email=testuser1681280391428@liquido.vote&emailToken=c199e7c2-fd13-423e-8648-ec4ae4375608'>Login TestUser1681280391428</a>"
		Pattern p = Pattern.compile(".*<a.*?id='loginLink'.*?href='.+/login\\?email=(.+?)&emailToken=(.+?)'>.*", Pattern.DOTALL);
		Matcher matcher = p.matcher(html);
		boolean matches = matcher.matches();
		assertTrue(matches);
		String resEmail = matcher.group(1);
		String resToken = matcher.group(2);
		assertNotNull(resEmail);
		assertNotNull(resToken);
		log.info("Successfully received login link for email: " + adminEmail + " with token: " + resToken);


	}



	//TODO: test the full login flow incl. activating your Authy token via GraphQL
	@Test
	@Disabled
	public void testVerifyAuthFactorViaGraphQL() {
		String authToken = "";

		String query2 = "mutation verifyAuthFactor($authToken: String) {" +
				" verifyAuthToken(authToken: $authToken) { message } }";
		Lson variables2 = new Lson("authToken", "");

		given().log().all()
				.when().post(TestFixtures.GRAPHQL_URI)
				.then().log().all()
				.rootPath("data")
				.body("message", is(""));

	}


	//TODO: test join Team via GraphQL












}
