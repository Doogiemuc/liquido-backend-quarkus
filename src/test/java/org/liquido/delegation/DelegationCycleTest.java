package org.liquido.delegation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.hamcrest.core.DescribedAs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.LiquidoTestUtils;
import org.liquido.TestFixtures;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;
import org.liquido.util.Lson;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * <h1>A delegation cycle must be impossible, not merely unlikely</h1>
 *
 * {@code castVoteRec()} and every upward walk of the delegation graph recurse over it. A cycle
 * therefore turns vote casting and proxy lookups into a hung request or a StackOverflowError -- and
 * it is reachable by two cooperating users, which makes it a denial of service anyone can trigger,
 * not a theoretical data-integrity concern.
 *
 * <h2>Why the check on delegateTo() was not enough</h2>
 *
 * Delegating to a NON-public proxy does not create an edge; it creates a pending request. So:
 *
 * <ol>
 *   <li>Alice requests a delegation to Bob. No edge exists yet, so nothing looks circular.</li>
 *   <li>Bob requests a delegation to Alice. Alice's request is still pending, so her right to vote
 *       still delegates to nobody -- this does not look circular either.</li>
 *   <li>Alice accepts Bob's request. Now Bob delegates to Alice.</li>
 *   <li>Bob accepts Alice's request. <b>This</b> closes the loop.</li>
 * </ol>
 *
 * Every individual step passed the old check, because the old check only ran at step 1 and 2 -- at
 * request time, when there was nothing to find. The edge is created at accept time, so that is where
 * the check has to be.
 */
@QuarkusTest
@DisplayName("Two cooperating users cannot build a delegation cycle")
public class DelegationCycleTest {

	@Inject
	LiquidoTestUtils util;

	@Test
	@DisplayName("The accept that would close a delegation loop is refused")
	public void mutualDelegationRequestsCannotCloseALoop() {
		// GIVEN two members of one team, neither a public proxy (so delegations are only REQUESTED)
		TeamDataResponse team = util.createFreshTeam("DelegationCycle");
		UserEntity alice = util.joinTeam(team.team.getInviteCode(), null).user;
		UserEntity bob = util.joinTeam(team.team.getInviteCode(), null).user;

		// WHEN each requests a delegation to the other. Neither request looks circular at request time,
		// because a pending request is not yet an edge.
		String aliceJwt = util.devLogin(alice.email).jwt;
		util.delegateTo(bob, aliceJwt);

		String bobJwt = util.devLogin(bob.email).jwt;
		util.delegateTo(alice, bobJwt);

		// AND Alice accepts Bob's request -- legitimate, this creates the first edge (bob -> alice)
		aliceJwt = util.devLogin(alice.email).jwt;
		util.acceptDelegationRequests(util.getDelegationRequestIds(aliceJwt), aliceJwt);

		// THEN Bob accepting Alice's request must be REFUSED: it would close the loop.
		bobJwt = util.devLogin(bob.email).jwt;
		List<Long> bobsPendingRequests = util.getDelegationRequestIds(bobJwt);
		assertFalse(bobsPendingRequests.isEmpty(), "Bob should still have Alice's request pending to accept");

		assertAcceptRefusedAsCircular(bobsPendingRequests, bobJwt);
	}

	/** Accepting these requests must come back as CANNOT_ASSIGN_CIRCULAR_PROXY, not succeed. */
	private void assertAcceptRefusedAsCircular(List<Long> requestIds, String jwt) {
		String query = "mutation acceptDelegationRequests($ids: [BigInteger!]!) { acceptDelegationRequests(delegationRequestIds: $ids) }";
		ValidatableResponse res = given()
				.contentType(ContentType.JSON)
				.header("Authorization", "Bearer " + jwt)
				.body(String.format("{ \"query\": \"%s\", \"variables\": %s }", query, new Lson("ids", requestIds)))
				.when()
				.post(TestFixtures.GRAPHQL_URI)
				.then()
				.statusCode(200);

		res.body("errors[0].extensions.liquidoException.liquidoErrorName", DescribedAs.describedAs(
				"Accepting a delegation request that would close a cycle must be refused. Without this, " +
				"two cooperating users can make every vote cast in the poll recurse forever.",
				is("CANNOT_ASSIGN_CIRCULAR_PROXY")));
	}
}
