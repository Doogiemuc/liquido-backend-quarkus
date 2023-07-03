package org.liquido;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.liquido.security.JwtTokenUtils;
import org.liquido.services.TwilioVerifyClient;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;
import org.liquido.util.Lson;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.liquido.TestFixtures.CREATE_OR_JOIN_TEAM_RESULT;
import static org.liquido.TestFixtures.GRAPHQL_URI;
import static org.liquido.security.JwtTokenUtils.LIQUIDO_ISSUER;

/**
 * Some simple test cases for authenticate.
 * <h3>Precondition!</h3>
 * These test cases rely on the data that is created by TestDataCreator!
 * They will fail without that data!
 */
@Slf4j
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AuthenticationTests {

	@Inject
	MockMailbox mailbox;

	@Inject
	LiquidoConfig config;

	@Inject
	TwilioVerifyClient twilioVerifyClient;


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
	 * Most basic test. Ping our GraphQL API.
	 */
	@Test
	public void pingApi() {
		String query = "{ ping }";
		TestFixtures.sendGraphQL(query);
	}


	/** Send a request authenticated with a JWT */
	@Test
	public void testAuthenticatedRequest() throws LiquidoException {
		// https://quarkus.io/guides/security-customization#registering-security-providers
		// https://quarkus.io/guides/security-jwt#dealing-with-the-verification-keys

		UserEntity user = TestFixtures.getRandomUser();
		String JWT = Jwt
				.subject(user.email)
				//.upn("upn@liquido.vote")  // if upn is set, this will be used instead of subject   see JWTCallerPrincipal.getName()
				.issuer(LIQUIDO_ISSUER)
				.groups(Collections.singleton(JwtTokenUtils.LIQUIDO_USER_ROLE))  // role
				//.expiresIn(9000)
				//.jws().algorithm(SignatureAlgorithm.HS256)
				.sign();

		System.out.println("======= JWT: "+JWT);
		String query = "{ requireUser }";
		TestFixtures.sendGraphQL(query, null, JWT);

	}

	@Test
	public void testDevLogin() {
		// GIVEN a random user
		UserEntity user = TestFixtures.getRandomUser();
		String query = "query devLogin($devLoginToken: String, $email: String) {" +
				" devLogin(devLoginToken: $devLoginToken, email: $email)" + CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson vars = Lson.builder()
				.put("devLoginToken", config.devLoginToken())
				.put("email", user.getEmail());

		// WHEN doing a devLogin
		ValidatableResponse res = TestFixtures.sendGraphQL(query, vars);
		TeamDataResponse teamData = res.extract().jsonPath().getObject("data.devLogin", TeamDataResponse.class);

		// THEN a valid TeamDataResponse for this user with a JWT is returned.
		assertEquals(teamData.user.email, user.email);
		assertNotNull(teamData.jwt);

	}

	@Test
	@Transactional
	public void loginViaEmail() {
		UserEntity user = TestFixtures.getRandomUser();

		//  WHEN requesting and email token for this user
		String reqEmailQuery = "query reqEmail($email: String) { requestEmailToken(email: $email) }";
		ValidatableResponse reqEmailResponse = TestFixtures.sendGraphQL(reqEmailQuery, Lson.builder("email", user.email));

		// THEN login link is sent via email
		reqEmailResponse.body(containsString("successfully"));
		log.info("Successfully sent login email.");

		//  AND an email with a one time password (nonce) is received
		List<Mail> mails = mailbox.getMailsSentTo(user.email.toLowerCase());
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
		log.info("Successfully received login link for email: " + user.email + " with token: " + resToken);

		// ========= Admin: Login with token from email

		String loginQuery = "query loginWithEmailToken($email: String, $authToken: String) {" +
				"loginWithEmailToken(email: $email, authToken: $authToken)" + TestFixtures.CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson loginVars = new Lson().put("email", user.email).put("authToken", resToken);
		ValidatableResponse loginRes = TestFixtures.sendGraphQL(loginQuery, loginVars);
		TeamDataResponse teamDataResponse = loginRes.extract().jsonPath().getObject("data.loginWithEmailToken", TeamDataResponse.class);
		log.info("Successfully logged in with email token into team " + teamDataResponse.team + " as user " + teamDataResponse.user);
	}


	//TODO: test Twillio Login via GraphQL

	/**
	 * Twilio API + Authy App =  time based one time password (TOTP) authentication
	 *
	 * General Doc
	 * https://www.twilio.com/docs/verify/quickstarts/totp
	 *
	 * Technical Doc with sequence diagrams
	 * https://www.twilio.com/docs/verify/totp/technical-overview
	 *
	 * "The Authy API has been replaced with the Twilio Verify API."
	 * (Source: https://github.com/twilio/authy-java)
	 */
	@Test
	@Disabled  // Don't want to flood the API.
	@TestTransaction
	public void testCreateTotpFactor() throws LiquidoException {
		log.info("Twilio ACCOUNT_SID=" + config.twilio().accountSid());

		Long now = new Date().getTime();
		UserEntity newUser = new UserEntity(
				"TestUser" + now,
				"testuser" + now + "@liquido.vote",
				"+49 555 " + now
		);
		newUser.persistAndFlush();  // MUST flush!

		twilioVerifyClient.createFactor(newUser);

		log.info("Create new " + newUser.toStringShort());
		log.info("TOTP URI: "+newUser.getTotpFactorUri());

		String firstToken = "";
		boolean verified = twilioVerifyClient.verifyFactor(newUser, firstToken);
		assertTrue(verified, "Cannot verify Authy factor");

		String loginToken = "";
		boolean approved = twilioVerifyClient.loginWithAuthyToken(newUser, loginToken);
		assertTrue(approved, "Cannot login with Authy token");

		log.info("====== LOGGED IN SUCCESSFULLY with Authy Token");





		/*   This was the original test by hand. Now moved to TwillioVerifyClient implementation.

		String userUUID = "TwilioTestUserUUID";
		String username = "Twilio TestUser";

		Twilio.init(ACCOUNT_SID, AUTH_TOKEN);

		// 1. Register: Create a new TOTP Factor  https://www.twilio.com/docs/verify/quickstarts/totp#create-a-new-totp-factor
		NewFactor newFactor = NewFactor.creator(
						SERVICE_SID,
						userUUID,
						username,
						NewFactor.FactorTypes.TOTP)
				//.setConfigAppId("org.liquido")
				//.setConfigCodeLength(6)
				//.setConfigSkew(1)
				//.setConfigTimeStep(60)
				.create();

		String factorUri = (String) newFactor.getBinding().get("uri");
		String factorSid = newFactor.getSid();

		System.out.println("=== Newly created twilio authentication Factor =====");
		System.out.println(newFactor);
		System.out.println("========================");
		System.out.println("TOTP URL           " + factorUri);
		System.out.println("Twilio Username:   " + username);
		System.out.println("Twilio userUUID:   " + userUUID);
		System.out.println("Twilio Factor_SID: " + factorSid);
		System.out.println("========================");

		assertTrue(factorUri.contains("LIQUIDO"));

		System.out.println("Available TOTP Factors (FACTOR_SID) for Twilio authentication:");
		// List available factors
		ResourceSet<Factor> factors = Factor.reader(
						SERVICE_SID,
						userUUID)
				//.limit(20)
				.read();
		for (Factor record : factors) {
			System.out.println(record);
		}
		System.out.println("========================");


    /*

		// Step 2.  Verify the Factor (finish registration)
		//
		// Before a factor can be used it must be verified once to prove that the user can create authTokens.
		//
		// This part cannot be tested automatically. You have to manually enter your TOTP.
		// That's the whole reason for 2FA in the first place, that human interaction is necessary! :-)
		// But you can run this in a debugger in your IDE:

		String firstToken = "";        // <===== SET A BREAKPOINT HERE AND UPDATE THIS VALUE MANUALLY IN YOUR DEBUGGER !!!!!
		System.out.println("First AuthToken:    " + firstToken);

		// Update a factor  (verify it)
		Factor factor = Factor.updater(
						SERVICE_SID,
						userUUID,
						factorSid)
				.setAuthPayload(firstToken)
				.update();
		System.out.println(factor);
		assertEquals(Factor.FactorStatuses.VERIFIED, factor.getStatus(), "Factor should  now be verified");

		// Step 3: Challenge: Login via TOTP
		String loginToken = "";        // <===== SET ANOTHER BREAKPOINT HERE AND UPDATE THIS VALUE MANUALLY IN YOUR DEBUGGER !!!!!
		log.info("Trying to log in wiht authToken="+loginToken);
		Challenge challenge = Challenge.creator(
						SERVICE_SID,
						userUUID,
						factorSid)
				.setAuthPayload(loginToken).create();
		System.out.println(challenge.toString());
		assertEquals(Challenge.ChallengeStatuses.APPROVED, challenge.getStatus(), "Challenge denied. Maybe authToken was wrong.");

		log.info("====== LOGGED IN SUCCESSFULLY with Authy Token");


     */

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
				.when().post(GRAPHQL_URI)
				.then().log().all()
				.rootPath("data")
				.body("message", is(""));

	}


}