package org.liquido.vote;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.liquido.poll.PollEntity;
import org.liquido.poll.ProposalEntity;
import org.liquido.user.UserEntity;
import org.liquido.util.DoogiesUtil;
import org.liquido.util.LiquidoConfig;
import org.liquido.util.LiquidoException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * This service handles everything related to casting a vote.
 */
@Slf4j
@ApplicationScoped
public class CastVoteService {

	@Inject
	LiquidoConfig config;

	// Some more resources around secure authentication with tokens:
	//TODO: create really secure voterTokens like this: U2F  https://blog.trezor.io/why-you-should-never-use-google-authenticator-again-e166d09d4324
	//TODO: RSA Tokens  https://stackoverflow.com/questions/37722090/java-jwt-with-public-private-keys
	//OpenID Nice article  https://connect2id.com/learn/openid-connect#id-token

	/**
	 * When a user wants to cast a vote in LIQUIDO, then they need
	 *   1. A general RightToVote to be allowed to vote at all and
	 *   2. A one-time voterToken for that specific poll
	 *
	 * This method will generate a plainVoterToken, hash it and create a OneTimeVotingTokenEntity from this hash.
	 *
	 * @param voter the currently logged in and correctly authenticated user
	 * @param poll the voterToken is only valid for one vote in this poll
	 * @return the voter's plainVoterToken, that only this user must know
	 */
	@Transactional
	public String createOneTimeVoterToken(UserEntity voter, PollEntity poll) throws LiquidoException {
		// [SECURITY] Deliberately does NOT log who asked. Token issuance is the one authenticated
		// step in voting, and casting is anonymous -- so a line naming the voter here, next to a
		// line recording a cast in the same poll seconds later, reconstructs voter -> ballot from
		// the log alone, defeating the separation the whole design is built around.
		log.debug("createOneTimeVoterToken: for poll.id={}", poll.id);
		if (DoogiesUtil.isEmpty(voter.getEmail()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CREATE_VOTING_TOKEN, "Need voter with email to create a OneTimeVoterToken!");

		// A right to vote is scoped to ONE team, so the poll's team is what identifies it. This is
		// also where the team boundary for voting is enforced: token issuance is the one authenticated
		// step, and a voter without a right to vote in THIS poll's team gets no token at all.
		RightToVoteEntity rightToVote = RightToVoteEntity.findByVoterAndTeam(voter, poll.getTeam(), config)
				.orElseThrow(LiquidoException.supplyAndLog(LiquidoException.Errors.CANNOT_CREATE_VOTING_TOKEN, "You are not allowed to vote! No RightToVote!"));
		if (!rightToVote.isValid()) throw new LiquidoException(LiquidoException.Errors.CANNOT_CREATE_VOTING_TOKEN, "Your right to vote has expired.");

		// Create a new one-time voter token for this poll and hash it with an additional salt.
		// The hash input must exactly be the same as in castVote(). It CANNOT contain voter.id, because that is not known in castVote()
		// I thought about adding an additional voterSecret, that a user passes in here and in castVote.
		// But plainVoterToken is already random. This would only add little security.
		// At most ONE live token per voter per poll: revoke anything still outstanding for this
		// (voter, poll) before minting the replacement. See OneTimeVotingToken.revokeTokensOf().
		long revoked = OneTimeVotingToken.revokeTokensOf(rightToVote, poll);
		if (revoked > 0) log.debug("createOneTimeVoterToken: revoked {} outstanding token(s) for poll.id={}", revoked, poll.id);

		String plainVoterToken  =  UUID.randomUUID().toString();
		String hashedVoterToken =  calcHashedVoterToken(plainVoterToken, poll.id);
		int validMinutes = config.voterTokenExpirationMinutes();
		OneTimeVotingToken ott = OneTimeVotingToken.buildAndPersist(hashedVoterToken, poll, rightToVote, validMinutes);

		// Only return the plainOneTimeToken to the voter. They can then use this token to anonymously cast one vote in this poll.
		return plainVoterToken;
	}

	/**
	 * Consume the one-time voterToken for a poll.
	 * Check that the plainVoterToken hashes to a known OneTimeVotingTokenEntity.
	 * And that a valid RightToVote is linked.
	 *
	 * <pre>plainVoterToken --hashed--> OneTimeVotingTokenEntity --> RightToVoteEntity</pre>
	 *
	 * If everything is fine, then extends the validity of the RightToVoteEntity and
	 * delete the consumed OnetimeVotingTokenEntity.
	 *
	 * @param plainVoterToken the plain voter token that the voter sent
	 * @param poll the poll we want to vote in.
	 * @return the voter's rightToVote if voterToken is valid
	 * @throws LiquidoException when voterToken is invalid or its corresponding rightToVote is not known.
	 */
	public RightToVoteEntity consumeVoterToken(String plainVoterToken, PollEntity poll) throws LiquidoException {
		if (plainVoterToken == null || plainVoterToken.length() < 10)
			throw new LiquidoException(LiquidoException.Errors.INVALID_VOTER_TOKEN, "Need plainVoterToken to cast a vote.");

		// check voterToken
		String hashedVoterToken = calcHashedVoterToken(plainVoterToken, poll.id);
		//log.debug("consumeVoterToken: plainVoterToken = {} hashedVoterToken = {} in poll.id = {}", "XXXXXX", hashedVoterToken, poll.id);
		OneTimeVotingToken voterToken = OneTimeVotingToken.<OneTimeVotingToken>findByIdOptional(hashedVoterToken)
				.orElseThrow(LiquidoException.supply(LiquidoException.Errors.INVALID_VOTER_TOKEN, "You don't have a voter token for this poll."));
		if (LocalDateTime.now().isAfter(voterToken.expiresAt))
			throw new LiquidoException(LiquidoException.Errors.INVALID_VOTER_TOKEN, "This voterToken is expired.");

		// check that the VoterToken linked to a valid right to vote.
		RightToVoteEntity rightToVote = voterToken.getRightToVote();
		if (rightToVote == null || !rightToVote.isValid())
			throw new LiquidoException(LiquidoException.Errors.INVALID_VOTER_TOKEN, "You are not allowed to cast a vote. (no valid rightToVote)");
		// and extends the RightToVote's expiration time.
		rightToVote.renewExpiry(config.rightToVoteExpirationDays());
		rightToVote.persist();

		// Finally delete the consumed voterToken. It may only be used ONCE!
		voterToken.delete();

		return voterToken.getRightToVote();
	}

	/**
	 * Hash a plain voter token. Server will add an internal hashSecret for more security.
	 * Keep in mind that the hashedVoterToken is anonymous. It is not traceable back to a voter.
	 *
	 * @param plainVoterToken the plain token
	 * @param pollId voter token is only valid for this poll
	 * @return the hashed voterToken
	 */
	private String calcHashedVoterToken(String plainVoterToken, Long pollId) {
		return DigestUtils.sha3_256Hex(plainVoterToken + pollId + config.hashSecret());
	}


	/**
	 * User casts their own vote. Keep in mind that this method is called anonymously. No UserEntity involved.
	 * If that user is a proxy for other voters, then their ballots will also be added automatically.
	 *
	 * @param plainVoterToken The anonymous voter must present a valid plainVoterToken that he fetched via {@link #createOneTimeVoterToken(UserEntity, PollEntity)}
	 * @param poll the poll to cast the vote in.
	 * @param voteOrderIds ordered list of proposal.IDs as sorted by the user. No ID may appear more than once!
	 * @return CastVoteResponse with ballot and the voteCount how often the vote was actually counted for this proxy. (Some voters might already have voted on their own.)
	 * @throws LiquidoException when voterToken is invalid or there is <b>anything</b> suspicious with the ballot
	 */
	@Transactional
	public CastVoteResponse castVote(String plainVoterToken, PollEntity poll, List<Long> voteOrderIds) throws LiquidoException {
		//TODO: For even more security we could implement a challenge response mechanism for verifying plainVoterToken
		// [SECURITY] The ranking itself is not logged. It is the content of a secret ballot, and at
		// INFO it would be on in production; anyone who could correlate it with an issuance line
		// would learn not just that someone voted but how. Only the poll is recorded.
		log.info("castVote in poll.id={}", poll.id);

		// We need a poll
		if (poll == null || poll.getId() == null)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Need poll to cast vote");

		// Poll must be in status voting
		if (!PollEntity.PollStatus.VOTING.equals(poll.getStatus()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Poll must be in status VOTING");

		// voterOrder must contain at least one element
		if (voteOrderIds == null || voteOrderIds.isEmpty())
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Need voteOrder to cast vote");

		// Convert voteOrderIds to list of actual ProposalEntities from poll.
		// Therefore, voteOrderIds must only contain proposal.ids from this poll, and it must not contain any ID more than once!
		List<ProposalEntity> voteOrder = new ArrayList<>();
		Map<Long, ProposalEntity> pollProposals = new HashMap<>();
		for (ProposalEntity prop : poll.getProposals()) {
			pollProposals.put(prop.getId(), prop);
		}

		for (Long propId : voteOrderIds) {
			ProposalEntity prop = pollProposals.get(propId);
			if (prop == null)
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "All proposal you want to vote for must be from poll(id="+poll.id+"). Proposal(id="+propId+") isn't");
			if (voteOrder.contains(prop))
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Your voteOrder must not contain any proposal twice! Proposal(id="+ propId+") appears twice.");
			voteOrder.add(prop);
		}

		// Validate voter token and lookup linked RightToVote
		RightToVoteEntity rightToVote = consumeVoterToken(plainVoterToken, poll);

		// Cast at level 0 for the voter themselves, then recursively for everyone delegating to them.
		return castVoteRec(poll, 0, voteOrder, rightToVote);
	}

	/**
	 * This method calls itself recursively. The <b>upsert</b> algorithm for storing a ballot works like this:
	 *
	 * 1) Check the integrity of the passed newBallot. Especially check the validity of its RightToVoteEntity.
	 *    The rightToVote must be known.
	 *
	 * 2) IF there is NO existing ballot for this poll yet,
	 *    THEN save a new ballot
	 *    ELSE // a ballot already exists
	 *      IF the level of the existing ballot is SMALLER than the passed newBallot.level
	 *      THEN do NOT update the existing ballot, because it was cast by a lower proxy or the voter himself
	 *      ELSE IF newBallot.level == 0 AND the existing ballot's level is ALSO 0
	 *      THEN reject: a vote that was cast directly cannot be changed. Level 0 is only ever built at
	 *           the top of {@link #castVote}, for whichever RightToVote's token was just consumed - so
	 *           existingBallot.level == 0 here can only mean this exact voter already cast their own
	 *           ballot before. This check must NOT fire for level &gt; 0: those ballots are only ever
	 *           produced inside step 3 below, as a proxy's cast cascading to a delegee, and that path
	 *           must keep updating even at an equal level - e.g. a delegee who switches proxies gets a
	 *           new cascade that legitimately lands at the same numeric level as the old proxy's now
	 *           stale one, and that is not the delegee "changing their own vote".
	 *      ELSE update the existing ballot's level and vote order
	 *
	 *  3) FOR EACH directly delegated RightToVote
	 *              build a childBallot and recursively cast this childBallot.
	 *
	 *  Remark: The child ballot might not be stored when there already is one with a smaller level. This is
	 *          our recursion limit.
	 *
	 * @param newBallot the ballot that shall be stored. The ballot will be checked very thoroughly. Especially if the ballot's right to vote is valid.
	 * @return the newly created or updated existing ballot OR
	 *         null if the ballot wasn't stored due to an already existing ballot with a smaller level.
	 * @throws LiquidoException with {@link LiquidoException.Errors#ALREADY_VOTED} when this voter already cast a direct (level 0) vote in this poll.
	 */
	//@Transactional Do not open a transaction for each recursion!
	private CastVoteResponse castVoteRec(PollEntity poll, int level, List<ProposalEntity> voteOrder, RightToVoteEntity rightToVote) throws LiquidoException {
		// The persistent, team-scoped RightToVote is what we RECURSE over; the poll-scoped pseudonym
		// derived from it is the only thing that ever reaches a ballot row. That split is what lets
		// delegation be a standing arrangement while ballots stay unlinkable across polls.
		String ballotPseudonym = rightToVote.deriveBallotPseudonym(poll.id, config);
		BallotEntity newBallot = new BallotEntity(poll, level, voteOrder, ballotPseudonym);
		log.debug("   castVoteRec: {}", newBallot);

		//----- check the validity of the ballot
		checkBallot(newBallot);

		//----- check if there already is a ballot, then update that, otherwise save newBallot
		Optional<BallotEntity> existingBallotOpt = BallotEntity.findByPollAndPseudonym(poll, ballotPseudonym);
		BallotEntity savedBallot;

		if (existingBallotOpt.isPresent()) {
			//----- Update existing ballot if the level of newBallot is smaller.  Proxy must not overwrite a voter's own vote OR a vote from a proxy below him
			BallotEntity existingBallot = existingBallotOpt.get();
			if (existingBallot.getLevel() < newBallot.getLevel()) {
				log.debug("   Voter has already voted for himself {}", existingBallot);
				return null;
			}
			// A vote that was cast directly cannot be changed. Level 0 is only ever built at the top of
			// castVote(), for whichever RightToVote's token was just consumed - so if a level-0 ballot
			// already exists here, this exact voter already cast their own vote in this poll before.
			// Scoped to level 0 on BOTH sides deliberately: it must not fire when existingBallot.level
			// is > 0 (that is a proxy's earlier cascade, and the voter's own first direct vote must
			// still be allowed to override it - see the class-level algorithm doc), and it must not fire
			// for a delegee's cascaded ballot inside the recursion below, where an equal level can
			// legitimately recur (e.g. after the delegee switches to a different proxy).
			if (newBallot.getLevel() == 0 && existingBallot.getLevel() == 0) {
				log.debug("   Rejecting: RightToVote has already cast a direct vote in poll.id={}", newBallot.getPoll().id);
				throw new LiquidoException(LiquidoException.Errors.ALREADY_VOTED,
						"You have already voted in this poll. A cast vote cannot be changed.");
			}
			log.debug("  Update existing ballot {}", existingBallot.id);
			existingBallot.setVoteOrder(newBallot.getVoteOrder());
			existingBallot.setLevel(newBallot.getLevel());
			existingBallot.persist();
			savedBallot = existingBallot;
		} else {
			//----- If there is no existing ballot yet with that rightToVote, then builder a completely new one.
			log.debug("   Saving new ballot");
			try {
				newBallot.persist();
				BallotEntity.flush();   // surface uq_ballot_poll_voter HERE, not at commit
			} catch (PersistenceException e) {
				// The lookup above said there was no ballot, the database says there is. That is exactly
				// the read-then-insert race the constraint exists for: another request for this same
				// RightToVote inserted a ballot in between. One vote per voter per poll stands, and the
				// caller gets a real error rather than an opaque INTERNAL_ERROR at commit time.
				log.debug("   Duplicate ballot rejected by uq_ballot_poll_voter in poll.id={}", newBallot.getPoll().id);
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE,
						"Cannot cast vote: a vote for this poll has already been counted.");
			}
			savedBallot = newBallot;
		}

		//----- When a user is a proxy, then recursively cast a ballot for each delegated rightToVote
		long voteCount = 0;   // count for how many delegees (that have not voted yet for themselves) the proxy's ballot is also cast
		for (RightToVoteEntity delegatedRightToVote : rightToVote.delegations) {
			List<ProposalEntity> voteOrderClone = new ArrayList<>(voteOrder);   // BUGFIX for org.hibernate.HibernateException: Found shared references to a collection
			// Each delegee derives their OWN pseudonym, so the proxy's ranking lands on a row that is
			// still the delegee's ballot -- and the uniqueness constraint is not tripped by a proxy
			// writing one ballot per delegee.
			CastVoteResponse childRes = castVoteRec(poll, level + 1, voteOrderClone, delegatedRightToVote);  // returns null when an existing childBallot has a smaller level => ends the recursion
			if (childRes != null) voteCount += 1 + childRes.getVoteCount();
		}

		// voteCount does NOT include the voters (or proxies) own ballot
		return new CastVoteResponse(savedBallot, voteCount);
	}

	/**
	 * Check if a ballot is valid before we store it
	 * @param ballot a cast vote with a sorted voteOrder inside.
	 * @throws LiquidoException when something inside ballot is invalid
	 */
	public void checkBallot(BallotEntity ballot) throws LiquidoException {
		// check that poll is actually in voting phase and has at least two alternative proposals
		PollEntity poll = ballot.getPoll();
		if (!PollEntity.PollStatus.VOTING.equals(poll.getStatus())) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Poll must be in voting phase.");
		}
		if (poll.getProposals().size() < 2)
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Poll must have at least two alternative proposals.");

		// check that voter Order is not empty
		if (ballot.getVoteOrder().isEmpty()) {
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE,"Cannot cast vote: VoteOrder is empty!");
		}

		// check that there is no duplicate vote for any one proposal
		HashSet<Long> proposalIds = new HashSet<>();
		for(ProposalEntity proposal : ballot.getVoteOrder()) {
			if (proposalIds.contains(proposal.getId())) {
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Duplicate vote for proposal_id="+proposal.getId());
			} else {
				proposalIds.add(proposal.getId());
			}
		}

		// check that all proposals you want to vote for are in this poll and that they are also in voting phase
		for(ProposalEntity proposal : ballot.getVoteOrder()) {
			if (!proposal.getPoll().getId().equals(ballot.getPoll().getId()))   //BUGFIX: Cannot compare whole poll. Must compare IDs:  https://hibernate.atlassian.net/browse/HHH-3799  PersistentSet does not honor hashcode/equals contract when loaded eagerly
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: The Proposal(id="+proposal.getId()+") from your voteOrder is not part of poll(id="+ballot.getPoll().getId()+")!");
			if (!ProposalEntity.LawStatus.VOTING.equals(proposal.getStatus())) {
				throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: proposals must be in voting phase.");
			}
		}

		// A ballot must carry a pseudonym. It deliberately cannot be checked against a RightToVote:
		// the ballot holds no reference to one, which is the entire point of deriving it per poll.
		// The right to vote behind it was already validated in consumeVoterToken() before we got here.
		if (DoogiesUtil.isEmpty(ballot.getBallotPseudonym()))
			throw new LiquidoException(LiquidoException.Errors.CANNOT_CAST_VOTE, "Cannot cast vote: Ballot must have a pseudonym.");
	}


	//TODO: automatically refresh RightToVote, e.g. when vote is casted
	/**
	 * Refresh the expiration time of this valid rightToVote.
	 * @param rightToVote the voter's encoded right to vote
	 */
	@Transactional
	public void refreshRightToVote(RightToVoteEntity rightToVote) {
		rightToVote.renewExpiry(config.rightToVoteExpirationDays());
		rightToVote.persist();
	}

}