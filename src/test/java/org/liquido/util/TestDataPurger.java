package org.liquido.util;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.liquido.delegation.DelegationEntity;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;
import org.liquido.security.PasswordResetToken;
import org.liquido.security.webauthn.WebAuthnCredential;
import org.liquido.team.TeamEntity;
import org.liquido.team.TeamMemberEntity;
import org.liquido.user.UserEntity;
import org.liquido.vote.BallotEntity;
import org.liquido.vote.OneTimeVotingToken;
import org.liquido.vote.RightToVoteEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <h1>DANGER: deletes test data. Read the guard before you call anything here.</h1>
 *
 * Shared by the two things that destroy data on purpose: {@code TestDataCreator}, which purges the
 * fixed-name teams before recreating them, and {@code PurgeTestData}, the manual out-of-band sweep.
 * Both go through here so the deletion ORDER exists in exactly one place.
 *
 * <h2>Why the order is the way it is</h2>
 *
 * The schema has two shapes that make naive deletion fail, and both were verified against the live
 * schema rather than inferred:
 *
 * <ul>
 *   <li><b>A foreign key cycle</b>: {@code polls.winner_id -> proposals} and
 *       {@code proposals.poll_id -> polls}. {@link PollEntity#winner} is {@code @OneToOne(cascade =
 *       PERSIST)} with no orphanRemoval, so nothing clears it for us. The winner must be nulled and
 *       flushed BEFORE its proposals are deleted, or the poll still points at a deleted row.</li>
 *   <li><b>Join tables that bulk deletes do not touch</b>: {@code ballot_voteorder.voteorder_id ->
 *       proposals} and {@code proposal_supporters.supporters_id -> liquido_user}. A JPQL bulk
 *       {@code delete from BallotEntity where poll = ?} removes the ballots but leaves their
 *       {@code ballot_voteorder} rows, which then block the proposal delete. Ballots are therefore
 *       loaded and deleted as ENTITIES, with their collections cleared first.</li>
 * </ul>
 *
 * <h2>The guard</h2>
 *
 * Every public entry point calls {@link #assertPurgeAllowed}, which refuses unless the datasource
 * names a database on {@link #PURGEABLE_DATABASES}. This deliberately replaces the old
 * {@code LaunchMode != DEVELOPMENT} check, which was worse than useless: under {@code @QuarkusTest}
 * the mode is TEST, so the old guard silently returned having deleted nothing, and the caller could
 * not tell (the method returned void). It had, as far as the repo shows, never once executed.
 */
@Slf4j
@ApplicationScoped
public class TestDataPurger {

	/**
	 * The only databases this class will ever delete from. A compile-time constant on purpose: a
	 * config-file allowlist would be defeated by the very environment variable that selects the
	 * database, since env config has a higher ordinal than a properties file.
	 */
	public static final Set<String> PURGEABLE_DATABASES = Set.of("LIQUIDO-TEST", "LIQUIDO-DEV", "liquido-int");

	@Inject
	EntityManager entityManager;

	@Inject
	LiquidoConfig config;

	/** The datasource actually in effect, which is what the guard below is asserted against. */
	@ConfigProperty(name = "quarkus.datasource.jdbc.url")
	String jdbcUrl;

	/** Counts of what was removed, so a caller can report it rather than guess. */
	public static class PurgeReport {
		public int teams, polls, users, ballots;

		public void add(PurgeReport other) {
			teams += other.teams; polls += other.polls; users += other.users; ballots += other.ballots;
		}

		@Override
		public String toString() {
			return String.format("%d team(s), %d poll(s), %d ballot(s), %d user(s)", teams, polls, ballots, users);
		}
	}

	/**
	 * Refuse to run against anything that is not a known throwaway database.
	 *
	 * @param jdbcUrl the datasource URL actually in effect
	 * @throws IllegalStateException if the database is not on {@link #PURGEABLE_DATABASES}
	 */
	public static void assertPurgeAllowed(String jdbcUrl) {
		String dbName = databaseNameOf(jdbcUrl);
		if (!PURGEABLE_DATABASES.contains(dbName)) {
			throw new IllegalStateException(
					"REFUSING to purge: database '" + dbName + "' is not one of " + PURGEABLE_DATABASES +
					"\n  jdbcUrl was: " + jdbcUrl);
		}
	}

	/** The database name out of a JDBC URL, ignoring any ?query=params. */
	public static String databaseNameOf(String jdbcUrl) {
		if (jdbcUrl == null) return "<null>";
		String withoutParams = jdbcUrl.split("\\?")[0];
		int lastSlash = withoutParams.lastIndexOf('/');
		return lastSlash < 0 ? withoutParams : withoutParams.substring(lastSlash + 1);
	}

	/**
	 * Purge several teams in ONE pass.
	 *
	 * <p>Purging multiTeamA and multiTeamB separately would not work: their shared member belongs to
	 * both, so each call would see them still in "another" team. Collecting the victims across all
	 * the named teams first is what lets that user actually be deleted.
	 *
	 * @param teamNames exact team names. Names that do not exist are skipped, not an error - a first
	 *                  run against a clean database must succeed.
	 * @param deleteMembersUnconditionally true to delete members even when they belong to teams
	 *                  outside this list. Required for the fixed-name teams, whose users must be gone
	 *                  before the same fixed emails can be created again.
	 */
	@Transactional
	public PurgeReport purgeTeams(List<String> teamNames, boolean deleteMembersUnconditionally) {
		assertPurgeAllowed(jdbcUrl);
		PurgeReport report = new PurgeReport();

		List<TeamEntity> teams = teamNames.stream()
				.map(TeamEntity::findByTeamName)
				.filter(java.util.Optional::isPresent)
				.map(java.util.Optional::get)
				.toList();
		if (teams.isEmpty()) return report;

		log.info("PURGE {} team(s): {}", teams.size(), teamNames);

		// Collect every membership across ALL the doomed teams first, so a user shared between two of
		// them is judged against the whole set rather than one team at a time.
		Set<Long> doomedTeamIds = new HashSet<>();
		teams.forEach(t -> doomedTeamIds.add(t.id));
		Set<UserEntity> usersToDelete = new HashSet<>();
		for (TeamEntity team : teams) {
			for (TeamMemberEntity membership : TeamMemberEntity.<TeamMemberEntity>list("team", team)) {
				UserEntity user = membership.getUser();
				if (deleteMembersUnconditionally) {
					usersToDelete.add(user);
				} else {
					// Only users who exist nowhere outside the doomed set.
					long elsewhere = TeamMemberEntity.<TeamMemberEntity>list("user", user).stream()
							.filter(m -> !doomedTeamIds.contains(m.getTeam().id))
							.count();
					if (elsewhere == 0) usersToDelete.add(user);
				}
			}
		}

		for (TeamEntity team : teams) {
			for (PollEntity poll : PollEntity.<PollEntity>list("team", team)) {
				report.ballots += purgePollInternal(poll);
				report.polls++;
			}
		}
		entityManager.flush();

		// Rights to vote are deleted BY TEAM, never by re-deriving HMAC(secret, email | teamId).
		// findByVoterAndTeam() would need the same server secret that created them, and the sweep runs
		// against databases seeded by a different deployment - on liquido-int it silently matched
		// nothing, leaving righttovote rows that then blocked the team delete on
		// righttovote.team_id. The team column needs no secret and cannot miss.
		for (TeamEntity team : teams) {
			List<RightToVoteEntity> rightsToVote = RightToVoteEntity.list("team", team);
			// Detach the delegation graph first: righttovote references itself via delegated_to, so a
			// row somebody still delegates to cannot be deleted.
			for (RightToVoteEntity rtv : rightsToVote) {
				rtv.removeDelegationToProxy();
				new HashSet<>(rtv.getDelegations()).forEach(RightToVoteEntity::removeDelegationToProxy);
				rtv.setPublicProxy(null);
			}
			entityManager.flush();
			for (RightToVoteEntity rtv : rightsToVote) {
				OneTimeVotingToken.delete("rightToVote", rtv);
				rtv.delete();
			}
		}
		entityManager.flush();

		for (TeamEntity team : teams) {
			TeamMemberEntity.<TeamMemberEntity>list("team", team).forEach(TeamMemberEntity::delete);
		}
		entityManager.flush();

		for (TeamEntity team : teams) {
			team.delete();
			report.teams++;
		}
		entityManager.flush();

		for (UserEntity user : usersToDelete) {
			deleteUser(user);
			report.users++;
		}
		entityManager.flush();

		log.info("PURGED {}", report);
		return report;
	}

	/** Purge a single team. See {@link #purgeTeams}. */
	@Transactional
	public PurgeReport purgeTeam(String teamName, boolean deleteMembersUnconditionally) {
		return purgeTeams(List.of(teamName), deleteMembersUnconditionally);
	}

	/**
	 * Delete ONE poll with everything hanging off it, leaving its team and members alone.
	 * Used by the sweep to remove polls that tests appended to the seed team.
	 */
	@Transactional
	public PurgeReport purgePoll(PollEntity poll) {
		assertPurgeAllowed(jdbcUrl);
		PurgeReport report = new PurgeReport();
		report.ballots = purgePollInternal(poll);
		report.polls = 1;
		entityManager.flush();
		return report;
	}

	/** @return how many ballots were deleted. Caller is responsible for the surrounding transaction. */
	private int purgePollInternal(PollEntity poll) {
		// 1. Break the polls.winner_id -> proposals cycle BEFORE the proposals go, and flush so the
		//    UPDATE really reaches the database ahead of the DELETEs.
		if (poll.getWinner() != null) {
			poll.setWinner(null);
			poll.persist();
			entityManager.flush();
		}

		// 2. Ballots as ENTITIES, clearing voteOrder first: a bulk delete would leave ballot_voteorder
		//    rows pointing at proposals we are about to delete.
		List<BallotEntity> ballots = BallotEntity.list("poll", poll);
		for (BallotEntity ballot : ballots) {
			ballot.getVoteOrder().clear();
			ballot.delete();
		}
		OneTimeVotingToken.delete("poll", poll);
		entityManager.flush();

		// 3. Proposals, clearing the supporters join first for the same reason.
		for (ProposalEntity proposal : ProposalEntity.<ProposalEntity>list("poll", poll)) {
			proposal.getSupporters().clear();
			proposal.setPoll(null);
			proposal.delete();
		}
		entityManager.flush();

		poll.delete();
		return ballots.size();
	}

	/**
	 * Delete one user who belongs to no team at all.
	 *
	 * <p>Separate entry point from {@link #purgeTeams}, because an orphan has no team to purge through
	 * - typically it survived an older purge by belonging to a second team at the time, and then that
	 * team went away too. Their rights to vote are found by scan rather than by team for the same
	 * reason: {@code findByVoterAndTeam} needs a team that no longer exists.
	 */
	@Transactional
	public void deleteOrphanedUser(UserEntity user) {
		assertPurgeAllowed(jdbcUrl);
		for (RightToVoteEntity rtv : RightToVoteEntity.<RightToVoteEntity>listAll()) {
			// A right to vote holds no reference back to its user (that is the whole point), so the only
			// way to match one is to re-derive it per team the user could have belonged to. There is no
			// team left here, so instead drop any right to vote nobody can reach: see the publicProxy
			// detach in deleteUser() for the one link that does name a user.
			if (user.equals(rtv.getPublicProxy())) rtv.setPublicProxy(null);
		}
		entityManager.flush();
		deleteUser(user);
		entityManager.flush();
	}

	private void deleteRightToVote(RightToVoteEntity rightToVote) {
		// Detach both directions of the delegation graph: righttovote is self-referencing, so a row
		// still pointed at by delegated_to cannot be deleted.
		rightToVote.removeDelegationToProxy();
		new HashSet<>(rightToVote.getDelegations()).forEach(RightToVoteEntity::removeDelegationToProxy);
		OneTimeVotingToken.delete("rightToVote", rightToVote);
		rightToVote.delete();
	}

	/**
	 * Delete one user and everything that points at them.
	 *
	 * <p>The references were read off the live schema, not guessed: delegations (fromuser_id,
	 * toproxy_id AND createdby_id), password_reset_tokens, webauthncredential, proposal_supporters,
	 * righttovote.publicproxy_id, team_members, and the createdby_id attribution on teams, polls and
	 * proposals. The last group is NULLed rather than deleted - those rows may belong to a team we are
	 * not purging, and losing "who created this" is preferable to deleting somebody else's poll.
	 */
	private void deleteUser(UserEntity user) {
		DelegationEntity.delete("fromUser = ?1 or toProxy = ?1 or createdBy = ?1", user);
		PasswordResetToken.delete("user", user);
		TeamMemberEntity.delete("user", user);

		// WebAuthn credentials are deliberately NOT deleted here. UserEntity.webAuthnCredentials is
		// cascade=ALL with orphanRemoval, so user.delete() below already removes them - and a bulk
		// delete first would take the rows out behind Hibernate's back, leaving the cascade to delete
		// rows that are already gone and fail with "expected row count 1 but was 0".

		// Any right to vote still naming this user as a PUBLIC proxy, in any team.
		RightToVoteEntity.<RightToVoteEntity>list("publicProxy", user)
				.forEach(rtv -> rtv.setPublicProxy(null));

		// Likes. proposal_supporters is a plain join table with no entity of its own.
		entityManager.createNativeQuery("delete from proposal_supporters where supporters_id = ?1")
				.setParameter(1, user.id).executeUpdate();

		// Attribution on rows that may outlive this user.
		entityManager.createNativeQuery("update teams set createdby_id = null where createdby_id = ?1")
				.setParameter(1, user.id).executeUpdate();
		entityManager.createNativeQuery("update polls set createdby_id = null where createdby_id = ?1")
				.setParameter(1, user.id).executeUpdate();
		entityManager.createNativeQuery("update proposals set createdby_id = null where createdby_id = ?1")
				.setParameter(1, user.id).executeUpdate();
		entityManager.flush();

		user.delete();
	}
}
