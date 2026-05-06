package fi.vesas.jdbclens.capture;

/**
 * Immutable snapshot of a single stack frame used by the analysis
 * layer for call-site attribution (spec §3, §8.1). Constructed only
 * on the slow path when a new stack trace is first interned.
 */
public record StackFrameSnapshot(String className, String methodName, int lineNumber) {
}
