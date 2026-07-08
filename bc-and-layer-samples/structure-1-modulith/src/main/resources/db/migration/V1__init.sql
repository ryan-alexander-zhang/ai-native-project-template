-- Ordering context (schema s1_ordering)
create table if not exists s1_ordering.customers (
    id                 text   primary key,
    name               text   not null,
    credit_limit_minor bigint not null
);

create table if not exists s1_ordering.orders (
    id           text        primary key,
    customer_id  text        not null,
    status       text        not null,
    total_minor  bigint      not null,
    currency     text        not null,
    created_at   timestamptz not null default now()
);

create table if not exists s1_ordering.order_lines (
    id               bigserial primary key,
    order_id         text      not null references s1_ordering.orders (id),
    sku              text      not null,
    qty              int       not null,
    unit_price_minor bigint    not null
);

-- Inventory context (schema s1_inventory)
create table if not exists s1_inventory.stock_items (
    sku       text primary key,
    available int  not null
);

-- Inventory inbox / idempotency: one reservation decision per order.
create table if not exists s1_inventory.reservations (
    order_id text primary key,
    sku      text not null,
    qty      int  not null,
    outcome  text not null
);

-- Seed data
insert into s1_ordering.customers (id, name, credit_limit_minor)
values ('C1', 'Acme Buyer', 100000)
on conflict (id) do nothing;

insert into s1_inventory.stock_items (sku, available)
values ('BOOK-1', 100)
on conflict (sku) do nothing;
