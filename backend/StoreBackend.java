/* The store server. Browser requests use these routes to read and update SQLite. */
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class StoreBackend {
    static final Path ROOT = Path.of("").toAbsolutePath().normalize(); // This is the folder Java was started from; run.ps1 selects the project folder first.
    static final SecureRandom RANDOM = new SecureRandom();
    static final Map<String, Session> sessions = new ConcurrentHashMap<>(); // Links each login token to a user and expiry time; restarting Java clears these sessions.
    // pricing
    // Store cents as integers; 250 means S$2.50.
    static final int FEE = 250;
    static int port;
    record Session(int user, Instant expires) {} // A small data holder for the signed-in user ID and the time their login expires.
    static class Problem extends RuntimeException { // Carries an HTTP error code and a message that the page can display.
        final int status;
        Problem(int status, String message) { super(message); this.status = status; }
    }

    // starting
    // Load the schema before accepting local requests.
    public static void main(String[] args) throws Exception {
        port = args.length == 0 ? 8080 : Integer.parseInt(args[0]); // Use port 8080 unless a different port was supplied when starting Java.
        Class.forName("org.sqlite.JDBC");
        try (Connection db = connect(); Statement s = db.createStatement()) {
            for (String sql : Files.readString(ROOT.resolve("backend/database-setup.sql")).split(";")) {
                if (!sql.isBlank()) s.execute(sql);
            }
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", StoreBackend::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Grocery store: http://127.0.0.1:" + port);
    }

    static Connection connect() throws SQLException {
        Connection db = DriverManager.getConnection("jdbc:sqlite:" + ROOT.resolve("backend/store.db")); // Open the SQLite file; SQLite creates it if this is the first run.
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON"); // Reject links to missing records, such as a cart row for a user who does not exist.
            s.execute("PRAGMA busy_timeout=5000");
        }
        return db;
    }

    // routing
    // API paths return JSON; other paths serve frontend files.
    static void handle(HttpExchange request) throws IOException {
        try {
            request.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            request.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            if (request.getRequestURI().getPath().startsWith("/api/")) {
                request.getResponseHeaders().set("Cache-Control", "no-store");
                // validating
                // Accept state changes only from this local origin.
                if (request.getRequestMethod().equals("POST")) {
                    String origin = request.getRequestHeaders().getFirst("Origin");
                    if (!("http://127.0.0.1:" + port).equals(origin) &&
                        !("http://localhost:" + port).equals(origin))
                        throw new Problem(403, "The request origin is not permitted.");
                    String type = request.getRequestHeaders().getFirst("Content-Type");
                    if (type == null || !type.startsWith("application/x-www-form-urlencoded"))
                        throw new Problem(415, "Expected form data.");
                } else if (!request.getRequestMethod().equals("GET")) {
                    throw new Problem(405, "Method not permitted.");
                }
                Object result;
                // Serialise database requests to prevent overlapping stock updates.
                synchronized (StoreBackend.class) {
                    try (Connection db = connect()) { result = api(request, db); }
                }
                reply(request, 200, "application/json", json(result).getBytes(StandardCharsets.UTF_8));
            } else {
                if (!request.getRequestMethod().equals("GET")) throw new Problem(405, "Method not permitted.");
                String name = request.getRequestURI().getPath();
                if (name.equals("/")) name = "/main-grocery-page.html";
                Path folder = ROOT.resolve("frontend");
                Path file = folder.resolve(name.substring(1)).normalize();
                // Reject paths outside frontend after resolving directory traversal.
                if (!file.startsWith(folder) || !Files.isRegularFile(file)) throw new Problem(404, "File not found.");
                String type = name.endsWith(".css") ? "text/css" : name.endsWith(".js") ? "text/javascript" :
                    name.endsWith(".svg") ? "image/svg+xml" : "text/html";
                reply(request, 200, type, Files.readAllBytes(file));
            }
        } catch (Problem e) {
            reply(request, e.status, "application/json", json(Map.of("error", e.getMessage())).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("Request failed: " + e.getClass().getSimpleName());
            reply(request, 500, "application/json", "{\"error\":\"The server could not complete this request.\"}".getBytes(StandardCharsets.UTF_8));
        } finally { request.close(); }
    }

    static Object api(HttpExchange request, Connection db) throws Exception {
        String path = request.getRequestURI().getPath();
        boolean post = request.getRequestMethod().equals("POST");
        // Read one extra byte to detect forms above the 8 KiB limit.
        byte[] body = post ? request.getRequestBody().readNBytes(8193) : new byte[0];
        if (body.length > 8192) throw new Problem(413, "Form is too large.");
        Map<String, String> form = parse(post ? new String(body, StandardCharsets.UTF_8) : request.getRequestURI().getRawQuery()); // Turn submitted fields into named values, such as quantity=2.
        // searching
        // Bind %Apples% as data, not as part of the SQL command.
        if (path.equals("/api/groceries") && !post) {
            return rows(db, "SELECT * FROM groceries WHERE item_name LIKE ? ORDER BY id", "%" + form.getOrDefault("q", "") + "%");
        }
        // registering
        // Repeat validation on the server, then store a salted password hash.
        if (path.equals("/api/register") && post) {
            String username = value(form, "username", 40), email = value(form, "email", 160);
            String password = form.getOrDefault("password", "");
            if (!username.matches("[A-Za-z0-9_]{3,40}")) throw new Problem(400, "Username requires 3–40 letters, digits or underscores.");
            if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new Problem(400, "Enter a valid email address.");
            if (password.length() < 10 || password.length() > 128) throw new Problem(400, "Password requires 10–128 characters.");
            if (!rows(db, "SELECT id FROM users WHERE username=? OR email=?", username, email).isEmpty())
                throw new Problem(409, "Username or email is already registered.");
            update(db, "INSERT INTO users(username,email,password_hash) VALUES(?,?,?)", username, email, hash(password, null));
            return Map.of("message", "Account created. You can now sign in.");
        }
        // authenticating
        // Verify the password before issuing a new two-hour session.
        if (path.equals("/api/login") && post) {
            String login = value(form, "login", 160);
            String password = form.getOrDefault("password", "");
            if (password.length() > 128) throw new Problem(400, "Password is too long.");
            var found = rows(db, "SELECT * FROM users WHERE username=? OR email=?", login, login);
            
            if (found.isEmpty() || !verify(password, (String) found.get(0).get("password_hash")))
                throw new Problem(401, "Username/email or password is incorrect.");
            var user = found.get(0);
            sessions.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
            sessions.remove(cookie(request));
            byte[] token = new byte[32]; RANDOM.nextBytes(token); // Generate an unpredictable login token rather than using the customer ID as a cookie.
            String key = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
            sessions.put(key, new Session(((Number) user.get("id")).intValue(), Instant.now().plusSeconds(7200)));
            request.getResponseHeaders().add("Set-Cookie", "session=" + key + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=7200");
            String email = (String) user.get("email");
            Thread.startVirtualThread(() -> notifyLogin(email));
            return Map.of("message", "Signed in.");
        }
        // authorising
        // Derive the user ID from the session, never from submitted form data.
        Session session = sessions.get(cookie(request));
        if (session == null || session.expires().isBefore(Instant.now())) throw new Problem(401, "Please sign in to continue.");
        int user = session.user();
        if (path.equals("/api/logout") && post) {
            sessions.remove(cookie(request));
            request.getResponseHeaders().add("Set-Cookie", "session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            return Map.of("message", "Signed out.");
        }
        if (path.equals("/api/account") && !post)
            return rows(db, "SELECT username,email,address,map_x,map_y FROM users WHERE id=?", user).get(0);
        if (path.equals("/api/address") && post) {
            String address = value(form, "address", 300);
            update(db, "UPDATE users SET address=? WHERE id=?", address, user);
            return Map.of("message", "Delivery address saved.");
        }
        if (path.equals("/api/cart") && !post) return cart(db, user);
        if (path.equals("/api/cart") && post) {
            int item = number(form, "id", 1, Integer.MAX_VALUE), quantity = number(form, "quantity", 0, 999);
            var found = rows(db, "SELECT stock,expiry_date FROM groceries WHERE id=?", item);
            if (found.isEmpty()) throw new Problem(404, "Item not found.");
            if (quantity > 0 && LocalDate.parse((String) found.get(0).get("expiry_date")).isBefore(LocalDate.now(ZoneOffset.UTC)))
                throw new Problem(409, "This item has expired.");
            if (quantity > ((Number) found.get(0).get("stock")).intValue()) throw new Problem(409, "Requested quantity exceeds available stock.");
            // updating cart
            // Zero removes an item; other quantities replace its current total.
            if (quantity == 0) update(db, "DELETE FROM cart WHERE user_id=? AND grocery_id=?", user, item);
            else update(db, "INSERT INTO cart VALUES(?,?,?) ON CONFLICT(user_id,grocery_id) DO UPDATE SET quantity=excluded.quantity", user, item, quantity);
            return cart(db, user);
        }
        if (path.equals("/api/checkout") && post) return checkout(db, user, form);
        if (path.equals("/api/orders") && !post) {
            var orders = rows(db, "SELECT o.*,d.mode,d.instructions,d.leave_at_door,d.scheduled,d.tip_cents,d.fee_cents FROM orders o LEFT JOIN order_delivery d ON d.order_id=o.id WHERE o.user_id=? ORDER BY o.id DESC", user);
            for (var order : orders) order.put("items", rows(db, "SELECT item_name,quantity,price_cents FROM order_items WHERE order_id=?", order.get("id")));
            return orders;
        }
        throw new Problem(404, "API endpoint not found.");
    }

    static Map<String, Object> cart(Connection db, int user) throws SQLException {
        var items = rows(db, "SELECT g.*,c.quantity FROM cart c JOIN groceries g ON g.id=c.grocery_id WHERE c.user_id=? ORDER BY g.id", user);
        int subtotal = 0;
        for (var item : items) subtotal += ((Number) item.get("price_cents")).intValue() * ((Number) item.get("quantity")).intValue();
        int fee = items.isEmpty() ? 0 : FEE; // Charge delivery only when the cart contains items.
        return Map.of("items", items, "subtotal", subtotal, "fee", fee, "total", subtotal + fee);
    }

    // checking out
    // Commit stock, receipt and cart changes together; roll back on failure.
    static Object checkout(Connection db, int user, Map<String, String> form) throws SQLException {
        db.setAutoCommit(false); // Hold database changes until the whole checkout succeeds.
        try {
            String mode = form.getOrDefault("mode", "standard");
            if (!Set.of("standard", "priority", "scheduled").contains(mode)) throw new Problem(400, "Invalid delivery option.");
            int tip = number(Map.of("tip", form.getOrDefault("tip", "0")), "tip", 0, 300);
            if (!Set.of(0, 150, 200, 300).contains(tip)) throw new Problem(400, "Invalid tip.");
            String instructions = form.getOrDefault("instructions", "").trim();
            if (instructions.length() > 300) throw new Problem(400, "Delivery instructions are too long.");
            String scheduled = mode.equals("scheduled") ? value(form, "scheduled", 40) : "";
            int estimate = mode.equals("priority") ? 20 : 30;
            // validating
            // Scheduled delivery must be 30 minutes to seven days ahead.
            if (mode.equals("scheduled")) {
                try {
                    long seconds = Duration.between(Instant.now(), Instant.parse(scheduled)).getSeconds(); // Calculate how far ahead the selected delivery time is, using UTC instants.
                    if (seconds < 1800 || seconds > 604800) throw new IllegalArgumentException();
                    estimate = (int) Math.ceil(seconds / 60.0);
                } catch (RuntimeException e) { throw new Problem(400, "Choose a delivery time 30 minutes to seven days ahead."); }
            }
            if (form.containsKey("address")) update(db, "UPDATE users SET address=? WHERE id=?", value(form, "address", 300), user);
            var account = rows(db, "SELECT * FROM users WHERE id=?", user).get(0);
            if (((String) account.get("address")).isBlank()) throw new Problem(400, "Save a delivery address before checkout.");
            var items = rows(db, "SELECT g.*,c.quantity FROM cart c JOIN groceries g ON g.id=c.grocery_id WHERE c.user_id=?", user);
            if (items.isEmpty()) throw new Problem(400, "Your cart is empty.");
            int deliveryFee = FEE + (mode.equals("priority") ? 110 : 0); // Priority adds 110 cents to the usual delivery fee; the tip is added separately.
            int total = deliveryFee + tip;
            for (var item : items) {
                int qty = ((Number) item.get("quantity")).intValue();
                if (LocalDate.parse((String) item.get("expiry_date")).isBefore(LocalDate.now(ZoneOffset.UTC)))
                    throw new Problem(409, item.get("item_name") + " has expired. Remove it from your cart.");
                // Recheck stock at purchase time because a cart does not reserve it.
                if (update(db, "UPDATE groceries SET stock=stock-? WHERE id=? AND stock>=?", qty, item.get("id"), qty) != 1)
                    throw new Problem(409, item.get("item_name") + " has insufficient stock.");
                total += qty * ((Number) item.get("price_cents")).intValue();
            }
            update(db, "INSERT INTO orders(user_id,address,total_cents,delivery_minutes) VALUES(?,?,?,?)", user, account.get("address"), total, estimate);
            // Copy prices into receipt rows so later catalogue edits cannot change them.
            Object id = rows(db, "SELECT last_insert_rowid() AS id").get(0).get("id"); // Get the order ID created by this connection so its receipt rows use the correct order.
            update(db, "INSERT INTO order_delivery VALUES(?,?,?,?,?,?,?)", id, mode, instructions,
                form.getOrDefault("leave_at_door", "false").equals("true") ? 1 : 0, scheduled, tip, deliveryFee);
            for (var item : items) update(db, "INSERT INTO order_items VALUES(?,?,?,?,?)", id, item.get("id"), item.get("item_name"), item.get("quantity"), item.get("price_cents"));
            update(db, "DELETE FROM cart WHERE user_id=?", user);
            db.commit(); // Save the stock changes, receipt and cleared cart as one successful operation.
            return Map.of("id", id, "minutes", estimate, "message", "Order recorded. This demonstration does not take payment.");
        } catch (SQLException | RuntimeException e) { db.rollback(); throw e; }
        finally { db.setAutoCommit(true); }
    }

    static String cookie(HttpExchange request) { // Find the session token among the cookies sent by the browser.
        String cookies = request.getRequestHeaders().getFirst("Cookie");
        if (cookies != null) for (String part : cookies.split(";")) if (part.trim().startsWith("session=")) return part.trim().substring(8);
        return "";
    }
    static String value(Map<String, String> form, String name, int max) { // Read a required text field and reject blank or overly long values.
        String value = form.getOrDefault(name, "").trim();
        if (value.isEmpty() || value.length() > max) throw new Problem(400, "Invalid " + name + ".");
        return value;
    }
    static int number(Map<String, String> form, String name, int min, int max) { // Accept whole numbers only, within the given minimum and maximum.
        try { int n = Integer.parseInt(form.getOrDefault(name, "")); if (n >= min && n <= max) return n; }
        catch (NumberFormatException ignored) { }
        throw new Problem(400, "Invalid " + name + ".");
    }
    static Map<String, String> parse(String text) {
        Map<String, String> form = new HashMap<>();
        if (text != null && !text.isBlank()) for (String pair : text.split("&")) {
            String[] parts = pair.split("=", 2); // Split at the first equals sign only, so a value can still contain another equals sign.
            try { form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : ""); }
            catch (IllegalArgumentException e) { throw new Problem(400, "Invalid form encoding."); }
        }
        return form;
    }
    // binding
    // JDBC placeholders count from 1; Java array indexes count from 0.
    static PreparedStatement statement(Connection db, String sql, Object... values) throws SQLException {
        PreparedStatement s = db.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
        return s;
    }
    static int update(Connection db, String sql, Object... values) throws SQLException { // Run an INSERT, UPDATE or DELETE and return how many rows changed.
        try (var s = statement(db, sql, values)) { return s.executeUpdate(); }
    }
    static List<Map<String, Object>> rows(Connection db, String sql, Object... values) throws SQLException { // Convert query results into a list of named fields that can later become JSON.
        List<Map<String, Object>> result = new ArrayList<>();
        try (var s = statement(db, sql, values); var rs = s.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                result.add(row);
            }
        }
        return result;
    }
    // hashing
    // Create a salt at registration and reuse it when checking a password.
    static String hash(String password, byte[] salt) throws Exception {
        if (salt == null) { salt = new byte[16]; RANDOM.nextBytes(salt); }
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 600000, 256); // Apply the password derivation 600,000 times and produce a 256-bit result.
        byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        spec.clearPassword();
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derived);
    }
    static boolean verify(String password, String stored) throws Exception { // Hash the entered password with the saved salt, then compare it with the stored result.
        return MessageDigest.isEqual(hash(password, Base64.getDecoder().decode(stored.split(":")[0])).getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }
    // notifying
    // Pass arguments directly to Python and stop a stalled process after 25 seconds.
    static void notifyLogin(String email) {
        try {
            Process p = new ProcessBuilder(System.getenv().getOrDefault("PYTHON", "python"), ROOT.resolve("backend/email-sender.py").toString(), email).inheritIO().start();
            if (!p.waitFor(25, TimeUnit.SECONDS)) { p.destroyForcibly(); System.err.println("Login alert timed out."); }
        } catch (Exception e) { System.err.println("Login alert could not start."); }
    }
    // encoding
    // Escape quotes and control characters in strings, including nested values.
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringJoiner joined = new StringJoiner(",", "{", "}");
            map.forEach((k, v) -> joined.add(json(k.toString()) + ":" + json(v))); return joined.toString();
        }
        if (value instanceof Iterable<?> list) {
            StringJoiner joined = new StringJoiner(",", "[", "]");
            list.forEach(v -> joined.add(json(v))); return joined.toString();
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    static void reply(HttpExchange request, int status, String type, byte[] bytes) throws IOException { // Send the HTTP status and content type before writing the response bytes.
        request.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        request.sendResponseHeaders(status, bytes.length);
        request.getResponseBody().write(bytes);
    }
}
