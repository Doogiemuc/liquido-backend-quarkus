package org.liquido.vote;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComparisonComparatorTest {

	@Test
	void comparePrefersMoreSupport() {
		Matrix duelMatrix = new Matrix(4, 4);
		duelMatrix.set(0, 1, 10); // support for 0 > 1
		duelMatrix.set(1, 0, 2);  // opposition against 0 > 1
		duelMatrix.set(2, 3, 7);  // support for 2 > 3
		duelMatrix.set(3, 2, 1);  // opposition against 2 > 3

		RankedPairVoting.Comparison moreSupport = new RankedPairVoting.Comparison(0, 1, 10);
		RankedPairVoting.Comparison lessSupport = new RankedPairVoting.Comparison(2, 3, 7);

		ComparisonComparator comparator = new ComparisonComparator(duelMatrix);
		assertTrue(comparator.compare(moreSupport, lessSupport) < 0);
		assertTrue(comparator.compare(lessSupport, moreSupport) > 0);

		// More support wins, even though the weaker comparison has the smaller opposition (1 < 2).
		List<RankedPairVoting.Comparison> comparisons = new ArrayList<>();
		comparisons.add(lessSupport);
		comparisons.add(moreSupport);
		comparisons.sort(comparator);

		assertEquals(moreSupport, comparisons.get(0));
	}

	@Test
	void comparePrefersLowerOppositionWhenSupportIsEqual() {
		Matrix duelMatrix = new Matrix(4, 4);
		duelMatrix.set(0, 1, 10); // support for 0 > 1
		duelMatrix.set(1, 0, 2);  // opposition against 0 > 1
		duelMatrix.set(2, 3, 10); // support for 2 > 3
		duelMatrix.set(3, 2, 5);  // opposition against 2 > 3

		RankedPairVoting.Comparison lowerOpposition = new RankedPairVoting.Comparison(0, 1, 10);
		RankedPairVoting.Comparison higherOpposition = new RankedPairVoting.Comparison(2, 3, 10);

		ComparisonComparator comparator = new ComparisonComparator(duelMatrix);
		assertTrue(comparator.compare(lowerOpposition, higherOpposition) < 0);
		assertTrue(comparator.compare(higherOpposition, lowerOpposition) > 0);

		List<RankedPairVoting.Comparison> comparisons = new ArrayList<>();
		comparisons.add(higherOpposition);
		comparisons.add(lowerOpposition);
		comparisons.sort(comparator);

		assertEquals(lowerOpposition, comparisons.get(0));
	}
}
