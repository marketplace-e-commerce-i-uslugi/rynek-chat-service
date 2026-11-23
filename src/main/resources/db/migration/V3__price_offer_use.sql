-- Zaakceptowaną propozycję ceny można wykorzystać w jednym zamówieniu (order-service); ważność liczy się od decyzji sprzedawcy.
ALTER TABLE price_offers ADD COLUMN used_at timestamptz;
