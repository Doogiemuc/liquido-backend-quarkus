package org.liquido;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.liquido.poll.PollEntity;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMemberEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.TestDataPurger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * <h1>The manual, on-demand sweep of leftover test data</h1>
 *
 * Every e2e run creates a real team through the UI and leaves it there; every backend test that calls
 * {@code createFreshTeam} does the same. Nothing has ever cleaned any of it up, which is why GISMO's
 * integration database had grown to ~53 teams and this test database to over 800.
 *
 * <p>This is deliberately <b>not</b> reachable from the product API. Exposing a "purge" endpoint would
 * have to be guarded by configuration, because Quarkus' {@code LaunchMode} is {@code NORMAL} on the
 * GISMO deployment - exactly where the residue is - so the guard that protects {@code devLogin} would
 * disable it precisely where it is needed. Keeping the sweep here means no destructive code ships in
 * the deployed artifact at all.
 *
 * <h2>How to run it</h2>
 *
 * Dry run (the default - prints what it WOULD delete and deletes nothing):
 * <pre>
 * QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/liquido-int \
 *   ./mvnw -B test -Dmaven.surefire.includedGroups=purgeTestData -Dmaven.surefire.excludedGroups=""
 * </pre>
 *
 * For real, naming the target database a second time by hand:
 * <pre>
 * QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/liquido-int \
 *   ./mvnw -B test -Dmaven.surefire.includedGroups=purgeTestData -Dmaven.surefire.excludedGroups="" \
 *   -Dpurge.dry-run=false -Dpurge.confirm=liquido-int
 * </pre>
 *
 * Three independent things must agree before a single row is deleted: the database must be on
 * {@link TestDataPurger#PURGEABLE_DATABASES}, {@code purge.dry-run} must be explicitly false, and
 * {@code confirm} must spell the same database name that the JDBC URL resolved to.
 */
@Slf4j
@Tag("purgeTestData")   // never runs in a normal build; see the class javadoc for the command
@QuarkusTest
@DisplayName("Manual sweep: delete leftover test data")
public class TestDataPurgeSweep {

	/** How many of the timestamped seed teams to keep. The newest is "the" seed; the rest are history. */
	private static final int KEEP_NEWEST_SEED_TEAMS = 5;

	@Inject
	TestDataPurger purger;

	@ConfigProperty(name = "quarkus.datasource.jdbc.url")
	String jdbcUrl;

	@ConfigProperty(name = "purge.dry-run", defaultValue = "true")
	boolean dryRun;

	// Optional rather than defaultValue="": Quarkus reads an empty default as "no default at all" and
	// then refuses to start because the property is required.
	@ConfigProperty(name = "purge.confirm")
	java.util.Optional<String> confirm;

	@Test
	public void purgeLeftoverTestData() {
		String dbName = TestDataPurger.databaseNameOf(jdbcUrl);
		TestDataPurger.assertPurgeAllowed(jdbcUrl);   // refuses anything not allowlisted

		if (!dryRun && !dbName.equals(confirm.orElse(""))) {
			fail("Refusing to delete from '" + dbName + "': pass -Dpurge.confirm=" + dbName
					+ " to confirm you mean that database. (Got confirm='" + confirm.orElse("") + "')");
		}

		log.info("=======================================================");
		log.info("  PURGE TEST DATA   database={}   mode={}", dbName, dryRun ? "DRY RUN" : "DELETING FOR REAL");
		log.info("=======================================================");

		TestDataPurger.PurgeReport total = new TestDataPurger.PurgeReport();

		total.add(purgeE2eTeams());
		total.add(purgeOldSeedTeams());
		total.add(purgeAppendedPollsInCurrentSeedTeam());
		total.add(purgeOrphanedUsers());

		log.info("=======================================================");
		log.info("  {} {}", dryRun ? "WOULD DELETE:" : "DELETED:", total);
		if (dryRun) {
			log.info("  Nothing was deleted. Re-run with -Dpurge.dry-run=false -Dpurge.confirm={}", dbName);
		}
		if (failures > 0) {
			log.error("  {} team(s) could NOT be purged - see the errors above", failures);
		}
		log.info("=======================================================");

		// Fail the run if anything was left behind. Swallowing per-team errors keeps one bad row from
		// stopping the sweep, but without this a SYSTEMATIC failure - every single team refusing to
		// delete, as happened when rights to vote were looked up by a secret this database never used -
		// still exits green and looks like a successful cleanup.
		if (failures > 0) {
			fail(failures + " of " + (failures + total.teams) + " team(s) could not be purged. See errors above.");
		}
	}

	/** Teams that could not be deleted. Counted so a systematic failure cannot pass as success. */
	private int failures = 0;

	/** Teams the Cypress suite created for itself. Every e2e team name starts with "Cypress ". */
	private TestDataPurger.PurgeReport purgeE2eTeams() {
		List<TeamEntity> teams = TeamEntity.list("teamName like ?1 order by id", TestFixtures.E2E_TEAM_PREFIX + "%");
		log.info("-- e2e leftovers: {} team(s) matching '{}*'", teams.size(), TestFixtures.E2E_TEAM_PREFIX);
		return purgeEach(teams);
	}

	/**
	 * Older seed teams. The newest {@value #KEEP_NEWEST_SEED_TEAMS} are kept, so a suite that is
	 * mid-run against a slightly older seed is not pulled out from under it.
	 */
	private TestDataPurger.PurgeReport purgeOldSeedTeams() {
		List<TeamEntity> all = TeamEntity.list("teamName like ?1 order by id desc", TestFixtures.SEED_TEAM_PREFIX + "%");
		List<TeamEntity> old = all.stream().skip(KEEP_NEWEST_SEED_TEAMS).toList();
		log.info("-- seed teams: {} found, keeping newest {}, purging {}", all.size(), KEEP_NEWEST_SEED_TEAMS, old.size());
		return purgeEach(old);
	}

	/**
	 * Polls that tests appended to the CURRENT seed team, restoring it to its just-seeded shape
	 * without a full reseed.
	 *
	 * <p>A seeded poll's title starts with {@code "TestPoll <runId>"}, where runId is the same
	 * timestamp as the team's own name suffix - so anything in that team whose title does not carry
	 * this team's runId was added afterwards by a test.
	 */
	private TestDataPurger.PurgeReport purgeAppendedPollsInCurrentSeedTeam() {
		TestDataPurger.PurgeReport report = new TestDataPurger.PurgeReport();
		var seedTeamOpt = TeamEntity.findNewestByPrefix(TestFixtures.SEED_TEAM_PREFIX);
		if (seedTeamOpt.isEmpty()) {
			log.info("-- appended polls: no seed team at all, nothing to do");
			return report;
		}
		TeamEntity seedTeam = seedTeamOpt.get();
		String runId = seedTeam.getTeamName().substring(TestFixtures.SEED_TEAM_PREFIX.length());
		String seededTitlePrefix = TestFixtures.SEED_POLL_PREFIX + runId;

		List<PollEntity> appended = PollEntity.<PollEntity>list("team", seedTeam).stream()
				.filter(p -> p.getTitle() == null || !p.getTitle().startsWith(seededTitlePrefix))
				.toList();
		log.info("-- appended polls in '{}': {} not matching '{}*'", seedTeam.getTeamName(), appended.size(), seededTitlePrefix);

		for (PollEntity poll : appended) {
			log.info("   {} poll '{}'", dryRun ? "WOULD PURGE" : "PURGING", poll.getTitle());
			if (!dryRun) report.add(purger.purgePoll(poll));
			else report.polls++;
		}
		return report;
	}

	/**
	 * Users left with no team membership at all - typically ones that survived an older purge because
	 * they belonged to a second team at the time.
	 *
	 * <p>Restricted to test-shaped addresses. Anything else is reported and left alone: on the shared
	 * integration database a membership-less account could be a real person who has not joined a team
	 * yet, and deleting them would be unrecoverable.
	 */
	private TestDataPurger.PurgeReport purgeOrphanedUsers() {
		TestDataPurger.PurgeReport report = new TestDataPurger.PurgeReport();
		// "team_members" is the JPQL entity NAME (@Entity(name="team_members")), not the class name.
		List<UserEntity> orphans = UserEntity.list(
				"id not in (select tm.user.id from team_members tm) order by id");

		List<UserEntity> testShaped = orphans.stream().filter(this::looksLikeTestData).toList();
		long skipped = orphans.size() - testShaped.size();
		log.info("-- orphaned users: {} with no membership, {} test-shaped, {} left alone", orphans.size(), testShaped.size(), skipped);
		if (skipped > 0) {
			orphans.stream().filter(u -> !looksLikeTestData(u))
					.forEach(u -> log.info("   SKIPPING non-test orphan <{}>", u.getEmail()));
		}

		for (UserEntity user : testShaped) {
			log.info("   {} user <{}>", dryRun ? "WOULD DELETE" : "DELETING", user.getEmail());
			if (!dryRun) purger.deleteOrphanedUser(user);
			report.users++;
		}
		return report;
	}

	private boolean looksLikeTestData(UserEntity user) {
		String email = user.getEmail();
		if (email == null) return false;
		return email.endsWith("@liquido.vote") || email.toLowerCase().startsWith(TestFixtures.E2E_EMAIL_PREFIX);
	}

	/** Purge teams one at a time, each in its own transaction, so one bad row cannot abort the sweep. */
	private TestDataPurger.PurgeReport purgeEach(List<TeamEntity> teams) {
		TestDataPurger.PurgeReport report = new TestDataPurger.PurgeReport();
		for (TeamEntity team : teams) {
			String name = team.getTeamName();
			log.info("   {} team '{}'", dryRun ? "WOULD PURGE" : "PURGING", name);
			if (dryRun) {
				report.teams++;
				continue;
			}
			try {
				report.add(purger.purgeTeam(name, true));
			} catch (RuntimeException e) {
				// Counted and reported, not thrown: one undeletable leftover must not stop the other 800.
				// The count is asserted at the end, so this cannot pass silently.
				failures++;
				log.error("   FAILED to purge team '{}': {}", name, e.toString());
			}
		}
		return report;
	}
}
