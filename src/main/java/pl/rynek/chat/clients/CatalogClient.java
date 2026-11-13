package pl.rynek.chat.clients;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Oferta z catalog-service: właściciel i dostępność przy zakładaniu rozmowy oraz karta oferty przypięta do rozmowy. */
@Component
public class CatalogClient {

    /** Pola karty oferty ({@code OfferCard} we frontendzie); szczegóły oferty mają ich więcej. */
    private static final Set<String> CARD_FIELDS = Set.of("id", "type", "title", "price", "priceUnit", "condition", "thumbnailUrl", "tone",
            "placeholder", "videoDurationSec", "channel", "rating", "location", "nextSlot");

    private final RestClient http;

    public CatalogClient(@Value("${rynek.catalog.url}") String catalogUrl) {
        this.http = Clients.http(catalogUrl);
    }

    /** Oferta albo pusta, gdy nie istnieje lub została usunięta. Gdy catalog nie odpowiada: 503. */
    public Optional<OfferInfo> offer(UUID offerId) {
        try {
            return Optional.ofNullable(http.get().uri("/internal/offers/{id}/price-and-stock", offerId).retrieve().body(OfferInfo.class));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest e) {
            return Optional.empty();
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw Clients.unavailable("Katalog ofert");
        }
    }

    /**
     * Karta oferty (publiczny {@code GET /offers/{id}}) albo pusta, gdy oferty już nie ma albo catalog nie odpowiada:
     * rozmowa działa dalej bez przypiętej karty.
     */
    public Optional<Map<String, Object>> card(UUID offerId) {
        try {
            Map<String, Object> detail = http.get().uri("/api/v1/offers/{id}", offerId).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            if (detail == null) {
                return Optional.empty();
            }
            Map<String, Object> card = new LinkedHashMap<>();
            detail.forEach((key, value) -> {
                if (CARD_FIELDS.contains(key)) {
                    card.put(key, value);
                }
            });
            return Optional.of(card);
        } catch (RestClientResponseException | ResourceAccessException e) {
            return Optional.empty();
        }
    }

    /** {@code available}: oferta jest opublikowana; {@code ownerId} to właściciel (sprzedawca). */
    public record OfferInfo(UUID offerId, boolean available, String title, UUID channelId, UUID ownerId) {
    }
}
