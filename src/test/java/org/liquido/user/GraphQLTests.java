package org.liquido.user;

import com.twilio.Twilio;
import com.twilio.base.ResourceSet;
import com.twilio.rest.verify.v2.service.entity.Factor;
import com.twilio.rest.verify.v2.service.entity.NewFactor;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.mailer.runtime.MutinyMailerImpl;
import io.quarkus.test.junit.QuarkusTest;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.*;
import org.liquido.util.Lson;

import javax.inject.Inject;
import javax.transaction.Transactional;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Date;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
@QuarkusTest
public class GraphQLTests {

	@Inject
	MockMailbox mailbox;

	public static final String GRAPHQL_URI = "http://localhost:8081/graphql";

	// GraphQL queries
	public static final String JQL_USER =
		"{ id name email mobilephone picture website }";
	public static final String JQL_TEAM_MEMBER =
		"{ id role joinedAt user " + JQL_USER + "}";
	public static final String JQL_PROPOSAL =
		"{ id title description icon status createdAt numSupporters isLikedByCurrentUser createdBy " + JQL_USER + "}";
	public static final String JQL_POLL =
		"{ id title status votingStartAt votingEndAt proposals " + JQL_PROPOSAL +
			"winner " + JQL_PROPOSAL +
			"numBallots " +
			"duelMatrix { data } " +
		"}";
	public static final String JQL_TEAM =
		"{ id teamName inviteCode " +
			"members " + JQL_TEAM_MEMBER +
			// "polls " + JQL_POLL +
			"firstAdmin " + JQL_USER +
		"}";
	public static final String CREATE_OR_JOIN_TEAM_RESULT =
		"{ " +
			"team " + JQL_TEAM +
			"user " + JQL_USER +
			"jwt" +
		"}";

	@BeforeEach
	public void beforeEachTest(TestInfo testInfo) {
		log.info("==========> Starting: " + testInfo.getDisplayName());
		//mailbox.clear();
	}

	@AfterEach
	public void afterEachTest(TestInfo testInfo) {
		log.info("<========== Finished: " + testInfo.getDisplayName());
	}


	@Transactional
	UserEntity createTestUser() {
		Long now = new Date().getTime();
		UserEntity user = new UserEntity(
				"TestUser" + now,
				"testuser"+now+"@liquido.vote",
				"+49 555 " + now
		);
		user.persist();
		return user;
	}


	@Test
	public void dummyTest() {
		int res = 3 * 2;
		assertEquals(6, res);
	}

	@Test
	public void testCreateNewTeam() throws Exception {
		// GIVEN a test team
		Long now = new Date().getTime();
		String teamName = "testTeam" + now;
		Lson admin = Lson.builder()
				.put("name", "TestAdmin "+now)
				.put("email", "testadmin"+now+"@liquido.vote")
				.put("mobilephone", "0151 555 "+now);

		// WHEN creating a new team via GraphQL
		String query = "mutation createNewTeam($teamName: String, $admin: UserEntityInput) { " +
			" createNewTeam(teamName: $teamName, admin: $admin) " + CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson variables = Lson.builder()
				.put("teamName", teamName)
				.put("admin", admin);
		HttpResponse<String> res = this.sendGraphQL(query, variables);

		// THEN the team should have successfully been created
		log.info("CreateNewTeam res.body:\n" + res.body());
		assertEquals(200, res.statusCode(), "Could not createNewTeam");
	}

	@Test
	public void testLoginViaEmail() throws Exception {
		// GIVEN a test user
		UserEntity testUser = createTestUser();

		//  WHEN requesting and email token for this user
		String query = "query reqEmail($email: String) { requestEmailToken(email: $email) }";
		HttpResponse<String> res = this.sendGraphQL(query, Lson.builder("email", testUser.email));

		// THEN login link is sent via email
		assertEquals(200, res.statusCode(), "Could not requestEmailToken");
		log.info("Successfully sent login email.");

		//  AND an email with a one time password (nonce) is received
		List<Mail> mails = mailbox.getMessagesSentTo(testUser.email.toLowerCase());
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
		String email = matcher.group(1);
		String token = matcher.group(2);
		assertNotNull(email);
		assertNotNull(token);
		log.info("Successfully received login link for email: " + email+ " with token: "+token);
	}

	@ConfigProperty(name = "liquido.twilio.accountSID")
	String ACCOUNT_SID;   // "ACXXXXX..."

	@ConfigProperty(name = "liquido.twilio.authToken")
	String AUTH_TOKEN;    /// hex

	@ConfigProperty(name = "liquido.twilio.serviceSID")
	String SERVICE_SID;   // "VAXXXXX..."

	@Test
	@Disabled
	public void testCreateTotpFactor() {
		log.info("Twilio ACCOUNT_SID=" + ACCOUNT_SID);
		long now = new Date().getTime();
		String userUUID = UUID.randomUUID().toString();
		String username = "TwilioUser" + now;

		Twilio.init(ACCOUNT_SID, AUTH_TOKEN);

		// 1. Create a new TOTP Factor  https://www.twilio.com/docs/verify/quickstarts/totp#create-a-new-totp-factor
		NewFactor newFactor = NewFactor.creator(
						SERVICE_SID,
						userUUID,
						username,
						NewFactor.FactorTypes.TOTP)
				.create();

		String FACTOR_SID = newFactor.getSid();

		System.out.println("========================");
		System.out.println(newFactor);
		System.out.println("========================");
		System.out.println("TOTP URL           " + newFactor.getBinding().get("uri"));
		System.out.println("Twilio Username:   " + username);
		System.out.println("Twilio userUUID:   " + userUUID);
		System.out.println("Twilio Factor_SID: " + FACTOR_SID);
		System.out.println("========================");
	}

	// 2. Verify that TOTP factor
	@Test
	@Disabled    // <==== This CANNOT be tested automatically. (That's the whole reason for 2FA in the first place! :-)
	public void testVerifyTotpFactor() {
		// Manually set these parameters as returned by the previous test:  testCreateTotpFactor()
		String userUUID    = "8766ff14-f81a-4087-8f6d-8eb5d32d601c";
		String FACTOR_SID  = "YF0264366f99f5ba32e818bd93604241d4";
		String authToken   = "841661";    // <==== the current token as shown in the Authy App

		Twilio.init(ACCOUNT_SID, AUTH_TOKEN);

		System.out.println("===========================");
		System.out.println("Available TOTP Factors (FACTOR_SID) for Twilio authentication:");
		// List available factors
		ResourceSet<Factor> factors = Factor.reader(
					SERVICE_SID,
					userUUID)
			//.limit(20)
		  .read();
		for(Factor record : factors) {
			System.out.println(record);
		}
		System.out.println("===========================");

		// Update a factor
		Factor factor = Factor.updater(
						SERVICE_SID,
						userUUID,
						FACTOR_SID)
				.setAuthPayload(authToken).update();
		System.out.println(factor);

		assertEquals(Factor.FactorStatuses.VERIFIED, factor.getStatus(), "Factor should  now be verified");

		log.info("Successfully verified TOTP factor");
	}

	/**
	 * send a GraphQL request to our backend
	 * @param query the GraphQL query string
	 * @param variables (optional) variables for the query
	 * @return the HttpResponse with the GraphQL result in its String body
	 * @throws Exception
	 */
	private HttpResponse<String> sendGraphQL(String query, Lson variables) throws Exception {
		if (variables == null) variables = new Lson();
		String body = String.format("{ \"query\": \"%s\", \"variables\": %s }", query, variables);
		log.info("Sending GraphQL request:\n" + body);
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(new URI(GRAPHQL_URI))
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> res = HttpClient.newBuilder()
					.build().send(request, HttpResponse.BodyHandlers.ofString());
			return res;
		} catch (Exception e) {
			log.error("Cannot send graphQL request. Query:\n" + query + "\nVariables:" + variables + "\n ERROR: "+e.getMessage());
			throw e;
		}

	}

}
