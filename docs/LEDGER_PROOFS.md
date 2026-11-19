# PayFlow Cryptographic Merkle-Tree Ledger Proofs (RFC 6962)

## 1. Overview & Architectural Motivation

Financial ledgers require irrefutable, tamper-evident proof of state. While standard relational databases enforce constraints via engine mechanisms, they cannot independently prove to external counterparties, mobile clients, or auditors that a historical entry has not been altered, omitted, or inserted post-hoc.

PayFlow implements **Cryptographically Sealed Merkle-Tree Ledger Proofs** modeled after **RFC 6962 (Certificate Transparency)**. 

Key Architectural Highlights:
1. **RFC 6962 Pure Binary Tree**: Standardized domain separation prefixes (`0x00` for leaves, `0x01` for internal nodes) protecting against second-preimage attacks.
2. **Non-Power-of-Two Balancing**: Deterministic splitting using the largest power of 2 strictly less than $n$ ($k = 2^{\lfloor \log_2(n-1) \rfloor}$), guaranteeing logarithmic proof depth ($O(\log n)$) without artificial leaf padding.
3. **Sequential Root Chaining**: Cryptographic hash chaining across batches:
   $$\text{chain\_hash} = \text{SHA256}(\text{previous\_root\_hash} \parallel \text{root\_hash} \parallel \text{batch\_number} \parallel \text{leaf\_count})$$
   Linking back to the genesis root (64 zeros), forming an append-only verifiable ledger timeline.
4. **Engine-Level Database Immutability**: PostgreSQL trigger `prevent_merkle_tampering` strictly prohibits `UPDATE` and `DELETE` on batch and leaf tables.
5. **Zero-Trust Client Verification**: Framework-free, pure Java verification ported natively to the Android Client SDK (`payflow-client-android`), enabling mobile clients to cryptographically verify ledger inclusion offline without trusting backend servers.

---

## 2. Canonical Serialization & Domain Separation

To ensure consistent hashing across diverse platforms (backend JVM, Android client, external auditors), double-entry ledger records are canonicalized before hashing.

### Canonical Ledger Entry Format
A ledger entry is serialized into a pipe-delimited UTF-8 string:
```text
entryId|paymentId|walletId|entryType|amount|balanceAfter|createdAtEpochMicros
```
- `amount` and `balanceAfter`: Formatted strictly to 4 decimal places with `RoundingMode.HALF_UP` (e.g. `100.0000`).
- `createdAtEpochMicros`: Microsecond-precision epoch timestamp (`epochSecond * 1,000,000 + nanoOfSecond / 1,000`).
- `entryType`: `DEBIT` or `CREDIT`.

### RFC 6962 Domain Separation Hashing
To prevent second-preimage collisions between leaves and interior nodes:
- **Leaf Hash**:
  $$\text{LeafHash} = \text{SHA256}(0x00 \parallel \text{canonicalBytes})$$
- **Internal Node Hash**:
  $$\text{NodeHash} = \text{SHA256}(0x01 \parallel \text{leftChildHash} \parallel \text{rightChildHash})$$
  *(where left and right child hashes are the 32-byte binary representations)*.

### Edge Case Handling
- **Single-Leaf Batch ($n = 1$)**: The root hash of the tree is the leaf hash itself; no interior node is created, and the audit path is empty (`[]`).
- **Chain Hash Linkage**:
  $$\text{chain\_hash} = \text{SHA256}(\text{previous\_root\_hash} \parallel \text{root\_hash} \parallel \text{batch\_number} \parallel \text{leaf\_count})$$
  Genesis Batch ($1$) uses $\text{previous\_root\_hash} = \text{"0000000000000000000000000000000000000000000000000000000000000000"}$ (64 zeros).

---

## 3. Merkle Inclusion Proofs & Verification Algorithm

An inclusion proof consists of an ordered sequence of sibling hashes and their spatial orientation (`isRightSide: true/false`) required to reconstruct the root from a given leaf.

```
                  Root Hash (Internal Node 0x01)
                           /        \
                          /          \
                Node 0-1 (0x01)     Leaf 2 (0x00)
                 /          \
                /            \
          Leaf 0 (0x00)  Leaf 1 (0x00)  <-- Sibling in Path for Leaf 0
```

### Verification Algorithm
```java
String currentHash = leafHash;
for (MerkleAuditPathStep step : auditPath) {
    if (step.isRightSide()) {
        currentHash = sha256(0x01 || currentHash || step.getSiblingHash());
    } else {
        currentHash = sha256(0x01 || step.getSiblingHash() || currentHash);
    }
}
return currentHash.equalsIgnoreCase(expectedRootHash);
```

Complexity:
- Proof generation: $O(\log n)$
- Proof verification: $O(\log n)$
- Proof size: $\lceil \log_2 n \rceil$ hash steps (typically $< 10$ hashes for batches of 500 entries, under 700 bytes).

---

## 4. Scheduled Batch Builder & Concurrency Controls

The `LedgerMerkleBatchBuilder` runs periodically to seal unanchored ledger entries into immutable batches.

### Concurrency & Lag Window
1. **PostgreSQL Advisory Lock**:
   Executes `SELECT pg_try_advisory_xact_lock(7429103)`. Guarantees exactly one cluster node builds a batch at any given time. Lock automatically releases on transaction commit/rollback.
2. **Lag Window Filtering**:
   Only ledger entries older than `lagSeconds` (default: 60s) are eligible for anchoring. This ensures any in-flight database transactions with slightly older timestamps have completed committing before the batch window closes.
3. **Atomic Unit of Work**:
   Batch insertion, leaf mapping, outbox event generation, and metrics updates occur within a single database transaction.

---

## 5. REST API & Verification Endpoints

### 1. Retrieve Inclusion Proof for Ledger Entry
```http
GET /api/ledger/entries/{entryId}/proof
Authorization: Bearer <jwt-token>
```
- **Access Control**: Caller must be the wallet owner of the entry or have `ROLE_ADMIN`. Unauthorized requests receive `403 Forbidden`.
- **Response (200 OK - Anchored)**:
```json
{
  "status": "CONFIRMED",
  "entryId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "leafHash": "a3b2c1...",
  "leafIndex": 0,
  "leafCount": 2,
  "batchNumber": 1,
  "rootHash": "05b10e99cacdefc6d2f2f475e14bb32dbfef249bf334e1580ea7db74d131144f",
  "chainHash": "8e814ab2311648e82633cdac4c9070070fbc6cfa0c7b4a112d88c2b5b05ba056",
  "previousRootHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "auditPath": [
    {
      "siblingHash": "c4d5e6...",
      "isRightSide": true
    }
  ],
  "canonicalPayload": "3fa85f64-...|...|...|DEBIT|100.0000|900.0000|1700000000000000",
  "verified": true,
  "message": "Proof cryptographically verified against batch root"
}
```
- **Response (202 Accepted - In-Flight / Unanchored)**:
```json
{
  "status": "PENDING_ANCHORING",
  "entryId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "verified": false,
  "message": "Entry has been committed to ledger but not yet anchored in a Merkle batch"
}
```

### 2. Public Query for Committed Roots
```http
GET /api/public/ledger/roots?page=0&size=20
GET /api/public/ledger/roots/latest
```
- **Access Control**: Publicly accessible (`permitAll`).
- **Response (200 OK)**:
```json
{
  "batchNumber": 1,
  "leafCount": 2,
  "rootHash": "05b10e99cacdefc6d2f2f475e14bb32dbfef249bf334e1580ea7db74d131144f",
  "previousRootHash": "0000000000000000000000000000000000000000000000000000000000000000",
  "chainHash": "8e814ab2311648e82633cdac4c9070070fbc6cfa0c7b4a112d88c2b5b05ba056",
  "createdAt": "2026-11-19T06:00:00Z",
  "firstEntryCreatedAt": "2026-11-19T05:58:00Z",
  "lastEntryCreatedAt": "2026-11-19T05:59:00Z"
}
```

### 3. Administrative Batch Integrity Verification
```http
POST /api/admin/ledger/batches/{batchNumber}/verify
Authorization: Bearer <admin-jwt-token>
```
- **Access Control**: Requires `ROLE_ADMIN`.
- **Response (200 OK)**:
```json
{
  "batchNumber": 1,
  "leafCount": 2,
  "rootHash": "05b10e99cacdefc6d2f2f475e14bb32dbfef249bf334e1580ea7db74d131144f",
  "recomputedRootHash": "05b10e99cacdefc6d2f2f475e14bb32dbfef249bf334e1580ea7db74d131144f",
  "rootVerified": true,
  "chainHash": "8e814ab2311648e82633cdac4c9070070fbc6cfa0c7b4a112d88c2b5b05ba056",
  "recomputedChainHash": "8e814ab2311648e82633cdac4c9070070fbc6cfa0c7b4a112d88c2b5b05ba056",
  "chainVerified": true,
  "valid": true,
  "verifiedAt": "2026-11-19T06:05:00Z"
}
```

---

## 6. Reconciliation & Automated Tamper Detection

The `ReconciliationService` automatically verifies the cryptographic integrity of recent Merkle batches during scheduled audits:
1. Validates that every leaf in the database recomputes to the stored batch `root_hash`.
2. Validates that the stored `chain_hash` matches $\text{SHA256}(\text{previous\_root\_hash} \parallel \text{root\_hash} \parallel \text{batch\_number} \parallel \text{leaf\_count})$.
3. Validates continuous chain linkage across batches ($\text{batch}[n].\text{previous\_root\_hash} == \text{batch}[n-1].\text{root\_hash}$).
4. On any detected discrepancy:
   - Increments metric `ledger_merkle_verify_failures_total`.
   - Records discrepancy in non-destructive reconciliation audit report.
   - Publishes `LEDGER_ROOT_VERIFICATION_FAILED` event to the Transactional Outbox, triggering **CRITICAL alert dispatch** to administrators via the Phase 3 Fraud Alert pipeline.

---

## 7. Cryptographic Guarantees: What It Proves vs What It Does Not Prove

> [!IMPORTANT]
> A sound security architecture clearly delineates what a cryptographic mechanism proves and what remains outside its boundary.

### What Merkle Ledger Proofs Cryptographically Prove
1. **Proof of Inclusion**: An entry with exact details (wallet, amount, balance after, timestamp) was committed to the database and included in batch $N$.
2. **Proof of Immutability**: Any alteration to a transaction (modifying amount, changing timestamp, switching debits/credits) changes the leaf hash and produces a root hash collision, mathematically detected by any verifier.
3. **Proof of Ordering & Non-Omission**: The audit path proves the exact leaf index within the batch. Entries cannot be deleted or reordered without breaking the root hash.
4. **Timeline Continuity**: Chained root hashing ensures batches cannot be excised or inserted between historical batches.
5. **Client-Side Self-Verification**: Mobile clients can verify their transaction history directly using the public batch roots without relying on server trust.

### What Merkle Ledger Proofs Do NOT Prove
1. **Does NOT Prove Balance Sufficiency at Transaction Time**: Merkle proofs prove historical commitment, not that funds were available prior to execution. Fund sufficiency is guaranteed by pessimistic database row locks (`SELECT FOR UPDATE`) and ACID constraints.
2. **Does NOT Prove External Gateway Settlement**: Proves internal ledger double-entry commitment; does not prove external payment rails (e.g. Fedwire, UPI, ACH) settled with banking partners (audited separately by the external reconciliation module).
3. **Does NOT Prevent Client Device Snapshot Restores**: Rooted clients equivocating offline are bound by hardware StrongBox/TEE protections and cryptographic fraud proofs, not by server Merkle proofs.
