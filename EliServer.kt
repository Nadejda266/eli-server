import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mindrot.jbcrypt.BCrypt
import java.sql.DriverManager
import java.sql.Connection
import java.time.Instant
import java.util.UUID

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 3000
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::setup)
        .start(wait = true)
}

// ── Database connection ───────────────────────────────────────────────────────

fun getConnection(): Connection {
    val url = System.getenv("DATABASE_URL")
        ?: throw IllegalStateException("DATABASE_URL not set")
    // Render gives postgres:// but JDBC needs postgresql://
    val jdbcUrl = url.replace("postgres://", "postgresql://")
        .let { if (!it.contains("?")) "$it?sslmode=require" else it }
    return DriverManager.getConnection(jdbcUrl)
}

fun initDb() {
    getConnection().use { conn ->
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                email TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
        """.trimIndent())
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS media_items (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                source TEXT NOT NULL,
                source_url TEXT NOT NULL,
                media_type TEXT NOT NULL,
                format TEXT,
                title TEXT,
                downloaded_at TEXT NOT NULL
            )
        """.trimIndent())
    }
    println("Database initialized OK")
}

// ── Application setup ─────────────────────────────────────────────────────────

fun Application.setup() {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
    }

    // Init tables on startup
    try { initDb() } catch (e: Exception) { println("DB init error: ${e.message}") }

    routing {

        get("/") {
            call.respond(mapOf("status" to "Eli server OK", "version" to "2.0", "db" to "PostgreSQL"))
        }

        // ── REGISTER ─────────────────────────────────────────────────────────
        post("/register") {
            val r = call.receive<RegisterReq>()
            if (r.username.isBlank() || r.email.isBlank() || r.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, Err("Fill all fields"))
                return@post
            }
            try {
                getConnection().use { conn ->
                    // Check email exists
                    val check = conn.prepareStatement("SELECT id FROM users WHERE LOWER(email)=LOWER(?)")
                    check.setString(1, r.email)
                    if (check.executeQuery().next()) {
                        call.respond(HttpStatusCode.Conflict, Err("Email already registered"))
                        return@post
                    }
                    val id = UUID.randomUUID().toString()
                    val hash = BCrypt.hashpw(r.password, BCrypt.gensalt())
                    val now = Instant.now().toString()
                    val stmt = conn.prepareStatement(
                        "INSERT INTO users(id,username,email,password_hash,created_at) VALUES(?,?,?,?,?)")
                    stmt.setString(1, id)
                    stmt.setString(2, r.username)
                    stmt.setString(3, r.email)
                    stmt.setString(4, hash)
                    stmt.setString(5, now)
                    stmt.executeUpdate()
                    call.respond(HttpStatusCode.Created,
                        AuthRes(id, r.username, r.email, token = id))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, Err("DB error: ${e.message}"))
            }
        }

        // ── LOGIN ─────────────────────────────────────────────────────────────
        post("/login") {
            val r = call.receive<LoginReq>()
            try {
                getConnection().use { conn ->
                    val stmt = conn.prepareStatement(
                        "SELECT id,username,email,password_hash FROM users WHERE LOWER(email)=LOWER(?)")
                    stmt.setString(1, r.email)
                    val rs = stmt.executeQuery()
                    if (!rs.next()) {
                        call.respond(HttpStatusCode.Unauthorized, Err("Wrong email or password"))
                        return@post
                    }
                    val hash = rs.getString("password_hash")
                    if (!BCrypt.checkpw(r.password, hash)) {
                        call.respond(HttpStatusCode.Unauthorized, Err("Wrong email or password"))
                        return@post
                    }
                    call.respond(AuthRes(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        token = rs.getString("id")
                    ))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, Err("DB error: ${e.message}"))
            }
        }

        // ── MEDIA ─────────────────────────────────────────────────────────────
        post("/media") {
            val r = call.receive<SaveMediaReq>()
            try {
                getConnection().use { conn ->
                    val id = UUID.randomUUID().toString()
                    val stmt = conn.prepareStatement(
                        "INSERT INTO media_items(id,user_id,source,source_url,media_type,format,title,downloaded_at) VALUES(?,?,?,?,?,?,?,?)")
                    stmt.setString(1, id)
                    stmt.setString(2, r.userId)
                    stmt.setString(3, r.source)
                    stmt.setString(4, r.sourceUrl)
                    stmt.setString(5, r.mediaType)
                    stmt.setString(6, r.format)
                    stmt.setString(7, r.title)
                    stmt.setString(8, Instant.now().toString())
                    stmt.executeUpdate()
                    call.respond(HttpStatusCode.Created,
                        MediaItem(id, r.userId, r.source, r.sourceUrl, r.mediaType, r.format, r.title, Instant.now().toString()))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, Err("DB error: ${e.message}"))
            }
        }

        get("/media/{userId}") {
            val userId = call.parameters["userId"] ?: ""
            try {
                getConnection().use { conn ->
                    val stmt = conn.prepareStatement(
                        "SELECT * FROM media_items WHERE user_id=? ORDER BY downloaded_at DESC")
                    stmt.setString(1, userId)
                    val rs = stmt.executeQuery()
                    val items = mutableListOf<MediaItem>()
                    while (rs.next()) {
                        items.add(MediaItem(
                            rs.getString("id"), rs.getString("user_id"),
                            rs.getString("source"), rs.getString("source_url"),
                            rs.getString("media_type"), rs.getString("format") ?: "",
                            rs.getString("title") ?: "", rs.getString("downloaded_at")
                        ))
                    }
                    call.respond(items)
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, Err("DB error: ${e.message}"))
            }
        }

        get("/categories") {
            call.respond(listOf(
                Cat("1", "music",  listOf("pop","rock","jazz","electronic")),
                Cat("2", "art",    listOf("painting","sculpture","digital")),
                Cat("3", "cook",   listOf("recipes","techniques","reviews")),
                Cat("4", "design", listOf("ui","graphic","interior"))
            ))
        }
    }
}

// ── Models ────────────────────────────────────────────────────────────────────

@Serializable data class RegisterReq(val username: String, val email: String, val password: String)
@Serializable data class LoginReq(val email: String, val password: String)
@Serializable data class AuthRes(val id: String, val username: String, val email: String, val token: String)
@Serializable data class SaveMediaReq(
    val userId: String, val source: String = "", val sourceUrl: String,
    val mediaType: String, val format: String = "", val title: String = ""
)
@Serializable data class MediaItem(
    val id: String, val userId: String, val source: String,
    val sourceUrl: String, val mediaType: String, val format: String,
    val title: String, val downloadedAt: String
)
@Serializable data class Cat(val id: String, val name: String, val subcategories: List<String>)
@Serializable data class Err(val error: String)
