# Decision log

Written as I went. The entries I find most useful are 3, 5, 7 and 10, where the
data or my own tooling changed my mind.

---

### 1. Transaction identity is derived from content, not from the message id

**Chose:** `account | occurredAt.toInstant() | direction | amount`, in
`store/TxnId`, used as the dedup key in ingest, the unique index in SQL, and the
`_id` in MongoDB.

**Rejected:** deduplicating on `message_id`. `RawMessage` warns that the id
identifies the upload rather than the message, and the corpus contains a batch
received on 2026-08-15 that re-uploads earlier SMS with new ids. Keying on it
would have doubled about 190 messages.

**Why it matters beyond dedup:** the same decision delivers idempotent
re-ingest, idempotent backfill, and the merge of an SMS with its email — three
requirements from one property.

---

### 2. Categorisation runs over the whole set, not per transaction

TRANSFER cannot be seen in a single transaction. It is a debit on one tracked
account and a matching credit on another minutes later. So `Categories.apply`
takes the full list after ingest rather than deciding as each message is parsed.

**Rejected:** matching on merchant text. The corpus punishes it deliberately —
`IMPS/P2A/RAHUL SHARMA` for 12,000.00 looks like a transfer and has no
counter-leg, so it is spending; `NEFT INWARD SELF` for 18,000.00 says SELF, has
no counter-leg, and is income. Only the pairing is evidence.

**Window:** ten minutes. The legs in the corpus are one to two minutes apart; ten
gives margin without risking a coincidental match. Unsure about this one — a
same-amount debit and credit across two accounts within ten minutes could in
principle be unrelated. With more time I would require closer agreement or a
shared reference.

---

### 3. MICRO matches the UPI token, not a `UPI/` prefix

I started with `UPI/`, because most micro debits are `UPI/CHAIWALA` and similar.
That gave 44 micro on account 9075 where the fixture expects 45. The missing one
is a 0.50 debit labelled `UPI MANDATE VERIFY` — UPI, no slash. The data changed
the rule.

---

### 4. The ledger is one transaction short of the fixture, on purpose

Account 4821 has 145 message-evidenced transactions against 146 expected, and
spend 7,500.00 below the fixture. The balance chain shows exactly one break: a
7,500.00 debit on 29 Jul 2026 that no message reports. 79,568.38 + 7,500.00 =
87,068.38 exactly.

**Rejected:** synthesising the missing row to make the count match.
`NormalizedTxn` requires at least one source message id, and the brief asks for
every row to be traceable to its evidence. An inferred transaction has none. It
goes in `reconciliation.json` and is explained in the README.

---

### 5. Reconciliation means the balance chain, and only true balances

The spec deliberately does not say what "cannot account for" means. Most bank
messages quote the balance after the transaction, so the balances chain:
previous balance, plus or minus this amount, equals this balance. A break is
money that moved with no message to explain it.

**Excluded the credit card.** It quotes `Avl Limit`, which in this corpus is a
per-message snapshot, not a running figure — it returns to the same value
repeatedly. Chaining it produced 19 false discrepancies before I looked at the
raw values and realised what the field was.

---

### 6. `verify.sh` dictates where the Mongo driver can live

`verify.sh` compiles every file in `src/main/java` with plain `javac` and no
classpath, and `build.gradle` notes that the main source set compiles against the
JDK alone. So a single `import com.mongodb...` in main breaks a required
deliverable.

**Chose:** a separate `src/docstore/java` source set with the driver on its
classpath, and a `DocStoreApp` entry point. `DocumentStore`, `Backfill` and
`ConsistencyChecker` stay in main because they only touch the interface.

---

### 7. The backfill merges evidence instead of dropping duplicates

**First version:** skip any SQL row whose identity has already been seen.
**Problem the checker found:** the legacy duplicates are not identical. The same
transaction was written twice under *different* message ids
(`m-legacy-0001` / `m-legacy-0002`). Skipping the second row lost that evidence,
and a message id that should resolve to a transaction resolved to nothing.
**Now:** identical transactions collapse, and their message ids are unioned.

Every figure matched while this was wrong. Only comparing `source_message_ids`
and re-resolving each id through Q3 exposed it.

---

### 8. MongoDB rather than DynamoDB

DynamoDB is the stated preference and is the better fit for this access pattern
in production. I chose Mongo because I could bring it up, load 100,000 documents,
measure it and explain it within the time available, and because a working store
beats a partial integration. The seam is `DocumentStore`, so swapping it is
contained.

---

### 9. Category totals are maintained on write

Q2 could be an aggregation over the account's history, examining roughly 33,000
documents at 100k scale. Instead `account_totals` holds one document per account,
updated on every write, so Q2 examines 1.

**The cost, stated plainly:** writes do more work, and a re-saved transaction has
to reverse its previous contribution before applying the new one or totals drift
on re-ingest. I accepted that because a ledger is read far more than written, and
because the write path was already doing a read for the message-id union.

---

### 10. Fixes to the inherited repo, all logged rather than quietly patched

- **No Gradle wrapper**, though the README says to run `./gradlew`. Generated and
  committed one so the graders need not install Gradle.
- **`V1__initial.sql` would not run.** `id IDENTITY PRIMARY KEY`; H2 2.x removed
  that type and `build.gradle` pins 2.2.224. Changed to `BIGINT GENERATED BY
  DEFAULT AS IDENTITY`.
- **The migration runner splits on `;` with no awareness of comments**, so a
  semicolon inside a SQL comment produces a syntax error. I kept my comments
  free of them rather than change the runner, but it is a latent trap.
- **`V2__seed.sql` legacy rows are kept out of ingest.** They are the subject of
  the backfill, not part of the corpus ledger; loading them into the same table
  would have put duplicates and the bad water-can row into `ledger.json`.
  `migrate` skips V2, `migrate legacy` loads it.

Each of these was invisible to the seed's own CI because the workflow runs only
`verify.sh`, which uses neither Gradle nor the database.
