package org.liquido.vote;


import java.util.Arrays;
import java.util.Comparator;

/**
 * Compare two majorities which one is "better" and wins.
 * A Majority is how often a candidate i was preferred to candidate j.
 * int[3] = { i, j, numPreferences_I_over_J }
 *
 */
class MajorityComparator implements Comparator<long[]> {
	/**
	 * The duelMatrix contains the number of pairwise preferences of a candidate i over another candidate j.
	 */
	Matrix duelMatrix;

	public MajorityComparator(Matrix duelMatrix) {
		this.duelMatrix = duelMatrix;
	}

	/**
	 * Compare two majorities m1 and m2
	 * (1) The majority having more support for its alternative is ranked first.
	 * (2) Where the majorities are equal, the majority with the smaller minority opposition is ranked first.
	 *
	 * @param m1 majority one
	 * @param m2 majority two
	 * @return a positive number IF m1 < m2 OR a negative number IF m1 < m2 OR zero IF m1 exactly equals m2
	 */
	@Override
	public int compare(long[] m1, long[] m2) {
		if (m1 == null && m2 == null) return 0;
		if (Arrays.equals(m1, m2)) return 0;
		if (m1 == null) return -1;
		if (m2 == null) return 1;

		int supportDiff = Long.compare(m2[2], m1[2]); // (1)
		if (supportDiff != 0) return supportDiff;

		return Long.compare(
				duelMatrix.get((int)m1[1], (int)m1[0]),
				duelMatrix.get((int)m2[1], (int)m2[0])
		); // (2)
	}
}