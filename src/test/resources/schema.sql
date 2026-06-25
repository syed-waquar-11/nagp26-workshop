create table if not exists products (
    id bigint primary key,
    name varchar(120) not null,
    category varchar(80) not null,
    price numeric(10, 2) not null,
    stock_quantity integer not null
);