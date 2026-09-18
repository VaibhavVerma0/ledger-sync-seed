package in.simplifymoney.ledgersync.ingest;
import in.simplifymoney.ledgersync.store.TxnId;
import java.util.LinkedHashMap;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * This is the naive version. It parses each message on its own and saves
 * whatever comes back. It does not ask whether two messages describe the same
 * transaction, and it decides the category from the direction alone.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

        public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        Map<String, List<ParsedTxn>> byTransaction = new LinkedHashMap<>();
        int skipped = 0;

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn t = p.get();
            String id = TxnId.of(t.accountLast4(), t.occurredAt(), t.direction(), t.amount());
            byTransaction.computeIfAbsent(id, k -> new ArrayList<>()).add(t);
        }

        for (List<ParsedTxn> evidence : byTransaction.values()) {
            store.save(toTransaction(evidence));
        }
        return new Stats(messages.size(), byTransaction.size(), skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    private NormalizedTxn toTransaction(List<ParsedTxn> evidence) {
        ParsedTxn first = evidence.get(0);
        List<String> ids = evidence.stream()
                .map(ParsedTxn::sourceMessageId)
                .distinct()
                .sorted()
                .toList();
        Category c = first.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
        return new NormalizedTxn(first.accountLast4(), first.occurredAt(), first.direction(),
                first.amount(), c, first.merchant(), ids);
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
