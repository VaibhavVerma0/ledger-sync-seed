package in.simplifymoney.ledgersync.docstore;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.DocumentStore;
import in.simplifymoney.ledgersync.store.TxnId;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;

/**
 * MongoDB implementation of the three queries this service makes.
 *
 * The document is shaped so each query is an index lookup, never a scan:
 *
 *  transactions._id = the transaction identity (account|instant|direction|amount).
 *      Deterministic, so backfill and ingest are both idempotent by construction.
 *      ym is denormalised ("2026-07") so Q1 is an equality match rather than a
 *      range over dates, keeping the compound index a single contiguous span.
 *
 *  account_totals is maintained on write, one document per account, so Q2 is a
 *      single _id fetch instead of an aggregation over the account's history.
 *      Totals are held in paise as a long: exact, and safe to $inc.
 */
public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    private final MongoClient client;
    private final MongoCollection<Document> txns;
    private final MongoCollection<Document> totals;

    public MongoDocumentStore(String uri, String database) {
        this.client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(database);
        this.txns = db.getCollection("transactions");
        this.totals = db.getCollection("account_totals");
        ensureIndexes();
    }

    private void ensureIndexes() {
        // Q1: one account's month, newest first.
        txns.createIndex(Indexes.compoundIndex(
                Indexes.ascending("account", "ym"), Indexes.descending("at")),
                new IndexOptions().name("q1_account_month_at"));
        // Q3: message id to transaction. Multikey over the array.
        txns.createIndex(Indexes.ascending("msgs"), new IndexOptions().name("q3_msgs"));
    }

    @Override
    public void save(NormalizedTxn t) {
        String id = TxnId.of(t);
        Document existing = txns.find(eq("_id", id)).first();

        Document doc = new Document("_id", id)
                .append("account", t.accountLast4())
                .append("ym", YearMonth.from(t.occurredAt()).toString())
                .append("at", Date.from(t.occurredAt().toInstant()))
                .append("at_iso", t.occurredAt().toString())
                .append("dir", t.direction().name())
                .append("amount", t.amount().toPlainString())
                .append("paise", paise(t.amount()))
                .append("cat", t.category().name())
                .append("merchant", t.merchant())
                .append("msgs", mergedMessageIds(existing, t));

        txns.replaceOne(eq("_id", id), doc, new ReplaceOptions().upsert(true));
        applyToTotals(existing, doc);
    }

    /** Re-running must not double the totals, so the old contribution is removed first. */
    private void applyToTotals(Document existing, Document doc) {
        Document inc = new Document();
        if (existing != null) {
            inc.append(totalsField(existing), -existing.getLong("paise"));
            inc.append(countField(existing), -1);
        }
        inc.append(totalsField(doc), inc.containsKey(totalsField(doc))
                ? (long) inc.get(totalsField(doc)) + doc.getLong("paise")
                : doc.getLong("paise"));
        inc.append(countField(doc), inc.containsKey(countField(doc))
                ? (int) inc.get(countField(doc)) + 1 : 1);

        totals.updateOne(eq("_id", doc.getString("account")),
                new Document("$inc", inc),
                new UpdateOptions().upsert(true));
    }

    private static String totalsField(Document d) {
        return "paise_" + d.getString("cat") + "_" + d.getString("dir");
    }

    private static String countField(Document d) {
        return "count_" + d.getString("cat") + "_" + d.getString("dir");
    }

    private static List<String> mergedMessageIds(Document existing, NormalizedTxn t) {
        List<String> ids = new ArrayList<>();
        if (existing != null) ids.addAll(existing.getList("msgs", String.class));
        for (String id : t.sourceMessageIds()) if (!ids.contains(id)) ids.add(id);
        ids.sort(String::compareTo);
        return ids;
    }

    private static long paise(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : txns.find(and(eq("account", accountLast4), eq("ym", month.toString())))
                .sort(descending("at"))) {
            out.add(toTxn(d));
        }
        return out;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> out = new EnumMap<>(Category.class);
        Document d = totals.find(eq("_id", accountLast4)).first();
        if (d == null) return out;
        for (Category c : Category.values()) {
            long sum = 0;
            for (Direction dir : Direction.values()) {
                Long v = d.getLong("paise_" + c.name() + "_" + dir.name());
                if (v != null) sum += v;
            }
            out.put(c, BigDecimal.valueOf(sum).movePointLeft(2).setScale(2));
        }
        return out;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document d = txns.find(eq("msgs", messageId)).first();
        return Optional.ofNullable(d).map(MongoDocumentStore::toTxn);
    }

    private static NormalizedTxn toTxn(Document d) {
        return new NormalizedTxn(
                d.getString("account"),
                OffsetDateTime.parse(d.getString("at_iso")),
                Direction.valueOf(d.getString("dir")),
                new BigDecimal(d.getString("amount")).setScale(2),
                Category.valueOf(d.getString("cat")),
                d.getString("merchant"),
                d.getList("msgs", String.class));
    }

    @Override
    public void close() {
        client.close();
    }
}