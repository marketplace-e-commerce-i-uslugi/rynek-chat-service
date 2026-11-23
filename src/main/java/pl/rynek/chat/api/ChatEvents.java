package pl.rynek.chat.api;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import pl.rynek.chat.api.dto.ChatDtos;
import pl.rynek.chat.chats.Chat;
import pl.rynek.chat.chats.ChatService;
import pl.rynek.chat.chats.Message;
import pl.rynek.chat.chats.PriceOffer;

/**
 * Zdarzenia na żywo dla rozmówcy ({@code /user/queue/chat}): nowa wiadomość (w widoku odbiorcy), przeczytanie wątku i „pisze…”.
 * Nadawca dostaje wynik z odpowiedzi REST, więc nie dostaje własnych zdarzeń. Publikacja nigdy nie psuje żądania: gdy rozmówca
 * nie jest połączony albo wysyłka się nie uda, zdarzenie przepada, a stan zostaje w bazie (rozmówca zobaczy go po wejściu w rozmowę).
 */
@Component
class ChatEvents {

    private static final Logger log = LoggerFactory.getLogger(ChatEvents.class);

    private final SimpMessagingTemplate template;
    private final ChatViews views;
    private final ChatService chats;

    ChatEvents(SimpMessagingTemplate template, ChatViews views, ChatService chats) {
        this.template = template;
        this.views = views;
        this.chats = chats;
    }

    /** Nowa wiadomość (także z propozycją ceny albo załącznikiem) dla drugiej strony rozmowy. */
    void messageSent(Chat chat, UUID senderId, Message message, PriceOffer offer) {
        UUID to = chat.counterpartOf(senderId);
        ChatDtos.Message dto = views.message(message, chat, to, offer, chats.attachmentUrl(message).orElse(null));
        push(to, new ChatDtos.ChatEvent("message", chat.getId(), dto));
    }

    /** Czytający przeczytał wątek: autor wiadomości odświeża statusy „Przeczytano”. */
    void read(Chat chat, UUID readerId) {
        push(chat.counterpartOf(readerId), new ChatDtos.ChatEvent("read", chat.getId(), null));
    }

    /** Sprzedawca przyjął albo odrzucił propozycję ceny: kupujący odświeża wiadomość z propozycją. */
    void priceDecided(Chat chat, UUID deciderId) {
        push(chat.counterpartOf(deciderId), new ChatDtos.ChatEvent("price-decided", chat.getId(), null));
    }

    void typing(UUID chatId, UUID to) {
        push(to, new ChatDtos.ChatEvent("typing", chatId, null));
    }

    private void push(UUID to, ChatDtos.ChatEvent event) {
        try {
            template.convertAndSendToUser(to.toString(), "/queue/chat", event);
        } catch (RuntimeException e) {
            log.warn("Nie wysłano zdarzenia {} rozmowy {}: {}", event.type(), event.chatId(), e.getMessage());
        }
    }
}
