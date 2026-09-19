# What is unfinished, and what I could not verify

## Could not verify on my machine

**`docker compose up` is untested.** Docker Desktop will not start on my laptop —
it reports that hardware virtualisation is unavailable, and I do not have BIOS
access on this machine. I developed against MongoDB Community installed natively
as a Windows service on the same `mongodb://localhost:27017`, which is what the
code connects to, so the application path is exercised. The compose file is
written and committed but I have not run it. Treat that as unverified rather
than working.

## Known gaps in the code

**The SMS parsers still take their amount from a body-wide scan.** `Amounts.first`
searches the whole message for the first rupee figure. That is the mechanism
behind INC-2026-09-11; making the paise optional fixes today's failure, but the
fragility remains — any format where a non-transaction figure appears before the
amount would break it again. `EmailParser` captures the amount inside its own
pattern, which is the correct shape. I would move the SMS parsers to the same
approach next.

**No test for merge or idempotency.** The suite has 12 tests, including the one
pinning the incident. Re-ingest producing 256 rows twice, and an SMS merging
with its email into one transaction with two message ids, are both verified by
hand and not by a test. Those are the two behaviours their harness exercises, so
they should be the next two tests written.

**The transfer window is a guess.** Ten minutes, with the corpus legs one to two
minutes apart. A same-amount debit and credit across two tracked accounts inside
that window would be misclassified. A shared bank reference would be firmer
evidence where one exists.

**The consistency checker enumerates months from SQL.** It is written against the
same three queries the service has rather than a privileged full scan, which I
think is right, but it means a document in an account-month that SQL has never
seen would not be found. Extra documents are only detected within months SQL
knows about.

**`Backfill.Result.skipped` is now misnamed** — those rows are collapsed with
their evidence merged, not skipped. The console output says so; the field name
does not.

**Unparsed messages are counted, not listed.** 43 messages are skipped and I have
inspected them by hand — OTPs, adverts, delivery notices, balance enquiries,
e-mandate notices, phishing. But the pipeline only reports a count. On an unseen
corpus, a skipped message could equally be a format I do not handle. Those should
be written into `reconciliation.json` as "could not read", which is the whole
point of distinguishing "not a transaction" from "not understood".

## Not attempted

**DynamoDB.** Chose MongoDB for the reasons in the decision log. The
`DocumentStore` seam means the swap is contained, but it is not done.

**No deployed URL.** Optional per the brief, and not attempted.
