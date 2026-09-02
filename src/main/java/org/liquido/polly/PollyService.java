package org.liquido.polly;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.liquido.security.JwtTokenUtils;
import org.liquido.util.LiquidoConfig;
import org.liquido.vote.Matrix;
import org.liquido.vote.RankedPairVoting;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything a polly can do. {@link PollyGraphQL} is only the adapter.
 *
 * <p>The order of the checks in each method is part of the contract, not an accident - the
 * frontend branches on which error comes back first. Voting without a session on a polly that
 * exists must say NEED_PASSKEY, while editing a polly that does not exist must say
 * POLLY_NOT_FOUND even to a stranger. {@code polly-client.mock.js} in the frontend repo is the
 * reference implementation, and {@code tests/unit/polly-flow.spec.js} asserts the codes.
 */
@Slf4j
@ApplicationScoped
public class PollyService {

	/** A polly needs a question and something to choose between. */
	private static final int MIN_PROPOSALS = 2;

	/** Public ids are random; on the (vanishingly unlikely) collision, just draw again. */
	private static final int PUBLIC_ID_ATTEMPTS = 10;

	@Inject
	PollySession session;

	@Inject
	PollyKeys pollyKeys;

	@Inject
	JwtTokenUtils jwtTokenUtils;

	@Inject
	LiquidoConfig config;

	// ============================================================== the six operations

	/**
	 * Create a polly. It is open for voting immediately - there is no start step.
	 * The caller becomes the owner.
	 */
	@Transactional
	public PollyResponse createPolly(String title, List<String> proposalTitles) {
		String credentialId = session.requireCredentialId();       // NEED_PASSKEY comes first
		String cleanTitle = requireTitle(title);
		List<String> cleanProposals = requireProposalTitles(proposalTitles);

		PollyEntity polly = new PollyEntity(newUniquePublicId(), cleanTitle, pollyKeys.ownerKey(credentialId));
		polly.replaceProposals(cleanProposals);
		// Flush, not just persist: @CreationTimestamp fills createdAt when the INSERT is
		// generated, and the response we build below has to carry it.
		polly.persistAndFlush();

		log.info("Created {}", polly);
		return view(polly);
	}

	/**
	 * Read a polly by its public id.
	 *
	 * <p>Deliberately works with no session at all: the friend who just opened the link has not
	 * decided to vote yet, and demanding a passkey to merely look would be a wall in front of
	 * the one thing a polly is - a link you send to people.
	 */
	public PollyResponse getPolly(String publicId) {
		return view(findOrThrow(publicId));
	}

	/** Change the question or the options. Owner only, and only while nobody has voted. */
	@Transactional
	public PollyResponse editPolly(String publicId, String title, List<String> proposalTitles) {
		PollyEntity polly = findOrThrow(publicId);                 // not-found before not-owner
		requireOwner(polly);
		if (polly.isFinished()) throw new PollyException(PollyError.POLLY_FINISHED, "Polly is finished");
		if (PollyBallotEntity.countByPolly(polly) > 0) {
			throw new PollyException(PollyError.POLLY_ALREADY_STARTED,
					"Cannot change a polly once somebody has voted");
		}
		String cleanTitle = requireTitle(title);
		List<String> cleanProposals = requireProposalTitles(proposalTitles);

		polly.title = cleanTitle;
		// Safe to replace outright: we just established that no ballot references these options.
		polly.replaceProposals(cleanProposals);
		polly.persist();

		return view(polly);
	}

	/**
	 * Cast a ballot: the option ids in the voter's preferred order, favourite first.
	 * A voter may rank a subset - unranked options simply lose to every ranked one.
	 */
	@Transactional
	public PollyResponse voteInPolly(String publicId, List<String> voteOrder) {
		String credentialId = session.requireCredentialId();       // NEED_PASSKEY before POLLY_NOT_FOUND
		PollyEntity polly = findOrThrow(publicId);
		if (polly.isFinished()) throw new PollyException(PollyError.POLLY_FINISHED, "Polly is finished");

		String voterKey = pollyKeys.voterKey(credentialId, polly.publicId);
		// Fast path for a clean error message. The DB constraint below is the actual authority:
		// it is what still holds under a double tap or two concurrent requests.
		if (PollyBallotEntity.hasVoted(polly, voterKey)) {
			throw new PollyException(PollyError.ALREADY_VOTED, "You already voted in this polly");
		}

		PollyBallotEntity ballot = new PollyBallotEntity(polly, voterKey, resolveVoteOrder(polly, voteOrder));
		try {
			ballot.persist();
			PollyBallotEntity.flush();   // surface UNIQUE (polly_id, voter_key) here, not at commit
		} catch (PersistenceException e) {
			log.debug("Duplicate ballot rejected by the database constraint in polly {}", polly.publicId);
			throw new PollyException(PollyError.ALREADY_VOTED, "You already voted in this polly");
		}

		return view(polly);
	}

	/**
	 * Close the polly and work out the winner with Ranked Pairs. Owner only.
	 *
	 * <p>Idempotent: finishing an already finished polly returns it unchanged rather than
	 * erroring. The frontend hides the button once it is done, so a second call means a stale
	 * tab or a double tap, and neither deserves an error.
	 */
	@Transactional
	public PollyResponse finishPolly(String publicId) {
		PollyEntity polly = findOrThrow(publicId);
		requireOwner(polly);
		if (polly.isFinished()) return view(polly);

		polly.status = PollyStatus.FINISHED;
		// Truncated to SECONDS so the value we RETURN is the value we STORED. LocalDateTime.now() has
		// nanosecond resolution on Linux, but a Postgres "timestamp" holds only microseconds - so an
		// untruncated value came back from the DB different from the one this call had just returned,
		// and finishing twice appeared to move the end time. Seconds is ample for a poll.
		polly.votingEndAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
		polly.winner = determineWinner(polly);
		polly.persist();

		log.info("Finished {} with winner {}", polly, polly.winner);
		return view(polly);
	}

	/** Every polly this passkey created. This is why a polly needs no email address. */
	public List<PollyResponse> myPollys() {
		String ownerKey = session.requireOwnerKey();
		return PollyEntity.findByOwnerKey(ownerKey).stream().map(this::view).toList();
	}

	/**
	 * Mint a polly session without a passkey. <b>Development and testing only.</b>
	 *
	 * <p>Guarded exactly like {@code UserGraphQL.devLogin}: refused in prod, refused when no
	 * {@code liquido.dev-login-token} is configured, refused on a wrong token. Those three
	 * checks are the only thing standing between this and an open session vending machine, so
	 * they must stay together and stay first.
	 */
	public String devLoginPolly(String devLoginToken, String credentialId) {
		if (LaunchMode.current() == LaunchMode.NORMAL || ConfigUtils.isProfileActive("prod"))
			throw new PollyException(PollyError.NEED_PASSKEY, "devLoginPolly is not allowed in PROD!");
		if (config.devLoginTokenOpt().isEmpty())
			throw new PollyException(PollyError.NEED_PASSKEY, "No devLoginToken defined in our config");
		if (devLoginToken == null || !devLoginToken.equals(config.devLoginTokenOpt().get()))
			throw new PollyException(PollyError.NEED_PASSKEY, "Invalid devLoginToken passed.");
		if (credentialId == null || credentialId.isBlank())
			throw PollyException.invalid("devLoginPolly needs a credentialId to stand in for a passkey");

		log.info("devLoginPolly: minting a polly session without a passkey (development only)");
		return jwtTokenUtils.generatePollyToken(credentialId);
	}

	// ============================================================== the winner

	/**
	 * Tideman Ranked Pairs - the one piece of logic Polly and LIQUIDO team polls genuinely
	 * share. {@link RankedPairVoting} is already free of any entity coupling, so this is just
	 * the same thin mapping {@code PollService.calcWinnerOfPoll} does for team polls.
	 *
	 * <p>Two polly-specific rules on top:
	 * <ul>
	 *   <li><b>No ballots, no winner.</b> With an all-zero duel matrix every option is a source
	 *       of the graph and "the winner" would be meaningless. The frontend renders a null
	 *       winner fine.</li>
	 *   <li><b>Ties break deterministically</b> by sort order, so the same ballots always
	 *       produce the same winner. {@code calcRankedPairWinners} returns the graph's sources
	 *       as a HashSet, so simply taking the first would be arbitrary - fine for a team of
	 *       twenty where ties are rare, not fine for three friends and three options where
	 *       they are common.</li>
	 * </ul>
	 */
	PollyProposalEntity determineWinner(PollyEntity polly) {
		List<PollyBallotEntity> ballots = PollyBallotEntity.findByPolly(polly);
		if (ballots.isEmpty()) return null;

		// Index i in the duel matrix is position i in allIds, so the two lists must stay aligned.
		List<PollyProposalEntity> proposals = polly.proposals;
		List<Long> allIds = proposals.stream().map(p -> p.id).toList();
		List<List<Long>> idsInBallots = ballots.stream()
				.map(ballot -> ballot.voteOrder.stream().map(p -> p.id).toList())
				.collect(Collectors.toList());

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, idsInBallots);
		List<Integer> winnerIndexes = RankedPairVoting.calcRankedPairWinners(duelMatrix);

		return winnerIndexes.stream()
				.filter(i -> i != null && i >= 0 && i < proposals.size())
				.map(proposals::get)
				.min(Comparator.comparingInt(p -> p.sortOrder))     // deterministic tie-break
				.orElse(null);
	}

	// ============================================================== helpers

	/** Same "not found" whether the id is unknown or malformed - never an enumeration oracle. */
	private PollyEntity findOrThrow(String publicId) {
		if (publicId == null || publicId.isBlank()) throw PollyException.notFound("<blank>");
		return PollyEntity.findByPublicId(publicId).orElseThrow(() -> PollyException.notFound(publicId));
	}

	/** @throws PollyException NEED_PASSKEY without a session, NOT_POLLY_OWNER for someone else's polly */
	private void requireOwner(PollyEntity polly) {
		String ownerKey = session.requireOwnerKey();
		if (!ownerKey.equals(polly.ownerKey)) throw PollyException.notOwner();
	}

	private String requireTitle(String title) {
		String clean = title == null ? "" : title.trim();
		if (clean.isEmpty()) throw PollyException.invalid("A polly needs a title");
		return clean;
	}

	/** Trim, drop the blanks the UI's always-one-empty-row pattern produces, then require two. */
	private List<String> requireProposalTitles(List<String> proposalTitles) {
		List<String> clean = proposalTitles == null ? List.of() : proposalTitles.stream()
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(t -> !t.isEmpty())
				.toList();
		if (clean.size() < MIN_PROPOSALS) {
			throw PollyException.invalid("A polly needs at least " + MIN_PROPOSALS + " options");
		}
		return clean;
	}

	/**
	 * Turn the ids the client sent into this polly's own proposals, in the order given.
	 * Rejects an empty order, unknown ids and duplicates - the last one matters because
	 * {@code RankedPairVoting} throws {@code IllegalArgumentException} on a repeated id, and
	 * that would surface as a 500 rather than INVALID_POLLY.
	 */
	private List<PollyProposalEntity> resolveVoteOrder(PollyEntity polly, List<String> voteOrder) {
		if (voteOrder == null || voteOrder.isEmpty()) throw PollyException.invalid("Invalid vote order");

		Map<String, PollyProposalEntity> byId = polly.proposals.stream()
				.collect(Collectors.toMap(p -> String.valueOf(p.id), Function.identity()));

		Set<String> seen = new HashSet<>();
		List<PollyProposalEntity> resolved = new ArrayList<>(voteOrder.size());
		for (String id : voteOrder) {
			PollyProposalEntity proposal = id == null ? null : byId.get(id);
			if (proposal == null) throw PollyException.invalid("Invalid vote order");
			if (!seen.add(id)) throw PollyException.invalid("Invalid vote order: " + id + " appears twice");
			resolved.add(proposal);
		}
		return resolved;
	}

	/** Shape a polly for whoever is currently asking. Recomputed per request, never cached. */
	private PollyResponse view(PollyEntity polly) {
		return PollyResponse.of(
				polly,
				PollyBallotEntity.countByPolly(polly),
				session.ownerKey().orElse(null),
				session.voterKey(polly.publicId).orElse(null));
	}

	private String newUniquePublicId() {
		for (int i = 0; i < PUBLIC_ID_ATTEMPTS; i++) {
			String candidate = pollyKeys.newPublicId();
			if (PollyEntity.findByPublicId(candidate).isEmpty()) return candidate;
		}
		throw new IllegalStateException("Could not find an unused polly public id in " + PUBLIC_ID_ATTEMPTS + " attempts");
	}
}
