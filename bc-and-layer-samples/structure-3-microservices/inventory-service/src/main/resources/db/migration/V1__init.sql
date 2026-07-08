create table if not exists s3_inventory.stock_items (
    sku text primary key, available int not null);
create table if not exists s3_inventory.reservations (
    order_id text primary key, sku text not null, qty int not null, outcome text not null);

insert into s3_inventory.stock_items(sku, available)
values ('BOOK-1', 100) on conflict (sku) do nothing;
