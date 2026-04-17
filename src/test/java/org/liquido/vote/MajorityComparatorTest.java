package org.liquido.vote;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MajorityComparatorTest {

	@Test
	void comparePrefersLowerOppositionWhenSupportIsEqual() {
		Matrix duelMatrix = new Matrix(4, 4);
		duelMatrix.set(0, 1, 10); // support for 0 > 1
		duelMatrix.set(1, 0, 2);  // opposition against 0 > 1
		duelMatrix.set(2, 3, 10); // support for 2 > 3
		duelMatrix.set(3, 2, 5);  // opposition against 2 > 3

		long[] lowerOpposition = new long[] {0, 1, 10};
		long[] higherOpposition = new long[] {2, 3, 10};

		MajorityComparator comparator = new MajorityComparator(duelMatrix);
		assertTrue(comparator.compare(lowerOpposition, higherOpposition) < 0);
		assertTrue(comparator.compare(higherOpposition, lowerOpposition) > 0);

		List<long[]> majorities = new ArrayList<>();
		majorities.add(higherOpposition);
		majorities.add(lowerOpposition);
		majorities.sort(comparator);

		assertArrayEquals(lowerOpposition, majorities.get(0));
	}
}
