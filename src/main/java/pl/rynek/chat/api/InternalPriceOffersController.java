package pl.rynek.chat.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.chat.api.dto.ChatDtos;
import pl.rynek.chat.chats.ChatService;
import pl.rynek.chat.chats.PriceOffer;

/** Wykorzystanie zaakceptowanej ceny przez order-service. Tylko komunikacja między serwisami. */
@RestController
class InternalPriceOffersController {

    private final ChatService chats;

    InternalPriceOffersController(ChatService chats) {
        this.chats = chats;
    }

    /**
     * Zużywa zaakceptowaną propozycję ceny kupującego dla oferty i zwraca cenę. 404: cudza albo nieznana propozycja,
     * 409: nierozpatrzona, odrzucona, wykorzystana albo wygasła. Dostęp: int.
     */
    @PostMapping("/internal/price-offers/{priceOfferId}/redeem")
    ChatDtos.Money redeem(@PathVariable UUID priceOfferId, @Valid @RequestBody RedeemRequest body) {
        PriceOffer offer = chats.redeem(priceOfferId, body.buyerId(), body.offerId());
        return new ChatDtos.Money(offer.getAmount(), offer.getCurrency());
    }

    /** Oddaje cenę do użycia, gdy zamówienie nie powstało. Dostęp: int. */
    @PostMapping("/internal/price-offers/{priceOfferId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable UUID priceOfferId) {
        chats.release(priceOfferId);
    }

    record RedeemRequest(@NotNull UUID buyerId, @NotNull UUID offerId) {
    }
}
