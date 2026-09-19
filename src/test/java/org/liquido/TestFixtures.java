package org.liquido;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;
import org.hamcrest.core.DescribedAs;
import org.liquido.util.Lson;

import java.util.Date;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * This is used throughout all tests.
 */
@Slf4j
public class TestFixtures {


	// Test Data Set

	/*
	 * ======================= The SEED team: timestamped, found by PREFIX =======================
	 *
	 * TestDataCreator ADDS a new seed team on every run rather than replacing the previous one, so its
	 * name carries a timestamp. That means no later JVM can find it by recomputing a constant - the
	 * timestamp would differ - so the seed is always resolved by PREFIX via
	 * LiquidoTestUtils.getSeedTeam() / TeamEntity.findNewestByPrefix(). Never look the seed up by
	 * TestFixtures.teamName from a test: that field only names the team THIS JVM would create.
	 *
	 * Full milliseconds, deliberately not "% 1000000": that wraps every ~16.7 minutes, so two seed
	 * runs that far apart would collide on the unique email and mobilephone constraints.
	 */
	public static final String SEED_TEAM_PREFIX   = "testTeam";
	public static final String SEED_ADMIN_PREFIX  = "testadmin";
	public static final String SEED_MEMBER_PREFIX = "testmember";
	public static final String SEED_POLL_PREFIX   = "TestPoll ";

	public static Long   now          = new Date().getTime();
	public static String teamName     = SEED_TEAM_PREFIX + now;
	public static String adminEmail   = SEED_ADMIN_PREFIX + now + "@liquido.vote";
	public static String memberEmail  = SEED_MEMBER_PREFIX + now + "@liquido.vote";
	public static String pollTitle    = SEED_POLL_PREFIX + now;
	public static final String PASSWORD_SUFFIX = "_PWD"; // must be same as in cypress-base-config.js

	/*
	 * ======================= FIXED-name teams: purged and recreated each run =======================
	 *
	 * Everything the frontend or the purge sweep refers to by constant lives here. These names must
	 * NOT carry a timestamp: Cypress specs hard-code them (see cypress-base-config.js), and they can
	 * only stay hard-coded because TestDataCreator purges and recreates these teams on every run.
	 *
	 * Each admin needs a DISTINCT mobilephone, or createNewTeam is rejected with
	 * USER_MOBILEPHONE_EXISTS.
	 */

	/** Anything goes. No test may assume ANY property of this team beyond its existence. */
	public static final String SCRATCH_TEAM_NAME    = "scratchTeam";
	public static final String SCRATCH_ADMIN_EMAIL  = "scratchadmin@liquido.vote";
	public static final String SCRATCH_ADMIN_NAME   = "Scratch Admin";
	public static final String SCRATCH_ADMIN_MOBILE = "0151 555 0001";
	public static final String SCRATCH_MEMBER_EMAIL = "scratchmember@liquido.vote";

	/** Owned by login-tests.cy.js. Fixed email AND display name - the spec asserts both. */
	public static final String LOGIN_TEAM_NAME    = "loginTeam";
	public static final String LOGIN_ADMIN_EMAIL  = "loginadmin@liquido.vote";
	public static final String LOGIN_ADMIN_NAME   = "Login Admin";
	public static final String LOGIN_ADMIN_MOBILE = "0151 555 0004";

	/** Team names the e2e suite creates for itself, which the purge sweep matches on. */
	public static final String E2E_TEAM_PREFIX  = "Cypress ";
	public static final String E2E_EMAIL_PREFIX = "cypress";

	// A self-contained TWO-TEAM scenario: one user who is a member of both teams (see TestDataCreator).
	//
	// Deliberately kept OUT of the shared testTeam4711 above. joinTeam() sets the user's lastTeamId, and
	// devLogin() logs a user into their last team -- so a multi-team member of the shared seed team would
	// log in to the *other* team, and every later test that makes them act in the seed team would fail
	// with "Poll(id=...) not found" from the team-scoping guard. Keeping this scenario in its own two
	// teams means it can never disturb the shared fixtures.
	//
	// Each admin needs a distinct mobilephone or createNewTeam is rejected with USER_MOBILEPHONE_EXISTS.
	//
	// FIXED names, because switch-team.cy.js hard-codes them. The multi-team MEMBER below is the one
	// user who deliberately belongs to two teams, which is why purging these teams has to delete
	// members unconditionally: a purge that spares users belonging to another team could never delete
	// them, and the next run would then collide on the unique email.
	public static final String multiTeamAName        = "multiTeamA";
	public static final String multiTeamAAdminEmail  = "multiteamadmina@liquido.vote";
	public static final String multiTeamAAdminMobile = "0151 666 0002";
	public static final String multiTeamBName        = "multiTeamB";
	public static final String multiTeamBAdminEmail  = "multiteamadminb@liquido.vote";
	public static final String multiTeamBAdminMobile = "0151 777 0003";
	/** The user who is a member of BOTH multiTeamA and multiTeamB, and who votes in the second one. */
	public static final String multiTeamMemberEmail  = "multiteammember@liquido.vote";

	public static final String staticDummyEmail = "staticDummyEmail@liquido.vote";

	// GraphQL   This is port 8081 during testing, but 8443 in prod!
	public static final String LIQUIDO_API = "http://localhost:8081";
	public static final String GRAPHQL_URI = LIQUIDO_API+"/graphql";

	public static final String JQL_USER =
			"{ id name email mobilephone picture website }";
	public static final String JQL_TEAM_MEMBER =
			"{ id role joinedAt user " + JQL_USER + "}";
	public static final String JQL_PROPOSAL =
			"{ id title description icon status createdAt likedByCurrentUser numSupporters createdBy " + JQL_USER + "}";  //no "is" before likedByCurrentUser !
	public static final String JQL_POLL =
			"{ id title status membersCanAddProposals proposals " + JQL_PROPOSAL +  // TODO: votingStartAt votingEndAt
					" winner " + JQL_PROPOSAL +
					//TODO:" numBallots " +
					//TODO:" duelMatrix { data } " +
					"}";
	public static final String JQL_TEAM =
			"{ id teamName inviteCode " +
					" members " + JQL_TEAM_MEMBER +
					" polls " + JQL_POLL +
					"}";
	public static final String CREATE_OR_JOIN_TEAM_RESULT =
			"{ " +
					" team " + JQL_TEAM +
					" user " + JQL_USER +
					" teams { id teamName }" +   // ALL teams of this user, for the team switcher
					" jwt" +
					"}";
	public static final String JQL_BALLOT =
			"{" +
					"level checksum voteOrder " + JQL_PROPOSAL +
					"}";

	public static void sendGraphQL(String query) {
		sendGraphQL(query, null, null);
	}

	public static ValidatableResponse sendGraphQL(String query, Lson vars) {
		return sendGraphQL(query, vars, null);
	}

	/**
	 * send a GraphQL request to our backend
	 *
	 * @param query     the GraphQL query string
	 * @param variables (optional) variables for the query
	 * @return the HttpResponse with the GraphQL result in its String body
	 */
	public static ValidatableResponse sendGraphQL(String query, Lson variables, String jwt) {
		RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
		if (variables == null) variables = new Lson();
		String body = String.format("{ \"query\": \"%s\", \"variables\": %s }", query, variables);
		log.debug("Sending GraphQL request:\n     " + body);

		if (jwt == null) {
			return given() //.log().all()
					.contentType(ContentType.JSON)
					.body(body)
					.when()
					.post(TestFixtures.GRAPHQL_URI)
					.then() //.log().all()
					.statusCode(200)  // But be careful: GraphQL always returns 200, so we need to
					.body("errors", DescribedAs.describedAs("no GraphQL errors returned from anonymous query: "+query, anyOf(nullValue(), hasSize(0))));   // check for no GraphQL errors: []
		} else {
			return given() //.log().all()
					.contentType(ContentType.JSON)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
					.body(body)
					.when()
					.post(TestFixtures.GRAPHQL_URI)
					.then().log().all()
					.statusCode(200)  // But be careful: GraphQL always returns 200, so we need to
					.body("errors", DescribedAs.describedAs("no GraphQL errors returned from authenticated query: "+query, anyOf(nullValue(), hasSize(0))));    // check for no GraphQL errors: []
		}

	}



}