package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        Set<String> seen = new HashSet<>();
        long written = 0;
        long skipped = 0;

        for (NormalizedTxn t : rows) {
            String id = TxnId.of(t);
            if (!seen.add(id)) {
                skipped++;      // the same transaction again, from the duplicate SQL rows
                continue;
            }
            target.save(t);
            written++;
        }
        return new Result(rows.size(), written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}