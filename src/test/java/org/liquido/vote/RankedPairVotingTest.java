package org.liquido.vote;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RankedPairVotingTest {

	@Test
	void calcDuelMatrixTreatsUnrankedCandidatesAsNeutral() {
		List<Long> allIds = List.of(1L, 2L, 3L, 4L, 5L);
		List<List<Long>> ballots = List.of(List.of(2L, 1L));

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		assertEquals(1, duelMatrix.get(1, 0)); // 2 > 1
		assertEquals(0, duelMatrix.get(0, 1));
		assertEquals(0, duelMatrix.get(1, 2)); // 2 vs 3 is neutral
		assertEquals(0, duelMatrix.get(2, 1));
		assertEquals(0, duelMatrix.get(0, 4)); // 1 vs 5 is neutral
		assertEquals(0, duelMatrix.get(4, 0));
	}

	@Test
	void calcRankedPairWinnersKeepsUncomparedCandidatesAsSources() {
		List<Long> allIds = List.of(1L, 2L, 3L, 4L, 5L);
		List<List<Long>> ballots = List.of(List.of(2L, 1L));

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		Set<Integer> winnerIndexes = Set.copyOf(RankedPairVoting.calcRankedPairWinners(duelMatrix));
		assertEquals(Set.of(1, 2, 3, 4), winnerIndexes);
	}

	@Test
	void calcDuelMatrixRejectsUnknownCandidateIdInBallot() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = List.of(List.of(1L, 99L));

		assertThrows(IllegalArgumentException.class, () -> RankedPairVoting.calcDuelMatrix(allIds, ballots));
	}
}
