CREATE TABLE IF NOT EXISTS da_products (
    id       SERIAL PRIMARY KEY,
    name     VARCHAR(100)   NOT NULL,
    category VARCHAR(50)    NOT NULL,
    price    NUMERIC(10, 2) NOT NULL,
    stock    INT            NOT NULL,
    added_on DATE           NOT NULL
);

CREATE TABLE IF NOT EXISTS da_orders (
    id            SERIAL PRIMARY KEY,
    product_id    INT            REFERENCES da_products(id),
    customer_name VARCHAR(100)   NOT NULL,
    city          VARCHAR(50)    NOT NULL,
    quantity      INT            NOT NULL,
    total_amount  NUMERIC(10, 2) NOT NULL,
    order_date    DATE           NOT NULL,
    status        VARCHAR(20)    NOT NULL
);
