create table if not exists s3_ordering.customers (
    id text primary key, name text not null, credit_limit_minor bigint not null);
create table if not exists s3_ordering.orders (
    id text primary key, customer_id text not null, status text not null,
    total_minor bigint not null, currency text not null, created_at timestamptz not null default now());
create table if not exists s3_ordering.order_lines (
    id bigserial primary key, order_id text not null references s3_ordering.orders(id),
    sku text not null, qty int not null, unit_price_minor bigint not null);
create table if not exists s3_ordering.outbox (
    id bigserial primary key, topic text not null, msg_key text not null,
    payload text not null, sent boolean not null default false);

insert into s3_ordering.customers(id, name, credit_limit_minor)
values ('C1', 'Acme Buyer', 100000) on conflict (id) do nothing;
