package org.liquido;

import io.agroal.api.AgroalDataSource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.liquido.graphql.TeamDataResponse;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;
import org.liquido.util.Lson;
import org.liquido.vote.BallotEntity;
import org.liquido.vote.RightToVoteEntity;

import javax.inject.Inject;
import javax.transaction.Transactional;
import java.io.*;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.liquido.TestFixtures.*;

@Slf4j
@QuarkusTest
public class TestDataCreator {

	@Inject
	AgroalDataSource dataSource;


  String sampleDbFile = "import-testData.sql";

	boolean purgeDb = false;
	boolean recreateTestData = true;

	@Test
	public void createTestData() throws SQLException {
		RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

		if (purgeDb) {
			log.warn("Going to delete everything in DB!");
			purgeDb();
		}
		if (recreateTestData) {
			log.info("Recreating testdata ...");
			TeamDataResponse adminRes = createTeam(teamName, adminEmail, 5);
			TeamDataResponse memberRes = joinTeam(adminRes.team.inviteCode, memberEmail);
			//extractSql();
			Long now = new Date().getTime() & 1000000;

			PollEntity poll = createPoll(pollTitle, adminRes.jwt);
		}
	}


	public TeamDataResponse createTeam(String teamName, String adminEmail, int numMembers) {
		Long now = new Date().getTime();
		if (teamName == null) teamName = "TestTeam" + now;
		log.info("Creating new team "+teamName);
		if (adminEmail == null) adminEmail = "testadmin" + now + "@liquido.vote";
		Lson admin = Lson.builder()
				.put("name", "TestAdmin " + now)
				.put("email", adminEmail)
				.put("mobilephone", "0151 555 " + now % 1000000);

		// WHEN creating a new team via GraphQL
		String query = "mutation createNewTeam($teamName: String, $admin: UserEntityInput) { " +
				" createNewTeam(teamName: $teamName, admin: $admin) " + CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson variables = Lson.builder()
				.put("teamName", teamName)
				.put("admin", admin);
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
				.body("data.createNewTeam.user.email", is(adminEmail))
				.extract().jsonPath().getObject("data.createNewTeam", TeamDataResponse.class);

		// Add further members that join this team
		for (int i = 0; i < numMembers; i++) {
			joinTeam(res.team.inviteCode, "created_membr"+now+i+"@liquido.vote");
		}

		return res;
	}

	public TeamDataResponse joinTeam(String inviteCode, String memberEmail) {
		Long now = new Date().getTime();
		if (memberEmail == null) memberEmail = "member" + now + "@liquido.vote";
		Lson member = Lson.builder()
				.put("name", "Member " + now)
				.put("email", memberEmail)
				.put("mobilephone", "0151 666 " + now % 1000000);

		// join team via GraphQL
		String query = "mutation joinTeam($inviteCode: String, $member: UserEntityInput) { " +
				" joinTeam(inviteCode: $inviteCode, member: $member) " + CREATE_OR_JOIN_TEAM_RESULT + "}";
		Lson variables = Lson.builder()
				.put("inviteCode", inviteCode)
				.put("member", member);
		String body = String.format("{ \"query\": \"%s\", \"variables\": %s }", query, variables);

		TeamDataResponse res = given()  // .log().body()
				.contentType(ContentType.JSON)
				.body(body)
				.when()
				.post(GRAPHQL_URI)
				.then()
				.body("data.joinTeam.team.inviteCode", is(inviteCode))
				.body("data.joinTeam.user.id", greaterThan(0))
				.body("data.joinTeam.user.email", is(memberEmail))
				.extract().jsonPath().getObject("data.joinTeam", TeamDataResponse.class);

		log.debug("User joined team " + res.team.getTeamName() + ": " + res.user.toStringShort());
		return res;
	}

	/**
	 * Create a new poll. MUST be logged in for this!
	 * @param title title for the poll
	 * @param jwt JsonWebToken of an admin
	 * @return the newly created poll
	 */
	public PollEntity createPoll(String title, String jwt) {
		// WHEN creating a Poll
		String query = "mutation { createPoll(title: \\\"" + title + "\\\") " + JQL_POLL+ "}";
		PollEntity poll = sendGraphQL(query, null, jwt)
				.extract().jsonPath().getObject("data.createPoll", PollEntity.class);
		// THEN it has the correct title
		assertEquals(title, poll.getTitle());
		return poll;
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

	@Transactional
	void purgeDb() {
		log.info("================================");
		log.info("       PURGE DB !!!");
		log.info("================================");
		// order is important!
		//BUGFIX: Must delete each instance individually!  https://github.com/quarkusio/quarkus/issues/13941
		RightToVoteEntity.findAll().stream().forEach(PanacheEntityBase::delete);
		BallotEntity.findAll().stream().forEach(PanacheEntityBase::delete);
		ProposalEntity.findAll().stream().forEach(PanacheEntityBase::delete);
		PollEntity.findAll().stream().forEach(PanacheEntityBase::delete);
		//TeamMemberEntity.findAll().stream().forEach(PanacheEntityBase::delete);  // CASCADE
		//TeamEntity.findAll().stream().forEach(PanacheEntityBase::delete);
		//UserEntity.findAll().stream().forEach(PanacheEntityBase::delete);

	}

}