package com.mrcpdf;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Encryption policy.
 *
 * <p>mrcpdf cannot re-encrypt: the output is always written unencrypted. For a
 * document that arrived encrypted, that is a downgrade of the very protection
 * the caller relied on, and doing it silently is the dangerous part — the output
 * looks like a normal file and is not.
 *
 * <p>The distinction that matters: a file we opened with a blank password is
 * owner-password-only, i.e. permission-restricted. Its author applied
 * restrictions on purpose. Refusing there, rather than quietly producing an
 * unrestricted copy, is the whole point.
 */
class EncryptionPolicyTest {

    @Test
    void unencryptedInputNeedsNoComment() {
        assertNull(MrcPdf.encryptionDecision(false, false, false));
    }

    @Test
    void ownerPasswordOnlyIsRefused() {
        // encrypted, opened without a password => owner-password-only
        String msg = MrcPdf.encryptionDecision(true, false, false);
        assertNotNull(msg,
            "a permission-restricted document must not be silently re-emitted"
            + " without its restrictions");
    }

    @Test
    void refusalExplainsWhatHappened() {
        String msg = MrcPdf.encryptionDecision(true, false, false);
        assertTrue(msg.contains("encrypted"),
            "the user must be told the input was encrypted: " + msg);
        assertTrue(msg.contains("permission"),
            "the user must understand the restriction is being dropped: " + msg);
    }

    @Test
    void refusalExplainsTheOutputWouldNotBeEncrypted() {
        String msg = MrcPdf.encryptionDecision(true, false, false).toLowerCase();
        assertTrue(msg.contains("not be encrypted") || msg.contains("unencrypted"),
            "the consequence must be explicit: " + msg);
    }

    @Test
    void refusalNamesTheFlagThatOverrides() {
        String msg = MrcPdf.encryptionDecision(true, false, false);
        assertTrue(msg.contains("--ignore-encryption"),
            "a refusal with no override is a dead end: " + msg);
    }

    @Test
    void ignoreEncryptionLetsItProceed() {
        assertNull(MrcPdf.encryptionDecision(true, false, true),
            "the override must actually work");
    }

    @Test
    void correctPasswordIsAllowedButStillWarns() {
        // Supplied the user password, so access is legitimate. Proceed, but the
        // output is still unprotected and the user must hear about it.
        assertNull(MrcPdf.encryptionDecision(true, true, false),
            "having the password is sufficient reason to proceed");
    }

    @Test
    void theWarningIsSeparateFromTheRefusal() {
        // The "output will be unencrypted" notice must be emitted for every
        // encrypted input that proceeds, including via the override — otherwise
        // the override becomes a silent downgrade.
        assertNotNull(MrcPdf.encryptionWarning(true, false, true),
            "even an overridden run must say the output is unprotected");
        assertNotNull(MrcPdf.encryptionWarning(true, true, false));
    }

    @Test
    void unencryptedInputProducesNoWarning() {
        assertNull(MrcPdf.encryptionWarning(false, false, false));
    }
}