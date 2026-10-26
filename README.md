# 🛒 Little Pantry

A modern, full-stack grocery shopping platform where users can seamlessly browse products, manage a dynamic shopping cart, and place simulated orders with custom delivery logistics.

---

## 🛠️ Tech Stack & Architecture

Discover the technologies powering the Little Pantry application ecosystem:

| Technology | Badge | Purpose |
| :--- | :--- | :--- |
| **Java** | ![Java](https://shields.io) | Core server orchestration, session management, request validation, and checkout logic. |
| **JavaScript** | ![JavaScript](https://shields.io) | Handles asynchronous browser interactions and dynamic frontend API requests. |
| **SQLite** | ![SQLite](https://shields.io) | Lightweight database structure managing accounts, catalogs, carts, and order tables. |
| **Python** | ![Python](https://shields.io) | Deploys an automated micro-script to handle optional login email notifications. |
| **PowerShell** | ![PowerShell](https://shields.io) | Direct setup commands and execution scripts for effortless application startup. |
| **Web Basics** | ![HTML5](https://shields.io) ![CSS3](https://shields.io) | Responsive page layouts, uniform themes, and modern interface elements. |

---

## ✨ Features

* **Secure Authentication:** Account registration and user logins protected via salted password hashes.
* **Live Catalog:** Instant search functionality across 10 distinct grocery items, mapping price points, expiration dates, and live stock tracking.
* **Active Cart Management:** Smooth addition of products with instant quantity adjustment tools.
* **Flexible Checkout:** Support for standard, priority, or scheduled delivery workflows paired with drop-off instructions and tipping options.
* **Real-time Inventory:** Automated system adjustments that decrement product stock limits immediately when checkouts conclude.
* **Personalized Dashboard:** View personal profiles, update your single-saved address, and track comprehensive order logs.

---

## 🚀 Getting Started

### Prerequisites
* **Java JDK 21** or later
* **Python 3.10** or later

### Installation & Launch
1. Open your terminal inside the root project directory.
2. Execute the setup and initialization scripts via PowerShell:


3. Launch your browser and navigate to: **`http://127.0.0.1:8080`**.

> 💡 **System Note:** Keep your terminal session running while exploring the site. The initial setup phase requires an active internet connection to download necessary external Java dependencies.

---

## 📂 Project Architecture

├── backend/
│   ├── StoreBackend.java      # Java web server engine and business logic
│   ├── database-setup.sql     # Initial setup schema for DB tables & baseline products
│   ├── store.db               # Live local SQLite binary storage file
│   └── email-sender.py        # Automated alerts triggering Gmail user notifications
├── frontend/
│   ├── images/                # Product illustration assets
│   ├── store-styles.css       # Unified project-wide styling configuration
│   ├── store.js               # Event-driven UI interactions and server hooks
│   └── *.html                 # Presentation pages (Login, Catalog, Cart, Checkout, Profile)
├── setup.ps1                  # Auto-downloads core libraries and compiles target Java server
└── run.ps1                    # Main application bootstrapper script

### 🗄️ Database Insight
The underlying architecture operates across six primary relational tables: `users`, `groceries`, `cart`, `orders`, `order_items`, and `order_delivery`. 
* *Developer Tip:* Monetary calculations and prices are tracked natively in **cents** to guarantee floating-point calculation stability and prevent rounding discrepancies.

---

## ⚠️ Scope & Limitations

* **Educational Sandbox:** This build exists purely as a localized instructional tool. No actual card networks, financial gates, or active couriers are tied to the execution flow.
* **Account Models:** User accounts are restricted to storing exactly one primary address mapping at any given time.
* **Checkout Page:** The checkout route retains its historical `delivery-and-map.html` filename, though the map layer itself has been uncoupled from the codebase.
* **Email Safety:** The integrated Gmail connection requires separate local environment variables and has not been tested against a live staging server. Keep production credentials and user databases excluded from public version control structures.
