package pl.rynek.chat.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.chat.api.dto.CreateChatRequest;
import pl.rynek.chat.api.dto.CreateMessageRequest;
import pl.rynek.chat.api.dto.CreatePriceOfferRequest;

import static pl.rynek.chat.common.NotImplemented.notImplemented;

@RestController
public class ChatsController {

    /**
     * Lista rozmów (Wszystkie / Kupuję / Sprzedaję). Dostęp: user.
     */
    @GetMapping("/api/v1/chats")
    public void listChats(@RequestParam(required = false) String role, @RequestParam(required = false) String cursor) {
        throw notImplemented();
    }

    /**
     * Rozpoczęcie rozmowy z kontekstem oferty (offerId). Dostęp: user.
     */
    @PostMapping("/api/v1/chats")
    public void createChat(@RequestBody CreateChatRequest request) {
        throw notImplemented();
    }

    /**
     * Rozmowa wraz z przypiętą ofertą. Dostęp: user.
     */
    @GetMapping("/api/v1/chats/{chatId}")
    public void getChat(@PathVariable String chatId) {
        throw notImplemented();
    }

    /**
     * Historia wiadomości. Dostęp: user.
     */
    @GetMapping("/api/v1/chats/{chatId}/messages")
    public void listMessages(@PathVariable String chatId, @RequestParam(required = false) String cursor) {
        throw notImplemented();
    }

    /**
     * Wysłanie wiadomości (tekst, załącznik mediaId). Dostęp: user.
     */
    @PostMapping("/api/v1/chats/{chatId}/messages")
    public void createMessage(@PathVariable String chatId, @RequestBody CreateMessageRequest request) {
        throw notImplemented();
    }

    /**
     * Oznaczenie jako przeczytane. Dostęp: user.
     */
    @PostMapping("/api/v1/chats/{chatId}/read")
    public void markAsRead(@PathVariable String chatId) {
        throw notImplemented();
    }

    /**
     * „Zaproponuj cenę”. Dostęp: user.
     */
    @PostMapping("/api/v1/chats/{chatId}/price-offers")
    public void createPriceOffer(@PathVariable String chatId, @RequestBody CreatePriceOfferRequest request) {
        throw notImplemented();
    }

    /**
     * Akceptacja propozycji ceny. Dostęp: seller.
     */
    @PostMapping("/api/v1/chats/{chatId}/price-offers/{priceOfferId}/accept")
    public void acceptPriceOffer(@PathVariable String chatId, @PathVariable String priceOfferId) {
        throw notImplemented();
    }

    /**
     * Odrzucenie. Dostęp: seller.
     */
    @PostMapping("/api/v1/chats/{chatId}/price-offers/{priceOfferId}/decline")
    public void declinePriceOffer(@PathVariable String chatId, @PathVariable String priceOfferId) {
        throw notImplemented();
    }

    /**
     * Zablokowanie rozmówcy. Dostęp: user.
     */
    @PostMapping("/api/v1/chats/{chatId}/block")
    public void blockParticipant(@PathVariable String chatId) {
        throw notImplemented();
    }

    /**
     * Szybkie odpowiedzi („Czy nadal aktualne?”…). Dostęp: user.
     */
    @GetMapping("/api/v1/chats/quick-replies")
    public void getQuickReplies(@RequestParam(required = false) String context) {
        throw notImplemented();
    }

    /**
     * Licznik nieprzeczytanych (ikona w pasku nawigacji). Dostęp: user.
     */
    @GetMapping("/api/v1/chats/unread-count")
    public void getUnreadCount() {
        throw notImplemented();
    }
}
