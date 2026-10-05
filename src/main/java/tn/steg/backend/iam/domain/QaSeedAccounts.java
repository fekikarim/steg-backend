package tn.steg.backend.iam.domain;

import java.util.List;
import java.util.UUID;

/**
 * The fixed primary-key ids of the V27 QA seed accounts
 * ({@code db/migration/V27__seed_backoffice_test_accounts.sql}:
 * supervisor/finance/admin {@code *.steg@steg.tn} with passwords public in
 * the repository). These are SEED artifacts, not operator accounts:
 *
 * <ul>
 *   <li>the production Flyway callback disables them before the server
 *       accepts requests ({@code ProdQaSeedAccountCallback});</li>
 *   <li>the first-run admin bootstrap never counts them as "an ADMIN
 *       already exists" — they must never suppress the creation of the real
 *       operator account.</li>
 * </ul>
 *
 * Lives in the domain so both the application-layer bootstrap and the
 * infrastructure-layer callback can reference it without breaking the
 * dependency rule.
 */
public final class QaSeedAccounts {

    public static final List<UUID> IDS = List.of(
            UUID.fromString("c0000000-0000-0000-0000-000000000002"),
            UUID.fromString("c0000000-0000-0000-0000-000000000003"),
            UUID.fromString("c0000000-0000-0000-0000-000000000004"));

    private QaSeedAccounts() {
    }
}
