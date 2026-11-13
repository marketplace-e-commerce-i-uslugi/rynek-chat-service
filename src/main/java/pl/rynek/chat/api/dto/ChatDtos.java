package pl.rynek.chat.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Kształty z rynek-frontend ({@code core/api/models/chat.ts}). */
public final class ChatDtos {

    private ChatDtos() {
    }

    public record CreateChatRequest(@NotNull(message = "Wybierz ofertę, o którą chcesz zapytać.") UUID offerId) {
    }

    /** {@code attachmentMediaId} jest rozpoznawane, ale załączniki nie są jeszcze obsługiwane (422). */
    public record SendMessageRequest(
            @NotBlank(message = "Napisz wiadomość.") @Size(max = 2000, message = "Wiadomość może mieć do 2000 znaków.") String text,
            UUID attachmentMediaId) {
    }

    public record Money(@NotNull(message = "Podaj kwotę.") Long amount, @NotBlank(message = "Podaj walutę.") String currency) {
    }

    public record CreatePriceOfferRequest(@NotNull(message = "Podaj cenę.") @Valid Money price) {
    }

    public record Counterpart(String name, String initials, boolean verified) {
    }

    public record ChatSummary(UUID id, String role, Counterpart counterpart, String lastMessage, Instant lastMessageAt, int unread) {
    }

    /** {@code offer}: karta przypiętej oferty albo {@code null}, gdy oferty już nie ma. */
    public record Chat(UUID id, String role, Counterpart counterpart, String lastMessage, Instant lastMessageAt, int unread,
                       Map<String, Object> offer, boolean blocked, String responseTime) {
    }

    public record PriceOffer(UUID id, Money price, String status) {
    }

    public record Message(UUID id, UUID chatId, boolean mine, String text, Instant sentAt, String status, String attachmentUrl,
                          PriceOffer priceOffer) {
    }

    public record UnreadCount(long count) {
    }
}
