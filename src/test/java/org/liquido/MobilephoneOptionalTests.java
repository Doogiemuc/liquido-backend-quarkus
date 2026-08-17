package org.liquido;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liquido.team.TeamDataResponse;
import org.liquido.user.UserEntity;
import org.liquido.util.Lson;

import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.liquido.TestFixtures.CREATE_OR_JOIN_TEAM_RESULT;
import static org.liquido.TestFixtures.PASSWORD_SUFFIX;

/**
 * <h1>A mobilephone is OPTIONAL in LIQUIDO</h1>
 *
 * Design decision: a mobile phone number is never required to register. It stays on the entity and in
 * the GraphQL API as an optional field so that SMS login can be built later, but nothing in the UI
 * collects one and nothing in the backend may demand one.
 *
 * <h2>The bug these tests exist to prevent</h2>
 *
 * {@code cleanMobilephone("")} used to return {@code ""} rather than null, and
 * {@code findByMobilephone} then ran {@code where mobilephone = ''}. So the first user stored with an
 * empty string effectively claimed the empty string as a unique key, and every later phone-less
 * registration was rejected with USER_MOBILEPHONE_EXISTS. It never fired in practice only because
 * callers happened to send null instead of "". That is luck, not a guarantee -- hence
 * {@link #twoUsersCanRegisterWithBlankMobilephone()}.
 *
 * <h2>Note on cleanup</h2>
 *
 * These tests register real users over HTTP, so {@code @TestTransaction} cannot roll them back (the
 * server request runs in its own transaction). They therefore create only their own throwaway teams
 * and never touch the shared seed -- see the seed contract in {@code AGENTS.md} and
 * {@link SeedContractTests}.
 */
@Slf4j
@QuarkusTest
public class MobilephoneOptionalTests {

	@Inject
	LiquidoTestUtils util;

	private static final String CREATE_TEAM_QUERY =
			"mutation createNewTeam($teamName: String!, $admin: UserEntityInput!, $password: String!) { " +
					" createNewTeam(teamName: $teamName, admin: $admin, password: $password) " + CREATE_OR_JOIN_TEAM_RESULT + "}";

	private static final String JOIN_TEAM_QUERY =
			"mutation joinTeam($inviteCode: String!, $member: UserEntityInput!, $password: String!) { " +
					"joinTeam(inviteCode: $inviteCode, member: $member, password: $password) " + CREATE_OR_JOIN_TEAM_RESULT + "}";

	/**
	 * Register a new team + admin.
	 * @param mobilephone the value to send, or null to omit the field from the payload entirely
	 */
	private TeamDataResponse createTeamWithMobilephone(String namePrefix, String mobilephone) {
		long unique = new Date().getTime() + Math.abs(namePrefix.hashCode() % 1000);
		String adminEmail = namePrefix.toLowerCase() + unique + "@liquido.vote";
		Lson admin = Lson.builder()
				.put("name", namePrefix + " Admin")
				.put("email", adminEmail)
				.put("picture", "Avatar1.png");
		if (mobilephone != null) admin.put("mobilephone", mobilephone);

		Lson variables = Lson.builder()
				.put("teamName", namePrefix + unique)
				.put("admin", admin)
				.put("password", adminEmail + PASSWORD_SUFFIX);

		// sendGraphQL already asserts that no GraphQL errors came back.
		return TestFixtures.sendGraphQL(CREATE_TEAM_QUERY, variables)
				.extract().jsonPath().getObject("data.createNewTeam", TeamDataResponse.class);
	}

	/** Read a user straight from the DB. Needs its own transaction - the HTTP call above ran in another. */
	private Optional<UserEntity> loadUserByEmail(String email) {
		return QuarkusTransaction.requiringNew().call(() -> UserEntity.findByEmail(email));
	}

	@Test
	@DisplayName("A new team can be registered without sending any mobilephone")
	public void registerWithoutMobilephone() {
		TeamDataResponse res = createTeamWithMobilephone("NoPhone", null);

		assertNotNull(res.user, "should have registered an admin");
		assertNull(res.user.getMobilephone(), "a user who sent no mobilephone must have none");

		UserEntity stored = loadUserByEmail(res.user.getEmail())
				.orElseThrow(() -> new AssertionError("admin was not persisted"));
		assertNull(stored.mobilephone, "mobilephone must be persisted as NULL, not as an empty string");
	}

	@Test
	@DisplayName("A blank mobilephone is stored as NULL, never as an empty string")
	public void blankMobilephoneIsNormalisedToNull() {
		// A client that sends "" means the same as one that omits the field. If "" were stored verbatim
		// it would become a real value that the next registration could collide with.
		TeamDataResponse res = createTeamWithMobilephone("BlankPhone", "");

		UserEntity stored = loadUserByEmail(res.user.getEmail())
				.orElseThrow(() -> new AssertionError("admin was not persisted"));
		assertNull(stored.mobilephone,
				"a blank mobilephone must normalise to NULL, otherwise it becomes a unique key that blocks the next user");
	}

	@Test
	@DisplayName("Two users can register with a blank mobilephone - the empty string is not a unique key")
	public void twoUsersCanRegisterWithBlankMobilephone() {
		// This is the actual regression. Before the fix the second call failed with
		// USER_MOBILEPHONE_EXISTS, because both users cleaned down to the same "" value.
		createTeamWithMobilephone("BlankA", "");
		createTeamWithMobilephone("BlankB", "");

		// Same again for the omitted-field form, which is what every real client sends.
		createTeamWithMobilephone("OmittedA", null);
		createTeamWithMobilephone("OmittedB", null);
	}

	@Test
	@DisplayName("A new member can join a team without sending a mobilephone")
	public void joinTeamWithoutMobilephone() {
		TeamDataResponse team = util.createFreshTeam("JoinNoPhone");
		long unique = new Date().getTime();
		String memberEmail = "joinnophone" + unique + "@liquido.vote";

		Lson member = Lson.builder()
				.put("name", "Member Without Phone " + unique)
				.put("email", memberEmail)
				.put("picture", "Avatar1.png");
		Lson variables = Lson.builder()
				.put("inviteCode", team.team.getInviteCode())
				.put("member", member)
				.put("password", memberEmail + PASSWORD_SUFFIX);

		TeamDataResponse res = TestFixtures.sendGraphQL(JOIN_TEAM_QUERY, variables)
				.extract().jsonPath().getObject("data.joinTeam", TeamDataResponse.class);

		assertEquals(team.team.getInviteCode(), res.team.getInviteCode());
		assertNull(res.user.getMobilephone());
	}

	@Test
	@DisplayName("A registered user WITH a mobilephone joins a further team without re-sending it")
	public void registeredUserWithPhoneJoinsFurtherTeamWithoutSendingIt() {
		// The identity check used to require the mobilephone to match as well as the email. Since no UI
		// collects a phone any more, that rejected every user who already had one stored - which is
		// every user created before this change. Email alone is the identity now.
		TeamDataResponse withPhone = createTeamWithMobilephone("HasPhone", "0151 555 " + new Date().getTime());
		assertNotNull(withPhone.user.getMobilephone(), "precondition: this user must actually have a phone stored");

		TeamDataResponse otherTeam = util.createFreshTeam("FurtherTeam");

		// joinTeamAsRegisteredUser deliberately sends no mobilephone, exactly like the real client.
		TeamDataResponse joined = util.joinTeamAsRegisteredUser(
				otherTeam.team.getInviteCode(), withPhone.user, withPhone.jwt);

		assertEquals(otherTeam.team.getInviteCode(), joined.team.getInviteCode());
		assertEquals(withPhone.user.getEmail().toLowerCase(), joined.user.getEmail().toLowerCase());
	}

	@Test
	@TestTransaction
	@DisplayName("findByMobilephone throws rather than silently matching on an absent number")
	public void findByMobilephoneRejectsBlankInput() {
		// Looking a user up by a phone number they do not have is a caller bug, not a query that can
		// legitimately return "not found". Failing loudly is what stops the empty-string trap coming back.
		assertThrows(IllegalArgumentException.class, () -> UserEntity.findByMobilephone(null));
		assertThrows(IllegalArgumentException.class, () -> UserEntity.findByMobilephone(""));
		assertThrows(IllegalArgumentException.class, () -> UserEntity.findByMobilephone("   "));
		// cleans away to nothing - no digits at all
		assertThrows(IllegalArgumentException.class, () -> UserEntity.findByMobilephone("()-/ "));

		// A real number still resolves normally (this one belongs to nobody).
		assertTrue(UserEntity.findByMobilephone("+49 151 000 000 999").isEmpty());
	}
}
