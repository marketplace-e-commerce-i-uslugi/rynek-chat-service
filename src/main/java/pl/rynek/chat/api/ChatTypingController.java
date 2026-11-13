package pl.rynek.chat.api;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;
import pl.rynek.chat.chats.ChatService;

/**
 * „Pisze…”: klient zgłasza je na {@code /app/chat.typing} z ciałem {@code {"chatId": "…"}}, a rozmówca dostaje zdarzenie
 * {@code typing}. Zgłoszenia z cudzej albo zablokowanej rozmowy są po cichu pomijane (to tylko sygnał pomocniczy).
 */
@Controller
class ChatTypingController {

    private static final Pattern CHAT_ID = Pattern.compile("\"chatId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");

    private final ChatService chats;
    private final ChatEvents events;

    ChatTypingController(ChatService chats, ChatEvents events) {
        this.chats = chats;
        this.events = events;
    }

    /** Ciało czytamy jako surowe bajty, bo klient STOMP nie ustawia typu zawartości. */
    @MessageMapping("chat.typing")
    void typing(Principal principal, @Payload byte[] body) {
        Matcher match = CHAT_ID.matcher(new String(body, StandardCharsets.UTF_8));
        if (principal == null || !match.find()) {
            return;
        }
        try {
            UUID chatId = UUID.fromString(match.group(1));
            chats.typingTarget(UUID.fromString(principal.getName()), chatId).ifPresent(to -> events.typing(chatId, to));
        } catch (IllegalArgumentException e) {
            // Zły identyfikator: pomijamy.
        }
    }
}
