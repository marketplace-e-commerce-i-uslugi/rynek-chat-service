package pl.rynek.chat.chats;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Blokady użytkowników; blokada działa na parę, w obie strony, we wszystkich ich rozmowach. */
public interface BlockRepository extends JpaRepository<Block, Block.Key> {

    @Query(value = "select count(*) > 0 from blocks where (blocker_id = :a and blocked_id = :b) or (blocker_id = :b and blocked_id = :a)", nativeQuery = true)
    boolean between(UUID a, UUID b);

    /** Ponowne zablokowanie tej samej osoby niczego nie zmienia. */
    @Modifying
    @Query(value = "insert into blocks (blocker_id, blocked_id, created_at) values (:blockerId, :blockedId, :now) on conflict do nothing", nativeQuery = true)
    int add(UUID blockerId, UUID blockedId, Instant now);
}
