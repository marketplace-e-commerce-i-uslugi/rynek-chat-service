-- Załącznik wiadomości: plik CHAT_ATTACHMENT z media-service (prywatny; adres podpisuje media-service na żądanie chatu).
-- Typ zapisujemy przy wysyłce, żeby lista wiadomości nie pytała media-service o niego osobno.
ALTER TABLE messages
    ADD COLUMN attachment_media_id uuid,
    ADD COLUMN attachment_content_type varchar(50);
