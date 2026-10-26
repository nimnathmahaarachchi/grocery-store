-- The store schema. Startup creates missing tables without replacing existing data.
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS users (
 id INTEGER PRIMARY KEY, username TEXT NOT NULL COLLATE NOCASE UNIQUE,
 email TEXT NOT NULL COLLATE NOCASE UNIQUE, password_hash TEXT NOT NULL,
 address TEXT NOT NULL DEFAULT '', map_x INTEGER NOT NULL DEFAULT 50,
 map_y INTEGER NOT NULL DEFAULT 50
);
-- pricing
-- Use cents and derive availability from stock instead of storing a separate status.
CREATE TABLE IF NOT EXISTS groceries (
 id INTEGER PRIMARY KEY, item_name TEXT NOT NULL, category TEXT NOT NULL,
 price_cents INTEGER NOT NULL CHECK(price_cents >= 0), expiry_date TEXT NOT NULL,
 stock INTEGER NOT NULL CHECK(stock >= 0), image TEXT NOT NULL,
 availability TEXT GENERATED ALWAYS AS
 (CASE WHEN stock > 0 THEN 'In Stock' ELSE 'Out of Stock' END) VIRTUAL
);
-- cart
-- Keep one row per user and product.
CREATE TABLE IF NOT EXISTS cart (
 user_id INTEGER REFERENCES users(id), grocery_id INTEGER REFERENCES groceries(id),
 quantity INTEGER NOT NULL CHECK(quantity > 0), PRIMARY KEY(user_id, grocery_id)
);
CREATE TABLE IF NOT EXISTS orders (
 id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id),
 placed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, address TEXT NOT NULL,
 total_cents INTEGER NOT NULL, delivery_minutes INTEGER NOT NULL
);
-- receipts
-- Preserve the purchased name and price when the catalogue changes.
CREATE TABLE IF NOT EXISTS order_items (
 order_id INTEGER REFERENCES orders(id), grocery_id INTEGER REFERENCES groceries(id),
 item_name TEXT NOT NULL, quantity INTEGER NOT NULL, price_cents INTEGER NOT NULL,
 PRIMARY KEY(order_id, grocery_id)
);
-- seeding
-- Relative dates apply to new records. Existing stock and dates stay unchanged.
INSERT OR IGNORE INTO groceries(id,item_name,category,price_cents,expiry_date,stock,image) VALUES
 (1,'Whole Milk','Dairy',320,date('now','+7 days'),18,'milk.svg'),
 (2,'Cheddar Cheese','Dairy',480,date('now','+30 days'),12,'cheese.svg'),
 (3,'White Bread','Bakery',250,date('now','+5 days'),20,'bread.svg'),
 (4,'Chocolate Chip Cookies','Bakery',390,date('now','+60 days'),14,'cookies.svg'),
 (5,'Fresh Bananas','Produce',210,date('now','+6 days'),25,'bananas.svg'),
 (6,'Gala Apples','Produce',360,date('now','+14 days'),22,'apples.svg'),
 (7,'Organic Carrots','Produce',280,date('now','+12 days'),16,'carrots.svg'),
 (8,'Fresh Eggs','Meat / Protein',420,date('now','+21 days'),15,'eggs.svg'),
 (9,'Chicken Breasts','Meat / Protein',690,date('now','+3 days'),8,'chicken.svg'),
 (10,'Spaghetti Pasta','Pantry',230,date('now','+365 days'),0,'pasta.svg');

-- delivery details
-- A separate table keeps older databases and receipts compatible.
CREATE TABLE IF NOT EXISTS order_delivery (
 order_id INTEGER PRIMARY KEY REFERENCES orders(id), mode TEXT NOT NULL,
 instructions TEXT NOT NULL, leave_at_door INTEGER NOT NULL,
 scheduled TEXT NOT NULL, tip_cents INTEGER NOT NULL, fee_cents INTEGER NOT NULL
);
