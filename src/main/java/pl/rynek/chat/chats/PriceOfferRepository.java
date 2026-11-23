package pl.rynek.chat.chats;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PriceOfferRepository extends JpaRepository<PriceOffer, UUID> {

    Optional<PriceOffer> findByIdAndChatId(UUID id, UUID chatId);

    /** 0: propozycja była już rozpatrzona (albo nie jest oczekująca); dwie odpowiedzi naraz nie rozstrzygną jej dwa razy. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update price_offers set status = :status, decided_at = :now where id = :id and chat_id = :chatId and status = 'PENDING'", nativeQuery = true)
    int decide(UUID id, UUID chatId, String status, Instant now);

    /** 1: oferta jest zaakceptowana, nieużyta i ważna ({@code decided_at > notBefore}); wykorzystuje ją tylko jedno zamówienie. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update price_offers set used_at = :now where id = :id and status = 'ACCEPTED' and used_at is null and decided_at > :notBefore",
            nativeQuery = true)
    int redeem(UUID id, Instant now, Instant notBefore);

    /** Zwraca cenę do użycia, gdy zamówienie nie powstało. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update price_offers set used_at = null where id = :id", nativeQuery = true)
    int release(UUID id);
}
