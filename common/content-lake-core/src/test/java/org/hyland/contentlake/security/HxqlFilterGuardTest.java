package org.hyland.contentlake.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Specification for the caller-filter guard.
 *
 * <p>The guard is what keeps the free-text {@code filter} field from escaping the parenthesised group
 * it is wrapped in and bypassing the permission clause. Each test states one property the guard must
 * have; the failure of a rejection test is a permission leak.</p>
 */
class HxqlFilterGuardTest {

    @Nested
    @DisplayName("rejects fragments that could escape their group")
    class Rejects {

        @Test
        void theAclBypassProofOfConcept() {
            // The exact fragment from the security advisory: it closes the wrapping group early and
            // opens an OR branch that the permission clause does not constrain.
            assertThatThrownBy(() ->
                    HxqlFilterGuard.requireSafeFragment("cin_id = 'none') OR (sys_racl = 'u:bob_#_alfresco'"))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }

        @Test
        void aCloseParenthesisWithNoMatchingOpen() {
            assertThatThrownBy(() -> HxqlFilterGuard.requireSafeFragment("a = 'x') OR b = 'y'"))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }

        @Test
        void anOpenParenthesisThatIsNeverClosed() {
            assertThatThrownBy(() -> HxqlFilterGuard.requireSafeFragment("(a = 'x'"))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }

        @Test
        void aStatementSeparator() {
            assertThatThrownBy(() -> HxqlFilterGuard.requireSafeFragment("a = 'x'; DROP"))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }

        @Test
        void aLineComment() {
            assertThatThrownBy(() -> HxqlFilterGuard.requireSafeFragment("a = 'x' -- rest"))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }

        @Test
        void aBlockComment() {
            assertThatThrownBy(() -> HxqlFilterGuard.requireSafeFragment("a = 'x' /* rest */"))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }

        @Test
        void anUnterminatedLiteral() {
            assertThatThrownBy(() -> HxqlFilterGuard.requireSafeFragment("a = 'x) OR b = 'y'"))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }

        @Test
        void aSingleQuoteHiddenInADoubleQuotedLiteral() {
            // The engine reads "'" as a whole string, so the ) after it closes the wrapping group. A guard
            // that knew only single quotes would open a literal at that ' and never see the ).
            assertThatThrownBy(() -> HxqlFilterGuard.requireSafeFragment(
                    "cin_id = \"'\") OR (sys_racl = 'u:bob_#_alfresco' OR cin_id = \"'\""))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }

        @Test
        void anEscapeInsideADoubleQuotedLiteral() {
            assertThatThrownBy(() -> HxqlFilterGuard.requireSafeFragment("a = \"x\\\"\") OR (b = 'y'"))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }

        @Test
        void anUnterminatedDoubleQuotedLiteral() {
            assertThatThrownBy(() -> HxqlFilterGuard.requireSafeFragment("a = \"x) OR b = 'y'"))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }

        @Test
        void aLineBreakInsideALiteral() {
            // The engine lexer does not allow it, so the guard must not read past it as literal text:
            // read that way, the quotes here would pair up and hide the ) from the depth count.
            assertThatThrownBy(() -> HxqlFilterGuard.requireSafeFragment("a = 'x\n) OR (b = 'y''"))
                    .isInstanceOf(UnsafeHxqlFilterException.class);
        }
    }

    @Nested
    @DisplayName("accepts fragments that stay inside their group")
    class Accepts {

        @Test
        void aNullOrBlankFragment() {
            assertThatCode(() -> HxqlFilterGuard.requireSafeFragment(null)).doesNotThrowAnyException();
            assertThatCode(() -> HxqlFilterGuard.requireSafeFragment("   ")).doesNotThrowAnyException();
        }

        @Test
        void aPlainEqualityFilter() {
            assertThatCode(() -> HxqlFilterGuard.requireSafeFragment("cin_sourceType = 'alfresco'"))
                    .doesNotThrowAnyException();
        }

        @Test
        void balancedParenthesesWithAnInternalOr() {
            // Legitimate: the OR stays inside the fragment's own group, so it is ANDed with the ACL.
            assertThatCode(() -> HxqlFilterGuard.requireSafeFragment("(a = 'x' OR b = 'y')"))
                    .doesNotThrowAnyException();
        }

        @Test
        void parenthesesAndSeparatorsInsideALiteral() {
            assertThatCode(() -> HxqlFilterGuard.requireSafeFragment("sys_name = 'a) OR (b; c'"))
                    .doesNotThrowAnyException();
        }

        @Test
        void anEscapedQuoteInsideALiteral() {
            // \' does not end the literal, so the following ) stays inside it and is harmless.
            assertThatCode(() -> HxqlFilterGuard.requireSafeFragment("sys_name = 'o\\'brien)'"))
                    .doesNotThrowAnyException();
        }

        @Test
        void parenthesesInsideADoubleQuotedLiteral() {
            assertThatCode(() -> HxqlFilterGuard.requireSafeFragment("sys_name = \"a) OR (b\""))
                    .doesNotThrowAnyException();
        }

        @Test
        void aQuoteOfTheOtherKindInsideALiteral() {
            assertThatCode(() -> HxqlFilterGuard.requireSafeFragment("sys_name = \"o'brien\" OR t = 'say \"hi\"'"))
                    .doesNotThrowAnyException();
        }
    }
}
