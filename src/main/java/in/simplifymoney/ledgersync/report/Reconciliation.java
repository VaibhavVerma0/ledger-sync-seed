package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.TxnId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the ledger cannot account for.
 *
 * Most bank messages quote the balance after the transaction, so the balances
 * form a chain: previous balance, plus or minus this amount, equals this
 * balance. Where the chain breaks, money moved that no message reports.
 *
 * Only true balances are used. The credit card quotes "Avl Limit", which in
 * this corpus is a snapshot and not a running figure, so it is excluded.
 */
public final class Reconciliation {

    private Reconciliation() {}

    private static final Pattern BALANCE = Pattern.compile(
            "(?:Avl\\s*Bal|Available\\s*Balance|BalAvl)\\s*:?\\s*"
                    + "(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{2})?)",
            Pattern.CASE_INSENSITIVE);

    public static Map<String, Object> document(List<NormalizedTxn> ledger,
                                               List<RawMessage> messages) {
        Map<String, BigDecimal> stated = statedBalances(messages);
        List<Object> discrepancies = new ArrayList<>();

        for (String account : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            List<NormalizedTxn> rows = ledger.stream()
                    .filter(t -> t.accountLast4().equals(account))
                    .sorted(Comparator.comparing(NormalizedTxn::occurredAt))
                    .toList();

            BigDecimal running = null;
            for (NormalizedTxn t : rows) {
                BigDecimal balance = stated.get(TxnId.of(t));
                BigDecimal signed = t.direction() == Direction.DEBIT
                        ? t.amount().negate() : t.amount();

                if (running != null && balance != null) {
                    BigDecimal implied = running.add(signed);
                    if (balance.compareTo(implied) != 0) {
                        BigDecimal delta = balance.subtract(implied);
                        discrepancies.add(discrepancy(account, t, delta));
                    }
                }
                running = balance != null ? balance
                        : (running == null ? null : running.add(signed));
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    private static Map<String, Object> discrepancy(String account, NormalizedTxn at,
                                                   BigDecimal delta) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("account_last4", account);
        d.put("occurred_at", at.occurredAt().toString());
        d.put("amount", delta.abs().toPlainString());
        d.put("note", "the balance the bank states here is "
                + delta.abs().toPlainString()
                + (delta.signum() < 0 ? " lower" : " higher")
                + " than this ledger implies, so a "
                + (delta.signum() < 0 ? "debit" : "credit")
                + " of that amount happened that no message in the corpus reports;"
                + " detected between the previous transaction on this account and "
                + at.occurredAt());
        return d;
    }

    private static Map<String, BigDecimal> statedBalances(List<RawMessage> messages) {
        Map<String, BigDecimal> out = new HashMap<>();
        Parsers parsers = new Parsers();
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) continue;
            Matcher b = BALANCE.matcher(m.body());
            if (!b.find()) continue;
            ParsedTxn t = p.get();
            out.putIfAbsent(
                    TxnId.of(t.accountLast4(), t.occurredAt(), t.direction(), t.amount()),
                    new BigDecimal(b.group(1).replace(",", "")).setScale(2));
        }
        return out;
    }
}
