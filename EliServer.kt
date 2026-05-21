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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 3000
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::setup)
        .start(wait = true)
}

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

    val users = ConcurrentHashMap<String, User>()
    val media = ConcurrentHashMap<String, MediaItem>()

    routing {
        get("/") {
            call.respond(mapOf("status" to "Eli server OK", "version" to "1.0"))
        }

        post("/register") {
            val r = call.receive<RegisterReq>()
            if (r.username.isBlank() || r.email.isBlank() || r.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, Err("Fill all fields")); return@post
            }
            if (users.values.any { it.email.equals(r.email, true) }) {
                call.respond(HttpStatusCode.Conflict, Err("Email exists")); return@post
            }
            val u = User(UUID.randomUUID().toString(), r.username, r.email,
                BCrypt.hashpw(r.password, BCrypt.gensalt()), Instant.now().toString())
            users[u.id] = u
            call.respond(HttpStatusCode.Created, AuthRes(u.id, u.username, u.email, u.id))
        }

        post("/login") {
            val r = call.receive<LoginReq>()
            val u = users.values.firstOrNull { it.email.equals(r.email, true) }
            if (u == null || !BCrypt.checkpw(r.password, u.hash)) {
                call.respond(HttpStatusCode.Unauthorized, Err("Wrong credentials")); return@post
            }
            call.respond(AuthRes(u.id, u.username, u.email, u.id))
        }

        post("/media") {
            val r = call.receive<SaveMediaReq>()
            val item = MediaItem(UUID.randomUUID().toString(), r.userId, r.source,
                r.sourceUrl, r.mediaType, r.format, r.title, Instant.now().toString())
            media[item.id] = item
            call.respond(HttpStatusCode.Created, item)
        }

        get("/media/{userId}") {
            val uid = call.parameters["userId"] ?: ""
            call.respond(media.values.filter { it.userId == uid })
        }

        get("/categories") {
            call.respond(listOf(
                Cat("1","music", listOf("pop","rock","jazz")),
                Cat("2","art",   listOf("painting","digital")),
                Cat("3","cook",  listOf("recipes","tips")),
                Cat("4","design",listOf("ui","graphic"))
            ))
        }
    }
}

@Serializable data class RegisterReq(val username: String, val email: String, val password: String)
@Serializable data class LoginReq(val email: String, val password: String)
@Serializable data class AuthRes(val id: String, val username: String, val email: String, val token: String)
@Serializable data class SaveMediaReq(val userId: String, val source: String = "", val sourceUrl: String,
    val mediaType: String, val format: String = "", val title: String = "")
@Serializable data class MediaItem(val id: String, val userId: String, val source: String,
    val sourceUrl: String, val mediaType: String, val format: String, val title: String, val downloadedAt: String)
@Serializable data class Cat(val id: String, val name: String, val subcategories: List<String>)
@Serializable data class Err(val error: String)

data class User(val id: String, val username: String, val email: String, val hash: String, val createdAt: String)
