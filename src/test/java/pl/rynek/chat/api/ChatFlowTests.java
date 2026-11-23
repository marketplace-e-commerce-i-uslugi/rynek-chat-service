package pl.rynek.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import pl.rynek.chat.TestcontainersConfig;
import pl.rynek.chat.clients.CatalogClient;
import pl.rynek.chat.clients.CatalogClient.OfferInfo;
import pl.rynek.chat.clients.IdentityClient;
import pl.rynek.chat.clients.MediaClient;
import pl.rynek.chat.clients.MediaClient.File;
import pl.rynek.chat.clients.NotificationClient;

/** Rozmowy na prawdziwym PostgreSQL; catalog, identity i notification to atrapy. Tokeny testowe: {@code user-<uuid>}. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ChatFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    @BeforeEach
    void stubs() {
        given(jwtDecoder.decode(anyString())).willAnswer(call -> {
            String token = call.getArgument(0);
            if (!token.startsWith("user-")) {
                throw new BadJwtException("Nieważny token");
            }
            return Jwt.withTokenValue(token).header("alg", "RS256").subject(token.substring(5))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        });
        given(identity.displayName(any())).willReturn(Optional.of("Anna Nowak"));
    }

    @Test
    void everythingNeedsLogin() throws Exception {
        mvc.perform(get("/api/v1/chats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/chats/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/chats/quick-replies")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/chats").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void buyerOpensChatOnceAndSellerSeesItAfterFirstMessage() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID offer = offer(seller, true);

        open(buyer, UUID.randomUUID()).andExpect(status().isNotFound());
        open(buyer, offer(seller, false)).andExpect(status().isNotFound());
        open(seller, offer).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Nie możesz napisać do siebie."));
        mvc.perform(post("/api/v1/chats").header(HttpHeaders.AUTHORIZATION, bearer(buyer)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        String body = open(buyer, offer).andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("buying"))
                .andExpect(jsonPath("$.counterpart.name").value("Elektro-Market"))
                .andExpect(jsonPath("$.counterpart.initials").value("E"))
                .andExpect(jsonPath("$.counterpart.verified").value(true))
                .andExpect(jsonPath("$.unread").value(0))
                .andExpect(jsonPath("$.blocked").value(false))
                .andExpect(jsonPath("$.lastMessage").value(""))
                .andExpect(jsonPath("$.lastMessageAt").exists())
                .andExpect(jsonPath("$.offer.title").value("Słuchawki"))
                .andExpect(jsonPath("$.offer.description").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        // Ponowne „Napisz” o tej samej ofercie to ta sama rozmowa.
        open(buyer, offer).andExpect(jsonPath("$.id").value(id));

        // Kupujący widzi pustą rozmowę, sprzedawca jeszcze nie.
        list(buyer, "").andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].id").value(id));
        list(seller, "").andExpect(jsonPath("$.items", hasSize(0)));
        list(seller, "?role=buying").andExpect(jsonPath("$.items", hasSize(0)));
        list(buyer, "?role=selling").andExpect(jsonPath("$.items", hasSize(0)));
        list(buyer, "?role=zly").andExpect(status().isBadRequest());

        send(buyer, id, "Czy oferta jest aktualna?").andExpect(status().isCreated());
        list(seller, "").andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].role").value("selling"))
                .andExpect(jsonPath("$.items[0].counterpart.name").value("Anna Nowak"))
                .andExpect(jsonPath("$.items[0].counterpart.initials").value("AN"))
                .andExpect(jsonPath("$.items[0].counterpart.verified").value(false))
                .andExpect(jsonPath("$.items[0].lastMessage").value("Czy oferta jest aktualna?"))
                .andExpect(jsonPath("$.items[0].unread").value(1));
        list(seller, "?role=selling").andExpect(jsonPath("$.items", hasSize(1)));

        // Obcy nie istnieje dla rozmowy.
        UUID stranger = UUID.randomUUID();
        mvc.perform(get("/api/v1/chats/" + id).header(HttpHeaders.AUTHORIZATION, bearer(stranger))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/chats/" + id + "/messages").header(HttpHeaders.AUTHORIZATION, bearer(stranger))).andExpect(status().isNotFound());
        send(stranger, id, "Cześć").andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/chats/" + id + "/read").header(HttpHeaders.AUTHORIZATION, bearer(stranger))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/chats/" + id + "/block").header(HttpHeaders.AUTHORIZATION, bearer(stranger))).andExpect(status().isNotFound());
        list(stranger, "").andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/v1/chats/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(buyer))).andExpect(status().isNotFound());

        // Karta oferty znika razem z ofertą, rozmowa zostaje.
        given(catalog.card(offer)).willReturn(Optional.empty());
        mvc.perform(get("/api/v1/chats/" + id).header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offer").doesNotExist());
    }

    @Test
    void messagesUnreadCountsAndReadStatus() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        String id = chat(buyer, offer(seller, true));

        send(buyer, id, "  ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Napisz wiadomość."));
        send(buyer, id, "x".repeat(2001)).andExpect(status().isBadRequest());

        send(buyer, id, " Dzień dobry ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Dzień dobry"))
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.status").value("Wysłano"))
                .andExpect(jsonPath("$.attachmentUrl").doesNotExist())
                .andExpect(jsonPath("$.priceOffer").doesNotExist());
        send(buyer, id, "Czy możliwy odbiór osobisty?").andExpect(status().isCreated());
        unreadCount(seller).andExpect(jsonPath("$.count").value(2));
        unreadCount(buyer).andExpect(jsonPath("$.count").value(0));

        // Sprzedawca czyta: licznik na zero, wiadomości kupującego mają status „Przeczytano”.
        messages(seller, id, "").andExpect(jsonPath("$.items[0].mine").value(false)).andExpect(jsonPath("$.items[0].status").value("Wysłano"));
        mvc.perform(post("/api/v1/chats/" + id + "/read").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/chats/" + id + "/read").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isNoContent());
        unreadCount(seller).andExpect(jsonPath("$.count").value(0));
        messages(buyer, id, "").andExpect(jsonPath("$.items[*].status", contains("Przeczytano", "Przeczytano")));

        // Odpowiedź sprzedawcy: kupujący ma 1 nieprzeczytaną, a własne wiadomości sprzedawcy są „Wysłano”.
        send(seller, id, "Tak, jest aktualna.").andExpect(status().isCreated());
        unreadCount(buyer).andExpect(jsonPath("$.count").value(1));
        list(buyer, "").andExpect(jsonPath("$.items[0].unread").value(1)).andExpect(jsonPath("$.items[0].lastMessage").value("Tak, jest aktualna."));
        messages(seller, id, "").andExpect(jsonPath("$.items[2].status").value("Wysłano"));
        mvc.perform(post("/api/v1/chats/" + id + "/read").header(HttpHeaders.AUTHORIZATION, bearer(buyer))).andExpect(status().isNoContent());
        unreadCount(buyer).andExpect(jsonPath("$.count").value(0));
        messages(seller, id, "").andExpect(jsonPath("$.items[2].status").value("Przeczytano"));
        // Własna wiadomość nie zwiększa własnego licznika.
        unreadCount(seller).andExpect(jsonPath("$.count").value(0));
    }

    /** Załącznik to własny, gotowy plik CHAT_ATTACHMENT z media-service; obie strony oglądają go pod podpisanym adresem. */
    @Test
    void attachmentIsSharedWithBothParticipants() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        String id = chat(buyer, offer(seller, true));
        UUID photo = file(buyer, "CHAT_ATTACHMENT", "image/png");
        given(media.url(photo)).willReturn(Optional.of("http://s3.local/chat/photo.png?X-Amz-Signature=abc"));

        sendWithAttachment(buyer, id, "Załącznik: zdjecie.png", photo).andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Załącznik: zdjecie.png"))
                .andExpect(jsonPath("$.attachmentUrl").value("http://s3.local/chat/photo.png?X-Amz-Signature=abc"))
                .andExpect(jsonPath("$.attachmentContentType").value("image/png"));
        // Obie strony widzą ten sam załącznik w historii (adres podpisuje media-service na żądanie chatu).
        for (UUID user : List.of(buyer, seller)) {
            messages(user, id, "").andExpect(jsonPath("$.items[0].attachmentUrl").value("http://s3.local/chat/photo.png?X-Amz-Signature=abc"))
                    .andExpect(jsonPath("$.items[0].attachmentContentType").value("image/png"));
        }
        list(seller, "").andExpect(jsonPath("$.items[0].lastMessage").value("Załącznik: zdjecie.png"));
        verify(notifications).send(eq(seller), eq("messages"), eq("Nowa wiadomość"), eq("Anna Nowak: Załącznik: zdjecie.png"),
                eq("/wiadomosci/" + id), anyString());

        // Brak adresu (media-service nie odpowiada) nie psuje historii: wiadomość jest, bez podglądu.
        given(media.url(photo)).willReturn(Optional.empty());
        messages(seller, id, "").andExpect(status().isOk()).andExpect(jsonPath("$.items[0].text").value("Załącznik: zdjecie.png"))
                .andExpect(jsonPath("$.items[0].attachmentUrl").doesNotExist());
        // Wiadomość bez załącznika nie ma pól załącznika.
        send(seller, id, "Dziękuję").andExpect(status().isCreated()).andExpect(jsonPath("$.attachmentUrl").doesNotExist())
                .andExpect(jsonPath("$.attachmentContentType").doesNotExist());

        String detail = "Nie znaleźliśmy tego załącznika. Wyślij plik jeszcze raz.";
        // Cudzy plik, zły rodzaj pliku i nieistniejący plik dają 422.
        sendWithAttachment(buyer, id, "Cudzy", file(seller, "CHAT_ATTACHMENT", "image/png")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(detail));
        sendWithAttachment(buyer, id, "Dokument", file(buyer, "KYC_DOCUMENT", "application/pdf")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(detail));
        sendWithAttachment(buyer, id, "Zdjęcie oferty", file(buyer, "OFFER_PHOTO", "image/png")).andExpect(status().isUnprocessableContent());
        given(media.file(any())).willReturn(Optional.empty());
        sendWithAttachment(buyer, id, "Brak", UUID.randomUUID()).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(detail));
        // Obcy w rozmowie nie dowie się nawet, czy plik istnieje: 404 jak dla cudzej rozmowy.
        sendWithAttachment(UUID.randomUUID(), id, "Obcy", photo).andExpect(status().isNotFound());
        // Zablokowana rozmowa nie przyjmuje załączników.
        mvc.perform(post("/api/v1/chats/" + id + "/block").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isNoContent());
        UUID blockedPhoto = file(buyer, "CHAT_ATTACHMENT", "image/png");
        sendWithAttachment(buyer, id, "Zdjęcie", blockedPhoto).andExpect(status().isForbidden());
    }

    @Test
    void historyIsPagedFromNewestInReadingOrder() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        String id = chat(buyer, offer(seller, true));
        for (int i = 1; i <= 3; i++) {
            send(i % 2 == 1 ? buyer : seller, id, "Wiadomość " + i).andExpect(status().isCreated());
        }
        String first = messages(buyer, id, "?limit=2").andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].text", contains("Wiadomość 2", "Wiadomość 3")))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        messages(buyer, id, "?limit=2&cursor=" + cursor).andExpect(jsonPath("$.items[*].text", contains("Wiadomość 1")))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        messages(buyer, id, "").andExpect(jsonPath("$.items[*].text", contains("Wiadomość 1", "Wiadomość 2", "Wiadomość 3")));
        messages(buyer, id, "?cursor=zly").andExpect(status().isBadRequest());
    }

    @Test
    void recipientIsNotifiedOncePerUnreadStreak() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        String id = chat(buyer, offer(seller, true));
        send(buyer, id, "Pierwsza").andExpect(status().isCreated());
        send(buyer, id, "Druga").andExpect(status().isCreated());
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(2)).send(eq(seller), eq("messages"), eq("Nowa wiadomość"), anyString(), eq("/wiadomosci/" + id), keys.capture());
        verify(notifications).send(eq(seller), eq("messages"), eq("Nowa wiadomość"), eq("Anna Nowak: Pierwsza"), eq("/wiadomosci/" + id), anyString());
        // Bez przeczytania obie wiadomości mają ten sam klucz, więc notification-service zapisze jedno powiadomienie.
        assertThat(keys.getAllValues()).hasSize(2).containsOnly(keys.getAllValues().get(0));
        assertThat(keys.getAllValues().get(0)).startsWith("chat-" + id + "-" + seller + "-");

        mvc.perform(post("/api/v1/chats/" + id + "/read").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isNoContent());
        send(buyer, id, "Trzecia").andExpect(status().isCreated());
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(3)).send(eq(seller), eq("messages"), eq("Nowa wiadomość"), anyString(), eq("/wiadomosci/" + id), after.capture());
        assertThat(after.getAllValues().get(2)).isNotEqualTo(keys.getAllValues().get(0));
        // Autor nie dostaje powiadomienia o własnej wiadomości.
        verify(notifications, never()).send(eq(buyer), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void priceOfferIsProposedByBuyerAndDecidedBySellerOnce() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        String id = chat(buyer, offer(seller, true));

        priceOffer(seller, id, 35000, "PLN").andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Cenę proponuje kupujący."));
        priceOffer(buyer, id, 0, "PLN").andExpect(status().isBadRequest());
        priceOffer(buyer, id, 100_000_001L, "PLN").andExpect(status().isBadRequest());
        priceOffer(buyer, id, 35000, "EUR").andExpect(status().isBadRequest());
        priceOffer(buyer, id, 50_001, "PLN").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Proponowana cena nie może być wyższa od ceny oferty."));
        mvc.perform(post("/api/v1/chats/" + id + "/price-offers").header(HttpHeaders.AUTHORIZATION, bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());

        String first = JsonPath.read(priceOffer(buyer, id, 35000, "PLN").andExpect(status().isCreated())
                .andExpect(jsonPath("$.price.amount").value(35000)).andExpect(jsonPath("$.price.currency").value("PLN"))
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn().getResponse().getContentAsString(), "$.id");
        verify(notifications).send(eq(seller), eq("messages"), eq("Propozycja ceny"), startsWith("Anna Nowak proponuje 350,00"),
                eq("/wiadomosci/" + id), eq("price-offer-" + first + "-proposed"));
        // Jedna oczekująca naraz, a propozycja jest wiadomością w wątku.
        priceOffer(buyer, id, 34000, "PLN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Poprzednia propozycja czeka jeszcze na odpowiedź sprzedawcy."));
        messages(seller, id, "").andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].priceOffer.id").value(first))
                .andExpect(jsonPath("$.items[0].priceOffer.status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].text").value(org.hamcrest.Matchers.startsWith("Propozycja ceny: 350,00")));
        unreadCount(seller).andExpect(jsonPath("$.count").value(1));

        decide(buyer, id, first, "accept").andExpect(status().isForbidden());
        decide(seller, id, UUID.randomUUID().toString(), "accept").andExpect(status().isNotFound());
        decide(UUID.randomUUID(), id, first, "accept").andExpect(status().isNotFound());
        decide(seller, id, first, "accept").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        verify(notifications).send(eq(buyer), eq("messages"), eq("Propozycja ceny przyjęta"), startsWith("Sprzedawca przyjął 350,00"),
                eq("/wiadomosci/" + id), eq("price-offer-" + first + "-accepted"));
        decide(seller, id, first, "accept").andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Ta propozycja została już rozpatrzona."));
        decide(seller, id, first, "decline").andExpect(status().isConflict());
        messages(buyer, id, "").andExpect(jsonPath("$.items[0].priceOffer.status").value("ACCEPTED"));

        // Po rozstrzygnięciu można zaproponować nową, a sprzedawca może ją odrzucić.
        String second = JsonPath.read(priceOffer(buyer, id, 33000, "PLN").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        decide(seller, id, second, "decline").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        verify(notifications).send(eq(buyer), eq("messages"), eq("Propozycja ceny odrzucona"), startsWith("Sprzedawca odrzucił 330,00"),
                eq("/wiadomosci/" + id), eq("price-offer-" + second + "-declined"));
        messages(buyer, id, "").andExpect(jsonPath("$.items[*].priceOffer.status", contains("ACCEPTED", "DECLINED")));
    }

    @Test
    void acceptedPriceIsRedeemedOnceByTheBuyerForTheOfferAndExpires() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID offerId = offer(seller, true);
        String id = chat(buyer, offerId);
        String po = JsonPath.read(priceOffer(buyer, id, 35000, "PLN").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        redeem(po, buyer, offerId).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Sprzedawca nie odpowiedział jeszcze na propozycję ceny."));
        decide(seller, id, po, "accept").andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty()).andExpect(jsonPath("$.used").value(false));
        redeem(po, UUID.randomUUID(), offerId).andExpect(status().isNotFound());
        redeem(po, buyer, UUID.randomUUID()).andExpect(status().isNotFound());
        redeem(UUID.randomUUID().toString(), buyer, offerId).andExpect(status().isNotFound());

        redeem(po, buyer, offerId).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(35000));
        redeem(po, buyer, offerId).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Ta cena została już wykorzystana w zamówieniu."));
        messages(buyer, id, "").andExpect(jsonPath("$.items[0].priceOffer.used").value(true));

        mvc.perform(post("/internal/price-offers/" + po + "/release")).andExpect(status().isNoContent());
        // Po 24 godzinach od akceptacji cena nie obowiązuje.
        jdbc.update("update price_offers set decided_at = now() - interval '25 hours' where id = ?::uuid", po);
        redeem(po, buyer, offerId).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Ta cena już nie obowiązuje. Zaproponuj nową."));

        String declined = JsonPath.read(priceOffer(buyer, id, 33000, "PLN").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        decide(seller, id, declined, "decline").andExpect(status().isOk());
        redeem(declined, buyer, offerId).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Sprzedawca odrzucił tę propozycję ceny."));
    }

    private ResultActions redeem(String priceOfferId, UUID buyer, UUID offerId) throws Exception {
        return mvc.perform(post("/internal/price-offers/" + priceOfferId + "/redeem").contentType(MediaType.APPLICATION_JSON)
                .content("{\"buyerId\":\"" + buyer + "\",\"offerId\":\"" + offerId + "\"}"));
    }

    @Test
    void blockingStopsTheWholePairAndKeepsHistoryReadable() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        String id = chat(buyer, offer(seller, true));
        send(buyer, id, "Dzień dobry").andExpect(status().isCreated());

        mvc.perform(post("/api/v1/chats/" + id + "/block").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/chats/" + id + "/block").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isNoContent());
        for (UUID user : List.of(seller, buyer)) {
            mvc.perform(get("/api/v1/chats/" + id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(true));
        }
        send(buyer, id, "Halo?").andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Rozmowa jest zablokowana."));
        send(seller, id, "Nie").andExpect(status().isForbidden());
        priceOffer(buyer, id, 30000, "PLN").andExpect(status().isForbidden());
        // Historia i przeczytanie działają dalej.
        messages(buyer, id, "").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(post("/api/v1/chats/" + id + "/read").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isNoContent());
        // Blokada obejmuje parę: nowa rozmowa kupującego z tym sprzedawcą też jest niemożliwa.
        open(buyer, offer(seller, true)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Nie możesz rozpocząć rozmowy z tym sprzedawcą."));
        // Inny kupujący rozmawia ze sprzedawcą normalnie.
        UUID other = UUID.randomUUID();
        open(other, offer(seller, true)).andExpect(status().isCreated()).andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void quickRepliesDependOnContext() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(get("/api/v1/chats/quick-replies").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasItem("Zaproponuj cenę"))).andExpect(jsonPath("$", hasItem("Czy oferta jest nadal aktualna?")));
        mvc.perform(get("/api/v1/chats/quick-replies?context=offer").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$", hasItem("Zaproponuj cenę")));
        mvc.perform(get("/api/v1/chats/quick-replies?context=service").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$", hasItem("Czy termin jest nadal wolny?"))).andExpect(jsonPath("$", not(hasItem("Zaproponuj cenę"))));
        unreadCount(user).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
    }

    private UUID offer(UUID owner, boolean available) {
        UUID id = UUID.randomUUID();
        given(catalog.offer(id)).willReturn(Optional.of(new OfferInfo(id, available, "Słuchawki", UUID.randomUUID(), owner, new CatalogClient.Money(50_000, "PLN"))));
        given(catalog.card(id)).willReturn(Optional.of(Map.of("id", id.toString(), "title", "Słuchawki",
                "channel", Map.of("id", UUID.randomUUID().toString(), "handle", "elektro", "name", "Elektro-Market", "verified", true))));
        return id;
    }

    private String chat(UUID buyer, UUID offer) throws Exception {
        return JsonPath.read(open(buyer, offer).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions open(UUID user, UUID offer) throws Exception {
        return mvc.perform(post("/api/v1/chats").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":\"" + offer + "\"}"));
    }

    private ResultActions list(UUID user, String query) throws Exception {
        return mvc.perform(get("/api/v1/chats" + query).header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private ResultActions messages(UUID user, String chatId, String query) throws Exception {
        return mvc.perform(get("/api/v1/chats/" + chatId + "/messages" + query).header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private ResultActions send(UUID user, String chatId, String text) throws Exception {
        return mvc.perform(post("/api/v1/chats/" + chatId + "/messages").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}"));
    }

    /** Plik w atrapie media-service: {@code owner} wgrał go z podanym przeznaczeniem i typem. */
    private UUID file(UUID owner, String purpose, String contentType) {
        UUID id = UUID.randomUUID();
        given(media.file(id)).willReturn(Optional.of(new File(id, owner, purpose, contentType)));
        return id;
    }

    private ResultActions sendWithAttachment(UUID user, String chatId, String text, UUID mediaId) throws Exception {
        return mvc.perform(post("/api/v1/chats/" + chatId + "/messages").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\",\"attachmentMediaId\":\"" + mediaId + "\"}"));
    }

    private ResultActions priceOffer(UUID user, String chatId, long amount, String currency) throws Exception {
        return mvc.perform(post("/api/v1/chats/" + chatId + "/price-offers").header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\":{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}}"));
    }

    private ResultActions decide(UUID user, String chatId, String priceOfferId, String action) throws Exception {
        return mvc.perform(post("/api/v1/chats/" + chatId + "/price-offers/" + priceOfferId + "/" + action)
                .header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private ResultActions unreadCount(UUID user) throws Exception {
        return mvc.perform(get("/api/v1/chats/unread-count").header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private static String bearer(UUID userId) {
        return "Bearer user-" + userId;
    }
}
