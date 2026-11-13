# rynek-chat-service

`chat-service` — wiadomości

> Status: **rozmowy działają** (PostgreSQL + Flyway, JWT z JWKS identity): rozmowa o ofercie, historia i wysyłka wiadomości z załącznikami, przeczytane i liczniki nieprzeczytanych, „Zaproponuj cenę”, blokada, szybkie odpowiedzi, powiadomienia i kanał na żywo (`WS /ws/chat`: nowa wiadomość, przeczytanie, „pisze…”). Broker kanału jest w pamięci procesu, więc działa poprawnie tylko przy jednej instancji serwisu.

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

## Działanie

- **Rozmowa:** zakłada ją kupujący w kontekście oferty (`POST /chats {offerId}`, 201). Jedna rozmowa na parę oferta–kupujący: ponowne wywołanie zwraca istniejącą. Własnej oferty nie da się zapytać (409), nieopublikowana lub nieistniejąca daje 404, a zablokowany sprzedawca 403. Rozmowę widzą tylko jej uczestnicy (dla pozostałych 404). Sprzedawca widzi ją na liście dopiero po pierwszej wiadomości kupującego.
- **Rozmówca:** nazwa to migawka z założenia rozmowy: dla kupującego nazwa kanału sprzedawcy (z `verified` z karty oferty), dla sprzedawcy imię kupującego z identity. `counterpart.initials` to pierwsze litery dwóch pierwszych słów. Karta oferty w `GET /chats/{id}` pochodzi na żywo z catalog (pola `OfferCard`); gdy oferty już nie ma, `offer` jest pusty, a rozmowa działa dalej.
- **Historia:** pierwsza strona to najnowsze wiadomości (domyślnie 50), podane od najstarszej do najnowszej, a `nextCursor` prowadzi do starszych. Wiadomość ma `status`: „Przeczytano”, gdy druga strona przeczytała wątek po jej wysłaniu (dla cudzych wiadomości: gdy Ty przeczytałeś), inaczej „Wysłano”.
- **Nieprzeczytane:** liczone z czasu ostatniego przeczytania przez daną stronę (`POST /read` przesuwa go do przodu). Własne wiadomości nigdy nie zwiększają własnego licznika. `GET /chats/unread-count` zwraca `{count}` (suma po rozmowach), a `unread` na liście i w rozmowie liczbę dla wątku.
- **Załączniki:** `POST /messages {text, attachmentMediaId}`. Plik wgrywa wysyłający do media-service (`purpose: CHAT_ATTACHMENT`, trzy kroki, JPG/PNG/WebP/PDF do 10 MB) i podaje jego `mediaId`. Chat sprawdza w media-service, że to gotowy, jego własny plik tego przeznaczenia (inaczej 422 „Nie znaleźliśmy tego załącznika. Wyślij plik jeszcze raz.”), a po sprawdzeniu, że rozmowa jest jego i nie jest zablokowana, zapisuje `mediaId` i typ pliku. Tekst wiadomości jest wymagany (frontend wysyła „Załącznik: nazwa”). W historii i odpowiedzi wiadomość ma `attachmentUrl` (podpisany adres, który obie strony rozmowy dostają od media-service przez `GET /internal/media/{id}/url`, ważny 10 minut, więc odświeżenie wątku wystawia nowy) i `attachmentContentType`. Gdy media-service nie odpowiada, wiadomość jest w historii bez podglądu, a wysyłka daje 503. Plik nie jest usuwany z media-service po usunięciu rozmowy (rozmów się nie usuwa), a wysłanego załącznika nie da się zmienić.
- **Propozycja ceny:** tylko kupujący (403 dla sprzedawcy), cena w PLN od 1 gr do 1 000 000 zł (400 inaczej), jedna oczekująca naraz na rozmowę (409). Trafia do wątku jako wiadomość z `priceOffer {id, price, status}`. Odpowiada sprzedawca (`accept`, `decline`, 403 dla kupującego); rozstrzygnięta propozycja nie wraca do stanu oczekującej (409).
- **Blokada:** `POST /block` blokuje rozmówcę we wszystkich rozmowach tej pary, w obie strony: wysyłanie wiadomości i propozycji daje 403 „Rozmowa jest zablokowana.”, nowa rozmowa z blokującym sprzedawcą 403. Historia i oznaczanie jako przeczytane działają dalej. Odblokowania nie ma w specyfikacji.
- **Kanał na żywo (`WS /ws/chat`, STOMP przez zwykły WebSocket):** przeglądarka nie może dodać nagłówka `Authorization` do uzgadniania WebSocketu (ścieżka jest więc publiczna w gateway i w serwisie), dlatego token JWT idzie w ramce `CONNECT` (`Authorization: Bearer …`). Zły albo brakujący token kończy się ramką `ERROR` i zamknięciem połączenia. Po połączeniu wolno tylko subskrybować `/user/queue/chat` i wysyłać na `/app/chat.typing`, każdy inny adres kończy sesję błędem. Zdarzenia (`{type, chatId, message?}`) dostaje wyłącznie druga strona rozmowy, nigdy nadawca ani osoba spoza rozmowy: `message` (nowa wiadomość, także propozycja ceny i załącznik, w widoku odbiorcy z `mine: false`), `read` (rozmówca przeczytał wątek: nadawca odświeża statusy „Przeczytano”) i `typing`. „Pisze…” klient zgłasza na `/app/chat.typing` z ciałem `{"chatId": "…"}`; zgłoszenia z cudzej, nieistniejącej lub zablokowanej rozmowy są po cichu pomijane. Publikacja zdarzenia nigdy nie psuje żądania REST: gdy rozmówca nie jest połączony, zdarzenie przepada, a stan zostaje w bazie. Zmiana statusu propozycji ceny (przyjęcie, odrzucenie) nie ma zdarzenia: druga strona dostaje powiadomienie, a status widzi po odświeżeniu wątku. Źródła uzgadniania ustawia `CHAT_ALLOWED_ORIGINS` (domyślnie `localhost`). Połączenie sprawdza token tylko przy `CONNECT`, więc trwa po wygaśnięciu tokenu aż do rozłączenia klienta.
- **Szybkie odpowiedzi:** stałe listy dla `context=offer` (domyślnie, z „Zaproponuj cenę”, które we frontendzie otwiera okno propozycji) i `service`.
- **Powiadomienia** (notification-service, kategoria `messages`, best effort po zatwierdzeniu transakcji): nowa wiadomość (jedno powiadomienie na serię nieprzeczytanych, klucz zdarzenia zawiera czas ostatniego przeczytania adresata), propozycja ceny dla sprzedawcy, przyjęcie i odrzucenie dla kupującego. Link: `/wiadomosci/{chatId}`.
## Zdarzenia Kafka

Wiersze z §17, w których występuje `chat` (publikuje lub słucha).

| Zdarzenie | Publikuje | Słuchają |
|---|---|---|
| `chat.message-sent` | chat | notification |

## Dane i zależności

- **Magazyn danych:** PostgreSQL (baza `chats`, tabele `chats`, `messages` (z `attachment_media_id` i `attachment_content_type`), `price_offers`, `blocks`); WebSocket/STOMP (broker w pamięci)
- **Zależności:** catalog-service (`/internal/offers/{id}/price-and-stock` i publiczna karta oferty), identity-service (imię kupującego, klucze JWKS), notification-service (`POST /internal/notifications`), media-service (`GET /internal/media/{id}` do sprawdzenia załącznika, `GET /internal/media/{id}/url` do podpisanego adresu)
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 7. `chat-service`, `engagement-service`, `notification-service`.
