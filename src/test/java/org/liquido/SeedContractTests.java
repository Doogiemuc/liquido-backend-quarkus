package org.liquido;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.poll.PollEntity;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMemberEntity;
import org.liquido.user.UserEntity;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>The seed contract, made executable</h1>
 *
 * Most LIQUIDO tests do not build their own fixtures. They reuse the shared seed that
 * {@link TestDataCreator} produces, because re-deriving a full team with members, polls in every
 * state, proposals and ballots per test would be far slower. That is a deliberate trade.
 *
 * The trade only works if the seed's shape is a <b>stated contract</b> rather than a folk memory.
 * This class states it. When one of these fails, the message tells you the seed is wrong -- rather
 * than some unrelated test failing three layers deep with {@code Poll(id=…) not found} or
 * {@code Cannot seed 5 proposals, because there are only 4 members}.
 *
 * <h2>The contract</h2>
 * <ol>
 *   <li>A test MAY <b>append</b> to the seed team: polls, proposals, likes, ballots, its own voter
 *       tokens. That is expected and must stay harmless.</li>
 *   <li>A test MUST NOT mutate the <b>identity or relationships</b> of seed rows: delegations,
 *       team membership, passwords, {@code lastTeamId}. Those belong in a
 *       {@link LiquidoTestUtils#createFreshTeam(String)} team. Remember that {@code @TestTransaction}
 *       does <i>not</i> roll back mutations made over HTTP.</li>
 *   <li>On the reading side, a test asks for the row it means <b>by name</b> -- never "the first row".</li>
 * </ol>
 */
@Slf4j
@QuarkusTest
public class SeedContractTests {

	@Inject
	LiquidoTestUtils util;

	@Test
	@TestTransaction
	@DisplayName("Seed team exists, with the members and single admin the suite relies on")
	public void seedTeamHasTheExpectedShape() {
		TeamEntity team = util.getSeedTeam();
		assertEquals(TestFixtures.teamName, team.getTeamName());

		Set<TeamMemberEntity> members = team.getMembers();
		// The tightest consumer is TestDataCreator's seedRandomProposals(poll, team, 5): it needs one
		// distinct member per proposal. Keep this number and that call in sync.
		assertTrue(members.size() >= 5,
				"Seed team must have >= 5 members because seedRandomProposals(poll, team, 5) gives each " +
				"proposal a different author, but has " + members.size());

		long admins = members.stream().filter(m -> m.getRole() == TeamMemberEntity.Role.ADMIN).count();
		assertEquals(1, admins, "Seed team must have exactly one ADMIN");

		long distinctUsers = members.stream().map(m -> m.getUser().id).distinct().count();
		assertEquals(members.size(), distinctUsers, "Each seed team member must be a distinct user");
	}

	@Test
	@TestTransaction
	@DisplayName("Seed admin and seed member are who they claim to be, and belong to exactly one team")
	public void seedIdentitiesAreUnambiguous() {
		TeamEntity team = util.getSeedTeam();
		UserEntity admin = util.getSeedAdmin();
		UserEntity member = util.getSeedMember();

		assertTrue(isMemberOf(team, admin, TeamMemberEntity.Role.ADMIN),
				"getSeedAdmin() must be an ADMIN of the seed team");
		assertTrue(isMemberOf(team, member, TeamMemberEntity.Role.MEMBER),
				"getSeedMember() must be a MEMBER of the seed team");
		assertNotEquals(admin.id, member.id, "Seed admin and seed member must be different people");

		// This is the guard that keeps devLogin unambiguous for these two. devLogin logs a user into
		// their lastTeamId, which joinTeam rewrites -- so the moment either of them belongs to a second
		// team, every later test that has them act in the seed team starts failing the team-scoping
		// check, intermittently. If this assertion fires, someone put a multi-team user in the seed.
		assertEquals(1, TeamMemberEntity.findTeamsByMember(admin).size(),
				"Seed admin must belong to exactly ONE team, otherwise devLogin() is ambiguous");
		assertEquals(1, TeamMemberEntity.findTeamsByMember(member).size(),
				"Seed member must belong to exactly ONE team, otherwise devLogin() is ambiguous");
	}

	@Test
	@TestTransaction
	@DisplayName("Seed team has polls in ELABORATION, VOTING and FINISHED")
	public void seedTeamHasPollsInEveryState() {
		TeamEntity team = util.getSeedTeam();
		List<PollEntity> polls = PollEntity.list("team", team);
		Set<PollEntity.PollStatus> states = polls.stream().map(PollEntity::getStatus).collect(Collectors.toSet());

		for (PollEntity.PollStatus required : PollEntity.PollStatus.values()) {
			assertTrue(states.contains(required),
					"Seed team must contain at least one poll in " + required + ", but only has " + states);
		}
	}

	/**
	 * The one that matters most.
	 *
	 * Tests that correctly isolate themselves with {@code createFreshTeam} leave their throwaway team
	 * behind -- roughly seven of them per full suite run, each with exactly one member, whose role is
	 * ADMIN. Under the old unordered "first row" lookups, one of those could be handed to a test that
	 * then demanded two members and a non-admin, and it failed far from the cause.
	 *
	 * Creating residue FIRST and then resolving the seed proves the lookups are anchored: extending
	 * the database cannot change what a test reads. This is what lets the suite leave residue behind
	 * on purpose instead of paying to clean it up.
	 */
	@Test
	@DisplayName("Seed lookups are immune to leftover data from other tests")
	public void seedLookupsIgnoreResidue() {
		TeamEntity before = util.getSeedTeam();

		util.createFreshTeam("SeedContractResidue");   // exactly the shape that used to poison the lookups

		assertEquals(before.id, util.getSeedTeam().id,
				"getSeedTeam() must still resolve to the seed team after another team was created");
		assertEquals(TestFixtures.adminEmail, util.getSeedAdmin().email,
				"getSeedAdmin() must still resolve to the seed admin after another ADMIN was created");
		assertEquals(TestFixtures.memberEmail, util.getSeedMember().email,
				"getSeedMember() must still resolve to the seed member");
	}

	private boolean isMemberOf(TeamEntity team, UserEntity user, TeamMemberEntity.Role role) {
		return team.getMembers().stream()
				.anyMatch(m -> m.getUser().id.equals(user.id) && m.getRole() == role);
	}
}
