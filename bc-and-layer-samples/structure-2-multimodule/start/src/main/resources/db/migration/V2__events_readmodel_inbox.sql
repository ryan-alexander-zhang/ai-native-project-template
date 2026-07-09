-- analysis-00005 expansion: CQRS read model, Ordering inbox, Inventory outbox.

-- Ordering read model (projection), updated in-process from domain events.
create table if not exists s2_ordering.order_view (
    order_id text primary key, status text not null,
    total_minor bigint not null, currency text not null,
    updated_at timestamptz not null default now());

-- Ordering inbox: idempotent consumer for the stock-result integration event (G4).
create table if not exists s2_ordering.processed_result (
    order_id text primary key, processed_at timestamptz not null default now());

-- Inventory outbox: reliable delivery of the StockResult return leg (symmetric to Ordering).
create table if not exists s2_inventory.outbox (
    id bigserial primary key, topic text not null, msg_key text not null,
    payload text not null, sent boolean not null default false);
