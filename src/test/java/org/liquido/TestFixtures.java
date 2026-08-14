package org.liquido;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;
import org.hamcrest.core.DescribedAs;
import org.liquido.util.Lson;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * This is used throughout all tests.
 */
@Slf4j
public class TestFixtures {


	// Test Data Set

	// every test data item will contain this "now" in one of its attributes
	public static Long   now          = 4711L; //new Date().getTime() % 1000000;
	public static String teamName     = "testTeam" + now;
	public static String adminEmail   = "testadmin" + now + "@liquido.vote";
	public static String memberEmail  = "testmember" + now + "@liquido.vote";
	public static String pollTitle    = "TestPoll " + now;
	public static final String PASSWORD_SUFFIX = "_PWD"; // must be same as in cypress.config.js

	// A self-contained TWO-TEAM scenario: one user who is a member of both teams (see TestDataCreator).
	//
	// Deliberately kept OUT of the shared testTeam4711 above. joinTeam() sets the user's lastTeamId, and
	// devLogin() logs a user into their last team -- so a multi-team member of the shared seed team would
	// log in to the *other* team, and every later test that makes them act in the seed team would fail
	// with "Poll(id=...) not found" from the team-scoping guard. Keeping this scenario in its own two
	// teams means it can never disturb the shared fixtures.
	//
	// Each admin needs a distinct mobilephone or createNewTeam is rejected with USER_MOBILEPHONE_EXISTS.
	public static String multiTeamAName          = "multiTeamA" + now;
	public static String multiTeamAAdminEmail    = "multiteamadmina" + now + "@liquido.vote";
	public static String multiTeamAAdminMobile   = "0151 666 " + now % 1000000;
	public static String multiTeamBName          = "multiTeamB" + now;
	public static String multiTeamBAdminEmail    = "multiteamadminb" + now + "@liquido.vote";
	public static String multiTeamBAdminMobile   = "0151 777 " + now % 1000000;
	/** The user who is a member of BOTH multiTeamA and multiTeamB, and who votes in the second one. */
	public static String multiTeamMemberEmail    = "multiteammember" + now + "@liquido.vote";

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
			"{ id title status proposals " + JQL_PROPOSAL +  // TODO: votingStartAt votingEndAt
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