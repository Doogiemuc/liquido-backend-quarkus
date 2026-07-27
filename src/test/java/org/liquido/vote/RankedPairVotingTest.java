package org.liquido.vote;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankedPairVotingTest {

	//
	// ===== calcDuelMatrix =====
	//

	@Test
	void calcDuelMatrixCountsEveryPairwisePreferenceOfOneBallot() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = List.of(List.of(1L, 2L, 3L)); // 1 > 2 > 3

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		assertEquals(3, duelMatrix.getRows());
		assertEquals(3, duelMatrix.getCols());

		assertEquals(1, duelMatrix.get(0, 1)); // 1 > 2
		assertEquals(1, duelMatrix.get(0, 2)); // 1 > 3 (transitive pair is counted, too)
		assertEquals(1, duelMatrix.get(1, 2)); // 2 > 3

		assertEquals(0, duelMatrix.get(1, 0)); // no ballot prefers 2 over 1
		assertEquals(0, duelMatrix.get(2, 0));
		assertEquals(0, duelMatrix.get(2, 1));

		// A candidate is never preferred over itself
		for (int i = 0; i < 3; i++) assertEquals(0, duelMatrix.get(i, i));
	}

	@Test
	void calcDuelMatrixSumsUpAllBallots() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = List.of(
				List.of(1L, 2L, 3L),
				List.of(1L, 3L, 2L),
				List.of(2L, 1L, 3L)
		);

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		assertEquals(2, duelMatrix.get(0, 1)); // 1 > 2 on ballot 1 and 2
		assertEquals(1, duelMatrix.get(1, 0)); // 2 > 1 on ballot 3
		assertEquals(3, duelMatrix.get(0, 2)); // 1 > 3 on every ballot
		assertEquals(0, duelMatrix.get(2, 0));
		assertEquals(2, duelMatrix.get(1, 2)); // 2 > 3 on ballot 1 and 3
		assertEquals(1, duelMatrix.get(2, 1)); // 3 > 2 on ballot 2
	}

	@Test
	void calcDuelMatrixRanksUnrankedCandidatesBelowRankedCandidates() {
		List<Long> allIds = List.of(1L, 2L, 3L, 4L, 5L);
		List<List<Long>> ballots = List.of(List.of(2L, 1L));

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		assertEquals(1, duelMatrix.get(1, 0)); // 2 > 1
		assertEquals(0, duelMatrix.get(0, 1));
		assertEquals(1, duelMatrix.get(1, 2)); // 2 > 3 because unranked candidates are treated as lower
		assertEquals(0, duelMatrix.get(2, 1));
		assertEquals(1, duelMatrix.get(0, 4)); // 1 > 5 for the same reason
		assertEquals(0, duelMatrix.get(4, 0));
	}

	@Test
	void calcDuelMatrixLeavesUnrankedCandidatesNeutralAmongThemselves() {
		List<Long> allIds = List.of(1L, 2L, 3L, 4L, 5L);
		List<List<Long>> ballots = List.of(List.of(2L, 1L)); // 3, 4 and 5 are unranked

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		// The ballot expresses no preference between the unranked candidates 3, 4 and 5.
		for (int i = 2; i < 5; i++) {
			for (int j = 2; j < 5; j++) {
				assertEquals(0, duelMatrix.get(i, j), "unranked " + i + " must not be preferred over unranked " + j);
			}
		}
	}

	@Test
	void calcDuelMatrixWithoutAnyBallotReturnsZeroMatrix() {
		List<Long> allIds = List.of(1L, 2L, 3L);

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, List.of());

		assertEquals(3, duelMatrix.getRows());
		assertEquals(3, duelMatrix.getCols());
		assertEquals(new Matrix(3, 3), duelMatrix);
	}

	@Test
	void calcDuelMatrixWithEmptyBallotCountsNoPreference() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = List.of(List.of()); // voter ranked nobody

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		assertEquals(new Matrix(3, 3), duelMatrix);
	}

	@Test
	void calcDuelMatrixRejectsNullParams() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = List.of(List.of(1L, 2L));

		assertThrows(IllegalArgumentException.class, () -> RankedPairVoting.calcDuelMatrix(null, ballots));
		assertThrows(IllegalArgumentException.class, () -> RankedPairVoting.calcDuelMatrix(allIds, null));
	}

	@Test
	void calcDuelMatrixRejectsNullBallot() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = Collections.singletonList(null);

		assertThrows(IllegalArgumentException.class, () -> RankedPairVoting.calcDuelMatrix(allIds, ballots));
	}

	@Test
	void calcDuelMatrixRejectsUnknownCandidateIdInBallot() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = List.of(List.of(1L, 99L));

		assertThrows(IllegalArgumentException.class, () -> RankedPairVoting.calcDuelMatrix(allIds, ballots));
	}

	@Test
	void calcDuelMatrixRejectsDuplicateCandidateIdInBallot() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = List.of(Arrays.asList(1L, 2L, 1L));

		assertThrows(IllegalArgumentException.class, () -> RankedPairVoting.calcDuelMatrix(allIds, ballots));
	}

	//
	// ===== calcRankedPairWinners =====
	//

	@Test
	void calcRankedPairWinnersFindsUnanimousWinner() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = List.of(
				List.of(2L, 1L, 3L),
				List.of(2L, 1L, 3L),
				List.of(2L, 3L, 1L)
		); // everybody puts candidate 2 first

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		assertEquals(List.of(1), RankedPairVoting.calcRankedPairWinners(duelMatrix)); // index 1 == id 2
	}

	@Test
	void calcRankedPairWinnersFindsCondorcetWinner() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = List.of(
				List.of(1L, 2L, 3L),
				List.of(1L, 3L, 2L),
				List.of(2L, 1L, 3L)
		); // candidate 1 beats 2 (2:1) and beats 3 (3:0)

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		assertEquals(List.of(0), RankedPairVoting.calcRankedPairWinners(duelMatrix));
	}

	@Test
	void calcRankedPairWinnersKeepsUnrankedCandidatesBelowRankedCandidates() {
		List<Long> allIds = List.of(1L, 2L, 3L, 4L, 5L);
		List<List<Long>> ballots = List.of(List.of(2L, 1L));

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		Set<Integer> winnerIndexes = Set.copyOf(RankedPairVoting.calcRankedPairWinners(duelMatrix));
		assertEquals(Set.of(1), winnerIndexes);
	}

	/**
	 * The Tennessee capital example from <a href="https://en.wikipedia.org/wiki/Ranked_pairs">Wikipedia</a>.
	 * Memphis has the most first preferences, but Nashville is the Condorcet winner.
	 */
	@Test
	void calcRankedPairWinnersSolvesTheTennesseeExample() {
		long memphis = 1L, nashville = 2L, chattanooga = 3L, knoxville = 4L;
		List<Long> allIds = List.of(memphis, nashville, chattanooga, knoxville);

		List<List<Long>> ballots = new ArrayList<>();
		addBallots(ballots, 42, List.of(memphis, nashville, chattanooga, knoxville));
		addBallots(ballots, 26, List.of(nashville, chattanooga, knoxville, memphis));
		addBallots(ballots, 15, List.of(chattanooga, knoxville, nashville, memphis));
		addBallots(ballots, 17, List.of(knoxville, chattanooga, nashville, memphis));

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		assertEquals(42, duelMatrix.get(0, 1)); // Memphis > Nashville
		assertEquals(58, duelMatrix.get(1, 0)); // Nashville > Memphis
		assertEquals(68, duelMatrix.get(1, 2)); // Nashville > Chattanooga
		assertEquals(83, duelMatrix.get(2, 3)); // Chattanooga > Knoxville

		assertEquals(List.of(1), RankedPairVoting.calcRankedPairWinners(duelMatrix)); // Nashville
	}

	/**
	 * A Condorcet cycle: 1 &gt; 2 &gt; 3 &gt; 1. Ranked Pairs locks in the strongest comparisons first,
	 * so the weakest one (3 &gt; 1) is dropped because it would close the cycle. Candidate 1 wins.
	 */
	@Test
	void calcRankedPairWinnersResolvesCondorcetCycleByDroppingTheWeakestComparison() {
		Matrix duelMatrix = new Matrix(3, 3);
		duelMatrix.set(0, 1, 7); duelMatrix.set(1, 0, 3); // 1 beats 2 with 7 votes  (strongest)
		duelMatrix.set(1, 2, 6); duelMatrix.set(2, 1, 4); // 2 beats 3 with 6 votes
		duelMatrix.set(2, 0, 5); duelMatrix.set(0, 2, 4); // 3 beats 1 with 5 votes  (weakest -> dropped)

		assertEquals(List.of(0), RankedPairVoting.calcRankedPairWinners(duelMatrix));
	}

	@Test
	void calcRankedPairWinnersReturnsAllCandidatesWhenEveryDuelIsTied() {
		List<Long> allIds = List.of(1L, 2L, 3L);
		List<List<Long>> ballots = List.of(
				List.of(1L, 2L, 3L),
				List.of(3L, 2L, 1L)
		); // the two ballots cancel each other out completely

		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(allIds, ballots);

		Set<Integer> winnerIndexes = Set.copyOf(RankedPairVoting.calcRankedPairWinners(duelMatrix));
		assertEquals(Set.of(0, 1, 2), winnerIndexes);
	}

	@Test
	void calcRankedPairWinnersWithOnlyOneCandidate() {
		Matrix duelMatrix = RankedPairVoting.calcDuelMatrix(List.of(1L), List.of(List.of(1L)));

		assertEquals(List.of(0), RankedPairVoting.calcRankedPairWinners(duelMatrix));
	}

	@Test
	void calcRankedPairWinnersOfEmptyPollHasNoWinner() {
		List<Integer> winners = RankedPairVoting.calcRankedPairWinners(new Matrix(0, 0));

		assertTrue(winners.isEmpty());
	}

	/** Add the same ballot {@code count} times, e.g. to model "42% of the voters voted like this". */
	private static void addBallots(List<List<Long>> ballots, int count, List<Long> ballot) {
		for (int i = 0; i < count; i++) ballots.add(ballot);
	}
}
