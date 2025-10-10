package org.liquido;

import io.agroal.api.AgroalDataSource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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
import org.liquido.vote.BallotEntity;
import org.liquido.vote.CastVoteResponse;
import org.liquido.vote.RightToVoteEntity;

import java.io.*;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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

	@Inject
	LiquidoTestUtils util;



  String sampleDbFile = "import-testData.sql";

	/**
	 * DANGER ZOME!!! BE CAREFULL!
	 *
	 * TestDataCreator can delete and re-create everything!
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
			TeamDataResponse adminRes = util.createTeam(teamName, adminEmail, 5);

			// Let another user join that team
			TeamDataResponse memberRes = util.joinTeam(adminRes.team.inviteCode, memberEmail);

			// Make sure that team has enough members to create more polls & proposals
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
			ProposalEntity prop = poll.getProposals().stream().findFirst().get();
			poll = util.likeProposal(poll, prop.id, adminRes.jwt);

			// Create Poll in VOTING with started voting phase
			poll = util.createPoll(pollTitle+" in voting", adminRes.jwt);
			poll = util.seedRandomProposals(poll, adminRes.team, 4);
			poll = util.startVotingPhase(poll.getId(), adminRes.jwt);

			// Create a FINISHED poll
			poll = util.createPoll(pollTitle+" finished", adminRes.jwt);
			poll = util.seedRandomProposals(poll, adminRes.team, 5);
			poll = util.startVotingPhase(poll.getId(), adminRes.jwt);

			// A member casts a vote
			String voterToken = util.getVoterToken(tokenSecret, memberRes.jwt);
			List<Long> voteOrderIds = poll.getProposals().stream().map(BaseEntity::getId).toList();
			CastVoteResponse castVoteResponse = util.castVote(poll.id, voteOrderIds, voterToken);

			// Admin also casts a vote
			String adminVoterToken = util.getVoterToken(tokenSecret, adminRes.jwt);
			CastVoteResponse adminCastVoteResponse = util.castVote(poll.id, voteOrderIds, adminVoterToken);

			// Verify ballot of admin
			BallotEntity ballot =util. verifyBallot(poll.getId(), adminCastVoteResponse.getBallot().getChecksum());

			// Finish the voting phase of this poll
			ProposalEntity winner = util.finishVotingPhase(poll.getId(), adminRes.jwt);

			// Print winner
			log.info("Winner: " + winner.toString());
		}
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