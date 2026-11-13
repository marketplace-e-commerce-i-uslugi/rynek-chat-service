# rynek-chat-service

`chat-service` — wiadomości

> Status: **rozmowy działają przez REST** (PostgreSQL + Flyway, JWT z JWKS identity): rozmowa o ofercie, historia i wysyłka wiadomości, przeczytane i liczniki nieprzeczytanych, „Zaproponuj cenę”, blokada, szybkie odpowiedzi i powiadomienia. **Nie ma jeszcze** kanału na żywo (`WS /ws/chat`: nowa wiadomość, przeczytanie, „pisze…”) ani załączników (`attachmentMediaId` daje 422). Frontend bez WebSocketu pokazuje status „offline” i odświeża wątek po wysłaniu wiadomości, więc wiadomości rozmówcy widać po ponownym wejściu w rozmowę albo odświeżeniu.

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
| WS | `/ws/chat` | Kanał na żywo: nowa wiadomość, przeczytanie, pisze… (jeszcze niezaimplementowany) | user |

## Działanie

- **Rozmowa:** zakłada ją kupujący w kontekście oferty (`POST /chats {offerId}`, 201). Jedna rozmowa na parę oferta–kupujący: ponowne wywołanie zwraca istniejącą. Własnej oferty nie da się zapytać (409), nieopublikowana lub nieistniejąca daje 404, a zablokowany sprzedawca 403. Rozmowę widzą tylko jej uczestnicy (dla pozostałych 404). Sprzedawca widzi ją na liście dopiero po pierwszej wiadomości kupującego.
- **Rozmówca:** nazwa to migawka z założenia rozmowy: dla kupującego nazwa kanału sprzedawcy (z `verified` z karty oferty), dla sprzedawcy imię kupującego z identity. `counterpart.initials` to pierwsze litery dwóch pierwszych słów. Karta oferty w `GET /chats/{id}` pochodzi na żywo z catalog (pola `OfferCard`); gdy oferty już nie ma, `offer` jest pusty, a rozmowa działa dalej.
- **Historia:** pierwsza strona to najnowsze wiadomości (domyślnie 50), podane od najstarszej do najnowszej, a `nextCursor` prowadzi do starszych. Wiadomość ma `status`: „Przeczytano”, gdy druga strona przeczytała wątek po jej wysłaniu (dla cudzych wiadomości: gdy Ty przeczytałeś), inaczej „Wysłano”.
- **Nieprzeczytane:** liczone z czasu ostatniego przeczytania przez daną stronę (`POST /read` przesuwa go do przodu). Własne wiadomości nigdy nie zwiększają własnego licznika. `GET /chats/unread-count` zwraca `{count}` (suma po rozmowach), a `unread` na liście i w rozmowie liczbę dla wątku.
- **Propozycja ceny:** tylko kupujący (403 dla sprzedawcy), cena w PLN od 1 gr do 1 000 000 zł (400 inaczej), jedna oczekująca naraz na rozmowę (409). Trafia do wątku jako wiadomość z `priceOffer {id, price, status}`. Odpowiada sprzedawca (`accept`, `decline`, 403 dla kupującego); rozstrzygnięta propozycja nie wraca do stanu oczekującej (409).
- **Blokada:** `POST /block` blokuje rozmówcę we wszystkich rozmowach tej pary, w obie strony: wysyłanie wiadomości i propozycji daje 403 „Rozmowa jest zablokowana.”, nowa rozmowa z blokującym sprzedawcą 403. Historia i oznaczanie jako przeczytane działają dalej. Odblokowania nie ma w specyfikacji.
- **Szybkie odpowiedzi:** stałe listy dla `context=offer` (domyślnie, z „Zaproponuj cenę”, które we frontendzie otwiera okno propozycji) i `service`.
- **Powiadomienia** (notification-service, kategoria `messages`, best effort po zatwierdzeniu transakcji): nowa wiadomość (jedno powiadomienie na serię nieprzeczytanych, klucz zdarzenia zawiera czas ostatniego przeczytania adresata), propozycja ceny dla sprzedawcy, przyjęcie i odrzucenie dla kupującego. Link: `/wiadomosci/{chatId}`.
## Zdarzenia Kafka

Wiersze z §17, w których występuje `chat` (publikuje lub słucha).

| Zdarzenie | Publikuje | Słuchają |
|---|---|---|
| `chat.message-sent` | chat | notification |

## Dane i zależności

- **Magazyn danych:** PostgreSQL (baza `chats`, tabele `chats`, `messages`, `price_offers`, `blocks`); docelowo WebSocket/STOMP
- **Zależności:** catalog-service (`/internal/offers/{id}/price-and-stock` i publiczna karta oferty), identity-service (imię kupującego, klucze JWKS), notification-service (`POST /internal/notifications`); docelowo media-service (załączniki: plik `CHAT_ATTACHMENT` jest prywatny, więc uczestnik rozmowy musi dostać do niego dostęp)
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 7. `chat-service`, `engagement-service`, `notification-service`.
