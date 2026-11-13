package pl.rynek.chat.chats;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChatRepository extends JpaRepository<Chat, UUID> {

    Optional<Chat> findByOfferIdAndBuyerId(UUID offerId, UUID buyerId);

    /**
     * Rozmowy użytkownika od ostatniej aktywności. Kupujący widzi też rozmowę, w której jeszcze nic nie napisał; sprzedawca
     * dopiero po pierwszej wiadomości kupującego.
     */
    @Query(value = "select * from chats where (:buying and buyer_id = :userId) "
            + "or (:selling and seller_id = :userId and last_message_at is not null) "
            + "order by coalesce(last_message_at, created_at) desc, id desc limit :limit offset :offset", nativeQuery = true)
    List<Chat> forUser(UUID userId, boolean buying, boolean selling, int offset, int limit);

    /** Wiersze {@code [chatId, nieprzeczytane]} tylko dla rozmów z nieprzeczytanymi wiadomościami. */
    @Query(value = "select c.id, count(m.id) from chats c join messages m on m.chat_id = c.id "
            + "where c.id in (:chatIds) and m.sender_id <> :userId "
            + "and m.sent_at > (case when c.buyer_id = :userId then c.buyer_read_at else c.seller_read_at end) group by c.id", nativeQuery = true)
    List<Object[]> unreadByChat(UUID userId, Collection<UUID> chatIds);

    @Query(value = "select count(m.id) from messages m join chats c on c.id = m.chat_id "
            + "where (c.buyer_id = :userId or c.seller_id = :userId) and m.sender_id <> :userId "
            + "and m.sent_at > (case when c.buyer_id = :userId then c.buyer_read_at else c.seller_read_at end)", nativeQuery = true)
    long unreadTotal(UUID userId);

    /** Ostatnia wiadomość rozmowy; nadawca ma od razu wszystko przeczytane (jego czas przeczytania przesuwa się na teraz). */
    @Modifying
    @Query(value = "update chats set last_message = :text, last_message_at = :now, "
            + "buyer_read_at = case when buyer_id = :senderId then :now else buyer_read_at end, "
            + "seller_read_at = case when seller_id = :senderId then :now else seller_read_at end where id = :chatId", nativeQuery = true)
    int recordMessage(UUID chatId, UUID senderId, String text, Instant now);

    /** Przeczytanie przesuwa czas tylko do przodu. */
    @Modifying
    @Query(value = "update chats set buyer_read_at = case when buyer_id = :userId and buyer_read_at < :now then :now else buyer_read_at end, "
            + "seller_read_at = case when seller_id = :userId and seller_read_at < :now then :now else seller_read_at end where id = :chatId", nativeQuery = true)
    int markRead(UUID chatId, UUID userId, Instant now);
}
