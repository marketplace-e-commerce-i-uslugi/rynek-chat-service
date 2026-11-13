package pl.rynek.chat.clients;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Pliki z media-service: sprawdzenie załącznika przy wysyłce i podpisany adres do jego obejrzenia przez uczestnika rozmowy. */
@Component
public class MediaClient {

    private static final Logger log = LoggerFactory.getLogger(MediaClient.class);

    private final RestClient http;

    public MediaClient(@Value("${rynek.media.url}") String mediaUrl) {
        this.http = Clients.http(mediaUrl);
    }

    /** Gotowy plik albo pusty, gdy go nie ma (niepotwierdzony, usunięty, zły identyfikator). Gdy media-service nie odpowiada: 503. */
    public Optional<File> file(UUID mediaId) {
        try {
            return Optional.ofNullable(http.get().uri("/internal/media/{id}", mediaId).retrieve().body(File.class));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest e) {
            return Optional.empty();
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw Clients.unavailable("Serwis plików");
        }
    }

    /**
     * Adres do obejrzenia pliku (podpisany, ważny 10 minut). Pusty, gdy media-service nie odpowiada albo pliku już nie ma:
     * brak podglądu jednego załącznika nie psuje historii rozmowy.
     */
    public Optional<String> url(UUID mediaId) {
        try {
            Url url = http.get().uri("/internal/media/{id}/url", mediaId).retrieve().body(Url.class);
            return Optional.ofNullable(url).map(Url::url).filter(u -> !u.isBlank());
        } catch (RestClientResponseException | ResourceAccessException e) {
            log.warn("Brak adresu załącznika {}: {}", mediaId, e.getMessage());
            return Optional.empty();
        }
    }

    /** {@code purpose} to nazwa stałej, np. {@code CHAT_ATTACHMENT}. */
    public record File(UUID id, UUID ownerId, String purpose, String contentType) {
    }

    record Url(String url) {
    }
}
