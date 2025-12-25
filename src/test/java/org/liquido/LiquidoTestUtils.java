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

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.liquido.TestFixtures.*;

@Slf4j
@ApplicationScoped
public class LiquidoTestUtils {

	@Inject
	LiquidoConfig config;

	Random rand = new Random();

	// ========= Create and join team (via GraphQl) ==============

	public TeamDataResponse createTeam(String teamName, String adminEmail, int numMembers) {
		if (teamName == null) teamName = "TestTeam" + now;
		log.info("Creating new team "+teamName);
		if (adminEmail == null) adminEmail = "testadmin" + now + "@liquido.vote";
		Lson admin = Lson.builder()
				.put("name", "TestAdmin " + now)
				.put("email", adminEmail)
				.put("mobilephone", "0151 555 " + now % 1000000)
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

	public TeamEntity ensureNumMembers(Long teamId, int requiredMembers) {
		TeamEntity team = TeamEntity.<TeamEntity>findByIdOptional(teamId).orElseThrow(() -> new RuntimeException("No team with id=" + teamId));
		int numMembers = team.members.size();
		if (requiredMembers < numMembers) {
			TeamDataResponse res = null;
			for (int i = 0; i < requiredMembers; i++) {
				res = joinTeam(team.getInviteCode(), "member" + i + numMembers + "_" + now + "@liquido.vote");
			}
			return loadOwnTeam(res.jwt);  //reload team
		} else {
			return team;
		}
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
		//Test Precondition: Make sure that there are enough members in the poll's team
		int numMembers = team.getMembers().size();   // poll.getTeam()  is not filled here in the client!
		if (numMembers < numProposals) {
			throw new RuntimeException("Cannot seed "+numProposals + " proposals, because there are only " + numMembers + " members in "+team);
		}
		List<UserEntity> users = team.getMembers().stream().map(TeamMemberEntity::getUser).toList();
		for (int i = 0; i < numProposals; i++) {
			String title = "Test Proposal " + i + "_" + now + loremIpsum(20);
			String description = "Proposal " + i + "_" + now + " from TestDataCreator. " + loremIpsum(rand.nextInt(500));
			String icon = getRandomIconName();
			TeamDataResponse res = devLogin(users.get(i).getEmail());
			poll = addProposal(poll.getId(), title, description, icon, res.jwt);
		}
		return poll;
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
				"  { voteCount ballot { level checksum voteOrder { id } } } " +
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


	/** Login user into team via mocked devLogin */
	public TeamDataResponse devLogin(@NonNull String email) {
		String query = "query devLogin($email: String!, $devLoginToken: String!) { " +
				"  devLogin(email: $email, devLoginToken: $devLoginToken) " + CREATE_OR_JOIN_TEAM_RESULT +
				"}";
		Lson vars = Lson.builder()
				.put("email", email)
				.put("devLoginToken", config.devLoginTokenOpt().orElseThrow(
						() -> new RuntimeException("Error int test.devLogin(): No devLogin defined in application.properties!")
				));
		return sendGraphQL(query, vars)
				.body("data.devLogin.user.email", equalToIgnoringCase(email))
				.extract().jsonPath().getObject("data.devLogin", TeamDataResponse.class);
	}

	public TeamEntity loadOwnTeam(@NonNull String jwt) {
		return sendGraphQL("query { team " + JQL_TEAM + "}", null, jwt)
				.extract().jsonPath().getObject("data.team", TeamEntity.class);
	}

	public TeamEntity getRandomTeam() {
		return TeamEntity.<TeamEntity>findAll().firstResultOptional()
				.orElseThrow(() -> new RuntimeException("Cannot find any random team!"));
	}

	/**
	 * Get a random user from the DB.
	 * @return a random UserEntity
	 */
	public UserEntity getRandomUser() {
		return UserEntity.<UserEntity>findAll().firstResultOptional().orElseThrow(
				() -> new RuntimeException("Cannot getRandomUser. No user in DB!")
		);
	}

	public UserEntity getRandomAdmin() {
		return TeamMemberEntity.find("role=?1", TeamMemberEntity.Role.ADMIN)
				.firstResultOptional()
				.map(member -> ((TeamMemberEntity)member).getUser())
				.orElseThrow(() -> new RuntimeException("Cannot getRandomAdmin. No admin in DB!")
		);
	}

}