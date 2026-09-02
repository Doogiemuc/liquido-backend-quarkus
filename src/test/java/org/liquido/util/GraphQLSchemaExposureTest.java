package org.liquido.util;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.TestFixtures;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>What the GraphQL schema is allowed to expose</h1>
 *
 * This asserts against the schema Quarkus actually <b>generates</b>, not against the entity source.
 * That distinction is the whole point: field-level hiding in this codebase is done with Jackson
 * annotations, and whether a given Jackson annotation reaches the GraphQL schema is a property of
 * the SmallRye GraphQL version, not of our code. Reading the entity tells you the intent; only the
 * generated schema tells you the result.
 *
 * <h2>What we learned by actually looking</h2>
 * <ul>
 *   <li>{@code @JsonIgnore} <b>is</b> honoured by SmallRye GraphQL - {@code UserEntity.passwordHash},
 *       {@code BallotEntity.rightToVote} and friends never reached the schema.</li>
 *   <li>{@code @JsonBackReference} is <b>not</b>. Every back reference stayed fully traversable, which
 *       gave the deliberately unauthenticated {@code verifyBallot} a path to
 *       {@code ballot -> poll -> team -> inviteCode} and the team's whole member list. Those four
 *       fields now carry {@code @Ignore} as well.</li>
 * </ul>
 *
 * If this test fails, do not weaken it. Either a sensitive field lost its annotation, or a new field
 * arrived without one.
 */
@QuarkusTest
@DisplayName("The generated GraphQL schema exposes no sensitive field")
public class GraphQLSchemaExposureTest {

	private static String schema;

	@BeforeAll
	static void fetchSchema() {
		schema = RestAssured.given().when()
				.get(TestFixtures.LIQUIDO_API + "/graphql/schema.graphql")
				.then().statusCode(200)
				.extract().asString();
		assertFalse(schema.isBlank(), "the generated schema must not be empty");
	}

	/**
	 * Secrets and credentials. None of these may ever be readable through the API, by anybody.
	 */
	@Test
	@DisplayName("No credential, secret or anonymity-breaking field is in the schema")
	public void schemaHasNoSecrets() {
		List<String> forbidden = List.of(
				"passwordHash",                // bcrypt hashes
				"emailVerificationNonce",      // would let anyone verify anyone's email
				"totpFactorUri", "totpFactorSid",
				"hashedVoterInfo",             // the ballot <-> voter link
				"hashedVoterToken",            // a one-time voting token
				"webAuthnCredentials"
		);
		for (String field : forbidden) {
			assertFalse(containsField(field),
					"Field '" + field + "' must never appear in the GraphQL schema, but it does. " +
					"Add @Ignore (org.eclipse.microprofile.graphql) next to its @JsonIgnore.");
		}
	}

	/**
	 * The RightToVote is the hinge of ballot anonymity: user -> RightToVote -> Ballot. If the type
	 * itself is reachable, so is the link, and ballots stop being anonymous.
	 */
	@Test
	@DisplayName("RightToVoteEntity is not part of the API surface at all")
	public void rightToVoteIsNotExposed() {
		assertFalse(schema.contains("type RightToVoteEntity"),
				"RightToVoteEntity must not be a GraphQL type - it is the ballot <-> voter link");
		assertFalse(containsField("rightToVote"),
				"No type may expose a rightToVote field");
		assertFalse(containsField("delegatedTo"), "The delegation graph must stay anonymous");
		assertFalse(containsField("publicProxy"), "The delegation graph must stay anonymous");
		assertFalse(containsField("requestedDelegationFrom"), "Pending delegations must stay private");
		assertFalse(containsField("supporters"),
				"Only the numSupporters count may be exposed, never who liked what by name");
	}

	/**
	 * verifyBallot is deliberately unauthenticated - a receipt should be checkable without logging in.
	 * That makes every field reachable FROM a ballot unauthenticated too, so the back references out
	 * of a ballot must be closed.
	 */
	@Test
	@DisplayName("A ballot is a dead end: no traversal from ballot to poll, team or invite code")
	public void ballotDoesNotLeadAnywhere() {
		String ballot = typeBody("BallotEntity");
		assertFalse(ballot.contains("poll:"),
				"BallotEntity.poll must be @Ignore'd: the unauthenticated verifyBallot would otherwise " +
				"reach ballot -> poll -> team -> inviteCode and the team's member list");

		String proposal = typeBody("ProposalEntity");
		assertFalse(proposal.contains("poll:"),
				"ProposalEntity.poll must be @Ignore'd: a ballot's voteOrder is otherwise a second " +
				"route to the same traversal");

		String poll = typeBody("PollEntity");
		assertFalse(poll.contains("team:"),
				"PollEntity.team must be @Ignore'd, or a poll leads back to its team");

		String teamMember = typeBody("TeamMemberEntity");
		assertFalse(teamMember.contains("team:"),
				"TeamMemberEntity.team must be @Ignore'd, or a member leads back to their team");
	}

	/** True if any type in the schema declares a field of this name. */
	private boolean containsField(String fieldName) {
		return schema.matches("(?s).*\\b" + fieldName + "\\s*(\\(|:).*");
	}

	/**
	 * The body of one GraphQL type declaration.
	 *
	 * Fails rather than returning "" when the type is absent: every assertion built on this asks
	 * whether a field is missing, so a silently empty body would make those assertions pass for the
	 * wrong reason the day someone renames a type.
	 */
	private String typeBody(String typeName) {
		int start = schema.indexOf("type " + typeName + " {");
		assertTrue(start >= 0, "Expected type '" + typeName + "' in the schema. If it was renamed or " +
				"removed, update this test - do not let it pass by looking at nothing.");
		int end = schema.indexOf("\n}", start);
		return schema.substring(start, end < 0 ? schema.length() : end);
	}
}
