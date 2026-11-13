package pl.rynek.chat.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.chat.api.dto.ChatDtos;
import pl.rynek.chat.api.dto.CursorPage;
import pl.rynek.chat.chats.Chat;
import pl.rynek.chat.chats.ChatService;
import pl.rynek.chat.chats.Message;
import pl.rynek.chat.chats.PriceOffer;
import pl.rynek.chat.clients.CatalogClient;

/** Rozmowy i wiadomości. Dostęp: user (uczestnik rozmowy); odpowiedź na propozycję ceny: sprzedawca tej rozmowy. */
@RestController
public class ChatsController {

    private final ChatService chats;
    private final ChatViews views;
    private final CatalogClient catalog;

    public ChatsController(ChatService chats, ChatViews views, CatalogClient catalog) {
        this.chats = chats;
        this.views = views;
        this.catalog = catalog;
    }

    /** Lista rozmów od ostatniej aktywności; {@code role}: {@code buying} (Kupuję) albo {@code selling} (Sprzedaję). */
    @GetMapping("/api/v1/chats")
    public CursorPage<ChatDtos.ChatSummary> listChats(@AuthenticationPrincipal Jwt token, @RequestParam(required = false) String role,
                                                      @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        UUID me = user(token);
        CursorPage<Chat> page = Cursors.page(cursor, limit, (offset, n) -> chats.list(me, role, offset, n), c -> c);
        // Liczniki nieprzeczytanych dla całej strony naraz, nie osobnym zapytaniem na rozmowę.
        Map<UUID, Integer> unread = chats.unread(me, page.items());
        return new CursorPage<>(page.items().stream().map(c -> views.summary(c, me, unread.get(c.getId()))).toList(), page.nextCursor());
    }

    /** Rozpoczęcie rozmowy z kontekstem oferty; istniejąca rozmowa o tej ofercie jest zwracana ponownie. */
    @PostMapping("/api/v1/chats")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatDtos.Chat createChat(@AuthenticationPrincipal Jwt token, @Valid @RequestBody ChatDtos.CreateChatRequest request) {
        UUID me = user(token);
        return detail(chats.open(me, request.offerId()), me);
    }

    /** Rozmowa z przypiętą kartą oferty (pustą, gdy oferty już nie ma). */
    @GetMapping("/api/v1/chats/{chatId}")
    public ChatDtos.Chat getChat(@AuthenticationPrincipal Jwt token, @PathVariable UUID chatId) {
        UUID me = user(token);
        return detail(chats.visible(me, chatId), me);
    }

    /**
     * Historia: pierwsza strona to najnowsze wiadomości, w kolejności czytania (od najstarszej do najnowszej);
     * {@code nextCursor} prowadzi do starszych.
     */
    @GetMapping("/api/v1/chats/{chatId}/messages")
    public CursorPage<ChatDtos.Message> listMessages(@AuthenticationPrincipal Jwt token, @PathVariable UUID chatId,
                                                     @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        UUID me = user(token);
        Chat chat = chats.visible(me, chatId);
        CursorPage<Message> newestFirst = Cursors.page(cursor, limit == null ? Cursors.MAX_LIMIT : limit, (offset, n) -> chats.messages(me, chatId, offset, n), m -> m);
        Map<UUID, PriceOffer> offers = chats.priceOffersOf(newestFirst.items());
        List<ChatDtos.Message> items = new ArrayList<>(newestFirst.items().stream()
                .map(m -> views.message(m, chat, me, m.getPriceOfferId() == null ? null : offers.get(m.getPriceOfferId()),
                        chats.attachmentUrl(m).orElse(null))).toList());
        Collections.reverse(items);
        return new CursorPage<>(items, newestFirst.nextCursor());
    }

    /**
     * Wysłanie wiadomości z opcjonalnym załącznikiem ({@code attachmentMediaId}: własny plik {@code CHAT_ATTACHMENT}
     * z media-service, inaczej 422).
     */
    @PostMapping("/api/v1/chats/{chatId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatDtos.Message createMessage(@AuthenticationPrincipal Jwt token, @PathVariable UUID chatId,
                                          @Valid @RequestBody ChatDtos.SendMessageRequest request) {
        UUID me = user(token);
        ChatService.Attachment attachment = chats.attachment(me, chatId, request.attachmentMediaId());
        Message message = chats.send(me, chatId, request.text(), attachment);
        return views.message(message, chats.visible(me, chatId), me, null, chats.attachmentUrl(message).orElse(null));
    }

    /** Oznaczenie rozmowy jako przeczytanej (zeruje licznik i ustawia status „Przeczytano” u rozmówcy). */
    @PostMapping("/api/v1/chats/{chatId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAsRead(@AuthenticationPrincipal Jwt token, @PathVariable UUID chatId) {
        chats.markRead(user(token), chatId);
    }

    /** „Zaproponuj cenę”: kupujący, jedna oczekująca propozycja naraz. */
    @PostMapping("/api/v1/chats/{chatId}/price-offers")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatDtos.PriceOffer createPriceOffer(@AuthenticationPrincipal Jwt token, @PathVariable UUID chatId,
                                                @Valid @RequestBody ChatDtos.CreatePriceOfferRequest request) {
        UUID me = user(token);
        Message message = chats.proposePrice(me, chatId, request.price().amount(), request.price().currency());
        return views.priceOffer(chats.priceOffersOf(List.of(message)).get(message.getPriceOfferId()));
    }

    @PostMapping("/api/v1/chats/{chatId}/price-offers/{priceOfferId}/accept")
    public ChatDtos.PriceOffer acceptPriceOffer(@AuthenticationPrincipal Jwt token, @PathVariable UUID chatId, @PathVariable UUID priceOfferId) {
        return views.priceOffer(chats.decide(user(token), chatId, priceOfferId, true));
    }

    @PostMapping("/api/v1/chats/{chatId}/price-offers/{priceOfferId}/decline")
    public ChatDtos.PriceOffer declinePriceOffer(@AuthenticationPrincipal Jwt token, @PathVariable UUID chatId, @PathVariable UUID priceOfferId) {
        return views.priceOffer(chats.decide(user(token), chatId, priceOfferId, false));
    }

    /** Blokuje rozmówcę we wszystkich rozmowach tej pary. */
    @PostMapping("/api/v1/chats/{chatId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void blockParticipant(@AuthenticationPrincipal Jwt token, @PathVariable UUID chatId) {
        chats.block(user(token), chatId);
    }

    /** Szybkie odpowiedzi; {@code context}: {@code offer} (domyślnie) albo {@code service}. */
    @GetMapping("/api/v1/chats/quick-replies")
    public List<String> getQuickReplies(@RequestParam(required = false) String context) {
        return ChatService.quickReplies(context);
    }

    /** Licznik nieprzeczytanych wiadomości do ikony w pasku nawigacji. */
    @GetMapping("/api/v1/chats/unread-count")
    public ChatDtos.UnreadCount getUnreadCount(@AuthenticationPrincipal Jwt token) {
        return new ChatDtos.UnreadCount(chats.unreadCount(user(token)));
    }

    private ChatDtos.Chat detail(Chat chat, UUID me) {
        int unread = chats.unread(me, List.of(chat)).get(chat.getId());
        return views.detail(chat, me, unread, catalog.card(chat.getOfferId()).orElse(null), chats.blocked(chat));
    }

    private static UUID user(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}
