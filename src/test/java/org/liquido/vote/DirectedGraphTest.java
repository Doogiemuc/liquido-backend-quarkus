package org.liquido.vote;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DirectedGraphTest {

	@Test
	void reachableDoesNotLoopForeverOnCycles() {
		DirectedGraph<Integer> graph = new DirectedGraph<>();
		graph.addDirectedEdge(1, 2);
		graph.addDirectedEdge(2, 3);
		graph.addDirectedEdge(3, 1);

		assertFalse(graph.reachable(1, 4));
		assertTrue(graph.reachable(1, 3));
	}

	@Test
	void getSourcesIncludesNodesWithOnlyIncomingEdges() {
		DirectedGraph<Integer> graph = new DirectedGraph<>();
		graph.addDirectedEdge(1, 2);

		assertEquals(Set.of(1), graph.getSources());
		assertTrue(graph.containsNode(2));
	}

	@Test
	void getSourcesReturnsLeafNodesWhenTheyHaveNoIncomingEdges() {
		DirectedGraph<Integer> graph = new DirectedGraph<>();
		graph.addDirectedEdge(2, 1);

		assertEquals(Set.of(2), graph.getSources());
	}

	/*
	@Test
	void reachableHandlesDeepChainsWithoutStackOverflow() {
		DirectedGraph<Integer> graph = new DirectedGraph<>();
		int depth = 20_000;
		for (int i = 0; i < depth; i++) {
			graph.addDirectedEdge(i, i + 1);
		}

		assertTrue(graph.reachable(0, depth));
	}
	*/

}