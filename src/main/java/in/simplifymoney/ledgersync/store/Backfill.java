package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Moves everything already in SQL into the document store.
 *
 * The SQL store ran for a long time without a uniqueness guarantee, so it holds
 * rows that are the same transaction more than once. Those collapse here: the
 * document _id is the transaction identity, so a repeat is an upsert onto the
 * row already written, not a second document.
 *
 * That is also why this is safe to re-run, including after a partial failure.
 * Every write is an upsert keyed on content, so a second run over rows already
 * moved is a no-op rather than a duplication. Nothing tracks "where we got to",
 * because nothing needs to.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

        public Result run() {
        List<NormalizedTxn> rows = source.all();
        Map<String, NormalizedTxn> merged = new LinkedHashMap<>();
        long collapsed = 0;

        for (NormalizedTxn t : rows) {
            String id = TxnId.of(t);
            NormalizedTxn existing = merged.get(id);
            if (existing == null) {
                merged.put(id, t);
            } else {
                merged.put(id, withEvidenceOf(existing, t));
                collapsed++;
            }
        }

        for (NormalizedTxn t : merged.values()) {
            target.save(t);
        }
        return new Result(rows.size(), merged.size(), collapsed);
    }

    /**
     * The duplicate SQL rows are not always identical: the same transaction was
     * written twice under different message ids. Dropping the second row would
     * lose that evidence, so the ids are unioned onto the transaction they both
     * describe.
     */
    private static NormalizedTxn withEvidenceOf(NormalizedTxn a, NormalizedTxn b) {
        Set<String> ids = new TreeSet<>(a.sourceMessageIds());
        ids.addAll(b.sourceMessageIds());
        return new NormalizedTxn(a.accountLast4(), a.occurredAt(), a.direction(),
                a.amount(), a.category(), a.merchant(), List.copyOf(ids));
    }

    public record Result(long read, long written, long skipped) {}
}