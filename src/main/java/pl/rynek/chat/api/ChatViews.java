package pl.rynek.chat.api;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import pl.rynek.chat.api.dto.ChatDtos;
import pl.rynek.chat.chats.Chat;
import pl.rynek.chat.chats.Message;
import pl.rynek.chat.chats.PriceOffer;

/** Widoki rozmów i wiadomości z perspektywy zalogowanego użytkownika. */
@Component
class ChatViews {

    ChatDtos.ChatSummary summary(Chat chat, UUID me, int unread) {
        return new ChatDtos.ChatSummary(chat.getId(), role(chat, me), counterpart(chat, me), lastMessage(chat), lastMessageAt(chat), unread);
    }

    ChatDtos.Chat detail(Chat chat, UUID me, int unread, Map<String, Object> offer, boolean blocked) {
        return new ChatDtos.Chat(chat.getId(), role(chat, me), counterpart(chat, me), lastMessage(chat), lastMessageAt(chat), unread,
                offer, blocked, null);
    }

    /**
     * Status „Przeczytano” dla wiadomości, którą druga strona (a dla cudzych: ja) przeczytała po jej wysłaniu. Załączników
     * nie ma, więc {@code attachmentUrl} jest zawsze pusty.
     */
    ChatDtos.Message message(Message m, Chat chat, UUID me, PriceOffer offer) {
        boolean mine = m.getSenderId().equals(me);
        UUID reader = mine ? chat.counterpartOf(me) : me;
        String status = chat.readAtOf(reader).isBefore(m.getSentAt()) ? "Wysłano" : "Przeczytano";
        return new ChatDtos.Message(m.getId(), m.getChatId(), mine, m.getText(), m.getSentAt(), status, null, offer == null ? null : priceOffer(offer));
    }

    ChatDtos.PriceOffer priceOffer(PriceOffer offer) {
        return new ChatDtos.PriceOffer(offer.getId(), new ChatDtos.Money(offer.getAmount(), offer.getCurrency()), offer.getStatus());
    }

    private static String role(Chat chat, UUID me) {
        return chat.isBuyer(me) ? "buying" : "selling";
    }

    private static ChatDtos.Counterpart counterpart(Chat chat, UUID me) {
        String name = chat.counterpartName(me);
        return new ChatDtos.Counterpart(name, initials(name), chat.counterpartVerified(me));
    }

    private static String lastMessage(Chat chat) {
        return chat.getLastMessage() == null ? "" : chat.getLastMessage();
    }

    private static java.time.Instant lastMessageAt(Chat chat) {
        return chat.getLastMessageAt() == null ? chat.getCreatedAt() : chat.getLastMessageAt();
    }

    /** Inicjały z pierwszych liter dwóch pierwszych słów nazwy. */
    static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.trim().split("\\s+")) {
            if (!word.isEmpty() && Character.isLetterOrDigit(word.codePointAt(0)) && out.length() < 2) {
                out.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
            }
        }
        return out.length() == 0 ? "?" : out.toString();
    }
}
