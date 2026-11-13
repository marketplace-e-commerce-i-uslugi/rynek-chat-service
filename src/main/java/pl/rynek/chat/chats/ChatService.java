package pl.rynek.chat.chats;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.chat.clients.CatalogClient;
import pl.rynek.chat.clients.CatalogClient.OfferInfo;
import pl.rynek.chat.clients.IdentityClient;
import pl.rynek.chat.clients.MediaClient;

/**
 * Rozmowy kupujących ze sprzedawcami. Rozmowę zakłada kupujący w kontekście oferty (jedna na parę oferta–kupujący).
 * Wątek widzą tylko jego uczestnicy (dla pozostałych nie istnieje). Blokada działa na parę użytkowników i zatrzymuje
 * wysyłanie wiadomości oraz propozycji cen. Załącznik to plik {@code CHAT_ATTACHMENT} z media-service, który wysyłający sam
 * wgrał; obie strony rozmowy oglądają go pod krótko ważnym podpisanym adresem. Kanał na żywo (WebSocket) nie jest jeszcze dostępny.
 */
@Service
public class ChatService {

    static final int MAX_PRICE = 100_000_000;
    static final String CURRENCY = "PLN";

    private final ChatRepository chats;
    private final MessageRepository messages;
    private final PriceOfferRepository priceOffers;
    private final BlockRepository blocks;
    private final CatalogClient catalog;
    private final IdentityClient identity;
    private final MediaClient media;
    private final ChatNotifications notices;
    private final Clock clock;

    public ChatService(ChatRepository chats, MessageRepository messages, PriceOfferRepository priceOffers, BlockRepository blocks,
                       CatalogClient catalog, IdentityClient identity, MediaClient media, ChatNotifications notices, Clock clock) {
        this.chats = chats;
        this.messages = messages;
        this.priceOffers = priceOffers;
        this.blocks = blocks;
        this.catalog = catalog;
        this.identity = identity;
        this.media = media;
        this.notices = notices;
        this.clock = clock;
    }

    /**
     * Zakłada rozmowę o ofercie albo zwraca istniejącą (ponowne kliknięcie „Napisz”). Bez transakcji obejmującej wywołania
     * innych serwisów; równoległe założenie tej samej rozmowy kończy się jedną rozmową dzięki kluczowi unikalnemu.
     */
    public Chat open(UUID buyerId, UUID offerId) {
        Optional<Chat> existing = chats.findByOfferIdAndBuyerId(offerId, buyerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        OfferInfo offer = catalog.offer(offerId).filter(OfferInfo::available)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy takiej oferty."));
        if (offer.ownerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nie możesz napisać do siebie.");
        }
        if (blocks.between(buyerId, offer.ownerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nie możesz rozpocząć rozmowy z tym sprzedawcą.");
        }
        Map<String, Object> channel = catalog.card(offerId).map(card -> card.get("channel")).filter(Map.class::isInstance)
                .map(c -> castMap(c)).orElse(Map.of());
        String sellerName = text(channel.get("name")).or(() -> identity.displayName(offer.ownerId())).orElse("Sprzedawca");
        boolean verified = Boolean.TRUE.equals(channel.get("verified"));
        String buyerName = identity.displayName(buyerId).orElse("Kupujący");
        try {
            return chats.saveAndFlush(new Chat(offerId, offer.title(), buyerId, offer.ownerId(), buyerName, sellerName, verified, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            return chats.findByOfferIdAndBuyerId(offerId, buyerId).orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public List<Chat> list(UUID userId, String role, int offset, int limit) {
        boolean buying = role == null || role.equals("buying");
        boolean selling = role == null || role.equals("selling");
        if (!buying && !selling) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rola musi mieć wartość buying albo selling.");
        }
        return chats.forUser(userId, buying, selling, offset, limit);
    }

    /** Liczba nieprzeczytanych wiadomości w każdej z rozmów; rozmowy bez nieprzeczytanych mają 0. */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> unread(UUID userId, Collection<Chat> forChats) {
        Map<UUID, Integer> unread = new HashMap<>();
        forChats.forEach(c -> unread.put(c.getId(), 0));
        if (!forChats.isEmpty()) {
            chats.unreadByChat(userId, forChats.stream().map(Chat::getId).toList())
                    .forEach(row -> unread.put((UUID) row[0], ((Number) row[1]).intValue()));
        }
        return unread;
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return chats.unreadTotal(userId);
    }

    @Transactional(readOnly = true)
    public Chat visible(UUID userId, UUID chatId) {
        return chats.findById(chatId).filter(c -> c.hasParticipant(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy takiej rozmowy."));
    }

    /** Rozmówca, któremu wolno wysłać „pisze…”: rozmowa musi być widoczna i niezablokowana, inaczej pusty wynik (bez błędu). */
    @Transactional(readOnly = true)
    public Optional<UUID> typingTarget(UUID userId, UUID chatId) {
        return chats.findById(chatId).filter(c -> c.hasParticipant(userId)).filter(c -> !blocked(c)).map(c -> c.counterpartOf(userId));
    }

    @Transactional(readOnly = true)
    public boolean blocked(Chat chat) {
        return blocks.between(chat.getBuyerId(), chat.getSellerId());
    }

    /** Wiadomości od najnowszej (strona starszych zaczyna się od {@code offset}); propozycje cen dołączane osobno. */
    @Transactional(readOnly = true)
    public List<Message> messages(UUID userId, UUID chatId, int offset, int limit) {
        visible(userId, chatId);
        return messages.newestFirst(chatId, offset, limit);
    }

    @Transactional(readOnly = true)
    public Map<UUID, PriceOffer> priceOffersOf(Collection<Message> forMessages) {
        List<UUID> ids = forMessages.stream().map(Message::getPriceOfferId).filter(java.util.Objects::nonNull).toList();
        return ids.isEmpty() ? Map.of() : priceOffers.findAllById(ids).stream().collect(Collectors.toMap(PriceOffer::getId, o -> o));
    }

    /** Załącznik zweryfikowany w media-service: typ pliku zapisywany przy wiadomości. */
    public record Attachment(UUID mediaId, String contentType) {
    }

    /**
     * Sprawdza załącznik przed wysłaniem (bez transakcji, bo woła media-service): rozmowa musi być widoczna dla wysyłającego,
     * a plik musi być jego własnym, gotowym {@code CHAT_ATTACHMENT}. Brak załącznika to {@code null}.
     */
    public Attachment attachment(UUID userId, UUID chatId, UUID mediaId) {
        if (mediaId == null) {
            return null;
        }
        visible(userId, chatId);
        MediaClient.File file = media.file(mediaId).filter(f -> f.ownerId().equals(userId) && "CHAT_ATTACHMENT".equals(f.purpose()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Nie znaleźliśmy tego załącznika. Wyślij plik jeszcze raz."));
        return new Attachment(file.id(), file.contentType());
    }

    /** Adres do obejrzenia załącznika wiadomości (podpisany, krótko ważny); pusty bez załącznika albo gdy pliku nie da się pobrać. */
    public Optional<String> attachmentUrl(Message message) {
        return message.getAttachmentMediaId() == null ? Optional.empty() : media.url(message.getAttachmentMediaId());
    }

    @Transactional
    public Message send(UUID userId, UUID chatId, String text, Attachment attachment) {
        Chat chat = openChat(userId, chatId);
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Napisz wiadomość.");
        }
        Instant recipientReadAt = chat.readAtOf(chat.counterpartOf(userId));
        Message message = store(chat, userId, clean, null, attachment);
        notices.messageSent(chat, userId, clean, recipientReadAt);
        return message;
    }

    @Transactional
    public void markRead(UUID userId, UUID chatId) {
        visible(userId, chatId);
        chats.markRead(chatId, userId, clock.instant());
    }

    /** Propozycja ceny kupującego: jedna oczekująca naraz; trafia do rozmowy jako wiadomość. */
    @Transactional
    public Message proposePrice(UUID userId, UUID chatId, long amount, String currency) {
        Chat chat = openChat(userId, chatId);
        if (!chat.isBuyer(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cenę proponuje kupujący.");
        }
        if (amount <= 0 || amount > MAX_PRICE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Podaj kwotę większą od zera i nie większą niż 1 000 000 zł.");
        }
        if (!CURRENCY.equals(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cenę podaj w złotych (PLN).");
        }
        PriceOffer offer;
        try {
            offer = priceOffers.saveAndFlush(new PriceOffer(chatId, amount, currency, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Poprzednia propozycja czeka jeszcze na odpowiedź sprzedawcy.");
        }
        Message message = store(chat, userId, "Propozycja ceny: " + ChatNotifications.money(offer), offer.getId(), null);
        notices.priceProposed(chat, offer);
        return message;
    }

    /** Odpowiedź sprzedawcy na propozycję; rozstrzygnięta nie wraca do stanu oczekującej. */
    @Transactional
    public PriceOffer decide(UUID userId, UUID chatId, UUID priceOfferId, boolean accept) {
        Chat chat = openChat(userId, chatId);
        if (chat.isBuyer(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Na propozycję ceny odpowiada sprzedawca.");
        }
        priceOffers.findByIdAndChatId(priceOfferId, chatId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy takiej propozycji."));
        if (priceOffers.decide(priceOfferId, chatId, accept ? PriceOffer.ACCEPTED : PriceOffer.DECLINED, clock.instant()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ta propozycja została już rozpatrzona.");
        }
        PriceOffer decided = priceOffers.findById(priceOfferId).orElseThrow();
        notices.priceDecided(chat, decided, accept);
        return decided;
    }

    /** Blokuje rozmówcę we wszystkich rozmowach tej pary; ponowne zablokowanie niczego nie zmienia. */
    @Transactional
    public void block(UUID userId, UUID chatId) {
        Chat chat = visible(userId, chatId);
        blocks.add(userId, chat.counterpartOf(userId), clock.instant());
    }

    /** Rozmowa uczestnika, w której można pisać (403, gdy któraś ze stron zablokowała drugą). */
    private Chat openChat(UUID userId, UUID chatId) {
        Chat chat = visible(userId, chatId);
        if (blocked(chat)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Rozmowa jest zablokowana.");
        }
        return chat;
    }

    private Message store(Chat chat, UUID senderId, String text, UUID priceOfferId, Attachment attachment) {
        Instant now = clock.instant();
        Message message = messages.saveAndFlush(new Message(chat.getId(), senderId, text, priceOfferId,
                attachment == null ? null : attachment.mediaId(), attachment == null ? null : attachment.contentType(), now));
        chats.recordMessage(chat.getId(), senderId, ChatNotifications.excerpt(text), now);
        return message;
    }

    private static Optional<String> text(Object value) {
        return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** Szybkie odpowiedzi do pola wiadomości; „Zaproponuj cenę” otwiera we frontendzie okno propozycji ceny. */
    public static List<String> quickReplies(String context) {
        if ("service".equals(context)) {
            return List.of("Czy termin jest nadal wolny?", "Jaki jest orientacyjny koszt?", "Czy wystawiasz fakturę?");
        }
        return List.of("Czy oferta jest nadal aktualna?", "Czy cena jest do negocjacji?", "Czy możliwy jest odbiór osobisty?", "Zaproponuj cenę");
    }
}
