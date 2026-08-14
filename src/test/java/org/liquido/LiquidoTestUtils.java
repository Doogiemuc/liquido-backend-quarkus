package org.liquido;

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.liquido.model.LiquidoBaseEntity;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMemberEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.Lson;
import org.liquido.vote.BallotEntity;
import org.liquido.vote.CastVoteResponse;

import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.liquido.TestFixtures.*;

/**
 * Helpers for driving LIQUIDO over its real GraphQL API from tests.
 *
 * <h1>The seed contract</h1>
 *
 * Most tests here do not build their own fixtures. They reuse the shared seed that
 * {@link TestDataCreator} produces -- a full team with members, polls in every state, proposals and
 * ballots -- because re-deriving that per test would be far slower. That trade only stays safe if
 * everybody honours two rules:
 *
 * <ol>
 *   <li><b>You may APPEND to the seed team.</b> New polls, proposals, likes, ballots, your own voter
 *       tokens -- all fine, and it must stay fine. Nothing may depend on the seed team's exact counts.</li>
 *   <li><b>You may NOT change the identity or relationships of seed rows.</b> Delegations, team
 *       membership, passwords, {@code lastTeamId}. Those alter other tests' preconditions rather than
 *       adding to them. Build a {@link #createFreshTeam(String)} team instead -- see
 *       {@code UseCaseTests.proxyCastsVoteForVoter} for the pattern.
 *       <br>Remember: {@code @TestTransaction} does <b>not</b> roll back anything you did over HTTP.</li>
 * </ol>
 *
 * And on the reading side: <b>ask for the row you mean, by name</b> ({@link #getSeedTeam()},
 * {@link #getSeedAdmin()}, …) -- never "the first row the database happens to return". That is what
 * makes the leftovers from {@code createFreshTeam} harmless, and why nothing here cleans the DB up.
 *
 * {@code SeedContractTests} asserts all of this, so a violation fails with a message about the seed
 * instead of surfacing three layers away in an unrelated test.
 */
@Slf4j
@ApplicationScoped
public class LiquidoTestUtils {

	@Inject
	LiquidoConfig config;

	Random rand = new Random();

	// ========= Create and join team (via GraphQl) ==============

	public TeamDataResponse createTeam(String teamName, String adminEmail, int numMembers) {
		return createTeam(teamName, adminEmail, "0151 555 " + now % 1000000, numMembers);
	}

	/**
	 * Same as {@link #createTeam(String, String, int)} but with an explicit admin mobilephone.
	 * Needed when the seed creates more than one team in a single run: the 3-arg version derives the
	 * phone number from the fixed {@link TestFixtures#now}, so a second call would collide with
	 * USER_MOBILEPHONE_EXISTS. Still reserved for TestDataCreator's deterministic seeding -- tests that
	 * just need their own throwaway team should use {@link #createFreshTeam(String)}.
	 */
	public TeamDataResponse createTeam(String teamName, String adminEmail, String adminMobilephone, int numMembers) {
		if (teamName == null) teamName = "TestTeam" + now;
		log.info("Creating new team "+teamName);
		if (adminEmail == null) adminEmail = "testadmin" + now + "@liquido.vote";
		Lson admin = Lson.builder()
				.put("name", "TestAdmin " + now)
				.put("email", adminEmail)
				.put("mobilephone", adminMobilephone)
				.put("picture", "Avatar1.png");

		// WHEN creating a new team via GraphQL
		String query = "mutation createNewTeam($teamName: String!, $admin: UserEntityInput!, $password: String!) { " +
				" createNewTeam(teamName: $teamName, admin: $admin, password: $password) " + CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson variables = Lson.builder()
				.put("teamName", teamName)
				.put("admin", admin)
				.put("password", adminEmail+TestFixtures.PASSWORD_SUFFIX);

		String body = String.format("{ \"query\": \"%s\", \"variables\": %s }", query, variables);

		TeamDataResponse res = given() //.log().body()
				.contentType(ContentType.JSON)
				.body(body)
				.when()
				.post(GRAPHQL_URI)
				.then()
				.statusCode(200)  // But be careful: GraphQL always returns 200, so we need to
				.body("errors", anyOf(nullValue(), hasSize(0)))		// check for no GraphQL errors: []
				.body("data.createNewTeam.team.teamName", is(teamName))
				.body("data.createNewTeam.user.id", greaterThan(0))
				.body("data.createNewTeam.user.email", equalToIgnoringCase(adminEmail))
				.extract().jsonPath().getObject("data.createNewTeam", TeamDataResponse.class);

		// Add further members that join this team
		for (int i = 0; i < numMembers; i++) {
			joinTeam(res.team.inviteCode, "membr"+now+i+"@liquido.vote");
		}

		return res;
	}

	/**
	 * Create a brand-new, fully isolated team with just its admin -- no members, no shared state.
	 * Unlike {@link #createTeam}, which is reserved for TestDataCreator's one-time seeding and reuses
	 * a fixed mobile phone number tied to {@link org.liquido.TestFixtures#now}, this uses a fresh
	 * timestamp-based phone number so it's safe to call as many times as needed within one test run
	 * (e.g. from regression tests that need their own team, without touching the shared seeded one).
	 * @param teamNamePrefix prefix for the team/admin name and email, made unique with a timestamp
	 * @return TeamDataResponse for the new admin
	 */
	public TeamDataResponse createFreshTeam(String teamNamePrefix) {
		long unique = new Date().getTime();
		String adminEmail = teamNamePrefix.toLowerCase() + unique + "@liquido.vote";
		Lson admin = Lson.builder()
				.put("name", teamNamePrefix + " Admin")
				.put("email", adminEmail)
				.put("mobilephone", "0151 555 " + unique)
				.put("picture", "Avatar1.png");
		String query = "mutation createNewTeam($teamName: String!, $admin: UserEntityInput!, $password: String!) { " +
				" createNewTeam(teamName: $teamName, admin: $admin, password: $password) " + CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson variables = Lson.builder()
				.put("teamName", teamNamePrefix + unique)
				.put("admin", admin)
				.put("password", adminEmail + PASSWORD_SUFFIX);
		return sendGraphQL(query, variables)
				.extract().jsonPath().getObject("data.createNewTeam", TeamDataResponse.class);
	}

	public TeamDataResponse joinTeam(String inviteCode, String memberEmail) {
		long now = new Date().getTime();
		if (memberEmail == null) memberEmail = "member" + now + "@liquido.vote";
		Lson member = Lson.builder()
				.put("name", "Member " + now)
				.put("email", memberEmail)
				.put("mobilephone", "0151 555 " + now)
				.put("picture", "Avatar1.png");

		// a new user joins an existing team
		String query = "mutation joinTeam($inviteCode: String!, $member: UserEntityInput!, $password: String!) { " +
				"joinTeam(inviteCode: $inviteCode, member: $member, password: $password) " + CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson variables = Lson.builder()
				.put("inviteCode", inviteCode)
				.put("member", member)
				.put("password", memberEmail+ PASSWORD_SUFFIX);

		String body = String.format("{ \"query\": \"%s\", \"variables\": %s }", query, variables);

		TeamDataResponse res = given()  // .log().body()
				.contentType(ContentType.JSON)
				.body(body)
				.when()
				.post(GRAPHQL_URI)
				.then()
				.body("data.joinTeam.team.inviteCode", is(inviteCode))
				.body("data.joinTeam.user.id", greaterThan(0))
				.body("data.joinTeam.user.email", equalToIgnoringCase(memberEmail))
				.extract().jsonPath().getObject("data.joinTeam", TeamDataResponse.class);

		log.debug("User joined team " + res.team.getTeamName() + ": " + res.user.toStringShort());
		return res;
	}

	/**
	 * An <b>already registered</b> user joins a <b>further</b> team.
	 *
	 * Two things make this different from {@link #joinTeam(String, String)}, which registers a
	 * brand-new user anonymously:
	 * <ol>
	 *   <li>The request is authenticated with the user's JWT, so the backend takes the
	 *       "already logged in" branch of {@code TeamGraphQL.joinTeam} instead of creating a new user.</li>
	 *   <li>It must present exactly the email AND mobilephone the user is already registered with.
	 *       {@code TeamGraphQL.assertProvidedIdentityMatches()} rejects anything else - a different
	 *       mobilephone, or a missing one when the user has one.</li>
	 * </ol>
	 *
	 * The returned JWT is scoped to the newly joined team, so use it for any follow-up call that is
	 * meant to act inside that team (creating proposals, fetching a voterToken, casting a vote).
	 *
	 * @param inviteCode inviteCode of the team to join
	 * @param registeredUser the user as returned by an earlier createTeam / joinTeam / devLogin
	 * @param jwt that user's current JWT
	 * @return TeamDataResponse for that user in their new team
	 */
	public TeamDataResponse joinTeamAsRegisteredUser(String inviteCode, UserEntity registeredUser, String jwt) {
		Lson member = Lson.builder()
				.put("name", registeredUser.getName())
				.put("email", registeredUser.getEmail())
				.put("mobilephone", registeredUser.getMobilephone());

		String query = "mutation joinTeam($inviteCode: String!, $member: UserEntityInput!, $password: String!) { " +
				"joinTeam(inviteCode: $inviteCode, member: $member, password: $password) " + CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson variables = Lson.builder()
				.put("inviteCode", inviteCode)
				.put("member", member)
				// Not used on this path - the JWT authenticates the caller and the existing account is
				// reused as-is - but the mutation declares password as non-null.
				.put("password", registeredUser.getEmail() + PASSWORD_SUFFIX);

		TeamDataResponse res = sendGraphQL(query, variables, jwt)
				.body("data.joinTeam.team.inviteCode", is(inviteCode))
				.body("data.joinTeam.user.email", equalToIgnoringCase(registeredUser.getEmail()))
				.extract().jsonPath().getObject("data.joinTeam", TeamDataResponse.class);

		log.info("Already registered user {} joined a FURTHER team: {}", res.user.toStringShort(), res.team.getTeamName());
		return res;
	}

	// ============== Create Poll with proposals ================


	/**
	 * Create a new poll. MUST be logged in for this!
	 * @param title title for the poll
	 * @param jwt JsonWebToken of an admin
	 * @return the newly created poll
	 */
	public PollEntity createPoll(String title, String jwt) {
		// WHEN creating a Poll
		String query = "mutation createPoll($title: String!)" +
				"{ createPoll(title: $title) " + JQL_POLL + " }";
		Lson vars = new Lson("title", title);
		return sendGraphQL(query, vars, jwt)
				.body("data.createPoll.title", is(title))
				.extract().jsonPath().getObject("data.createPoll", PollEntity.class);
	}

	/**
	 * Top a team up to at least {@code requiredMembers} members, by letting new users join it.
	 *
	 * <p>This was inverted until 2026-08-14: the condition read {@code if (requiredMembers < numMembers)},
	 * i.e. "add members when we already have more than we need". So it was a no-op in the one place it
	 * is called -- {@code ensureNumMembers(seedTeam, 10)} on a 7-member team evaluated {@code 10 < 7}
	 * and returned unchanged -- while an {@code ensureNumMembers(team, 3)} on that same team would have
	 * <i>added three more</i>. The seed therefore ran on 7 members while the code read as though it
	 * guaranteed 10, leaving only a 2-member margin over the tightest consumer,
	 * {@code seedRandomProposals(poll, team, 5)}.
	 *
	 * @param teamId the team to top up
	 * @param requiredMembers the minimum number of members the team must end up with
	 * @return the team, reloaded from the server if anybody joined
	 */
	public TeamEntity ensureNumMembers(Long teamId, int requiredMembers) {
		TeamEntity team = TeamEntity.<TeamEntity>findByIdOptional(teamId).orElseThrow(() -> new RuntimeException("No team with id=" + teamId));
		int numMembers = team.members.size();
		if (numMembers >= requiredMembers) return team;

		log.info("Team '{}' has {} members, topping up to {}", team.getTeamName(), numMembers, requiredMembers);
		TeamDataResponse res = null;
		// Add only the SHORTFALL (this used to loop requiredMembers times, overshooting), and index the
		// generated email by the member's final position so repeated top-ups can't collide. The old form
		// concatenated two ints with no separator ("member" + i + numMembers), so i=1,n=17 and i=11,n=7
		// both produced "member117_4711".
		for (int i = numMembers; i < requiredMembers; i++) {
			res = joinTeam(team.getInviteCode(), "member" + i + "_" + now + "@liquido.vote");
		}
		return loadOwnTeam(res.jwt);  //reload team
	}

	private String getRandomIconName() {
		String[] icons = {"grimace", "grin", "grin-alt", "grin-beam", "grin-beam-sweat", "grin-hearts", "grin-squint", "grin-squint-tears", "grin-stars", "grin-tears", "grin-tongue", "grin-tongue-squint", "grin-tongue-wink", "grin-wink", "grip-horizontal", "grip-vertical", "h-square", "hammer", "hamsa", "hand-holding", "hand-holding-heart", "hand-holding-usd", "hand-lizard", "hand-paper", "hand-peace", "hand-point-down", "hand-point-left", "hand-point-right", "hand-point-up", "hand-pointer", "hand-rock", "hand-scissors", "hand-spock", "hands", "hands-helping", "handshake", "hanukiah", "hashtag", "hat-wizard", "hdd", "headphones"};
		return icons[rand.nextInt(icons.length)];
	}

	public static final String lorem = "Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet.";

	private String loremIpsum(int len) {
		return lorem.substring(0, Math.min(len, lorem.length()));
	}

	/**
	 * Seed some random proposals. Each proposal will be created by one member of the team.
	 * @param poll
	 * @param team
	 * @param numProposals
	 * @return the poll which now has numProposals
	 */
	public PollEntity seedRandomProposals(PollEntity poll, TeamEntity team, int numProposals) {
		//Test Precondition: Make sure that there are enough members in the poll's team.
		// Each proposal needs its own author: PollService.addProposalToPoll allows a non-admin only ONE
		// proposal per poll.
		int numMembers = team.getMembers().size();   // poll.getTeam()  is not filled here in the client!
		if (numMembers < numProposals) {
			// Deliberately NOT team.toString(): TeamEntity.toString() calls getFirstAdmin(), which
			// orElseThrow()s - so a malformed team would blow up while formatting the error about itself.
			throw new RuntimeException("Cannot seed " + numProposals + " proposals, because there are only " +
					numMembers + " members in team '" + team.getTeamName() + "' (id=" + team.getId() + ")");
		}
		// Sorted by id, not raw HashSet order: TeamMemberEntity hashes on its id, so adding a member
		// rehashes the set and silently changes which people author which proposals.
		List<UserEntity> users = team.getMembers().stream()
				.map(TeamMemberEntity::getUser)
				.sorted(Comparator.comparing(u -> u.id))
				.toList();
		for (int i = 0; i < numProposals; i++) {
			String title = "Test Proposal " + i + "_" + now + loremIpsum(20);
			String description = "Proposal " + i + "_" + now + " from TestDataCreator. " + loremIpsum(rand.nextInt(500));
			String icon = getRandomIconName();
			UserEntity author = users.get(i);
			// Pin the login to THIS team. Without pinning, an author who belongs to several teams is
			// logged into whichever they joined last, and the addProposal below fails the team-scoping
			// check with a "Poll(id=…) not found" that names neither the user nor the team.
			TeamDataResponse res = devLoginInto(author.getEmail(), team.getId());
			assertLoggedIntoTeam(res, team, author);   // belt and braces: the guard also documents the trap
			poll = addProposal(poll.getId(), title, description, icon, res.jwt);
		}
		return poll;
	}

	/**
	 * Guard against the single nastiest failure mode in this suite.
	 *
	 * {@code devLogin} logs a user into their {@code lastTeamId}, and {@code joinTeam} rewrites that on
	 * every join. So as soon as one member of a team also belongs to another team, logging them in here
	 * yields a session scoped to the <i>wrong</i> team -- and the very next call, {@code addProposal},
	 * fails the team-scoping check in {@code PollService.getPollInCurrentTeam} with
	 * {@code Poll(id=…) not found}. That message names neither the user nor either team, and because
	 * authors are picked positionally it only fires on some runs. It has cost real debugging time.
	 *
	 * Checking here turns it into an immediate, self-explaining failure at the actual cause.
	 */
	private void assertLoggedIntoTeam(TeamDataResponse res, TeamEntity expectedTeam, UserEntity author) {
		if (res.team == null || !Objects.equals(res.team.getId(), expectedTeam.getId())) {
			throw new IllegalStateException(
					"devLogin(" + author.getEmail() + ") returned a session for team " +
					(res.team == null ? "null" : "'" + res.team.getTeamName() + "' (id=" + res.team.getId() + ")") +
					", but proposals were requested for team '" + expectedTeam.getTeamName() + "' (id=" + expectedTeam.getId() + ")." +
					"\n  That user belongs to more than one team and their lastTeamId points elsewhere." +
					"\n  Either use devLoginInto(email, teamId) to pin the team, or keep multi-team users out of this team.");
		}
	}

	public PollEntity addProposal(Long pollId, String propTitle, String propDescription, String propIcon, String jwt) {
		String query = "mutation addProposal($pollId: BigInteger!, $title: String!, $description: String!, $icon: String!) { " +
				"addProposal(pollId: $pollId, title: $title, description: $description, icon: $icon) " + JQL_POLL + "}";
		Lson vars = Lson.builder()
				.put("pollId", pollId)
				.put("title", propTitle)
				.put("description", propDescription)
				.put("icon", propIcon);

		return sendGraphQL(query, vars, jwt)
				.log().all()
				//TODO: https://stackoverflow.com/questions/64167768/restassured-unrecognized-field-not-marked-as-ignorable
				.extract().jsonPath().getObject("data.addProposal", PollEntity.class);
	}

	// ============= startVotingPhase, cast a vote, endVotingPhase

	public PollEntity startVotingPhase(Long pollId, String jwt) {
		String startVotingPhaseQuery = "mutation startVotingPhase($pollId: BigInteger!) {" +
				" startVotingPhase(pollId: $pollId) " + JQL_POLL + " }";
		Lson vars = new Lson("pollId", pollId);
		return sendGraphQL(startVotingPhaseQuery, vars, jwt)
				.extract().jsonPath().getObject("data.startVotingPhase", PollEntity.class);
	}


	public String getVoterToken(Long pollId, String jwt) {
		String query = "query voterToken($pollId: BigInteger!) { " +
				" voterToken(pollId: $pollId) }";
		Lson vars  = Lson.builder()
				.put("pollId", pollId);
		ValidatableResponse graphQlRes = sendGraphQL(query, vars, jwt);
		log.debug("graphQlRes" + graphQlRes);
		String voterToken = graphQlRes.extract().jsonPath().getObject("data.voterToken", String.class);
		log.debug("Got voter Token: {}", voterToken);
		return voterToken;
	}

	public CastVoteResponse castVote(Long pollId, List<Long> voteOrderIds, String voterToken) {
		String castVoteQuery = "mutation castVote($pollId: BigInteger!, $voteOrderIds: [BigInteger!]!, $voterToken: String!) { " +
				"  castVote(pollId: $pollId, voteOrderIds: $voteOrderIds, voterToken: $voterToken) " +
				"  { voteCount ballot { id level checksum voteOrder { id } } } " +
				"}";
		Lson castVoteVars = Lson.builder()
				.put("pollId", pollId)
				.put("voteOrderIds", voteOrderIds)
				.put("voterToken", voterToken);

		CastVoteResponse castVoteResponse = sendGraphQL(castVoteQuery, castVoteVars)  // anonymous! no JWT!
				.log().all()
				.body("data.castVote.ballot.checksum", matchesRegex("[a-zA-Z0-9]{5,}"))  // or with hamcrest, but would need custom matcher to check min length of string: allOf(IsInstanceOf.any(String.class), is(not(emptyString())))
				.extract().jsonPath().getObject("data.castVote", CastVoteResponse.class);

		List<Long> returnedVoteOrderIds = castVoteResponse.getBallot().getVoteOrder().stream().map(LiquidoBaseEntity::getId).toList();
		assert returnedVoteOrderIds.equals(voteOrderIds) : "vote did not return same list of voteOrderIDs";

		return castVoteResponse;
	}


	public BallotEntity getBallotOfCurrentUser(Long pollId, String jwt) {
		String myBallotQuery = "query myBallot($pollId: BigInteger!) { " +
				"  myBallot(pollId: $pollId) " + JQL_BALLOT + "}";
		Lson myBallotVars = Lson.builder()
				.put("pollId", pollId);
		return sendGraphQL(myBallotQuery, myBallotVars, jwt)
				.extract().jsonPath().getObject("data.myBallot", BallotEntity.class);
	}



	public BallotEntity verifyBallot(Long pollId, String checksum) {
		String verifyBallotQuery = "query verifyBallot($pollId: BigInteger!, $checksum: String!) { " +
				"  verifyBallot(pollId: $pollId, checksum: $checksum) " + JQL_BALLOT + "}";
		Lson verifyBallotVars = Lson.builder()
				.put("pollId", pollId)
				.put("checksum", checksum);
		return sendGraphQL(verifyBallotQuery, verifyBallotVars)
				.body("data.verifyBallot.checksum", is(checksum))
				.extract().jsonPath().getObject("data.verifyBallot", BallotEntity.class);
	}

	public ProposalEntity finishVotingPhase(Long pollId, String jwt) {
		String startVotingPhaseQuery = "mutation finishVotingPhase($pollId: BigInteger!) {" +
				" finishVotingPhase(pollId: $pollId) " + JQL_PROPOSAL + " }";
		Lson vars = new Lson("pollId", pollId);
		return sendGraphQL(startVotingPhaseQuery, vars, jwt)
				.extract().jsonPath().getObject("data.finishVotingPhase", ProposalEntity.class);
	}

	// ============ Delegations

	public void delegateTo(UserEntity proxy, String jwt) {
		String startVotingPhaseQuery = "mutation delegateTo($proxyId: BigInteger!) {" +
				" delegateTo(proxyId: $proxyId) }";
		Lson vars = new Lson("proxyId", proxy.id);
		sendGraphQL(startVotingPhaseQuery, vars, jwt);
	}

	/** IDs of the delegation requests waiting for the currently logged-in proxy to accept. */
	public List<Long> getDelegationRequestIds(String proxyJwt) {
		String query = "query { delegationRequests { id } }";
		return sendGraphQL(query, null, proxyJwt)
				.extract().jsonPath().getList("data.delegationRequests.id", Long.class);
	}

	public void acceptDelegationRequests(List<Long> delegationRequestIds, String proxyJwt) {
		String query = "mutation acceptDelegationRequests($ids: [BigInteger!]!) { acceptDelegationRequests(delegationRequestIds: $ids) }";
		Lson vars = new Lson("ids", delegationRequestIds);
		sendGraphQL(query, vars, proxyJwt);
	}

	// ============ Smaller Utility Methods

	public PollEntity likeProposal(PollEntity poll, Long propId, String jwt) {
		Iterator<ProposalEntity> it = poll.getProposals().iterator();
		ProposalEntity prop = null;
		while(it.hasNext()) {
			prop = it.next();
			if (prop.id.equals(propId)) break;
		}
		if (prop == null) throw new RuntimeException("Cannot find prop.id="+prop.id+" in "+poll);

		String query = "mutation likeProposal($pollId: BigInteger!, $proposalId: BigInteger! ) {" +
				" likeProposal(pollId: $pollId, proposalId: $proposalId) " + JQL_POLL + " }";
		Lson vars = new Lson("pollId", poll.id).put("proposalId", propId);
		return sendGraphQL(query, vars, jwt)
				.extract().jsonPath().getObject("data.likeProposal", PollEntity.class);
	}


	/**
	 * Login a user via mocked devLogin, into their <b>last</b> team.
	 *
	 * For a user who belongs to more than one team this is ambiguous - "last" means whichever team they
	 * most recently joined. If your test cares which team the session is scoped to (anything that then
	 * touches a poll, a voterToken or a ballot does), use {@link #devLoginInto(String, Long)}.
	 */
	public TeamDataResponse devLogin(@NonNull String email) {
		return devLoginInto(email, null);
	}

	/**
	 * Login a user via mocked devLogin, pinned to <b>one specific team</b>.
	 *
	 * Use this whenever the user might be in several teams and the following calls are team-scoped.
	 * The backend verifies membership, so this cannot log anyone into a team they don't belong to -
	 * it answers CANNOT_LOGIN_USER_NOT_MEMBER_OF_TEAM instead.
	 *
	 * @param email a registered email
	 * @param teamId the team to log into, or null for the user's last team
	 */
	public TeamDataResponse devLoginInto(@NonNull String email, Long teamId) {
		String query = "query devLogin($email: String!, $devLoginToken: String!, $teamId: BigInteger) { " +
				"  devLogin(email: $email, devLoginToken: $devLoginToken, teamId: $teamId) " + CREATE_OR_JOIN_TEAM_RESULT +
				"}";
		Lson vars = Lson.builder()
				.put("email", email)
				.put("teamId", teamId)
				.put("devLoginToken", config.devLoginTokenOpt().orElseThrow(
						() -> new RuntimeException("Error int test.devLogin(): No devLogin defined in application.properties!")
				));
		ValidatableResponse res = sendGraphQL(query, vars)
				.body("data.devLogin.user.email", equalToIgnoringCase(email));
		if (teamId != null) res.body("data.devLogin.team.id", is(teamId.intValue()));
		return res.extract().jsonPath().getObject("data.devLogin", TeamDataResponse.class);
	}

	public TeamEntity loadOwnTeam(@NonNull String jwt) {
		return sendGraphQL("query { team " + JQL_TEAM + "}", null, jwt)
				.extract().jsonPath().getObject("data.team", TeamEntity.class);
	}

	// =================== Reaching into the seed ======================================
	//
	// These replaced getRandomTeam() / getRandomAdmin() / getRandomUser(), which were
	// `findAll().firstResultOptional()` with NO ORDER BY. Those were not random -- they were
	// "the first row of an unordered scan", i.e. Postgres heap order, which changes whenever a
	// row is UPDATEd. Every login updates a user row (JwtTokenUtils.doLoginInternal), so the
	// answer could drift *within a single suite run*.
	//
	// That coupled every test to every other test's leftovers: the correctly-isolated tests
	// (createFreshTeam) leave behind one-member ADMIN-only teams, which is exactly the shape
	// that fails callers needing >= 2 members or a non-admin MEMBER.
	//
	// Asking by NAME makes residue structurally irrelevant instead of merely unlikely to matter.
	// That is why nothing cleans the database up: nothing reads the garbage.

	/** Reseed command, quoted in the error when the seed is missing, so the failure is self-diagnosing. */
	private static final String RESEED_HINT =
			"\n  Reseed with:\n" +
			"  QUARKUS_HIBERNATE_ORM_SCHEMA_MANAGEMENT_STRATEGY=drop-and-create \\\n" +
			"  QUARKUS_HIBERNATE_ORM_DATABASE_GENERATION=drop-and-create \\\n" +
			"  ./mvnw -B test -Dmaven.surefire.includedGroups=testDataCreator -Dmaven.surefire.excludedGroups=\"\"";

	/** The shared seeded team, by name. Immune to any amount of leftover data from other tests. */
	public TeamEntity getSeedTeam() {
		return TeamEntity.findByTeamName(TestFixtures.teamName)
				.orElseThrow(() -> new RuntimeException("No seed team '" + TestFixtures.teamName + "'." + RESEED_HINT));
	}

	/** The admin of the seed team, by email. */
	public UserEntity getSeedAdmin() {
		return UserEntity.findByEmail(TestFixtures.adminEmail)
				.orElseThrow(() -> new RuntimeException("No seed admin <" + TestFixtures.adminEmail + ">." + RESEED_HINT));
	}

	/** The well-known non-admin member of the seed team, by email. */
	public UserEntity getSeedMember() {
		return UserEntity.findByEmail(TestFixtures.memberEmail)
				.orElseThrow(() -> new RuntimeException("No seed member <" + TestFixtures.memberEmail + ">." + RESEED_HINT));
	}

	/**
	 * Any non-admin MEMBER of the seed team, lowest id first so it is the same one every run.
	 * Use when a test needs "a member of the seed team" rather than one specific person.
	 */
	public UserEntity getSeedTeamMember() {
		TeamEntity seedTeam = getSeedTeam();
		return TeamMemberEntity.<TeamMemberEntity>find("team = ?1 and role = ?2 order by id", seedTeam, TeamMemberEntity.Role.MEMBER)
				.firstResultOptional()
				.map(TeamMemberEntity::getUser)
				.orElseThrow(() -> new RuntimeException(
						"Seed team '" + seedTeam.getTeamName() + "' has no MEMBER (non-admin)." + RESEED_HINT));
	}

	/**
	 * <b>Any</b> user, deterministically the lowest id. Only for tests that need <i>a</i> user and
	 * assert nothing whatsoever about which one -- minting a JWT, exercising an auth path. If your
	 * test cares that the user is in a particular team, or is an admin, use the getSeed* methods.
	 */
	public UserEntity getAnyUser() {
		return UserEntity.<UserEntity>find("order by id").firstResultOptional().orElseThrow(
				() -> new RuntimeException("No user in DB at all." + RESEED_HINT)
		);
	}

	/**
	 * Get the numver of (transitive) delegations to a proxy
	 * @param proxy a proxy user
	 * @return number of delegations to that proxy (0 or more)
	 */
	public long getDelegationCount(@NonNull UserEntity proxy, String jwt) {
		String query = "query delegationCount($proxyId: BigInteger!) { " +
				"  delegationCount(proxyId: $proxyId) }";
		Lson vars = new Lson("proxyId", proxy.id);
		return sendGraphQL(query, vars, jwt)
				.extract().jsonPath().getLong("data.delegationCount");
	}
}