package org.liquido;

import io.agroal.api.AgroalDataSource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.liquido.model.BaseEntity;
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
import org.liquido.vote.RightToVoteEntity;

import java.io.*;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.liquido.TestFixtures.*;

/**
 * Many test cases rely on specific test data as their precondition.
 * This class creates all this test data.
 *
 * Here we use GraphQL calls against our backend in the same way as a client would call it.
 *
 * The result is an SQL script file, that can quickly be imported into the DB for future test runs.
 */
@Slf4j
@Disabled   // <<<<<<==== DO NOT run during regular maven build. Only manually on request
@QuarkusTest
public class TestDataCreator {

	@Inject
	AgroalDataSource dataSource;

	@Inject
	LiquidoConfig config;

	Random rand = new Random();

  String sampleDbFile = "import-testData.sql";

	/**
	 * DANGER ZOME!!! BE CAREFULL!
	 *
	 * TestDataCreateor can delete and re-create everything!
	 */
	boolean purgeDb = true;
	boolean createTestData = true;

	/**
	 * Run through the whole Happy Case:
	 * Register as new member
	 * Register as new admin
	 * Create a poll
	 * Add some proposals to that poll
	 * Start the voting phase
	 * Get a voter token
	 * Cast a vote
	 * Verify the ballot
	 * Finish the voting phase
	 * Check the winning poll
	 */
	@Test
	public void createTestData() {
		String url = "no DB URL!";
		try {
			url = dataSource.getConnection().getMetaData().getURL();

		} catch (SQLException e) {
			log.error("TestDataCreator Cannot connect to DB" + e.getMessage());
		}

		RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

		if (purgeDb) {
			log.warn("Going to delete everything in DB!" + url);
			purgeDb();
		}
		if (createTestData) {
			log.info("Creating testdata in " + url);

			// Create a new team
			TeamDataResponse adminRes = createTeam(teamName, adminEmail, 5);

			// Let another user join that team
			TeamDataResponse memberRes = joinTeam(adminRes.team.inviteCode, memberEmail);

			// Make sure that team has enough members to create more polls & proposals
			adminRes.team = ensureNumMembers(adminRes.team.id, 10);

			// Create some polls in ELABORATION
			PollEntity poll;
			poll = createPoll(pollTitle+"_1 "+now, adminRes.jwt);
			poll = seedRandomProposals(poll, adminRes.team, 3);

			poll = createPoll(pollTitle+"_2 "+now, adminRes.jwt);
			poll = seedRandomProposals(poll, adminRes.team, 4);

			poll = createPoll(pollTitle+"_4 "+now+" with a very long title just for testing", adminRes.jwt);
			poll = seedRandomProposals(poll, adminRes.team, 4);

			// Like a proposal
			ProposalEntity prop = poll.getProposals().stream().findFirst().get();
			poll = likeProposal(poll, prop.id, adminRes.jwt);

			// Create Poll in VOTING with started voting phase
			poll = createPoll(pollTitle+" in voting", adminRes.jwt);
			poll = seedRandomProposals(poll, adminRes.team, 4);
			poll = startVotingPhase(poll.getId(), adminRes.jwt);

			// Create a FINISHED poll
			poll = createPoll(pollTitle+" finished", adminRes.jwt);
			poll = seedRandomProposals(poll, adminRes.team, 5);
			poll = startVotingPhase(poll.getId(), adminRes.jwt);

			// A member casts a vote
			String voterToken = getVoterToken(tokenSecret, memberRes.jwt);
			List<Long> voteOrderIds = poll.getProposals().stream().map(BaseEntity::getId).toList();
			CastVoteResponse castVoteResponse = castVote(poll.id, voteOrderIds, voterToken);

			// Admin also casts a vote
			String adminVoterToken = getVoterToken(tokenSecret, adminRes.jwt);
			CastVoteResponse adminCastVoteResponse = castVote(poll.id, voteOrderIds, adminVoterToken);

			// Verify ballot of admin
			BallotEntity ballot = verifyBallot(poll.getId(), adminCastVoteResponse.getBallot().getChecksum());

			// Finish the voting phase of this poll
			ProposalEntity winner = finishVotingPhase(poll.getId(), adminRes.jwt);

			// Print winner
			log.info("Winner: " + winner.toString());
		}
	}


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
				.put("password", adminEmail+"pwd");

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
		String query = "mutation joinTeam($inviteCode: String, $member: UserEntityInput, $password: String) { " +
				"joinTeam(inviteCode: $inviteCode, member: $member, password: $password) " + CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson variables = Lson.builder()
				.put("inviteCode", inviteCode)
				.put("member", member)
				.put("password", memberEmail+"pwd");

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

	public TeamEntity loadOwnTeam(@NonNull String jwt) {
		return sendGraphQL("query { team " + JQL_TEAM + "}", null, jwt)
				.extract().jsonPath().getObject("data.team", TeamEntity.class);
	}

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

	public PollEntity likeProposal(PollEntity poll, Long propId, String jwt) {
		Iterator<ProposalEntity> it = poll.getProposals().iterator();
		ProposalEntity prop = null;
		while(it.hasNext()) {
			prop = it.next();
			if (prop.id == propId) break;
		}
		if (prop == null) throw new RuntimeException("Cannot find prop.id="+prop.id+" in "+poll);

		String query = "mutation likeProposal($pollId: BigInteger!, $proposalId: BigInteger! ) {" +
				" likeProposal(pollId: $pollId, proposalId: $proposalId) " + JQL_POLL + " }";
		Lson vars = new Lson("pollId", poll.id).put("proposalId", propId);
		return sendGraphQL(query, vars, jwt)
				.extract().jsonPath().getObject("data.likeProposal", PollEntity.class);
	}

	public String getVoterToken(String tokenSecret, String jwt) {
		String query = "query { voterToken(tokenSecret: \\\"" + tokenSecret +  "\\\", becomePublicProxy: false) }";
		String voterToken = sendGraphQL(query, null, jwt)
				.extract().jsonPath().getObject("data.voterToken", String.class);
		log.debug("Got voter Token: "+voterToken);
		return voterToken;
	}

	public PollEntity startVotingPhase(Long pollId, String jwt) {
		String startVotingPhaseQuery = "mutation startVotingPhase($pollId: BigInteger!) {" +
				" startVotingPhase(pollId: $pollId) " + JQL_POLL + " }";
		Lson vars = new Lson("pollId", pollId);
		return sendGraphQL(startVotingPhaseQuery, vars, jwt)
				.extract().jsonPath().getObject("data.startVotingPhase", PollEntity.class);
	}

	static class IsString extends TypeSafeMatcher<String> {
		int minLength = 0;
		public IsString(int minLength) {
			this.minLength = minLength;
		}

		@Override
		protected boolean matchesSafely(String s) {
			if (s == null) return false;
			return s.length() >= this.minLength;
		}

		@Override
		public void describeTo(Description description) {
			description.appendText("a String of length of at least " + this.minLength);
		}

		public static IsString lengthAtLeast(int minLength) {
			return new IsString(minLength);
		}
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

		CastVoteResponse castVoteResponse = sendGraphQL(castVoteQuery, castVoteVars)
				.log().all()
				.body("data.castVote.ballot.checksum", IsString.lengthAtLeast(5))  // allOf(IsInstanceOf.any(String.class), is(not(emptyString())))
				.extract().jsonPath().getObject("data.castVote", CastVoteResponse.class);

		List<Long> returnedVoteOrderIds = castVoteResponse.getBallot().getVoteOrder().stream().map(BaseEntity::getId).toList();
		assert returnedVoteOrderIds.equals(voteOrderIds) : "vote did not return same list of voteOrderIDs";

		return castVoteResponse;
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


	/** Login user into team via mocked devLogin */
	public TeamDataResponse devLogin(@NonNull String email) {
		String query = "query devLogin($email: String!, $devLoginToken: String!) { " +
		    "  devLogin(email: $email, devLoginToken: $devLoginToken) " + CREATE_OR_JOIN_TEAM_RESULT +
				"}";
		Lson vars = Lson.builder()
				.put("email", email)
				.put("devLoginToken", config.devLoginToken());
		return sendGraphQL(query, vars)
				.body("data.devLogin.user.email", equalToIgnoringCase(email))
				.extract().jsonPath().getObject("data.devLogin", TeamDataResponse.class);
	}









	@ConfigProperty(name = "quarkus.datasource.db-kind")
	String dbKind;

	public void extractSql() throws SQLException {
		if ("h2".equals(dbKind)) {       // The `SCRIPT TO` command only works for H2 in-memory DB
			PreparedStatement ps = dataSource.getConnection().prepareStatement("SCRIPT TO '" + sampleDbFile + "'");
			ps.execute();
			//adjustDbInitializationScript();
			log.info("===== Successfully stored test data in file: " + sampleDbFile);
		}
	}

	/**
	 * We need to "massage" the DB generation script a bit:
	 * <p>
	 * (1) We prepend the command <pre>DROP ALL OBJECTS</pre> so that the database is cleaned completely!
	 * <p>
	 * (2) And we need a crude hack for nasty race condition:
	 * <p>
	 * My nice SQL script contains the schema (CREATE TABLE ...) and data (INSERT INTO...) That way I can
	 * very quickly init a DB from scratch.  But TestDataCreator runs after my SpringApp has started.
	 * Our Quartz scheduler is started earlier. It can be configured to create or not create its own
	 * schema. But when I tell it to not create its own schema TestDataCreator runs too late to
	 * create the schema for Quartz.
	 * So I let Quartz create its own stuff and remove any Quarts related lines from my DB script
	 * <p>
	 * The alternative would be do copy the Quartz lines into schema.sql and data.sql
	 * Then I could also recreate Quartz sample data such as jobs.
	 */
	private void adjustDbInitializationScript() {
		log.trace("removeQartzSchema from SQL script: start");
		try {
			File sqlScript = new File(sampleDbFile);
			BufferedReader reader = new BufferedReader(new FileReader(sqlScript));
			List<String> lines = new ArrayList<>();
			String currentLine;
			Boolean removeBlock = false;
			while ((currentLine = reader.readLine()) != null) {
				currentLine = currentLine.trim();
				//log.trace("Checking line "+currentLine);
				if (currentLine.matches("(ALTER|CREATE).*TABLE \"PUBLIC\"\\.\"QRTZ.*\\(")) removeBlock = true;
				if (currentLine.matches("INSERT INTO \"PUBLIC\"\\.\"QRTZ.*VALUES")) removeBlock = true;
				if (removeBlock && currentLine.matches(".*\\); *")) {
					//log.trace("Remove end of block      );");
					removeBlock = false;
					continue;
				}
				if (removeBlock) {
					//log.trace("Removing line from block "+currentLine);
					continue;
				}
				if (currentLine.matches("(ALTER|CREATE).*TABLE \"PUBLIC\"\\.\"QRTZ.*;")) {
					//log.trace("Removing single line:    "+currentLine);
					continue;
				}
				lines.add(currentLine);
			}
			reader.close();

			BufferedWriter writer = new BufferedWriter(new FileWriter(sqlScript));
			writer.write("-- LIQUIDO  H2 Database initialization script\n");
			writer.write("-- This script contains the SCHEMA and TEST DATA\n");
			writer.write("-- BE CAREFUL: This script completely DROPs and RE-CREATES the DB !!!!!\n");
			writer.write("DROP ALL OBJECTS;\n");
			for (String line : lines) {
				writer.write(line);
				writer.newLine();        //  + System.getProperty("line.separator")
			}
			writer.close();
			log.trace("removeQuartzSchema from SQL script successful: " + sqlScript.getAbsolutePath());

		} catch (Exception e) {
			log.error("Could not remove Quarts statements from Schema: " + e.getMessage());
			throw new RuntimeException("Could not remove Quarts statements from Schema: " + e.getMessage(), e);
		}
	}

	@Inject
	EntityManager entityManager;

	@Transactional
	void purgeDb() {
		//TODO: purge only one team
		log.info("================================");
		log.info("       PURGE Test Data");
		log.info("================================");

		// order is important!

		// entityManager::remove  ????
		//BUGFIX: PanacheEntityBase::delete  ignores FK and relations:  https://github.com/quarkusio/quarkus/issues/13941

		BallotEntity.deleteAll();
		RightToVoteEntity.deleteAll();
		//BUGFIX: Cannot delete all proposals as long as they are referenced in a poll.
		//ProposalEntity.deleteAll();

		PollEntity.findAll().stream().forEach(poll -> {
			System.out.println("Going to delete " + poll.toString());
			// Must delete each proposal in this poll individually
			ProposalEntity.find("poll", poll).stream().forEach(PanacheEntityBase::delete);
			poll.delete();
		});

		entityManager.flush();

		TeamMemberEntity.deleteAll();
		TeamEntity.deleteAll();
		UserEntity.deleteAll();

	}

}