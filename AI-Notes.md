# AI disclosure

## What I used, and for what

**Claude (claude.ai)** throughout, as a pair I argued with rather than a code
generator I trusted.

- **Corpus analysis.** Clustering the 522 messages by shape to find the formats,
  counting the blast radius of the incident, and testing whether a proposed
  identity key reproduced the expected transaction counts before I wrote the Java.
- **Root-cause work on INC-2026-09-11.** I described the symptom and we narrowed
  it to the amount regex together; I confirmed it against the corpus myself.
- **Drafting and reviewing Java.** Parsers, the categoriser, the Mongo document
  model, the backfill and the consistency checker were drafted with Claude and
  then read, run, and in two cases corrected by me.
- **This write-up.** Structure and first drafts; the findings and the numbers are
  from my own runs.

I did not accept anything I could not run and check against
`corpus-a-totals.json` or my own output.

---

## Where its output was wrong, and what I did instead

**The case: two balance anomalies, one of which did not exist.**

Early on I asked Claude to analyse the corpus for reconciliation candidates. It
reported **two** anomalies on account 4821:

1. an unreported debit of 7,500.00 on 29 Jul
2. a "phantom" debit of 412.67 on 19 Jul, which it said the stated balance did
   not support

It also concluded the account had **146** message-evidenced transactions, and
built an arithmetic story on that: 79,981.05 − 412.67 + 7,500.00 = 87,068.38.
The arithmetic worked, which is what made it convincing.

**My implementation disagreed: 145 transactions, and only one anomaly.** Rather
than assume I had a bug, I pulled every message mentioning 412.67:

```
m-00130  sms    Rs 412.67 debited from a/c **4821 on 19-07-26 at 00:20 to UBER INDIA
m-00131  email  Date: Sat, 18 Jul 2026 18:50:00 +0000
m-00356  sms    (the same SMS, re-uploaded on 15 Aug with a new id)
```

The email's `Date` header is in **UTC**. 18:50 +0000 is 00:20 +05:30 the next
day — the same transaction. Claude's throwaway analysis script had parsed that
header without its offset, so it treated the email as a separate transaction,
counted 412.67 twice, and then "discovered" that the balance chain did not
support the second one. The phantom was an artefact of its own timezone bug.

**My version:** `EmailParser` parses the header as RFC-1123 with
`OffsetDateTime`, and `TxnId` compares `toInstant()`, so an email in UTC and an
SMS in IST for the same payment produce the same identity and merge into one
transaction citing both message ids.

```java
// mine
private static final DateTimeFormatter RFC_1123 =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
return OffsetDateTime.parse(d.group("when").trim(), RFC_1123);
```

**The difference:** with the offset respected, account 4821 has 145
message-evidenced transactions and exactly one reconciliation finding — the
7,500.00 gap — and 79,568.38 + 7,500.00 = 87,068.38 lands on the fixture with no
second adjustment. Had I taken the 146 figure on trust, I would have shipped a
`reconciliation.json` containing an invented discrepancy and a README explaining
a bug that was not in the data.

**What I took from it:** a plausible narrative that arrives with arithmetic
already balanced is the most dangerous kind of wrong answer, because the
arithmetic feels like verification. The check that mattered was going back to the
raw messages.

---

## A second, smaller one

Claude's first `Backfill` skipped duplicate SQL rows by identity. That silently
dropped message ids, because the legacy duplicates carry *different* ids for the
same transaction. My consistency checker caught it on the first run — six
divergences, three on `source_message_ids` and three message ids resolving to
nothing. The fix was to union the evidence rather than drop the row.

Worth noting that the checker only caught it because it compares evidence and
re-resolves message ids, rather than comparing counts and amounts. Both stores
would have passed a count check while the data was wrong.
