package org.liquido;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.jwt.build.Jwt;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.*;
import org.liquido.security.JwtTokenUtils;
import org.liquido.services.TwilioVerifyClient;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoException;
import org.liquido.util.Lson;

import javax.inject.Inject;
import java.util.Collections;
import java.util.Date;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.liquido.TestFixtures.GRAPHQL_URI;
import static org.liquido.security.JwtTokenUtils.LIQUIDO_ISSUER;

@Slf4j
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AuthyTests {

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
	 * Most basic test. Ping our GraphQL API.
	 */
	@Test
	public void pingApi() {
		String body = "{ \"query\": \"{ ping }\" }";
		given().log().all()
				.body(body)
				.when()
				.post(GRAPHQL_URI)
				.then().log().all()
				.statusCode(200)
				.body("errors", nullValue());
	}


	@Test
	public void testAuthenticatedRequest() {
		String body = "{ \"query\": \"{ requireUser }\" }";

		/*
		// secret MUST have at least 120 bytes
		SecretKey secretKey = KeyUtils.createSecretKeyFromSecret("secret3453qegegetlk3q4htlkqehrglkadhglkadfgj");

		//SecretKey secretKey = KeyUtils.generateSecretKey(SignatureAlgorithm.HS256);

		String JWT2222 = Jwt.subject("liquidoTestUser11@liquido.vote")
				.audience("LIQUIDO")
				.expiresIn(3600 * 1000)
				.sign(secretKey);

		// https://quarkus.io/guides/security-customization#registering-security-providers
		// https://quarkus.io/guides/security-jwt#dealing-with-the-verification-keys

		String key = new String(secretKey.getEncoded());
		System.out.println("======= secretKey Format" + secretKey.getFormat());
		System.out.println("======= secretKey" + key);

		 */

		String JWT = Jwt.issuer("https://www.LIQUIDO.vote")
				.subject(TestDataCreator.ADMIN_EMAIL)
				//.upn("upn@liquido.vote")  // if upn is set, this will be used instead of subject   see JWTCallerPrincipal.getName()
				.issuer(LIQUIDO_ISSUER)
				.groups(Collections.singleton(JwtTokenUtils.LIQUIDO_USER_ROLE))  // role
				//.expiresIn(9000)
				//.jws().algorithm(SignatureAlgorithm.HS256)
				.sign();

		System.out.println("======= JWT: "+JWT);


		given().log().all()
				.header(HttpHeaderNames.AUTHORIZATION.toString(), "Bearer "+JWT)
				.contentType(ContentType.JSON)
				.body(body)
				.when()
				.post(GRAPHQL_URI)
				.then().log().all()
				.statusCode(200)
				.body("errors", nullValue());
	}





	@ConfigProperty(name = "liquido.twilio.accountSID")
	String ACCOUNT_SID;   // "ACXXXXX..."

	@ConfigProperty(name = "liquido.twilio.authToken")
	String AUTH_TOKEN;    /// hex

	@ConfigProperty(name = "liquido.twilio.serviceSID")
	String SERVICE_SID;   // "VAXXXXX..."

	@Inject
	TwilioVerifyClient twilioVerifyClient;

	//TODO: test this via GraphQL

	/**
	 * Twilio + Authy App =  time based one time password (TOTP) authentication
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
		log.info("Twilio ACCOUNT_SID=" + ACCOUNT_SID);

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
		boolean approved = twilioVerifyClient.loginWithAuthToken(newUser, loginToken);
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