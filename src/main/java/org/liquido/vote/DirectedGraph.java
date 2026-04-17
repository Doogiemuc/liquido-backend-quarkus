package org.liquido.vote;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A directed graph with nodes.
 * Each node has an ID of type T (e.g. Long).
 * Each node has a set of outgoing links to other nodes.
 * This class can return the Root nodes of the tree.
 * (This class does not implement edge weights. We don't need them here.)
 *
 * This graph stores adjacency lists in an internal map.
 */
class DirectedGraph<T> {

	private final Map<T, Set<T>> adjacency = new HashMap<>();

	public DirectedGraph() {
	}

	public boolean addNode(T node) {
		Objects.requireNonNull(node, "node must not be null");
		return adjacency.putIfAbsent(node, new HashSet<>()) == null;
	}

	public boolean containsNode(T node) {
		return adjacency.containsKey(node);
	}

	/**
	 * add an edge from a node to another node
	 */
	public boolean addDirectedEdge(T from, T to) {
		Objects.requireNonNull(from, "from must not be null");
		Objects.requireNonNull(to, "to must not be null");
		if (Objects.equals(from, to)) throw new IllegalArgumentException("cannot add a circular edge from a node to itself");
		adjacency.computeIfAbsent(from, key -> new HashSet<>());
		adjacency.computeIfAbsent(to, key -> new HashSet<>());
		return adjacency.get(from).add(to);
	}

	/**
	 * @return true if there is a path from node A to node B along the directed edges
	 */
	public boolean reachable(T from, T to) {
		if (from == null || to == null) return false;
		if (!adjacency.containsKey(from)) return false;

		Set<T> visited = new HashSet<>();
		Deque<T> stack = new ArrayDeque<>();
		stack.push(from);

		while (!stack.isEmpty()) {
			T current = stack.pop();
			if (!visited.add(current)) continue;

			Set<T> neighbors = adjacency.get(current);
			if (neighbors == null) continue;
			if (neighbors.contains(to)) return true;
			for (T neighbor : neighbors) {
				if (!visited.contains(neighbor)) {
					stack.push(neighbor);
				}
			}
		}
		return false;
	}

	/**
	 * A "source" is a node that is not reachable from any other node.
	 * @return all sources, ie. nodes with no incoming links.
	 */
	public Set<T> getSources() {
		Set<T> sources = new HashSet<>(adjacency.keySet());
		for (Set<T> neighbors : adjacency.values()) {
			for (T neighbor : neighbors) sources.remove(neighbor);
		}
		return sources;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("DirectedGraph[");
		Iterator<T> it = adjacency.keySet().iterator();
		while (it.hasNext()) {
			T key = it.next();
			sb.append("[").append(key).append("->[");
			String neighborIDs = adjacency.getOrDefault(key, Collections.emptySet())
					.stream()
					.map(String::valueOf)
					.collect(Collectors.joining(","));
			sb.append(neighborIDs);
			sb.append("]]");
			if (it.hasNext()) sb.append(", ");
		}
		sb.append("]");
		return sb.toString();
	}
}
