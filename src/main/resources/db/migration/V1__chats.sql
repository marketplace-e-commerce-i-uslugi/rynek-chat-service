-- Rozmowa kupującego ze sprzedawcą w kontekście oferty: jedna na parę (oferta, kupujący).
-- Imiona to migawka z chwili założenia rozmowy (sprzedawca: nazwa kanału), żeby lista nie wołała innych serwisów.
-- buyer_read_at / seller_read_at: do kiedy dana strona przeczytała wątek (z nich liczą się nieprzeczytane i status „Przeczytano”).
CREATE TABLE chats (
    id               uuid PRIMARY KEY,
    offer_id         uuid         NOT NULL,
    offer_title      varchar(200) NOT NULL,
    buyer_id         uuid         NOT NULL,
    seller_id        uuid         NOT NULL,
    buyer_name       varchar(100) NOT NULL,
    seller_name      varchar(100) NOT NULL,
    seller_verified  boolean      NOT NULL,
    last_message     varchar(500),
    last_message_at  timestamptz,
    buyer_read_at    timestamptz  NOT NULL,
    seller_read_at   timestamptz  NOT NULL,
    created_at       timestamptz  NOT NULL,
    UNIQUE (offer_id, buyer_id)
);

CREATE INDEX chats_buyer_idx ON chats (buyer_id, last_message_at DESC NULLS LAST);
CREATE INDEX chats_seller_idx ON chats (seller_id, last_message_at DESC NULLS LAST);

-- Propozycja ceny kupującego; odpowiada sprzedawca. Jedna oczekująca naraz na rozmowę.
CREATE TABLE price_offers (
    id         uuid PRIMARY KEY,
    chat_id    uuid        NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    amount     bigint      NOT NULL CHECK (amount > 0),
    currency   varchar(3)  NOT NULL,
    status     varchar(10) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    created_at timestamptz NOT NULL,
    decided_at timestamptz
);

CREATE UNIQUE INDEX price_offers_pending_idx ON price_offers (chat_id) WHERE status = 'PENDING';

CREATE TABLE messages (
    id             uuid PRIMARY KEY,
    chat_id        uuid          NOT NULL REFERENCES chats (id) ON DELETE CASCADE,
    sender_id      uuid          NOT NULL,
    text           varchar(2000) NOT NULL,
    price_offer_id uuid REFERENCES price_offers (id),
    sent_at        timestamptz   NOT NULL
);

CREATE INDEX messages_chat_idx ON messages (chat_id, sent_at DESC, id DESC);

-- Blokada rozmówcy działa na parę użytkowników, we wszystkich ich rozmowach.
CREATE TABLE blocks (
    blocker_id uuid        NOT NULL,
    blocked_id uuid        NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id)
);

CREATE INDEX blocks_blocked_idx ON blocks (blocked_id);
