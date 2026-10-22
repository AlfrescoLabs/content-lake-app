package org.hyland.contentlake.rag.security;

import lombok.extern.slf4j.Slf4j;
import org.hyland.contentlake.rag.config.SourceRegistry;
import org.hyland.contentlake.security.CallerAuthenticator;
import org.hyland.contentlake.security.CallerCredentials;
import org.hyland.contentlake.security.CallerIdentities;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * An ordinary username and password, validated against Nuxeo.
 *
 * <p>Declines rather than throwing, and produces an untyped identity. See
 * {@link AlfrescoPasswordCallerAuthenticator} for both reasons; this is the second half of the
 * {@code tryAlfrescoAuth(..) || tryNuxeoAuth(..)} the provider used to hold.</p>
 */
@Slf4j
@Component
public class NuxeoPasswordCallerAuthenticator implements CallerAuthenticator {

    private final NuxeoDirectory directory;

    /** Asks every repository of the type when it has several (#183). Null keeps the single-repository path. */
    private final SourceCredentialChecker checker;

    public NuxeoPasswordCallerAuthenticator(NuxeoDirectory directory) {
        this(directory, null);
    }

    @Autowired
    public NuxeoPasswordCallerAuthenticator(NuxeoDirectory directory, SourceCredentialChecker checker) {
        this.directory = directory;
        this.checker = checker;
    }

    @Override
    public String id() {
        return "nuxeo-password";
    }

    @Override
    public int order() {
        return CallerAuthenticatorOrder.NUXEO;
    }

    @Override
    public boolean supports(CallerCredentials credentials) {
        return !ReservedPrincipals.isReserved(credentials);
    }

    @Override
    public CallerIdentities authenticate(CallerCredentials credentials) {
        if (checker != null && checker.checksPerSource(SourceRegistry.NUXEO)) {
            return checker.password(SourceRegistry.NUXEO, credentials.principal(), credentials.password());
        }
        if (!directory.authenticate(credentials.principal(), credentials.password())) {
            return null;
        }
        log.debug("Authenticated user '{}' via Nuxeo", credentials.principal());
        return CallerIdentities.single(credentials.principal());
    }
}
