package pl.rynek.chat.chats;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    /** Od najnowszej; widok odwraca stronę, więc klient dostaje najnowsze wiadomości w kolejności czytania. */
    @Query(value = "select * from messages where chat_id = :chatId order by sent_at desc, id desc limit :limit offset :offset", nativeQuery = true)
    List<Message> newestFirst(UUID chatId, int offset, int limit);
}
