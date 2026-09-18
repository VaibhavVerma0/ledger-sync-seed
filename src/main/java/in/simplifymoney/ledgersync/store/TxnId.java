package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The identity of a transaction, derived from what the transaction IS.
 *
 * Deliberately not derived from RawMessage.messageId: that identifies an
 * upload, not a transaction, so the same SMS uploaded twice carries two ids.
 * Keying on content makes ingest idempotent and lets several messages merge
 * into the one transaction they both evidence.
 */
public final class TxnId {

    private TxnId() {}

    public static String of(String accountLast4, OffsetDateTime occurredAt,
                            Direction direction, BigDecimal amount) {
        return String.join("|",
                accountLast4,
                occurredAt.toInstant().toString(),
                direction.name(),
                amount.toPlainString());
    }

    public static String of(NormalizedTxn t) {
        return of(t.accountLast4(), t.occurredAt(), t.direction(), t.amount());
    }
}