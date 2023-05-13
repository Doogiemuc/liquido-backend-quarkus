package org.liquido.vote;

import lombok.Data;
import lombok.NonNull;

@Data
public class CastVoteResponse {
	/** The cast ballot, includes its level, checksum and link to poll */
	@NonNull
	BallotEntity ballot;

	/**
	 * For how many delegees was this ballot cast, because of delegations.
	 * Some delegees may have already voted for themselves. Then voteCount is smaller
	 * than delegationCount.
	 */
	@NonNull
	Long voteCount;
}
