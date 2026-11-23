package pl.rynek.chat.chats;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Propozycja ceny od kupującego. Odpowiedź sprzedawcy zmienia status atomowo w {@link PriceOfferRepository#decide}. */
@Entity
@Table(name = "price_offers")
public class PriceOffer {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String DECLINED = "DECLINED";

    @Id
    private UUID id;

    @Column(name = "chat_id")
    private UUID chatId;

    private long amount;
    private String currency;
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected PriceOffer() {
    }

    public PriceOffer(UUID chatId, long amount, String currency, Instant now) {
        this.id = UUID.randomUUID();
        this.chatId = chatId;
        this.amount = amount;
        this.currency = currency;
        this.status = PENDING;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getChatId() {
        return chatId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    /** Do kiedy zaakceptowana cena obowiązuje; przed akceptacją brak terminu. */
    public Instant expiresAt(java.time.Duration validity) {
        return ACCEPTED.equals(status) && decidedAt != null ? decidedAt.plus(validity) : null;
    }
}
