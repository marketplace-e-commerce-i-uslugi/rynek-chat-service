package pl.rynek.chat.chats;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Użytkownik zablokował rozmówcę (klucz: blokujący i zablokowany). */
@Entity
@Table(name = "blocks")
@IdClass(Block.Key.class)
public class Block {

    public record Key(UUID blockerId, UUID blockedId) implements Serializable {
    }

    @Id
    @Column(name = "blocker_id")
    private UUID blockerId;

    @Id
    @Column(name = "blocked_id")
    private UUID blockedId;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Block() {
    }

    public UUID getBlockerId() {
        return blockerId;
    }

    public UUID getBlockedId() {
        return blockedId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
