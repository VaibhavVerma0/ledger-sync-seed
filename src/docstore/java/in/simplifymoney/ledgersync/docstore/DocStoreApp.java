package in.simplifymoney.ledgersync.docstore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.bson.Document;

/**
 * Everything that touches the document store.
 *
 *   backfill   move the SQL ledger across
 *   check      prove the two stores agree, and name where they do not
 *   bench N    load N synthetic transactions and report examined vs returned
 */
public final class DocStoreApp {

    private static final Path DB = Path.of("data", "ledger");
    private static final String URI =
            System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
    private static final String DATABASE = "ledger";

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0];
        switch (command) {
            case "backfill" -> backfill();
            case "check" -> check();
            case "bench" -> bench(args.length > 1 ? Integer.parseInt(args[1]) : 100_000);
            default -> System.out.println("usage: backfill | check | bench [n]");
        }
    }

    private static void backfill() throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(DB);
             MongoDocumentStore docs = new MongoDocumentStore(URI, DATABASE)) {
            Backfill.Result r = new Backfill(sql, docs).run();
            System.out.println("read " + r.read() + ", wrote " + r.written()
                    + ", skipped " + r.skipped() + " (duplicate rows in SQL)");
        }
    }

    private static void check() throws Exception {
        try (SqlLedgerStore sql = new SqlLedgerStore(DB);
             MongoDocumentStore docs = new MongoDocumentStore(URI, DATABASE)) {
            List<ConsistencyChecker.Divergence> d = new ConsistencyChecker(sql, docs).check();
            if (d.isEmpty()) {
                System.out.println("the two stores agree");
                return;
            }
            System.out.println(d.size() + " divergence(s):");
            for (ConsistencyChecker.Divergence x : d) {
                System.out.println("  " + x.what());
                System.out.println("      sql: " + x.inSql());
                System.out.println("      doc: " + x.inDocuments());
            }
        }
    }

    /** Loads n synthetic transactions into a throwaway database and explains the three queries. */
    private static void bench(int n) {
        String database = DATABASE + "_bench";
        try (MongoClient client = MongoClients.create(URI)) {
            client.getDatabase(database).drop();
        }
        try (MongoDocumentStore docs = new MongoDocumentStore(URI, database)) {
            Random random = new Random(42);
            String[] accounts = {"4821", "9075", "3310"};
            OffsetDateTime start = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0,
                    ZoneOffset.ofHoursMinutes(5, 30));
            for (int i = 0; i < n; i++) {
                String account = accounts[i % accounts.length];
                OffsetDateTime at = start.plusMinutes(i * 7L);
                docs.save(new NormalizedTxn(account, at,
                        i % 4 == 0 ? Direction.CREDIT : Direction.DEBIT,
                        new BigDecimal(100 + random.nextInt(900_000)).movePointLeft(2).setScale(2),
                        Category.values()[i % Category.values().length],
                        "BENCH " + i,
                        List.of("m-bench-" + i)));
                if (i % 10_000 == 0) System.out.println("  loaded " + i);
            }
            System.out.println("loaded " + n);
        }

        try (MongoClient client = MongoClients.create(URI)) {
            MongoDatabase db = client.getDatabase(database);
            YearMonth ym = YearMonth.of(2024, 3);

            explain(db, "Q1 one account's month, newest first",
                    new Document("find", "transactions")
                            .append("filter", new Document("account", "4821").append("ym", ym.toString()))
                            .append("sort", new Document("at", -1)));

            explain(db, "Q2 running totals per category for an account",
                    new Document("find", "account_totals")
                            .append("filter", new Document("_id", "4821")));

            explain(db, "Q3 message id to transaction",
                    new Document("find", "transactions")
                            .append("filter", new Document("msgs", "m-bench-50000")));
        }
    }

    private static void explain(MongoDatabase db, String label, Document command) {
        Document stats = db.runCommand(new Document("explain", command)
                        .append("verbosity", "executionStats"))
                .get("executionStats", Document.class);
        System.out.printf("%-48s examined %-8d returned %-8d%n", label,
                stats.getInteger("totalDocsExamined"), stats.getInteger("nReturned"));
    }
}