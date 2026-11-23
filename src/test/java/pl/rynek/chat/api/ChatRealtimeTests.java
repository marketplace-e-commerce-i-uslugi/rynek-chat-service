package pl.rynek.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import pl.rynek.chat.TestcontainersConfig;
import pl.rynek.chat.clients.CatalogClient;
import pl.rynek.chat.clients.CatalogClient.OfferInfo;
import pl.rynek.chat.clients.IdentityClient;
import pl.rynek.chat.clients.MediaClient;
import pl.rynek.chat.clients.NotificationClient;

/**
 * Kanał na żywo {@code /ws/chat} z prawdziwym klientem STOMP na losowym porcie i PostgreSQL: logowanie w ramce CONNECT,
 * ograniczenie adresów, zdarzenia dla rozmówcy (wiadomość, przeczytanie, „pisze…”) i brak zdarzeń dla nadawcy, obcych i blokady.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ChatRealtimeTests {

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    CatalogClient catalog;

    @MockitoBean
    IdentityClient identity;

    @MockitoBean
    NotificationClient notifications;

    @MockitoBean
    MediaClient media;

    private WebSocketStompClient client;
    private final List<StompSession> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(jwtDecoder.decode(anyString())).willAnswer(call -> {
            String token = call.getArgument(0);
            if (!token.startsWith("user-")) {
                throw new BadJwtException("Nieważny token");
            }
            return Jwt.withTokenValue(token).header("alg", "RS256").subject(token.substring(5))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        });
        given(identity.displayName(any())).willReturn(Optional.of("Anna Nowak"));
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new RawConverter());
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
        client.stop();
    }

    @Test
    void connectionNeedsAValidToken() throws Exception {
        assertThat(connect(null)).isNull();
        assertThat(connect("Bearer zly-token")).isNull();
        assertThat(connect("Basic abc")).isNull();
        assertThat(connect("Bearer user-nie-uuid")).isNull();
        assertThat(connect("Bearer user-" + UUID.randomUUID())).isNotNull();
    }

    @Test
    void onlyOwnQueueAndTypingAreAllowed() throws Exception {
        StompSession session = connect(bearer(UUID.randomUUID()));
        assertThat(session).isNotNull();
        // Cudze albo inne kolejki nie są dostępne: serwer kończy sesję błędem.
        session.subscribe("/queue/chat", frameHandler(new LinkedBlockingQueue<>()));
        awaitClosed(session);

        StompSession other = connect(bearer(UUID.randomUUID()));
        other.send("/app/cos-innego", "{}");
        awaitClosed(other);
    }

    @Test
    void recipientGetsMessageReadAndTypingEventsButSenderAndStrangersDoNot() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        String id = chat(buyer, offer(seller));

        BlockingQueue<String> sellerEvents = new LinkedBlockingQueue<>();
        BlockingQueue<String> buyerEvents = new LinkedBlockingQueue<>();
        BlockingQueue<String> strangerEvents = new LinkedBlockingQueue<>();
        StompSession sellerSession = connectAndSubscribe(seller, sellerEvents);
        StompSession buyerSession = connectAndSubscribe(buyer, buyerEvents);
        StompSession strangerSession = connectAndSubscribe(stranger, strangerEvents);

        // Nowa wiadomość: sprzedawca dostaje ją w swoim widoku, nadawca i obcy nic.
        act(buyer, id, "/messages", "{\"text\":\"Dzień dobry\"}").andExpect(status().isCreated());
        String event = sellerEvents.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat((String) JsonPath.read(event, "$.type")).isEqualTo("message");
        assertThat((String) JsonPath.read(event, "$.chatId")).isEqualTo(id);
        assertThat((String) JsonPath.read(event, "$.message.text")).isEqualTo("Dzień dobry");
        assertThat((Boolean) JsonPath.read(event, "$.message.mine")).isFalse();
        assertThat((String) JsonPath.read(event, "$.message.status")).isEqualTo("Wysłano");
        assertThat(Instant.parse(JsonPath.read(event, "$.message.sentAt"))).isBefore(Instant.now().plusSeconds(5));
        assertThat(buyerEvents.poll(700, TimeUnit.MILLISECONDS)).isNull();
        assertThat(strangerEvents.poll(300, TimeUnit.MILLISECONDS)).isNull();

        // Przeczytanie przez sprzedawcę: nadawca odświeża statusy.
        act(seller, id, "/read", null).andExpect(status().isNoContent());
        String read = buyerEvents.poll(5, TimeUnit.SECONDS);
        assertThat(read).isNotNull();
        assertThat((String) JsonPath.read(read, "$.type")).isEqualTo("read");
        assertThat((String) JsonPath.read(read, "$.chatId")).isEqualTo(id);
        assertThat(sellerEvents.poll(500, TimeUnit.MILLISECONDS)).isNull();

        // „Pisze…” dochodzi do rozmówcy, a od obcego nie dochodzi do nikogo.
        buyerSession.send("/app/chat.typing", "{\"chatId\":\"" + id + "\"}");
        String typing = sellerEvents.poll(5, TimeUnit.SECONDS);
        assertThat(typing).isNotNull();
        assertThat((String) JsonPath.read(typing, "$.type")).isEqualTo("typing");
        assertThat((String) JsonPath.read(typing, "$.chatId")).isEqualTo(id);
        strangerSession.send("/app/chat.typing", "{\"chatId\":\"" + id + "\"}");
        buyerSession.send("/app/chat.typing", "{\"chatId\":\"" + UUID.randomUUID() + "\"}");
        buyerSession.send("/app/chat.typing", "to nie jest json");
        assertThat(sellerEvents.poll(700, TimeUnit.MILLISECONDS)).isNull();
        assertThat(buyerEvents.poll(100, TimeUnit.MILLISECONDS)).isNull();
        assertThat(strangerEvents.poll(100, TimeUnit.MILLISECONDS)).isNull();
        // Błędne zgłoszenia nie zrywają sesji.
        assertThat(buyerSession.isConnected()).isTrue();

        // Propozycja ceny i załącznik też dochodzą na żywo, z danymi w widoku odbiorcy.
        act(buyer, id, "/price-offers", "{\"price\":{\"amount\":30000,\"currency\":\"PLN\"}}").andExpect(status().isCreated());
        String price = sellerEvents.poll(5, TimeUnit.SECONDS);
        assertThat(price).isNotNull();
        assertThat((Integer) JsonPath.read(price, "$.message.priceOffer.price.amount")).isEqualTo(30000);
        assertThat((String) JsonPath.read(price, "$.message.priceOffer.status")).isEqualTo("PENDING");

        UUID photo = UUID.randomUUID();
        given(media.file(photo)).willReturn(Optional.of(new MediaClient.File(photo, buyer, "CHAT_ATTACHMENT", "image/png")));
        given(media.url(photo)).willReturn(Optional.of("http://s3.local/chat/zdjecie.png?X-Amz-Signature=abc"));
        act(buyer, id, "/messages", "{\"text\":\"Załącznik: zdjecie.png\",\"attachmentMediaId\":\"" + photo + "\"}").andExpect(status().isCreated());
        String attachment = sellerEvents.poll(5, TimeUnit.SECONDS);
        assertThat(attachment).isNotNull();
        assertThat((String) JsonPath.read(attachment, "$.message.attachmentUrl")).contains("X-Amz-Signature");
        assertThat((String) JsonPath.read(attachment, "$.message.attachmentContentType")).isEqualTo("image/png");

        // Wiadomość sprzedawcy trafia do kupującego (druga strona w drugą stronę).
        act(seller, id, "/messages", "{\"text\":\"Odpowiedź\"}").andExpect(status().isCreated());
        String reply = buyerEvents.poll(5, TimeUnit.SECONDS);
        assertThat(reply).isNotNull();
        assertThat((String) JsonPath.read(reply, "$.message.text")).isEqualTo("Odpowiedź");
        assertThat((Boolean) JsonPath.read(reply, "$.message.mine")).isFalse();

        // Zablokowana rozmowa: „pisze…” nie dochodzi, wiadomości nie da się wysłać.
        act(seller, id, "/block", null).andExpect(status().isNoContent());
        buyerSession.send("/app/chat.typing", "{\"chatId\":\"" + id + "\"}");
        act(buyer, id, "/messages", "{\"text\":\"Halo?\"}").andExpect(status().isForbidden());
        assertThat(sellerEvents.poll(700, TimeUnit.MILLISECONDS)).isNull();
        assertThat(sellerSession.isConnected()).isTrue();
    }

    @Test
    void restWorksWhenNobodyIsConnected() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        String id = chat(buyer, offer(seller));
        act(buyer, id, "/messages", "{\"text\":\"Nikt nie słucha\"}").andExpect(status().isCreated());
        act(seller, id, "/read", null).andExpect(status().isNoContent());
    }

    /** Sesja po udanym CONNECT albo {@code null}, gdy serwer odrzucił ramkę lub połączenie nie powstało. */
    private StompSession connect(String authorization) throws Exception {
        StompHeaders headers = new StompHeaders();
        if (authorization != null) {
            headers.add("Authorization", authorization);
        }
        CompletableFuture<StompSession> connected = new CompletableFuture<>();
        client.connectAsync("ws://localhost:" + port + "/ws/chat", new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                connected.complete(session);
            }

            @Override
            public void handleFrame(StompHeaders frame, Object payload) {
                connected.complete(null);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                connected.complete(null);
            }
        });
        try {
            StompSession session = connected.get(5, TimeUnit.SECONDS);
            if (session != null) {
                sessions.add(session);
            }
            return session;
        } catch (Exception e) {
            return null;
        }
    }

    private StompSession connectAndSubscribe(UUID user, BlockingQueue<String> events) throws Exception {
        StompSession session = connect(bearer(user));
        assertThat(session).isNotNull();
        session.subscribe("/user/queue/chat", frameHandler(events));
        // Subskrypcja rejestruje się w brokerze asynchronicznie, a serwer nie potwierdza jej ramką RECEIPT, więc dajemy jej chwilę.
        Thread.sleep(400);
        return session;
    }

    private static StompFrameHandler frameHandler(BlockingQueue<String> events) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                events.add((String) payload);
            }
        };
    }

    private static void awaitClosed(StompSession session) throws InterruptedException {
        for (int i = 0; i < 50 && session.isConnected(); i++) {
            Thread.sleep(100);
        }
        assertThat(session.isConnected()).isFalse();
    }

    private UUID offer(UUID owner) {
        UUID id = UUID.randomUUID();
        given(catalog.offer(id)).willReturn(Optional.of(new OfferInfo(id, true, "Słuchawki", UUID.randomUUID(), owner, new CatalogClient.Money(50_000, "PLN"))));
        given(catalog.card(id)).willReturn(Optional.of(Map.of("id", id.toString(), "title", "Słuchawki",
                "channel", Map.of("id", UUID.randomUUID().toString(), "name", "Elektro-Market", "verified", true))));
        return id;
    }

    private String chat(UUID buyer, UUID offer) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/v1/chats").header(HttpHeaders.AUTHORIZATION, bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":\"" + offer + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions act(UUID user, String chatId, String path, String body) throws Exception {
        var request = post("/api/v1/chats/" + chatId + path).header(HttpHeaders.AUTHORIZATION, bearer(user));
        return mvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String bearer(UUID user) {
        return "Bearer user-" + user;
    }

    /** Surowy tekst w obie strony: serwer wysyła JSON, a klient testowy ma go czytać bez konwersji (jak frontend: bez typu zawartości). */
    static final class RawConverter implements MessageConverter {

        @Override
        public Object fromMessage(Message<?> message, Class<?> targetClass) {
            Object payload = message.getPayload();
            if (payload instanceof byte[] bytes && targetClass == String.class) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return payload;
        }

        @Override
        public Message<?> toMessage(Object payload, MessageHeaders headers) {
            byte[] bytes = payload instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : (byte[]) payload;
            return MessageBuilder.createMessage(bytes, headers == null ? new MessageHeaders(Map.of()) : headers);
        }
    }
}
