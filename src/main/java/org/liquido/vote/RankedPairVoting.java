package org.liquido.vote;

//Implementation note: This class is completely independent of any Liquido data model. It's just the algorithm

import java.util.*;

/**
 * Ranked Pairs voting
 * Ranked pairs (RP) or the Tideman method is an electoral system developed in 1987 by Nicolaus Tideman that selects a single winner using votes that express preferences.
 * If there is a candidate who is preferred over the other candidates, when compared in turn with each of the others, RP guarantees that candidate will win. Because of this property, RP is, by definition, a Condorcet method.
 * <a href="https://en.wikipedia.org/wiki/Ranked_pairs">...</a>
 *
 * When Nicolaus Tideman published his paper on Ranked Pairs in 1987, he used Winning Margin as the sorting metric.
 * However, there is a crucial caveat. Tideman’s original mathematical proofs implicitly assumed complete ballots (where every voter ranks every single candidate). When ballots are complete, the total number of voters in every pairwise matchup is identical. Under those conditions, sorting by Winning Votes and sorting by Winning Margin will always produce the exact same sequence.
 * The divergence between the two only emerges when you introduce incomplete ballots (which you are allowing). Because the classical algorithm wasn't originally designed for partial participation, voting theorists have been fiercely debating whether Margin or Winning Votes is the "truer" adaptation ever since.
 */
public class RankedPairVoting {

	/**
	 * Sum up the pairwise comparison of proposals/candidates in every ballot.
	 * Count how many ballots prefer candidate i over candidate j.
	 * Ballots may be partial. Any candidate that is not ranked on a ballot is treated as ranked below
	 * every candidate that is listed on that ballot. Unranked candidates remain neutral among themselves.
	 *
	 * <h3>Example</h3>
	 * Given a vote with five candidates A,B,C,D and E, and a ballot that ranks the candidates in the order
	 * C &gt; D &gt; E:
	 * <pre>ballot = [C, D, E]</pre>
	 * This ballot does not express any preference between candidates A and B.
	 * It does express that the ranked candidates C, D, and E are preferred over the unranked candidates A and B.
	 * The algorithm will therefore count the following pairwise comparisons:
	 * <ul>
	 *   <li>C&gt;D, D&gt;E, and C&gt;E</li>
	 *   <li>C>A, C>B, D>A, D>B, E>A, E>B</li>
	 * </ul>
	 *
	 * @param allIds all proposal/candidate IDs that can be voted for in this poll.
	 * @param idsInBallots the list of ballots. Each ballot consists of an ordered list of proposal/candidate IDs from allIds
	 *                     A ballot does not necessarily need to contain all candidate IDs.
	 * @throws IllegalArgumentException when one of the required param is null
	 * @return the duelMatrix, which is a pairwise comparison of each preference i > j
	 */
	public static Matrix calcDuelMatrix(List<Long> allIds, List<List<Long>> idsInBallots)  {
		if (allIds == null || idsInBallots == null)
			throw new IllegalArgumentException("allIds and idsInBallots params must not be null!");


		// Reverse map IDs to their array index in allIds, which will be used as the row and col numbers in the duelMatrix
		HashMap<Long, Integer> id2index = new HashMap<>();
		int index = 0;
		for (Long id : allIds) {
			id2index.put(id, index++);
		}

		// DuelMatrix is a pairwise comparison of preferences proposal1.id > proposal2.id
		// Proposal IDs are mapped to row/col index in duelMatrix via the id2index map.
		Matrix duelMatrix = new Matrix(id2index.size(), id2index.size());

		for (List<Long> votedForIds : idsInBallots) {
			addBallotToDuelMatrix(duelMatrix, id2index, allIds, votedForIds);
		}

		return duelMatrix;
	}

	/**
	 * Add each pairwise comparison the dualMatrix
	 * @param duelMatrix pairwise comparisons of preferences
	 * @param id2index reverse map proposal IDs to the row/col index in duelMatrix
	 * @param allIds all proposal IDs that can be voted for
	 * @param votedForIds a ballot: the <b>ordered</b> list of IDs that this voter sorted according to his preferences
	 */
	private static void addBallotToDuelMatrix(Matrix duelMatrix,
											  Map<Long, Integer> id2index,
											  List<Long> allIds,
											  List<Long> votedForIds) {
		if (votedForIds == null) {
			throw new IllegalArgumentException("Ballot must not be null");
		}

		Set<Long> rankedIds = new HashSet<>(votedForIds.size());
		List<Integer> rankedIndexes = new ArrayList<>(votedForIds.size());
		for (Long votedForId : votedForIds) {
			Integer votedForIndex = id2index.get(votedForId);
			if (votedForIndex == null) {
				throw new IllegalArgumentException("Ballot contains unknown candidate id " + votedForId);
			}
			if (!rankedIds.add(votedForId)) {
				throw new IllegalArgumentException("Ballot must not contain duplicate candidate id " + votedForId);
			}
			rankedIndexes.add(votedForIndex);
		}

		List<Integer> unrankedIndexes = new ArrayList<>(allIds.size() - rankedIndexes.size());
		for (Long id : allIds) {
			if (!rankedIds.contains(id)) {
				unrankedIndexes.add(id2index.get(id));
			}
		}

		for (int i = 0; i < rankedIndexes.size(); i++) {
			// Add a preference favoriteIndex > unpreferredIndex for each pairwise comparison in the ballot.
			int favoriteIndex = rankedIndexes.get(i);
			for (int j = i + 1; j < rankedIndexes.size(); j++) {
				int unpreferredIndex = rankedIndexes.get(j);
				duelMatrix.add(favoriteIndex, unpreferredIndex, 1);
			}
			// And add a preference favoriteIndex > unrankedIndex for each unranked proposal
			for (int unrankedIndex : unrankedIndexes) {
				duelMatrix.add(favoriteIndex, unrankedIndex, 1);
			}
		}
	}



	/**
	 * Calculate the winner of the Ranked Pairs voting method.
	 * 1. TALLY -   For each pair of proposals in the poll calculate the winner of the direct comparison
	 *              Which proposal has more preferences i&lt;j compared to j&gt;i.
	 * 2. SORT -    Sort these majorities by the absolute number of preferences i over j
	 * 3. LOCK IN - For each of the sorted majorities: add the majority to a directed graph,
	 *              IF this edge does not introduce a circle in the graph.
	 * 4. WINNERS - The source of the tree, ie. the node with no incoming links is the winner of the poll.
	 *              Unless there is a pairwise tie between two sources, then there will only be one winner.
	 * @return The (list of) winners of the poll as row/col indexes in duelMatrix.
	 *         In nearly every case, there is only one winner.
	 */
	public static List<Integer> calcRankedPairWinners(Matrix duelMatrix) {
		// TALLY
		// Majority  :=  [i,j,n]  where
		//   i  row index in duelMatrix  (actually an int)
		//   j  col index in duelMatrix
		//   n  number of ballots that prefer i > j   (a long)
		// This list of majorities contains each pair only once, where i is the winner.
		List<long[]> majorities = new ArrayList<>();
		for (int i = 0; i < duelMatrix.getRows()-1; i++) {
			for (int j = i+1; j < duelMatrix.getCols(); j++) {
				long[] maj_ij = new long[] {i,j, duelMatrix.get(i,j)};
				long[] maj_ji = new long[] {j,i, duelMatrix.get(j,i)};
				if (maj_ij[2] != maj_ji[2]) {
					majorities.add(maj_ij[2] > maj_ji[2] ? maj_ij : maj_ji);   // add the winner of this pair to the list of majorities (if there is a winner)
				}
			}
		}

		// SORT  majorities
		// https://en.wikipedia.org/wiki/Ranked_pairs#Sort
		majorities.sort(new MajorityComparator(duelMatrix));

		// LOCK IN
		// The node ids in DirectedGraph are row/col indexes in the duelMatrix (int)
		DirectedGraph<Integer> digraph = new DirectedGraph<>();
		for (int i = 0; i < duelMatrix.getRows(); i++) {
			digraph.addNode(i);
		}
		for (long[] majority : majorities) {
			if (!digraph.reachable((int)majority[1], (int)majority[0])) {
				digraph.addDirectedEdge((int)majority[0], (int)majority[1]);
			}
		}

		// WINNERS
		// In nearly every case, there is only one winner/one source.
		Set<Integer> sources = digraph.getSources();
		// TODO: Sort Ranked Pair winners, if there is more than one winner
		return new ArrayList<>(sources);
	}

}