# rynek-chat-service

`chat-service` — wiadomości

> Status: **szkielet**. Wszystkie endpointy z tabeli istnieją w kontrolerach (`src/main/java/.../api/`) i zwracają `501 Not Implemented` (RFC 7807). Ciała żądań to puste rekordy w `api/dto/`.

## Uruchomienie

```bash
./mvnw spring-boot:run   # port 8089, zmienna PORT
./mvnw verify            # testy
```

- Swagger UI: http://localhost:8089/swagger-ui.html
- Zdrowie: http://localhost:8089/actuator/health

## Zakres i endpointy

_Skopiowane z `rynek-docs/architektura/rynek-endpointy-mikroserwisy.md`. Od teraz to repozytorium jest właścicielem tej specyfikacji._

Ekrany: 7 Czat. Wiadomości w czasie rzeczywistym przez WebSocket/STOMP, historia przez REST.

| Metoda | Ścieżka | Opis | Dostęp |
|---|---|---|---|
| GET | `/api/v1/chats?role=buying\|selling&cursor=` | Lista rozmów (Wszystkie / Kupuję / Sprzedaję) | user |
| POST | `/api/v1/chats` | Rozpoczęcie rozmowy z kontekstem oferty (`offerId`) | user |
| GET | `/api/v1/chats/{chatId}` | Rozmowa wraz z przypiętą ofertą | user |
| GET | `/api/v1/chats/{chatId}/messages?cursor=` | Historia wiadomości | user |
| POST | `/api/v1/chats/{chatId}/messages` | Wysłanie wiadomości (tekst, załącznik `mediaId`) | user |
| POST | `/api/v1/chats/{chatId}/read` | Oznaczenie jako przeczytane | user |
| POST | `/api/v1/chats/{chatId}/price-offers` | „Zaproponuj cenę” | user |
| POST | `/api/v1/chats/{chatId}/price-offers/{priceOfferId}/accept` | Akceptacja propozycji ceny | seller |
| POST | `/api/v1/chats/{chatId}/price-offers/{priceOfferId}/decline` | Odrzucenie | seller |
| POST | `/api/v1/chats/{chatId}/block` | Zablokowanie rozmówcy | user |
| GET | `/api/v1/chats/quick-replies?context=` | Szybkie odpowiedzi („Czy nadal aktualne?”…) | user |
| GET | `/api/v1/chats/unread-count` | Licznik nieprzeczytanych (ikona w pasku nawigacji) | user |
| WS | `/ws/chat` | Kanał na żywo: nowa wiadomość, przeczytanie, pisze… | user |

## Zdarzenia Kafka

Wiersze z §17, w których występuje `chat` (publikuje lub słucha).

| Zdarzenie | Publikuje | Słuchają |
|---|---|---|
| `chat.message-sent` | chat | notification |

## Dane i zależności

- **Magazyn danych:** PostgreSQL; WebSocket/STOMP
- **Zależności:** catalog-service (przypięta oferta), media-service (załączniki)
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 7. `chat-service`, `engagement-service`, `notification-service`.
