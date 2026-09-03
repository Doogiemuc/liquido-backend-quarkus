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

import static org.junit.jupiter.api.Assertions.*;
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

		// Make sure that the team has enough members to create more polls & proposals.
		// 7 is what createTeam(.., 5) + the joinTeam above already produce, so this is a no-op that
		// states the invariant rather than changing the seed. The number must stay >= the largest
		// seedRandomProposals(..., n) below (currently 5), because that helper gives each proposal a
		// different author.
		// SeedContractTests asserts this too.
		adminRes.team = util.ensureNumMembers(adminRes.team.id, 7);

		// Create some polls in ELABORATION
		PollEntity poll;
		poll = util.createPoll(pollTitle+"_1 "+now, adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 3);

		poll = util.createPoll(pollTitle+"_2 "+now, adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 4);

		poll = util.createPoll(pollTitle+"_4 "+now+" with a very long title just for testing", adminRes.jwt);
		poll = util.seedRandomProposals(poll, adminRes.team, 4);

		// An ADMIN-ONLY poll: members may not add proposals here, so the admin writes all the options.
		// Every other seeded poll allows member proposals (util.createPoll opts in, because
		// seedRandomProposals needs member-authored proposals), so without this one the seed would only
		// ever show one half of the setting - and the admin-only path would never be visible by hand.
		PollEntity adminOnlyPoll = util.createPoll(pollTitle+"_5 "+now+" only the admin adds proposals", adminRes.jwt, false);
		adminOnlyPoll = util.addProposal(adminOnlyPoll.getId(), "Admin option A "+now,
				"The first option, written by the admin. Members cannot add their own here.", "crown", adminRes.jwt);
		adminOnlyPoll = util.addProposal(adminOnlyPoll.getId(), "Admin option B "+now,
				"The second option, also written by the admin. Members cannot add their own here.", "gavel", adminRes.jwt);

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

		createMultiTeamMemberWhoVotesInSecondTeam();

		try {
			extractSql();          // no-op unless dbKind=h2 (old Spring+H2+Quartz era, kept for reference)
			extractPostgresData(); // the one that actually does something today
		} catch (SQLException | IOException | InterruptedException e) {
			log.error("Cannot extract test data ", e);
		}

		log.info("========== CreateTestData SUCCESSFULLY for team {} =============", teamName);
	}

	/**
	 * <h1>Use case: a user joins a SECOND team, and votes in it</h1>
	 *
	 * Both halves of this were structurally impossible until 2026-08-13:
	 * <ul>
	 *   <li>{@code TeamMemberEntity.user} was {@code @OneToOne}, which generated {@code UNIQUE(user_id)}
	 *       on {@code team_members} and capped every user at exactly ONE team membership system-wide.
	 *       Joining a first team worked, so nothing looked broken - the second join failed at commit
	 *       with a constraint violation surfaced as an opaque INTERNAL_ERROR.</li>
	 *   <li>A voter's {@code RightToVote} is keyed by their email, NOT by team. Casting the vote below
	 *       therefore exercises one single RightToVote across two teams - the case that would break if
	 *       RightToVote ever became team-scoped (see the TODO in {@code TeamGraphQL.joinTeam}).</li>
	 * </ul>
	 *
	 * <h2>Why this scenario gets its own two teams</h2>
	 *
	 * It would be tempting to just let the existing {@code testmember4711} join a second team. Don't.
	 * {@code joinTeam} sets the user's {@code lastTeamId}, and {@code devLogin} logs a user into their
	 * last team - so that member would afterwards log in to the *second* team, and every later test that
	 * makes them act in the shared seed team fails with {@code Poll(id=...) not found} from the
	 * team-scoping guard. Worse, it would be intermittent: {@code seedRandomProposals} picks its authors
	 * out of a {@code HashSet}, so whether a run touches the multi-team user is luck.
	 *
	 * Keeping the whole scenario in its own two teams means it cannot disturb the shared fixtures, while
	 * still leaving a real multi-team user in the seed for the frontend and for manual testing.
	 *
	 * <p>Note on ordering: this used to have to run LAST, because tests reached for the seed via
	 * {@code getRandomTeam() / getRandomAdmin() / getRandomUser()} - unordered "first row found"
	 * lookups that anything appended could perturb. Those are gone; tests now ask for the seed
	 * <i>by name</i> ({@code getSeedTeam()} and friends), so position no longer carries meaning.
	 * The quarantine itself is now defence-in-depth rather than structurally required.
	 */
	private void createMultiTeamMemberWhoVotesInSecondTeam() {
		log.info("--- A registered user joins a second team and votes there ---");

		// Team A: the user's first team.
		TeamDataResponse teamA = util.createTeam(multiTeamAName, multiTeamAAdminEmail, multiTeamAAdminMobile, 0);
		TeamDataResponse memberInTeamA = util.joinTeam(teamA.team.inviteCode, multiTeamMemberEmail);

		// Team B: the SECOND team, which that same user now joins.
		TeamDataResponse teamB = util.createTeam(multiTeamBName, multiTeamBAdminEmail, multiTeamBAdminMobile, 0);

		// The join must present exactly the email AND mobilephone the user is already registered with -
		// see TeamGraphQL.assertProvidedIdentityMatches().
		TeamDataResponse memberInTeamB =
				util.joinTeamAsRegisteredUser(teamB.team.inviteCode, memberInTeamA.user, memberInTeamA.jwt);

		// Same human being, second team.
		assertEquals(memberInTeamA.user.id, memberInTeamB.user.id,
				"The user who joined the second team must be the very same user, not a newly created one");
		assertNotEquals(teamA.team.id, memberInTeamB.team.id,
				"The second team must really be a different team than the first one");

		// A poll in the SECOND team. Its admin may add more than one proposal; the newly joined member
		// adds one of their own, which also proves they are a fully fledged member here.
		PollEntity pollB = util.createPoll(pollTitle + " in second team", teamB.jwt);
		pollB = util.addProposal(pollB.getId(), "Second team proposal A " + now,
				"First alternative in the second team, added by its admin.", "hand-peace", teamB.jwt);
		pollB = util.addProposal(pollB.getId(), "Second team proposal B " + now,
				"Second alternative in the second team, added by its admin.", "hand-rock", teamB.jwt);
		pollB = util.addProposal(pollB.getId(), "Second team proposal C " + now,
				"Added by the member who joined this team as their second one.", "hand-scissors", memberInTeamB.jwt);
		pollB = util.startVotingPhase(pollB.getId(), teamB.jwt);

		// ... and the multi-team member casts a vote in that SECOND team.
		String voterTokenB = util.getVoterToken(pollB.getId(), memberInTeamB.jwt);
		List<Long> voteOrderIdsB = pollB.getProposals().stream().map(LiquidoBaseEntity::getId).toList();
		CastVoteResponse castVoteResB = util.castVote(pollB.getId(), voteOrderIdsB, voterTokenB);
		log.info("Vote cast in second team: {}", castVoteResB);

		// The ballot in the second team verifies just like any other.
		BallotEntity ballotB = util.verifyBallot(pollB.getId(), castVoteResB.getBallot().getChecksum());
		assertNotNull(ballotB, "Ballot cast in the second team must be verifiable by its checksum");
		log.info("Ballot in second team successfully verified: {}", ballotB);
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
	 * {@code --disable-triggers}: <b>required, not optional.</b> This schema has circular foreign keys
	 * -- {@code polls.winner_id -> proposals} while {@code proposals.poll_id -> polls}, the same shape
	 * between {@code polly} and {@code polly_proposal}, plus self-referencing {@code righttovote}. A
	 * {@code --data-only} dump writes tables in one flat order, and no order satisfies a cycle, so
	 * without this flag the dump is <b>unrestorable</b>: replaying it dies on the first {@code polls}
	 * row with "violates foreign key constraint". pg_dump warns about this at dump time; the warning
	 * is easy to miss because dumping still succeeds. The flag wraps the data in trigger-disabling
	 * statements, which is why restoring the dump requires a superuser (the {@code postgres} role).
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
				"--disable-triggers",   // circular FKs -- see javadoc. Without this the dump cannot be restored.
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

			// A right to vote is scoped to ONE team, so this purges only this team's.
			// No ballot delete by right to vote any more: a ballot holds a poll-scoped pseudonym and no
			// reference back, and every ballot this right to vote produced belongs to a poll in THIS
			// team -- all of which were already deleted above.
			RightToVoteEntity.findByVoterAndTeam(user, team, config).ifPresent(rightToVote -> {
				rightToVote.removeDelegationToProxy();
				new HashSet<>(rightToVote.getDelegations()).forEach(RightToVoteEntity::removeDelegationToProxy);
				OneTimeVotingToken.delete("rightToVote", rightToVote);
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