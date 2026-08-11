package org.liquido.polly;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import io.smallrye.jwt.build.Jwt;
import org.hamcrest.core.DescribedAs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.TestFixtures;
import org.liquido.security.JwtTokenUtils;
import org.liquido.util.Lson;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole Polly contract, exercised over real HTTP.
 *
 * <h3>Why the queries are copied verbatim from the frontend</h3>
 * Every query string below is the one in {@code liquido-mobile-pwa-vue3/src/polly/polly-client.js},
 * including its variable declarations. That makes these tests catch a schema mismatch the way
 * the browser would: a {@code $voteOrder: [ID!]!} variable handed to a {@code [String!]!}
 * argument is rejected by GraphQL validation before any resolver runs, and no amount of Java
 * unit testing would notice.
 *
 * <h3>Why identities are minted JWTs</h3>
 * A headless test cannot drive an authenticator, so the WebAuthn ceremony itself is out of
 * reach here and has to be walked by hand on real hardware. Everything below the ceremony -
 * which is every rule the product has - is reachable by minting the polly token the ceremony
 * would have issued. A distinct fake credential id per person is exactly the multi-device case.
 *
 * <p>These tests are self-contained: they create their own pollys with random public ids and
 * their own credential ids, and touch none of the shared seeded team data.
 */
@QuarkusTest
public class PollyTests {

	/** Exactly the field set polly-client.js asks for. */
	private static final String POLLY_FIELDS =
			"{ publicId title status createdAt votingEndAt numBallots isOwner alreadyVoted proposals { id title } winner { id title } }";

	private static final String CREATE_POLLY =
			"mutation createPolly($title: String!, $proposalTitles: [String!]!) { createPolly(title: $title, proposalTitles: $proposalTitles) " + POLLY_FIELDS + " }";
	private static final String GET_POLLY =
			"query polly($publicId: String!) { polly(publicId: $publicId) " + POLLY_FIELDS + " }";
	private static final String EDIT_POLLY =
			"mutation editPolly($publicId: String!, $title: String!, $proposalTitles: [String!]!) { editPolly(publicId: $publicId, title: $title, proposalTitles: $proposalTitles) " + POLLY_FIELDS + " }";
	// NOTE: [String!]!, not the [ID!]! polly-client.js originally declared. MicroProfile's @Id
	// cannot type a *list* of IDs - SmallRye applies it to the outer type and asks for a scalar
	// named java.util.List - and the schema therefore has no ID type at all, so an [ID!]! variable
	// is rejected as an unknown type before any resolver runs. polly-client.js must declare
	// [String!]! to match. See PollyGraphQL.voteInPolly.
	private static final String VOTE_IN_POLLY =
			"mutation voteInPolly($publicId: String!, $voteOrder: [String!]!) { voteInPolly(publicId: $publicId, voteOrder: $voteOrder) " + POLLY_FIELDS + " }";
	private static final String FINISH_POLLY =
			"mutation finishPolly($publicId: String!) { finishPolly(publicId: $publicId) " + POLLY_FIELDS + " }";
	private static final String MY_POLLYS =
			"query myPollys { myPollys " + POLLY_FIELDS + " }";

	private static final String QUESTION = "Where shall we go for dinner?";
	private static final List<String> OPTIONS = List.of("Pizza", "Sushi", "Burger");

	/** Makes every credential id in a run unique, so tests never collide over "already voted". */
	private static final AtomicInteger PERSON_COUNTER = new AtomicInteger();

	// ================================================================= plumbing

	/** A brand new person with their own phone and their own passkey. Returns their polly JWT. */
	private String asNewPerson() {
		String credentialId = "test-credential-" + UUID.randomUUID() + "-" + PERSON_COUNTER.incrementAndGet();
		return Jwt.subject(credentialId)
				.issuer(JwtTokenUtils.LIQUIDO_ISSUER)
				.groups(Set.of(JwtTokenUtils.LIQUIDO_POLLY_ROLE))
				.expiresIn(3600)
				.sign();
	}

	/** Send a polly operation. Deliberately does NOT assert the absence of errors. */
	private ValidatableResponse send(String query, Lson variables, String jwt) {
		String body = String.format("{ \"query\": \"%s\", \"variables\": %s }", query, variables == null ? new Lson() : variables);
		var request = given().contentType(ContentType.JSON);
		if (jwt != null) request = request.header("Authorization", "Bearer " + jwt);
		return request.body(body).when().post(TestFixtures.GRAPHQL_URI).then().statusCode(200);
	}

	/** Assert the call succeeded and return its payload for inspection. */
	private JsonPath okData(ValidatableResponse res) {
		res.body("errors", DescribedAs.describedAs("expected no GraphQL errors", anyOf(nullValue(), hasSize(0))));
		return res.extract().jsonPath();
	}

	/** Assert the call failed with exactly this polly error code. */
	private void assertPollyError(ValidatableResponse res, PollyError expected, String because) {
		res.body("errors[0].extensions.pollyErrorCode", DescribedAs.describedAs(because, is(expected.name())));
	}

	private JsonPath createPolly(String jwt, String title, List<String> options) {
		return okData(send(CREATE_POLLY, Lson.builder().put("title", title).put("proposalTitles", options), jwt));
	}

	/** A polly created by a fresh person, with the standard question and options. */
	private JsonPath createStandardPolly(String jwt) {
		return createPolly(jwt, QUESTION, OPTIONS);
	}

	private static List<String> proposalIds(JsonPath data, String op) {
		return data.getList("data." + op + ".proposals.id", String.class);
	}

	private static String proposalIdByTitle(JsonPath data, String op, String title) {
		List<String> ids = proposalIds(data, op);
		List<String> titles = data.getList("data." + op + ".proposals.title", String.class);
		return ids.get(titles.indexOf(title));
	}

	// ================================================================= the schema itself

	@Test
	@DisplayName("The published schema is the one polly-client.js codes against")
	public void schemaMatchesTheFrontendContract() {
		String schema = given().when().get(TestFixtures.LIQUIDO_API + "/graphql/schema.graphql")
				.then().statusCode(200).extract().asString();

		// Argument types must match the frontend's variable declarations exactly: GraphQL rejects
		// a query whose variable's named type differs from the argument's, before any resolver runs.
		assertTrue(schema.contains("polly(publicId: String!)"), "polly(publicId: String!) missing from:\n" + schema);
		assertTrue(schema.contains("proposalTitles: [String!]!") || schema.contains("proposalTitles: [String]!"),
				"createPolly/editPolly must take a non-null list of titles. Actual schema:\n" + schema);
		assertTrue(schema.contains("voteOrder: [String!]!") || schema.contains("voteOrder: [String]!"),
				"voteInPolly must take a list of String - polly-client.js declares its variable to match. Actual schema:\n" + schema);
		assertTrue(schema.contains("myPollys"), "myPollys missing");

		// There is no ID scalar anywhere in this schema, which is why an [ID!]! variable cannot
		// work even though ID is a GraphQL built-in: SmallRye only publishes scalars in use.
		assertFalse(schema.contains("scalar ID"), "an ID scalar would change what polly-client.js may declare");

		// The per-caller booleans must keep these exact names; a Lombok isOwner() getter would
		// silently publish them as 'owner' / no field at all, and the frontend asks for isOwner.
		assertTrue(schema.contains("isOwner"), "PollyResponse.isOwner missing from the schema");
		assertTrue(schema.contains("alreadyVoted"), "PollyResponse.alreadyVoted missing from the schema");

		// And the secrets must never be published.
		assertFalse(schema.contains("ownerKey"), "owner_key must never reach the schema");
		assertFalse(schema.contains("voterKey"), "voter_key must never reach the schema");
	}

	// ================================================================= creating

	@Test
	@DisplayName("One passkey and the polly is live immediately - there is no start step")
	public void createPollyIsLiveImmediately() {
		String me = asNewPerson();

		JsonPath data = createStandardPolly(me);

		assertEquals("VOTING", data.getString("data.createPolly.status"));
		assertNotNull(data.getString("data.createPolly.publicId"));
		assertTrue(data.getBoolean("data.createPolly.isOwner"));
		assertFalse(data.getBoolean("data.createPolly.alreadyVoted"));
		assertEquals(0, data.getInt("data.createPolly.numBallots"));
		assertNull(data.get("data.createPolly.winner"));
		assertNull(data.get("data.createPolly.votingEndAt"));
		assertNotNull(data.getString("data.createPolly.createdAt"));
		// the options keep the order they were typed in
		assertEquals(OPTIONS, data.getList("data.createPolly.proposals.title", String.class));
	}

	@Test
	@DisplayName("The public id is opaque, not a guessable sequence")
	public void publicIdIsOpaque() {
		String first = createStandardPolly(asNewPerson()).getString("data.createPolly.publicId");
		String second = createPolly(asNewPerson(), "Another question entirely", List.of("Yes", "No"))
				.getString("data.createPolly.publicId");

		assertNotEquals(first, second);
		assertFalse(first.matches("\\d+"), "a sequential id would let anyone enumerate every polly");
		assertTrue(first.length() >= 8, "public id too short: " + first);
	}

	@Test
	@DisplayName("A polly needs a question and at least two options")
	public void createPollyValidatesItsInput() {
		String me = asNewPerson();

		assertPollyError(send(CREATE_POLLY, Lson.builder().put("title", "  ").put("proposalTitles", OPTIONS), me),
				PollyError.INVALID_POLLY, "a blank title must be refused");

		assertPollyError(send(CREATE_POLLY, Lson.builder().put("title", QUESTION).put("proposalTitles", List.of("Only one")), me),
				PollyError.INVALID_POLLY, "one option is not a choice");

		// The UI always keeps one empty row at the bottom, so blanks are normal input, not an error
		JsonPath data = okData(send(CREATE_POLLY,
				Lson.builder().put("title", QUESTION).put("proposalTitles", List.of("Pizza", "Sushi", "", "  ")), me));
		assertEquals(List.of("Pizza", "Sushi"), data.getList("data.createPolly.proposals.title", String.class));
	}

	@Test
	@DisplayName("Creating without a passkey is refused")
	public void createPollyNeedsAPasskey() {
		assertPollyError(send(CREATE_POLLY, Lson.builder().put("title", QUESTION).put("proposalTitles", OPTIONS), null),
				PollyError.NEED_PASSKEY, "there is no session at all");
	}

	// ================================================================= one link for everyone

	@Test
	@DisplayName("The creator is recognised by their passkey and sees admin rights")
	public void ownerIsRecognisedOnReturn() {
		String owner = asNewPerson();
		String publicId = createStandardPolly(owner).getString("data.createPolly.publicId");

		JsonPath seen = okData(send(GET_POLLY, new Lson("publicId", publicId), owner));

		assertTrue(seen.getBoolean("data.polly.isOwner"));
	}

	@Test
	@DisplayName("A friend opening the same link is not the owner")
	public void friendIsNotTheOwner() {
		String publicId = createStandardPolly(asNewPerson()).getString("data.createPolly.publicId");

		JsonPath seen = okData(send(GET_POLLY, new Lson("publicId", publicId), asNewPerson()));

		assertFalse(seen.getBoolean("data.polly.isOwner"));
	}

	@Test
	@DisplayName("A polly reads with no session at all, so the link works before any tap")
	public void pollyIsReadableWithoutASession() {
		String publicId = createStandardPolly(asNewPerson()).getString("data.createPolly.publicId");

		JsonPath seen = okData(send(GET_POLLY, new Lson("publicId", publicId), null));

		assertEquals(QUESTION, seen.getString("data.polly.title"));
		assertFalse(seen.getBoolean("data.polly.isOwner"));
		assertFalse(seen.getBoolean("data.polly.alreadyVoted"));
	}

	@Test
	@DisplayName("An unknown link gives a clean not-found")
	public void unknownPollyIsNotFound() {
		assertPollyError(send(GET_POLLY, new Lson("publicId", "doesNotExist"), null),
				PollyError.POLLY_NOT_FOUND, "an unknown public id");
	}

	// ================================================================= voting

	@Test
	@DisplayName("A friend votes with no account, just one passkey")
	public void friendCanVote() {
		String publicId = createStandardPolly(asNewPerson()).getString("data.createPolly.publicId");
		String friend = asNewPerson();
		List<String> order = proposalIds(okData(send(GET_POLLY, new Lson("publicId", publicId), friend)), "polly");

		JsonPath voted = okData(send(VOTE_IN_POLLY,
				Lson.builder().put("publicId", publicId).put("voteOrder", order), friend));

		assertEquals(1, voted.getInt("data.voteInPolly.numBallots"));
		assertTrue(voted.getBoolean("data.voteInPolly.alreadyVoted"));
		assertFalse(voted.getBoolean("data.voteInPolly.isOwner"));
	}

	@Test
	@DisplayName("The owner votes in their own polly and keeps their admin rights")
	public void ownerCanVoteToo() {
		String owner = asNewPerson();
		JsonPath created = createStandardPolly(owner);
		String publicId = created.getString("data.createPolly.publicId");

		JsonPath voted = okData(send(VOTE_IN_POLLY,
				Lson.builder().put("publicId", publicId).put("voteOrder", proposalIds(created, "createPolly")), owner));

		assertEquals(1, voted.getInt("data.voteInPolly.numBallots"));
		assertTrue(voted.getBoolean("data.voteInPolly.alreadyVoted"));
		assertTrue(voted.getBoolean("data.voteInPolly.isOwner"), "voting must not cost the owner their admin rights");
	}

	@Test
	@DisplayName("The same passkey cannot vote twice, even in a brand new session")
	public void onePasskeyOneVote() {
		String me = asNewPerson();
		JsonPath created = createStandardPolly(me);
		String publicId = created.getString("data.createPolly.publicId");
		List<String> order = proposalIds(created, "createPolly");
		okData(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId).put("voteOrder", order), me));

		// same credential, a freshly minted token - i.e. the browser reloaded or came back later
		assertPollyError(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId).put("voteOrder", order), me),
				PollyError.ALREADY_VOTED, "one passkey means one vote");
	}

	@Test
	@DisplayName("Different people each get their own vote")
	public void everybodyGetsOneVote() {
		String publicId = createStandardPolly(asNewPerson()).getString("data.createPolly.publicId");

		for (int i = 0; i < 3; i++) {
			String friend = asNewPerson();
			List<String> order = proposalIds(okData(send(GET_POLLY, new Lson("publicId", publicId), friend)), "polly");
			okData(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId).put("voteOrder", order), friend));
		}

		// numBallots is shown to everyone, not only the owner
		JsonPath asStranger = okData(send(GET_POLLY, new Lson("publicId", publicId), null));
		assertEquals(3, asStranger.getInt("data.polly.numBallots"));
	}

	@Test
	@DisplayName("Voting without a passkey is refused, even on a polly that exists")
	public void votingNeedsAPasskey() {
		JsonPath created = createStandardPolly(asNewPerson());
		String publicId = created.getString("data.createPolly.publicId");

		// NEED_PASSKEY, not POLLY_NOT_FOUND: the session is checked before the lookup
		assertPollyError(send(VOTE_IN_POLLY,
						Lson.builder().put("publicId", publicId).put("voteOrder", proposalIds(created, "createPolly")), null),
				PollyError.NEED_PASSKEY, "no session");
	}

	@Test
	@DisplayName("A vote order that does not fit the polly is refused")
	public void voteOrderIsValidated() {
		String owner = asNewPerson();
		JsonPath created = createStandardPolly(owner);
		String publicId = created.getString("data.createPolly.publicId");
		List<String> valid = proposalIds(created, "createPolly");

		assertPollyError(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId).put("voteOrder", List.of("9999999")), asNewPerson()),
				PollyError.INVALID_POLLY, "an option id from another polly");

		assertPollyError(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId).put("voteOrder", List.<String>of()), asNewPerson()),
				PollyError.INVALID_POLLY, "an empty ballot");

		assertPollyError(send(VOTE_IN_POLLY,
						Lson.builder().put("publicId", publicId).put("voteOrder", List.of(valid.get(0), valid.get(0))), asNewPerson()),
				PollyError.INVALID_POLLY, "the same option ranked twice");
	}

	@Test
	@DisplayName("A voter may rank only some of the options")
	public void partialBallotsAreAllowed() {
		String owner = asNewPerson();
		JsonPath created = createStandardPolly(owner);
		String publicId = created.getString("data.createPolly.publicId");
		String favourite = proposalIdByTitle(created, "createPolly", "Sushi");

		JsonPath voted = okData(send(VOTE_IN_POLLY,
				Lson.builder().put("publicId", publicId).put("voteOrder", List.of(favourite)), asNewPerson()));

		assertEquals(1, voted.getInt("data.voteInPolly.numBallots"));
	}

	@Test
	@DisplayName("The database itself refuses a second ballot from the same voter")
	public void uniqueConstraintIsTheAuthority() {
		// The service checks first for a clean error message, but the constraint is what still
		// holds under a double tap or two concurrent requests. Assert the constraint exists.
		String publicId = createStandardPolly(asNewPerson()).getString("data.createPolly.publicId");
		String sameVoterKey = "aFixedVoterKeyForThisTest" + UUID.randomUUID();

		boolean rejected = false;
		try {
			QuarkusTransaction.requiringNew().run(() -> {
				PollyEntity polly = PollyEntity.findByPublicId(publicId).orElseThrow();
				new PollyBallotEntity(polly, sameVoterKey, List.of(polly.proposals.get(0))).persist();
				new PollyBallotEntity(polly, sameVoterKey, List.of(polly.proposals.get(1))).persist();
				PollyBallotEntity.flush();
			});
		} catch (Exception expected) {
			rejected = true;
		}

		assertTrue(rejected, "UNIQUE (polly_id, voter_key) must reject a second ballot from the same voter");
	}

	// ================================================================= editing

	@Test
	@DisplayName("The owner may fix the wording while nobody has voted")
	public void ownerCanEditBeforeAnyVote() {
		String owner = asNewPerson();
		String publicId = createStandardPolly(owner).getString("data.createPolly.publicId");

		JsonPath edited = okData(send(EDIT_POLLY, Lson.builder()
				.put("publicId", publicId).put("title", "Where shall we eat tonight?")
				.put("proposalTitles", List.of("Pizza", "Ramen")), owner));

		assertEquals("Where shall we eat tonight?", edited.getString("data.editPolly.title"));
		assertEquals(List.of("Pizza", "Ramen"), edited.getList("data.editPolly.proposals.title", String.class));
	}

	@Test
	@DisplayName("Editing is refused once somebody has voted")
	public void editingIsRefusedAfterTheFirstBallot() {
		String owner = asNewPerson();
		JsonPath created = createStandardPolly(owner);
		String publicId = created.getString("data.createPolly.publicId");
		okData(send(VOTE_IN_POLLY,
				Lson.builder().put("publicId", publicId).put("voteOrder", proposalIds(created, "createPolly")), asNewPerson()));

		assertPollyError(send(EDIT_POLLY, Lson.builder()
						.put("publicId", publicId).put("title", "Too late to change this")
						.put("proposalTitles", List.of("A", "B")), owner),
				PollyError.POLLY_ALREADY_STARTED, "a ballot already exists");
	}

	@Test
	@DisplayName("A friend cannot edit someone else's polly")
	public void nonOwnerCannotEdit() {
		String publicId = createStandardPolly(asNewPerson()).getString("data.createPolly.publicId");

		assertPollyError(send(EDIT_POLLY, Lson.builder()
						.put("publicId", publicId).put("title", "Hijacked completely")
						.put("proposalTitles", List.of("A", "B")), asNewPerson()),
				PollyError.NOT_POLLY_OWNER, "not their polly");
	}

	@Test
	@DisplayName("Editing an unknown polly says not-found, before it says anything about ownership")
	public void editingUnknownPollyIsNotFound() {
		assertPollyError(send(EDIT_POLLY, Lson.builder()
						.put("publicId", "doesNotExist").put("title", "x")
						.put("proposalTitles", List.of("A", "B")), asNewPerson()),
				PollyError.POLLY_NOT_FOUND, "not-found is checked before ownership");
	}

	// ================================================================= finishing

	@Test
	@DisplayName("The winner reflects the ballots that were cast")
	public void winnerReflectsTheBallots() {
		String owner = asNewPerson();
		JsonPath created = createStandardPolly(owner);
		String publicId = created.getString("data.createPolly.publicId");
		String sushi = proposalIdByTitle(created, "createPolly", "Sushi");
		List<String> sushiFirst = new ArrayList<>(List.of(sushi));
		proposalIds(created, "createPolly").stream().filter(id -> !id.equals(sushi)).forEach(sushiFirst::add);

		for (int i = 0; i < 2; i++) {
			okData(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId).put("voteOrder", sushiFirst), asNewPerson()));
		}

		JsonPath finished = okData(send(FINISH_POLLY, new Lson("publicId", publicId), owner));

		assertEquals("FINISHED", finished.getString("data.finishPolly.status"));
		assertEquals("Sushi", finished.getString("data.finishPolly.winner.title"));
		assertEquals(2, finished.getInt("data.finishPolly.numBallots"));
		assertNotNull(finished.getString("data.finishPolly.votingEndAt"));
	}

	@Test
	@DisplayName("A polly nobody voted in finishes with no winner rather than an arbitrary one")
	public void finishingWithNoBallotsLeavesNoWinner() {
		String owner = asNewPerson();
		String publicId = createStandardPolly(owner).getString("data.createPolly.publicId");

		JsonPath finished = okData(send(FINISH_POLLY, new Lson("publicId", publicId), owner));

		assertEquals("FINISHED", finished.getString("data.finishPolly.status"));
		assertNull(finished.get("data.finishPolly.winner"), "with no ballots there is nothing to declare");
	}

	@Test
	@DisplayName("A tie is broken deterministically by the order the options were typed")
	public void tiesAreBrokenDeterministically() {
		String owner = asNewPerson();
		JsonPath created = createStandardPolly(owner);
		String publicId = created.getString("data.createPolly.publicId");
		List<String> forwards = proposalIds(created, "createPolly");
		List<String> backwards = new ArrayList<>(forwards);
		java.util.Collections.reverse(backwards);

		// Two exactly opposed ballots: every duel ends 1-1, so Ranked Pairs locks nothing in and
		// every option is a source of the graph. Without a tie-break the winner would be
		// whatever a HashSet happened to yield first.
		okData(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId).put("voteOrder", forwards), asNewPerson()));
		okData(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId).put("voteOrder", backwards), asNewPerson()));

		JsonPath finished = okData(send(FINISH_POLLY, new Lson("publicId", publicId), owner));

		assertEquals(OPTIONS.get(0), finished.getString("data.finishPolly.winner.title"),
				"a tie must resolve to the first option listed, every time");
	}

	@Test
	@DisplayName("A friend cannot finish someone else's polly")
	public void nonOwnerCannotFinish() {
		String publicId = createStandardPolly(asNewPerson()).getString("data.createPolly.publicId");

		assertPollyError(send(FINISH_POLLY, new Lson("publicId", publicId), asNewPerson()),
				PollyError.NOT_POLLY_OWNER, "not their polly");
	}

	@Test
	@DisplayName("A finished polly takes no more votes, and no more edits")
	public void finishedPollyIsClosed() {
		String owner = asNewPerson();
		JsonPath created = createStandardPolly(owner);
		String publicId = created.getString("data.createPolly.publicId");
		List<String> order = proposalIds(created, "createPolly");
		okData(send(FINISH_POLLY, new Lson("publicId", publicId), owner));

		assertPollyError(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId).put("voteOrder", order), asNewPerson()),
				PollyError.POLLY_FINISHED, "voting is over");

		assertPollyError(send(EDIT_POLLY, Lson.builder()
						.put("publicId", publicId).put("title", "x").put("proposalTitles", List.of("A", "B")), owner),
				PollyError.POLLY_FINISHED, "editing is over");
	}

	@Test
	@DisplayName("Finishing twice is harmless - a stale tab should not see an error")
	public void finishingIsIdempotent() {
		String owner = asNewPerson();
		String publicId = createStandardPolly(owner).getString("data.createPolly.publicId");
		String firstEndAt = okData(send(FINISH_POLLY, new Lson("publicId", publicId), owner))
				.getString("data.finishPolly.votingEndAt");

		JsonPath again = okData(send(FINISH_POLLY, new Lson("publicId", publicId), owner));

		assertEquals("FINISHED", again.getString("data.finishPolly.status"));
		assertEquals(firstEndAt, again.getString("data.finishPolly.votingEndAt"), "must not move the end time");
	}

	@Test
	@DisplayName("A finished polly stays readable through the same link")
	public void finishedPollyStaysReadable() {
		String owner = asNewPerson();
		JsonPath created = createStandardPolly(owner);
		String publicId = created.getString("data.createPolly.publicId");
		okData(send(VOTE_IN_POLLY,
				Lson.builder().put("publicId", publicId).put("voteOrder", proposalIds(created, "createPolly")), owner));
		okData(send(FINISH_POLLY, new Lson("publicId", publicId), owner));

		JsonPath seen = okData(send(GET_POLLY, new Lson("publicId", publicId), null));

		assertEquals(QUESTION, seen.getString("data.polly.title"));
		assertEquals("FINISHED", seen.getString("data.polly.status"));
		assertNotNull(seen.getString("data.polly.winner.title"));
	}

	// ================================================================= myPollys

	@Test
	@DisplayName("A passkey finds back everything it created, and nothing it did not")
	public void myPollysIsScopedToTheOwner() {
		String me = asNewPerson();
		createPolly(me, "First question here", List.of("A", "B"));
		createPolly(me, "Second question here", List.of("C", "D"));

		List<String> mine = okData(send(MY_POLLYS, null, me)).getList("data.myPollys.title", String.class);
		assertEquals(2, mine.size());
		assertTrue(mine.containsAll(List.of("First question here", "Second question here")));

		// somebody else's passkey sees none of them
		assertEquals(0, okData(send(MY_POLLYS, null, asNewPerson())).getList("data.myPollys").size());
	}

	@Test
	@DisplayName("myPollys needs a passkey")
	public void myPollysNeedsAPasskey() {
		assertPollyError(send(MY_POLLYS, null, null), PollyError.NEED_PASSKEY, "no session");
	}

	// ================================================================= the passkey ceremony

	/*
	 * Only the two challenge endpoints are testable here. Verifying an attestation or an assertion
	 * needs a real authenticator, which a headless test has no way to drive - those legs have to be
	 * walked by hand on real hardware, against a real hostname. What these two do cover is that the
	 * endpoints are reachable with no session at all and that they ask for the right things.
	 */

	@Test
	@DisplayName("Login options are usernameless, so the share link needs no secret")
	public void loginChallengeOffersEveryPasskey() {
		var res = given().when().get(TestFixtures.LIQUIDO_API + "/polly/webauthn/login-options-challenge")
				.then().statusCode(200);

		JsonPath options = res.extract().jsonPath();
		assertNotNull(options.getString("challenge"), "no challenge in the login options");
		// Empty allowCredentials is the whole trick: the browser offers whatever passkey it holds
		// for this domain, so nobody has to be named and the link carries nothing secret.
		List<?> allowCredentials = options.getList("allowCredentials");
		assertTrue(allowCredentials == null || allowCredentials.isEmpty(),
				"allowCredentials must be empty for a usernameless login, was: " + allowCredentials);

		// The challenge comes back in a cookie, which is why the frontend sends withCredentials.
		assertFalse(res.extract().cookies().isEmpty(), "the challenge cookie must be set");
	}

	@Test
	@DisplayName("Registration options mint a unique user handle, callable with no session")
	public void registerChallengeMintsAUniqueHandle() {
		var first = given().when().get(TestFixtures.LIQUIDO_API + "/polly/webauthn/register-options-challenge")
				.then().statusCode(200).extract();
		var second = given().when().get(TestFixtures.LIQUIDO_API + "/polly/webauthn/register-options-challenge")
				.then().statusCode(200).extract();

		assertNotNull(first.jsonPath().getString("challenge"), "no challenge in the registration options");
		assertNotNull(first.jsonPath().getString("user.id"), "no user handle in the registration options");

		// WebAuthn keys a credential on (rpId, user.id): a shared handle would make a second
		// registration on a device silently replace the first, so one person could never be
		// two voters - and re-registering would destroy an existing identity.
		assertNotEquals(first.jsonPath().getString("user.id"), second.jsonPath().getString("user.id"),
				"every registration must get its own user handle");

		// ... and the handle rides a cookie to the register call, since the attestation does not echo it
		assertNotNull(first.cookie(PollyWebAuthnRestApi.USER_HANDLE_COOKIE),
				"the user handle cookie must be set alongside the challenge");
	}

	// ================================================================= devLoginPolly

	private static final String DEV_LOGIN_POLLY =
			"query devLoginPolly($devLoginToken: String!, $credentialId: String!) { devLoginPolly(devLoginToken: $devLoginToken, credentialId: $credentialId) }";

	/** Matches liquido.dev-login-token in config/application-test.properties. */
	private static final String TEST_DEV_LOGIN_TOKEN = "devLoginTokenTest";

	@Test
	@DisplayName("devLoginPolly mints a usable session, so e2e tests need no authenticator")
	public void devLoginPollyMintsAWorkingSession() {
		JsonPath res = okData(send(DEV_LOGIN_POLLY, Lson.builder()
				.put("devLoginToken", TEST_DEV_LOGIN_TOKEN).put("credentialId", "devLoginCredential-" + UUID.randomUUID()), null));
		String jwt = res.getString("data.devLoginPolly");
		assertNotNull(jwt);

		// the minted token must be a real polly session, not merely well formed
		JsonPath created = createPolly(jwt, QUESTION, OPTIONS);
		assertTrue(created.getBoolean("data.createPolly.isOwner"));
	}

	@Test
	@DisplayName("devLoginPolly refuses a wrong or missing token")
	public void devLoginPollyIsTokenGuarded() {
		assertPollyError(send(DEV_LOGIN_POLLY, Lson.builder()
						.put("devLoginToken", "notTheDevLoginToken").put("credentialId", "someCredential"), null),
				PollyError.NEED_PASSKEY, "a wrong dev login token must not mint a session");

		assertPollyError(send(DEV_LOGIN_POLLY, Lson.builder()
						.put("devLoginToken", "").put("credentialId", "someCredential"), null),
				PollyError.NEED_PASSKEY, "an empty dev login token must not mint a session");
	}

	@Test
	@DisplayName("Two devLogin credential ids are two different people")
	public void devLoginPollyCredentialIdIsTheIdentity() {
		String alice = devLogin("alice-" + UUID.randomUUID());
		String bob = devLogin("bob-" + UUID.randomUUID());

		JsonPath polly = createPolly(alice, QUESTION, OPTIONS);
		String publicId = polly.getString("data.createPolly.publicId");

		assertFalse(okData(send(GET_POLLY, new Lson("publicId", publicId), bob)).getBoolean("data.polly.isOwner"),
				"a different credential id must be a different person");

		// ... and they each get their own vote
		okData(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId)
				.put("voteOrder", proposalIds(polly, "createPolly")), alice));
		okData(send(VOTE_IN_POLLY, Lson.builder().put("publicId", publicId)
				.put("voteOrder", proposalIds(polly, "createPolly")), bob));
		assertEquals(2, okData(send(GET_POLLY, new Lson("publicId", publicId), null)).getInt("data.polly.numBallots"));
	}

	private String devLogin(String credentialId) {
		return okData(send(DEV_LOGIN_POLLY, Lson.builder()
				.put("devLoginToken", TEST_DEV_LOGIN_TOKEN).put("credentialId", credentialId), null))
				.getString("data.devLoginPolly");
	}

	// ================================================================= isolation from the team app

	@Test
	@DisplayName("A polly token is worthless against the team API")
	public void pollyTokenCannotReachTheTeamApi() {
		String pollyJwt = asNewPerson();

		// LIQUIDO_POLLY is deliberately not LIQUIDO_USER, so every @RolesAllowed endpoint rejects it
		ValidatableResponse res = send("query polls { polls { id } }", null, pollyJwt);

		res.body("errors", DescribedAs.describedAs(
				"a polly passkey must not open a team endpoint", not(empty())));
		res.body("data.polls", nullValue());
	}

	@Test
	@DisplayName("A team token is not a polly session")
	public void teamTokenIsNotAPollySession() {
		// A perfectly valid team JWT. If PollySession only checked "is there a token", a logged-in
		// team member would silently become the owner of pollys keyed on their email address.
		String teamJwt = Jwt.subject("someone@liquido.vote")
				.issuer(JwtTokenUtils.LIQUIDO_ISSUER)
				.groups(Set.of(JwtTokenUtils.LIQUIDO_USER_ROLE))
				.claim(JwtTokenUtils.TEAM_ID_CLAIM, "1")
				.expiresIn(3600)
				.sign();

		assertPollyError(send(CREATE_POLLY, Lson.builder().put("title", QUESTION).put("proposalTitles", OPTIONS), teamJwt),
				PollyError.NEED_PASSKEY, "a team session is not a polly session");
	}

	@Test
	@DisplayName("An ordinary polly error is not dressed up as a system error")
	public void pollyErrorsCarryNoBogusSystemError() {
		ValidatableResponse res = send(GET_POLLY, new Lson("publicId", "doesNotExist"), null);

		res.body("errors[0].extensions.pollyErrorCode", is(PollyError.POLLY_NOT_FOUND.name()));
		// LiquidoErrorExtensionProvider must stay out of the way, or every NEED_PASSKEY would be
		// logged as an internal error and carry "This should not have happened :-(".
		res.body("errors[0].extensions.liquidoException", nullValue());
	}
}
