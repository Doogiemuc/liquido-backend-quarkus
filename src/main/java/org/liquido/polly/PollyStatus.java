package org.liquido.polly;

/**
 * The only two states a polly ever has.
 *
 * <p>A polly is live from the moment it is created - there is no elaboration phase and no
 * start step. That is the whole difference in lifecycle from a LIQUIDO team poll, which
 * walks ELABORATION -> VOTING -> FINISHED.
 *
 * <p>Persisted as a STRING, deliberately unlike the rest of the codebase (which lets enums
 * default to ORDINAL). These tables are new, so there is no legacy data to be compatible
 * with, and STRING removes the trap where inserting or reordering a constant silently
 * rewrites the meaning of every existing row. GraphQL serialises enums by name either way,
 * so the frontend sees "VOTING" / "FINISHED" regardless of the storage choice.
 */
public enum PollyStatus {
	VOTING,
	FINISHED
}
