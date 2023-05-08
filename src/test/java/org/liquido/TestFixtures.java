package org.liquido;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.apache.http.HttpHeaders;
import org.liquido.util.Lson;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

public class TestFixtures {

	public static final String GRAPHQL_URI = "http://localhost:8081/graphql";


	public static final String JQL_USER =
			"{ id name email mobilephone picture website }";
	public static final String JQL_TEAM_MEMBER =
			"{ id role joinedAt user " + JQL_USER + "}";
	public static final String JQL_PROPOSAL =
			"{ id title description icon status createdAt numSupporters isLikedByCurrentUser createdBy " + JQL_USER + "}";
	public static final String JQL_POLL =
			"{ id title status votingStartAt votingEndAt proposals " + JQL_PROPOSAL +
					" winner " + JQL_PROPOSAL +
					" numBallots " +
					" duelMatrix { data } " +
					"}";
	public static final String JQL_TEAM =
			"{ id teamName inviteCode " +
					" members " + JQL_TEAM_MEMBER +
					// " polls " + JQL_POLL +
					"}";
	public static final String CREATE_OR_JOIN_TEAM_RESULT =
			"{ " +
					" team " + JQL_TEAM +
					" user " + JQL_USER +
					" jwt" +
					"}";


	public static ValidatableResponse sendGraphQL(String query) {
		return sendGraphQL(query, null, null);
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
	 * @throws Exception
	 */
	public static ValidatableResponse sendGraphQL(String query, Lson variables, String jwt) {
		RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
		if (variables == null) variables = new Lson();
		String body = String.format("{ \"query\": \"%s\", \"variables\": %s }", query, variables);
		//log.info("Sending GraphQL request:\n     " + body);

		if (jwt == null) {

			return given() //.log().all()
					.contentType(ContentType.JSON)
					.body(body)
					.when()
					.post(TestFixtures.GRAPHQL_URI)
					.then() //.log().all()
					.statusCode(200)  // But be careful: GraphQL always returns 200, so we need to
					.body("errors", anyOf(nullValue(), hasSize(0)));    // check for no GraphQL errors: []
		} else {
			return given() //.log().all()
					.contentType(ContentType.JSON)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
					.body(body)
					.when()
					.post(TestFixtures.GRAPHQL_URI)
					.then() //.log().all()
					.statusCode(200)  // But be careful: GraphQL always returns 200, so we need to
					.body("errors", anyOf(nullValue(), hasSize(0)));    // check for no GraphQL errors: []
		}
		/*

		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(new URI(GRAPHQL_URI))
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> res = HttpClient.newBuilder()
					.build().send(request, HttpResponse.BodyHandlers.ofString());
			return res;
		} catch (Exception e) {
			log.error("Cannot send graphQL request. Query:\n" + query + "\nVariables:" + variables + "\n ERROR: " + e.getMessage());
			throw e;
		}

		 */

	}

}