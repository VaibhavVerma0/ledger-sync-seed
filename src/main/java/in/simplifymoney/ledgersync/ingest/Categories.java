package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides the category of every transaction, once they are all known.
 *
 * TRANSFER cannot be decided from one transaction on its own: it is the user
 * moving money between two accounts we track, so it is only visible as a pair
 * of legs. The text is not evidence - the corpus contains a debit labelled
 * IMPS/P2A to a third party with no counter-leg (spending), and a credit
 * labelled NEFT INWARD SELF with no counter-leg (income).
 */
public final class Categories {

    private Categories() {}

    private static final BigDecimal MICRO_LIMIT = new BigDecimal("100.00");
    private static final Duration PAIR_WINDOW = Duration.ofMinutes(10);
    private static final Pattern UPI = Pattern.compile("\\bUPI\\b", Pattern.CASE_INSENSITIVE);

    public static List<NormalizedTxn> apply(List<NormalizedTxn> txns) {
        boolean[] transfer = new boolean[txns.size()];

        for (int i = 0; i < txns.size(); i++) {
            for (int j = 0; j < txns.size(); j++) {
                if (i == j || transfer[i]) continue;
                NormalizedTxn a = txns.get(i);
                NormalizedTxn b = txns.get(j);
                if (a.direction() == Direction.DEBIT
                        && b.direction() == Direction.CREDIT
                        && !a.accountLast4().equals(b.accountLast4())
                        && a.amount().compareTo(b.amount()) == 0
                        && within(a, b)) {
                    transfer[i] = true;
                    transfer[j] = true;
                }
            }
        }

        List<NormalizedTxn> out = new ArrayList<>(txns.size());
        for (int i = 0; i < txns.size(); i++) {
            out.add(withCategory(txns.get(i), categorise(txns.get(i), transfer[i])));
        }
        return out;
    }

    private static boolean within(NormalizedTxn a, NormalizedTxn b) {
        return Duration.between(a.occurredAt(), b.occurredAt()).abs().compareTo(PAIR_WINDOW) <= 0;
    }

    private static Category categorise(NormalizedTxn t, boolean isTransfer) {
        if (isTransfer) return Category.TRANSFER;
        if (t.direction() == Direction.CREDIT) return Category.INCOME;
        if (t.amount().compareTo(MICRO_LIMIT) <= 0 && UPI.matcher(t.merchant()).find()) {
            return Category.MICRO;
        }
        return Category.SPEND;
    }

    private static NormalizedTxn withCategory(NormalizedTxn t, Category c) {
        return new NormalizedTxn(t.accountLast4(), t.occurredAt(), t.direction(),
                t.amount(), c, t.merchant(), t.sourceMessageIds());
    }
}