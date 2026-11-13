package org.hyland.contentlake.security;

/**
 * Validates a caller-supplied HXQL {@code filter} fragment before it is combined with the permission
 * predicate.
 *
 * <h2>The invariant</h2>
 *
 * <p>{@link AclFilterBuilder#query} and the filter-combining helpers wrap a caller fragment in a
 * parenthesised group, {@code (<fragment>)}, and join it to the permission clause with {@code AND}:
 *
 * <pre>SELECT * FROM SysContent WHERE (&lt;permission clause&gt;) AND (&lt;fragment&gt;)</pre>
 *
 * <p>That is only safe while the fragment stays inside its own group. HXQL binds {@code AND} tighter
 * than {@code OR} (confirmed against a running engine), so a fragment that closes its group early and
 * opens an {@code OR} branch escapes the permission clause entirely:
 *
 * <pre>filter = cin_id = 'none') OR (sys_racl = 'u:bob'
 * -&gt; WHERE (&lt;permission&gt;) AND (cin_id = 'none') OR (sys_racl = 'u:bob')
 * -&gt; (&lt;permission&gt; AND cin_id = 'none') OR (sys_racl = 'u:bob')</pre>
 *
 * <p>The caller reads every document matching the second branch, with no permission check. Because
 * the fragment is run as the service account, which the engine treats as an administrator, the
 * permission clause is the only thing scoping the query.</p>
 *
 * <h2>What makes a fragment safe</h2>
 *
 * <p>A fragment can only break out of its wrapping group with a {@code )} that has no matching
 * {@code (} within the fragment. So the rule is: outside quoted literals the parenthesis depth must
 * never go negative and must end at zero. A fragment that satisfies that cannot inject a top-level
 * {@code OR}; every {@code )} it contains closes a {@code (} it opened, and the wrapping parentheses
 * stay matched around the whole fragment.</p>
 *
 * <p>Statement separators ({@code ;}) and comment markers ({@code --}, {@code /*}) are rejected too.
 * The HXQL grammar has no separator or comment token, so they cannot escape the group today; they are
 * not needed by a legitimate filter, and rejecting them keeps a parser that does accept them from
 * opening a path the parenthesis rule does not cover.</p>
 *
 * <p>This is a necessary guard, not the whole defence. A structured filter API (field, operator,
 * value) that never concatenates caller text would remove the attack surface instead of policing it;
 * this check keeps the free-text {@code filter} field safe until then.</p>
 *
 * <h2>Literals</h2>
 *
 * <p>HXQL has two string literals, single-quoted ({@code 'x'}) and double-quoted ({@code "x"}). The
 * guard must read both exactly as the engine lexer does: a quote of one kind inside a literal of the
 * other kind is an ordinary character. A guard that knew only one kind would see a literal open at a
 * {@code '} inside {@code "'"} and skip the parentheses that follow, which the engine reads as
 * structure.</p>
 *
 * <p>Inside a literal, {@code (}, {@code )}, {@code ;} and comment markers are ordinary characters and
 * are ignored. Both kinds escape with a backslash (see {@link AclFilterBuilder#escapeLiteral}), so
 * {@code \'} or {@code \"} does not end a literal and {@code \\} is a literal backslash. The engine
 * does not allow a raw line break in a literal, so neither does the guard. A fragment that ends inside
 * an unterminated literal is rejected.</p>
 */
public final class HxqlFilterGuard {

    private static final char NO_QUOTE = 0;

    private HxqlFilterGuard() {
    }

    /**
     * Rejects a caller-supplied filter fragment that could escape its parenthesised group.
     *
     * <p>A {@code null} or blank fragment is accepted: there is nothing to combine. Every other
     * fragment must have balanced parentheses outside quoted literals, no statement separator, no
     * comment marker, and no unterminated literal of either quote kind.</p>
     *
     * @param filter the raw caller-supplied fragment, before it is wrapped or combined
     * @throws UnsafeHxqlFilterException if the fragment could alter the query outside its own group
     */
    public static void requireSafeFragment(String filter) {
        if (filter == null) {
            return;
        }
        String fragment = filter.trim();
        if (fragment.isEmpty()) {
            return;
        }

        int depth = 0;
        char quote = NO_QUOTE; // the quote that opened the current literal, or NO_QUOTE outside one

        for (int i = 0; i < fragment.length(); i++) {
            char c = fragment.charAt(i);

            if (quote != NO_QUOTE) {
                if (c == '\\') {
                    i++; // the next char is escaped; it cannot end the literal
                } else if (c == quote) {
                    quote = NO_QUOTE;
                } else if (c == '\n' || c == '\r') {
                    throw new UnsafeHxqlFilterException("filter has a line break inside a string literal");
                }
                continue;
            }

            switch (c) {
                case '\'':
                case '"':
                    quote = c;
                    break;
                case '(':
                    depth++;
                    break;
                case ')':
                    depth--;
                    if (depth < 0) {
                        throw new UnsafeHxqlFilterException(
                                "filter closes a parenthesis it did not open");
                    }
                    break;
                case ';':
                    throw new UnsafeHxqlFilterException(
                            "filter contains a statement separator");
                case '-':
                    if (i + 1 < fragment.length() && fragment.charAt(i + 1) == '-') {
                        throw new UnsafeHxqlFilterException("filter contains a comment");
                    }
                    break;
                case '/':
                    if (i + 1 < fragment.length() && fragment.charAt(i + 1) == '*') {
                        throw new UnsafeHxqlFilterException("filter contains a comment");
                    }
                    break;
                default:
                    break;
            }
        }

        if (quote != NO_QUOTE) {
            throw new UnsafeHxqlFilterException("filter has an unterminated string literal");
        }
        if (depth != 0) {
            throw new UnsafeHxqlFilterException("filter has unbalanced parentheses");
        }
    }
}
