package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails. Both banks use the same body shape; the
 * timestamp comes from the Date header, which is RFC-1123 and already carries
 * its own offset, so it is not run through Dates.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern TXN = Pattern.compile(
            "account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with "
                    + "(?:Rs\\.?|INR)\\s*(?<amount>[0-9,]+(?:\\.[0-9]{2})?)\\.\\s*\\R"
                    + "Merchant / Remarks: (?<merchant>.+)");

    private static final Pattern DATE = Pattern.compile("^Date: (?<when>.+)$", Pattern.MULTILINE);

    private static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher txn = TXN.matcher(m.body());
        if (!txn.find()) return Optional.empty();

        OffsetDateTime at = headerDate(m.body());
        if (at == null) return Optional.empty();

        BigDecimal amount = new BigDecimal(txn.group("amount").replace(",", "")).setScale(2);
        Direction d = "debited".equals(txn.group("dir")) ? Direction.DEBIT : Direction.CREDIT;

        return Optional.of(new ParsedTxn(txn.group("acct"), at, d, amount,
                txn.group("merchant").trim(), Amounts.statedBalance(m.body()),
                m.messageId()));
    }

    private OffsetDateTime headerDate(String body) {
        Matcher d = DATE.matcher(body);
        if (!d.find()) return null;
        try {
            return OffsetDateTime.parse(d.group("when").trim(), RFC_1123);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}