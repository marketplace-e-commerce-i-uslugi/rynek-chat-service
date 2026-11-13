package pl.rynek.chat.chats;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Wiadomość w rozmowie; {@code priceOfferId} ma wiadomość z propozycją ceny. */
@Entity
@Table(name = "messages")
public class Message {

    @Id
    private UUID id;

    @Column(name = "chat_id")
    private UUID chatId;

    @Column(name = "sender_id")
    private UUID senderId;

    private String text;

    @Column(name = "price_offer_id")
    private UUID priceOfferId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "attachment_media_id")
    private UUID attachmentMediaId;

    @Column(name = "attachment_content_type")
    private String attachmentContentType;

    protected Message() {
    }

    public Message(UUID chatId, UUID senderId, String text, UUID priceOfferId, UUID attachmentMediaId, String attachmentContentType,
                   Instant sentAt) {
        this.id = UUID.randomUUID();
        this.attachmentMediaId = attachmentMediaId;
        this.attachmentContentType = attachmentContentType;
        this.chatId = chatId;
        this.senderId = senderId;
        this.text = text;
        this.priceOfferId = priceOfferId;
        this.sentAt = sentAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getChatId() {
        return chatId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public String getText() {
        return text;
    }

    public UUID getPriceOfferId() {
        return priceOfferId;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public UUID getAttachmentMediaId() {
        return attachmentMediaId;
    }

    public String getAttachmentContentType() {
        return attachmentContentType;
    }
}
