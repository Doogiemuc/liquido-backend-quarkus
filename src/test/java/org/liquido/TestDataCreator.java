package org.liquido;

import io.agroal.api.AgroalDataSource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.liquido.delegation.DelegationEntity;
import org.liquido.model.LiquidoBaseEntity;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;
import org.liquido.security.PasswordResetToken;
import org.liquido.security.webauthn.WebAuthnCredential;
import org.liquido.team.TeamDataResponse;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMemberEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.LiquidoConfig;
import org.liquido.vote.BallotEntity;
import org.liquido.vote.CastVoteResponse;
import org.liquido.vote.OneTimeVotingToken;
import org.liquido.vote.RightToVoteEntity;

import java.io.*;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

import static org.liquido.TestFixtures.*;

/**
 * <h1>My famous TestDataCreator</h1>
 *
 * In theory every test should be independent, atomic and repeatable. This would imply that every test
 * creates its own test data and also cleans up after itself. The Quarkus @TestTransaction annotation helps a lot with that.
 *
 * But this is theoretical. In a larger application, tests for use cases later in the process depend on all the data
 * that was created in earlier use case steps. For example to test the calculation of the winner of a poll you
 * need a team with an admin, one or more team members, a poll with proposals and some casted voted. It would
 * take forever to create all that everytime the winnerCalculationTest runs.
 *
 * <h1>Liquido Test data approach</h1>
 *
 * In LIQUIDO many tests still are independent, atomic and repeatable. But other tests depend on a specific set of
 * pre created test data. This TestDataCreator creates this test data.
 *
 * But now comes the trick: Creating all test data that is needed to test something late in the LIQUIDO voting use case
 * such as calculating the winner, actually IS the test itself. Creating the testdata means to run through all
 * steps in the use case flow. So TestDataCreator == HappyCaseTest   Do a full end-to-end test through my use case
 * flow does exactly create the test data that other smaller tests can then rely on.
 *
 * But obviously TestDataCreator is not repeatable. You can run it multiple times. But it will create a new team
 * everytime. So the previously generated team remains in the DB. (there is a purgeTeam() helper to clean this up.)
 *
 * <h1>Liquido Test Process</h1>
 *
 *  1. Start with a clean DB. (You can configure Quarkus to drop-and-create everything
 *  2. Run TestDataCreator once: ./mvnw test -Dmaven.surefire.includedGroups=testDataCreator -Dmaven.surefire.excludedGroups=""    - which is in itself already a very nice regression test!!!
 *  3. Now you can run all tests
 *
 */
@Slf4j
@Tag("testDataCreator")   // Will not run automatically. Only manually: ./mvnw test -Dmaven.surefire.includedGroups=testDataCreator -Dmaven.surefire.excludedGroups=""
@QuarkusTest
public class TestDataCreator {

	@Inject
	AgroalDataSource dataSource;

	@Inject
	LiquidoConfig config;

	@ConfigProperty(name = "quarkus.datasource.db-kind")
	String dbKind;

	@ConfigProperty(name = "quarkus.datasource.jdbc.url")
	String jdbcUrl;

	@ConfigProperty(name = "quarkus.datasource.username")
	String dbUsername;

	@ConfigProperty(name = "quarkus.datasource.password")
	String dbPassword;

	@Inject
	EntityManager entityManager;

	@Inject
	LiquidoTestUtils util;

  	String sampleDbFile = "liquido-testData.sql";


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
	//@Disabled  // Only run's manually through the @tag annotation on the class above
	public void createTestData() {
		String url = "no DB URL!";
		try {
			url = dataSource.getConnection().getMetaData().getURL();
		} catch (SQLException e) {
			log.error("TestDataCreator Cannot connect to DB{}", e.getMessage());
		}

		RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

		log.info("Creating test data in {} for team {}", url, teamName);

		// Create a new team
		TeamDataResponse adminRes = util.createTeam(teamName, adminEmail, 5);

		// Let another user join that team
		TeamDataResponse memberRes = util.joinTeam(adminRes.team.inviteCode, memberEmail);

		// Make sure that the team has enough members to create more polls & proposals
		adminRes.team = util.ensureNumMembers(adminRes.team.id, 10);

		// Create some polls in ELABORATION
		PollEntity poll;
		poll = util.createPoll(pollTitle+"_1 "+now, adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 3);

		poll = util.createPoll(pollTitle+"_2 "+now, adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 4);

		poll = util.createPoll(pollTitle+"_4 "+now+" with a very long title just for testing", adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 4);

		// Like a proposal
		Optional<ProposalEntity> prop = poll.getProposals().stream().findFirst();
		if (prop.isPresent()) {
			poll = util.likeProposal(poll, prop.get().getId(), adminRes.jwt);
		}

		// Create Poll in VOTING with started voting phase
		poll = util.createPoll(pollTitle+" in voting", adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 4);
		poll = util.startVotingPhase(poll.getId(), adminRes.jwt);

		// Create a FINISHED poll
		poll = util.createPoll(pollTitle+" finished", adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 5);
		poll = util.startVotingPhase(poll.getId(), adminRes.jwt);

		// A member casts a vote
		String voterToken = util.getVoterToken(poll.id, memberRes.jwt);
		List<Long> voteOrderIds = poll.getProposals().stream().map(LiquidoBaseEntity::getId).toList();
		CastVoteResponse castVoteResponse = util.castVote(poll.id, voteOrderIds, voterToken);
		log.debug("CastVoteResponse: {}", castVoteResponse.toString());

		// Admin also casts a vote
		String adminVoterToken = util.getVoterToken(poll.id, adminRes.jwt);
		CastVoteResponse adminCastVoteResponse = util.castVote(poll.id, voteOrderIds, adminVoterToken);

		// Verify ballot of admin
		BallotEntity ballot = util.verifyBallot(poll.getId(), adminCastVoteResponse.getBallot().getChecksum());
		log.debug("Ballot successfully verified: {}", ballot.toString());


		// Finish the voting phase of this poll
		ProposalEntity winner = util.finishVotingPhase(poll.getId(), adminRes.jwt);

		// Print winner
		log.info("Winner: {}", winner.toString());

		try {
			extractSql();          // no-op unless dbKind=h2 (old Spring+H2+Quartz era, kept for reference)
			extractPostgresData(); // the one that actually does something today
		} catch (SQLException | IOException | InterruptedException e) {
			log.error("Cannot extract test data ", e);
		}

		log.info("========== CreateTestData SUCCESSFULLY for team {} =============", teamName);
	}


	/*
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

	 */


	//===== We can extract the created DB content as SQL form the Quarkus build in H2 DB in DEV mode

	public void extractSql() throws SQLException {
		if ("h2".equals(dbKind)) {       // The `SCRIPT TO` command only works for H2 in-memory DB
			PreparedStatement ps = dataSource.getConnection().prepareStatement("SCRIPT TO '" + sampleDbFile + "'");
			ps.execute();
			//adjustDbInitializationScript();
			log.info("===== Successfully stored test data in file: {}", sampleDbFile);
		}
	}

	/**
	 * Dump the current Postgres DB's data (not schema -- that's still owned by Hibernate's
	 * drop-and-create, see AGENTS.md) to {@link #sampleDbFile}, via the {@code pg_dump} client tool.
	 * This is the Postgres-native replacement for {@link #extractSql()}, which only ever worked
	 * against the in-memory H2 dev-tools DB from an earlier Spring+H2+Quartz version of this app
	 * and is a no-op against Postgres.
	 * <p>
	 * Requires {@code pg_dump} on PATH (e.g. Postgres.app's {@code Contents/Versions/latest/bin/},
	 * or {@code brew install libpq}).
	 * <p>
	 * {@code --data-only}: schema is regenerated separately (see AGENTS.md's "Schema and seed data"),
	 * so the dump should only ever replay data into an already-drop-and-created schema.
	 * {@code --column-inserts}: each INSERT lists its column names explicitly, so replaying the dump
	 * doesn't silently break if a column is reordered later.
	 */
	public void extractPostgresData() throws IOException, InterruptedException {
		if (!"postgresql".equals(dbKind)) return;

		URI jdbcUri = URI.create(jdbcUrl.substring("jdbc:".length()));
		String host = jdbcUri.getHost();
		int port = jdbcUri.getPort() > 0 ? jdbcUri.getPort() : 5432;
		String dbName = jdbcUri.getPath().substring(1); // strip leading "/"

		ProcessBuilder pb = new ProcessBuilder(
				"pg_dump",
				"--data-only",
				"--column-inserts",
				"--no-owner",
				"--no-privileges",
				"-h", host,
				"-p", String.valueOf(port),
				"-U", dbUsername,
				"-d", dbName,
				"-f", sampleDbFile
		);
		pb.environment().put("PGPASSWORD", dbPassword);
		pb.redirectErrorStream(true);

		Process process = pb.start();
		String output = new String(process.getInputStream().readAllBytes());
		int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new IOException("pg_dump failed with exit code " + exitCode + ": " + output);
		}
		log.info("===== Successfully dumped Postgres test data to file: {}", sampleDbFile);
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
	 * The alternative would be to copy the Quartz lines into schema.sql and data.sql
	 * Then I could also recreate Quartz sample data such as jobs.
	 */
	private void adjustDbInitializationScript() {
		log.trace("removeQuartzSchema from SQL script: start");
		try {
			File sqlScript = new File(sampleDbFile);
			BufferedReader reader = new BufferedReader(new FileReader(sqlScript));
			List<String> lines = new ArrayList<>();
			String currentLine;
			boolean removeBlock = false;
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
					continue;
				}
				if (currentLine.matches("(ALTER|CREATE).*TABLE \"PUBLIC\"\\.\"QRTZ.*;")) {
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
			log.trace("removeQuartzSchema from SQL script successful: {}", sqlScript.getAbsolutePath());

		} catch (Exception e) {
			log.error("Could not remove Quarts statements from Schema: {}", e.getMessage());
			throw new RuntimeException("Could not remove Quarts statements from Schema: " + e.getMessage(), e);
		}
	}


	/**

	@Transactional
	void purgeDb() {
		log.info("================================");
		log.info("       PURGE Test Data");
		log.info("================================");

		// ORDER IS IMPORTANT HERE IN EVERY LINE!!
		// entityManager::remove  ????
		//BUGFIX: PanacheEntityBase::delete ignores FK and relations: https://github.com/quarkusio/quarkus/issues/13941

		BallotEntity.deleteAll();
		OneTimeVotingTokenEntity.deleteAll();
		RightToVoteEntity.deleteAll();
		WebAuthnCredential.deleteAll();
		OneTimeToken.deleteAll();

		entityManager.flush();

		PollEntity.findAll().stream().forEach(poll -> {
			//System.out.println("Going to delete " + poll.toString());
			// Must delete each proposal in this poll individually
			ProposalEntity.find("poll", poll).stream().forEach(PanacheEntityBase::delete);
			poll.delete();
		});

		entityManager.flush();

		DelegationEntity.deleteAll();
		TeamMemberEntity.deleteAll();
		TeamEntity.deleteAll();
		UserEntity.deleteAll();
	}
	 */


	/**
	 * <h1>DANGER!</h1> This deletes all the data of tone team
	 * @param teamName name of the team
	 */
	@Transactional
	void purgeTeam(String teamName) {
		if (LaunchMode.current() != LaunchMode.DEVELOPMENT) {
			log.warn("Will not purgeTeam. LaunchMode is not DEVELOPMENT");
			return;
		}

		TeamEntity team = TeamEntity.<TeamEntity>findByTeamName(teamName)
				.orElseThrow(() -> new IllegalArgumentException("No team found with teamName=" + teamName));

		log.info("================================");
		log.info("       PURGE Test Data for team {}", teamName);
		log.info("================================");

		List<TeamMemberEntity> teamMembers = TeamMemberEntity.list("team", team);
		Set<UserEntity> usersToDelete = new HashSet<>();
		for (TeamMemberEntity teamMember : teamMembers) {
			UserEntity user = teamMember.getUser();
			if (TeamMemberEntity.count("user", user) == 1) {
				// Only delete user's that are only in this team and no other.
				usersToDelete.add(user);
			}
		}

		List<PollEntity> polls = PollEntity.list("team", team);
		for (PollEntity poll : polls) {
			BallotEntity.delete("poll", poll);
			OneTimeVotingToken.delete("poll", poll);
		}

		entityManager.flush();

		for (PollEntity poll : polls) {
			ProposalEntity.find("poll", poll).stream().forEach(PanacheEntityBase::delete);
			poll.delete();
		}

		entityManager.flush();

		for (UserEntity user : usersToDelete) {
			DelegationEntity.find("fromUser = ?1 or toProxy = ?1", user).stream().forEach(PanacheEntityBase::delete);
		}

		entityManager.flush();

		for (UserEntity user : usersToDelete) {
			PasswordResetToken.delete("user", user);
			WebAuthnCredential.delete("liquidoUser", user);

			RightToVoteEntity.findByVoter(user, config.hashSecret()).ifPresent(rightToVote -> {
				rightToVote.removeDelegationToProxy();
				new HashSet<>(rightToVote.getDelegations()).forEach(RightToVoteEntity::removeDelegationToProxy);
				OneTimeVotingToken.delete("rightToVote", rightToVote);
				BallotEntity.delete("rightToVote", rightToVote);
				rightToVote.delete();
			});
		}

		entityManager.flush();

		teamMembers.forEach(PanacheEntityBase::delete);
		team.delete();

		entityManager.flush();

		usersToDelete.forEach(PanacheEntityBase::delete);
	}

}