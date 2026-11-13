package pl.rynek.chat.chats;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Rozmowa kupującego ze sprzedawcą w kontekście oferty. Imiona to migawka z założenia rozmowy. Czas przeczytania każdej
 * ze stron ({@code buyerReadAt}, {@code sellerReadAt}) wyznacza nieprzeczytane i status „Przeczytano”; ostatnią wiadomość
 * i czasy aktualizuje {@link ChatRepository#recordMessage}, żeby równoległe wiadomości się nie nadpisywały.
 */
@Entity
@Table(name = "chats")
public class Chat {

    @Id
    private UUID id;

    @Column(name = "offer_id")
    private UUID offerId;

    @Column(name = "offer_title")
    private String offerTitle;

    @Column(name = "buyer_id")
    private UUID buyerId;

    @Column(name = "seller_id")
    private UUID sellerId;

    @Column(name = "buyer_name")
    private String buyerName;

    @Column(name = "seller_name")
    private String sellerName;

    @Column(name = "seller_verified")
    private boolean sellerVerified;

    @Column(name = "last_message")
    private String lastMessage;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "buyer_read_at")
    private Instant buyerReadAt;

    @Column(name = "seller_read_at")
    private Instant sellerReadAt;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Chat() {
    }

    public Chat(UUID offerId, String offerTitle, UUID buyerId, UUID sellerId, String buyerName, String sellerName,
                boolean sellerVerified, Instant now) {
        this.id = UUID.randomUUID();
        this.offerId = offerId;
        this.offerTitle = offerTitle;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.buyerName = buyerName;
        this.sellerName = sellerName;
        this.sellerVerified = sellerVerified;
        this.buyerReadAt = now;
        this.sellerReadAt = now;
        this.createdAt = now;
    }

    public boolean hasParticipant(UUID userId) {
        return buyerId.equals(userId) || sellerId.equals(userId);
    }

    public boolean isBuyer(UUID userId) {
        return buyerId.equals(userId);
    }

    public UUID counterpartOf(UUID userId) {
        return isBuyer(userId) ? sellerId : buyerId;
    }

    /** Do kiedy ten użytkownik przeczytał rozmowę. */
    public Instant readAtOf(UUID userId) {
        return isBuyer(userId) ? buyerReadAt : sellerReadAt;
    }

    /** Nazwa wyświetlana użytkownikowi jako rozmówca (dla kupującego kanał sprzedawcy). */
    public String counterpartName(UUID userId) {
        return isBuyer(userId) ? sellerName : buyerName;
    }

    public boolean counterpartVerified(UUID userId) {
        return isBuyer(userId) && sellerVerified;
    }

    public String nameOf(UUID userId) {
        return isBuyer(userId) ? buyerName : sellerName;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public String getOfferTitle() {
        return offerTitle;
    }

    public UUID getBuyerId() {
        return buyerId;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
