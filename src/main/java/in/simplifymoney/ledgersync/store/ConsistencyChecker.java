package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and names precisely where they do not.
 *
 * Counting rows proves nothing: a document whose amount or category was edited
 * leaves the count untouched. So every transaction is compared field by field,
 * and the message-id mapping is checked separately, because a document can hold
 * the right figures while pointing at the wrong evidence.
 *
 * Deliberately written against the same three queries the service itself uses.
 * It needs no privileged full scan of the document store: months are enumerated
 * from the SQL side, so a document in a month SQL has never seen would not be
 * found. That limitation is stated rather than hidden.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> out = new ArrayList<>();

        Map<String, NormalizedTxn> expected = new LinkedHashMap<>();
        for (NormalizedTxn t : sql.all()) {
            expected.merge(TxnId.of(t), t, ConsistencyChecker::mergeEvidence);
        }

        Map<String, NormalizedTxn> actual = new HashMap<>();
        for (Map.Entry<String, Set<YearMonth>> e : monthsByAccount(expected.values()).entrySet()) {
            for (YearMonth ym : e.getValue()) {
                for (NormalizedTxn t : documents.forAccountMonth(e.getKey(), ym)) {
                    actual.put(TxnId.of(t), t);
                }
            }
        }

        for (Map.Entry<String, NormalizedTxn> e : expected.entrySet()) {
            NormalizedTxn want = e.getValue();
            NormalizedTxn got = actual.get(e.getKey());
            if (got == null) {
                out.add(new Divergence("missing from the document store: " + e.getKey(),
                        describe(want), "absent"));
                continue;
            }
            compare(out, e.getKey(), "category",
                    want.category().name(), got.category().name());
            compare(out, e.getKey(), "amount",
                    want.amount().toPlainString(), got.amount().toPlainString());
            compare(out, e.getKey(), "direction",
                    want.direction().name(), got.direction().name());
            compare(out, e.getKey(), "occurred_at",
                    want.occurredAt().toInstant().toString(),
                    got.occurredAt().toInstant().toString());
            compare(out, e.getKey(), "merchant",
                    String.valueOf(want.merchant()), String.valueOf(got.merchant()));
            compare(out, e.getKey(), "source_message_ids",
                    String.join(",", new TreeSet<>(want.sourceMessageIds())),
                    String.join(",", new TreeSet<>(got.sourceMessageIds())));

            for (String messageId : want.sourceMessageIds()) {
                Optional<NormalizedTxn> viaMessage = documents.byMessageId(messageId);
                String resolved = viaMessage.map(TxnId::of).orElse("nothing");
                if (!e.getKey().equals(resolved)) {
                    out.add(new Divergence(
                            "message " + messageId + " resolves to the wrong transaction",
                            e.getKey(), resolved));
                }
            }
        }

        for (String id : actual.keySet()) {
            if (!expected.containsKey(id)) {
                out.add(new Divergence("present in the document store but not in SQL: " + id,
                        "absent", describe(actual.get(id))));
            }
        }

        out.sort(Comparator.comparing(Divergence::what));
        return out;
    }

    private static void compare(List<Divergence> out, String id, String field,
                                String inSql, String inDocuments) {
        if (!inSql.equals(inDocuments)) {
            out.add(new Divergence(id + " differs on " + field, inSql, inDocuments));
        }
    }

    private static NormalizedTxn mergeEvidence(NormalizedTxn a, NormalizedTxn b) {
        Set<String> ids = new TreeSet<>(a.sourceMessageIds());
        ids.addAll(b.sourceMessageIds());
        return new NormalizedTxn(a.accountLast4(), a.occurredAt(), a.direction(),
                a.amount(), a.category(), a.merchant(), List.copyOf(ids));
    }

    private static Map<String, Set<YearMonth>> monthsByAccount(Iterable<NormalizedTxn> txns) {
        Map<String, Set<YearMonth>> out = new LinkedHashMap<>();
        for (NormalizedTxn t : txns) {
            out.computeIfAbsent(t.accountLast4(), k -> new HashSet<>())
                    .add(YearMonth.from(t.occurredAt()));
        }
        return out;
    }

    private static String describe(NormalizedTxn t) {
        return t.accountLast4() + " " + t.occurredAt() + " " + t.direction()
                + " " + t.amount().toPlainString() + " " + t.category();
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}