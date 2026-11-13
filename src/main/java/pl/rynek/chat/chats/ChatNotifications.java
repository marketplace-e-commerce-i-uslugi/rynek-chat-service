package pl.rynek.chat.chats;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.rynek.chat.clients.NotificationClient;

/**
 * Powiadomienia z rozmów (kategoria „wiadomości”), wysyłane dopiero po zatwierdzeniu transakcji. Jedno powiadomienie
 * przypada na serię nieprzeczytanych wiadomości: klucz zdarzenia zawiera czas, do którego adresat przeczytał rozmowę,
 * więc kolejne wiadomości, zanim ją otworzy, nie mnożą powiadomień, a po przeczytaniu następna znów powiadomi.
 */
@Component
class ChatNotifications {

    private static final int EXCERPT = 140;

    private final NotificationClient notifications;

    ChatNotifications(NotificationClient notifications) {
        this.notifications = notifications;
    }

    void messageSent(Chat chat, UUID senderId, String text, Instant recipientReadAt) {
        UUID to = chat.counterpartOf(senderId);
        send(to, "Nowa wiadomość", chat.nameOf(senderId) + ": " + excerpt(text), chat,
                "chat-" + chat.getId() + "-" + to + "-" + recipientReadAt.toEpochMilli());
    }

    void priceProposed(Chat chat, PriceOffer offer) {
        send(chat.getSellerId(), "Propozycja ceny", chat.nameOf(chat.getBuyerId()) + " proponuje " + money(offer) + " za: " + chat.getOfferTitle(),
                chat, "price-offer-" + offer.getId() + "-proposed");
    }

    void priceDecided(Chat chat, PriceOffer offer, boolean accepted) {
        send(chat.getBuyerId(), accepted ? "Propozycja ceny przyjęta" : "Propozycja ceny odrzucona",
                (accepted ? "Sprzedawca przyjął " : "Sprzedawca odrzucił ") + money(offer) + " za: " + chat.getOfferTitle(),
                chat, "price-offer-" + offer.getId() + (accepted ? "-accepted" : "-declined"));
    }

    static String money(PriceOffer offer) {
        return String.format(Locale.forLanguageTag("pl-PL"), "%,.2f zł", offer.getAmount() / 100.0);
    }

    private void send(UUID to, String title, String body, Chat chat, String key) {
        afterCommit(() -> notifications.send(to, "messages", title, body, "/wiadomosci/" + chat.getId(), key));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    static String excerpt(String text) {
        String clean = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        return clean.length() <= EXCERPT ? clean : clean.substring(0, EXCERPT - 1) + "…";
    }
}
