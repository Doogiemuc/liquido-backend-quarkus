package org.liquido.vote;


import java.util.Comparator;

/**
 * Compare two pairwise comparison results to determine sort order.
 * (1) The comparison with more votes for its winner is ranked first.
 * (2) Where winner votes are equal, the comparison with fewer votes for the loser is ranked first.
 */
class ComparisonComparator implements Comparator<RankedPairVoting.Comparison> {
	/**
	 * The duelMatrix contains the number of pairwise preferences of a candidate i over another candidate j.
	 */
	Matrix duelMatrix;

	public ComparisonComparator(Matrix duelMatrix) {
		this.duelMatrix = duelMatrix;
	}

	@Override
	public int compare(RankedPairVoting.Comparison c1, RankedPairVoting.Comparison c2) {
		if (c1 == null && c2 == null) return 0;
		if (c1 == null) return -1;
		if (c2 == null) return 1;

		int supportDiff = Long.compare(c2.votes(), c1.votes()); // (1) more winner votes first
		if (supportDiff != 0) return supportDiff;

		return Long.compare(
				duelMatrix.get(c1.loser(), c1.winner()),
				duelMatrix.get(c2.loser(), c2.winner())
		); // (2) fewer loser votes first
	}
}
