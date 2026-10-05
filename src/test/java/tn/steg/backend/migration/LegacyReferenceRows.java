package tn.steg.backend.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Shared fixture for the V52 sequence-backfill proofs: count-based legacy
 * reference rows of the exact shape the pre-V51 services minted
 * ({@code PREFIX-<mint-year>-NNNNN}).
 *
 * <p>The seed deliberately contains the two LOWEST current-year numbers for
 * every prefix (FC-2026-00001/00002, PAY-2026-00001/00002, DOC-2026-00001) —
 * precisely the numbers a fresh {@code START 1} sequence re-mints first — plus
 * one high cross-year maximum per prefix (FC-2025-00087, PAY-2026-00033,
 * DOC-2026-00052), so the backfill must jump past 87/33/52, not merely skip
 * the colliding low numbers.
 */
final class LegacyReferenceRows {

    static final int MAX_FC_SUFFIX = 87;
    static final int MAX_PAY_SUFFIX = 33;
    static final int MAX_DOC_SUFFIX = 52;

    private LegacyReferenceRows() {
    }

    static void seed(Connection c) throws Exception {
        UUID uniId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        UUID uploaderId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        UUID caseId3 = UUID.randomUUID();

        exec(c, "INSERT INTO universities (id, code, name, created_at, updated_at, version) VALUES (?, 'REFSEQ', 'Reference Seq Uni', now(), now(), 0)", uniId);
        exec(c, "INSERT INTO departments (id, code, name, created_at, updated_at, version) VALUES (?, 'REFSEQ', 'Reference Seq Dept', now(), now(), 0)", deptId);
        exec(c, "INSERT INTO users (id, email, password_hash, created_at, updated_at, version) VALUES (?, 'refseq.legacy@test.tn', 'hash', now(), now(), 0)", uploaderId);
        exec(c, "INSERT INTO file_assets (id, storage_key, original_file_name, checksum, mime_type, size_bytes, uploaded_at, uploaded_by_id, created_at, updated_at, version) "
                + "VALUES (?, 'refseq/legacy.pdf', 'legacy.pdf', 'chksum', 'application/pdf', 128, now(), ?, now(), now(), 0)", fileId, uploaderId);

        UUID[] internshipIds = new UUID[3];
        String[] candidateHashes = {"refseq-a", "refseq-b", "refseq-c"};
        String[] candidateEmails = {"refseq.a@test.tn", "refseq.b@test.tn", "refseq.c@test.tn"};
        String[] caseRefs = {"FC-2026-00001", "FC-2026-00002", "FC-2025-00087"};
        String[] receiptRefs = {"PAY-2026-00001", "PAY-2026-00002", "PAY-2026-00033"};
        for (int i = 0; i < 3; i++) {
            UUID candId = UUID.randomUUID();
            internshipIds[i] = UUID.randomUUID();
            exec(c, "INSERT INTO candidates (id, university_id, national_id_hash, first_name, last_name, email, created_at, updated_at, version) VALUES (?, ?, ?, 'Ref', 'Seq" + i + "', ?, now(), now(), 0)",
                    candId, uniId, candidateHashes[i], candidateEmails[i]);
            // V33-era status vocabulary (ACTIVE) — V43 maps it to IN_PROGRESS.
            exec(c, "INSERT INTO internships (id, reference, candidate_id, start_date, end_date, status, type, requirement, created_at, updated_at, version) VALUES (?, ?, ?, '2026-01-05', '2026-06-05', 'ACTIVE', 'PFE', 'OBLIGATOIRE', now(), now(), 0)",
                    internshipIds[i], "INT-REFSEQ-" + i, candId);
        }
        for (int i = 0; i < 3; i++) {
            exec(c, "INSERT INTO finance_cases (id, reference, status, opened_at, internship_id, created_at, updated_at, version) VALUES (?, ?, 'OPENED', now(), ?, now(), now(), 0)",
                    i == 0 ? caseId1 : i == 1 ? caseId2 : caseId3, caseRefs[i], internshipIds[i]);
            exec(c, "INSERT INTO payment_receipts (id, reference, finance_case_id, file_asset_id, status, amount, paid_months, currency_code, created_at, updated_at, version) VALUES (?, ?, ?, ?, 'GENERATED', 150.00, 3, 'TND', now(), now(), 0)",
                    UUID.randomUUID(), receiptRefs[i], i == 0 ? caseId1 : i == 1 ? caseId2 : caseId3, fileId);
        }
        exec(c, "INSERT INTO documents (id, reference, type, created_at, updated_at, version) VALUES (?, 'DOC-2026-00001', 'INTERNSHIP_APPLICATION', now(), now(), 0)", UUID.randomUUID());
        exec(c, "INSERT INTO documents (id, reference, type, created_at, updated_at, version) VALUES (?, 'DOC-2026-00052', 'CIN_COPY', now(), now(), 0)", UUID.randomUUID());
    }

    private static void exec(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }
}
