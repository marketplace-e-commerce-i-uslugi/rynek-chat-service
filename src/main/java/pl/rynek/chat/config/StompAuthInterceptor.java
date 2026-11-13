package pl.rynek.chat.config;

import java.util.List;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Ramki STOMP od klienta: {@code CONNECT} wymaga ważnego tokenu JWT z identity-service (nagłówek {@code Authorization}),
 * a jego właściciel staje się użytkownikiem sesji (nazwa = jego id). Po połączeniu wolno tylko subskrybować własną kolejkę
 * {@code /user/queue/chat} i wysyłać {@code /app/chat.typing}; wszystko inne kończy się błędem, więc nikt nie podsłucha
 * cudzych zdarzeń ani nie wyśle czegoś, czego serwis nie przewidział.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    static final String QUEUE = "/user/queue/chat";
    static final String TYPING = "/app/chat.typing";

    private final JwtDecoder jwtDecoder;

    public StompAuthInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        StompCommand command = accessor == null ? null : accessor.getCommand();
        if (command == null) {
            return message;
        }
        switch (command) {
            case CONNECT -> accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
            case SUBSCRIBE -> require(accessor, QUEUE);
            case SEND -> require(accessor, TYPING);
            default -> {
            }
        }
        return message;
    }

    private UsernamePasswordAuthenticationToken authenticate(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new MessagingException("Zaloguj się, aby korzystać z czatu na żywo.");
        }
        try {
            Jwt jwt = jwtDecoder.decode(header.substring(7).trim());
            UUID.fromString(jwt.getSubject());
            return new UsernamePasswordAuthenticationToken(jwt.getSubject(), null, List.of());
        } catch (JwtException | IllegalArgumentException e) {
            throw new MessagingException("Zaloguj się ponownie, aby korzystać z czatu na żywo.");
        }
    }

    private static void require(StompHeaderAccessor accessor, String destination) {
        if (accessor.getUser() == null) {
            throw new MessagingException("Zaloguj się, aby korzystać z czatu na żywo.");
        }
        if (!destination.equals(accessor.getDestination())) {
            throw new MessagingException("Ten adres nie jest dostępny.");
        }
    }
}
