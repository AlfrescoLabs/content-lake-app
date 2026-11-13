package org.hyland.contentlake.security;

/**
 * Thrown when a caller-supplied HXQL filter fragment cannot be safely wrapped in a parenthesised
 * group and combined with the permission predicate. The caller sent it, so it maps to HTTP 400.
 *
 * @see HxqlFilterGuard
 */
public class UnsafeHxqlFilterException extends IllegalArgumentException {

    public UnsafeHxqlFilterException(String message) {
        super(message);
    }
}
