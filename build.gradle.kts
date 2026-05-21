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
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        eliServer()
    }.start(wait = true)
}

fun Application.eliServer() {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; ignoreUnknownKeys = true })
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
    }

    val users      = ConcurrentHashMap<String, EliUser>()
    val mediaItems = ConcurrentHashMap<String, MediaItem>()
    val messages   = ConcurrentHashMap<String, MutableList<EliMessage>>()

    routing {
        get("/") {
            call.respond(mapOf("status" to "Eli server running", "version" to "1.0"))
        }

        post("/register") {
            val req = call.receive<RegisterRequest>()
            if (req.username.isBlank() || req.email.isBlank() || req.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Fill username, email, password"))
                return@post
            }
            if (users.values.any { it.email.equals(req.email, ignoreCase = true) }) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Email already registered"))
                return@post
            }
            val user = EliUser(
                id = UUID.randomUUID().toString(),
                username = req.username,
                email = req.email,
                passwordHash = BCrypt.hashpw(req.password, BCrypt.gensalt()),
                createdAt = Instant.now().toString()
            )
            users[user.id] = user
            call.respond(HttpStatusCode.Created, user.toAuthResponse())
        }

        post("/login") {
            val req = call.receive<LoginRequest>()
            val user = users.values.firstOrNull { it.email.equals(req.email, ignoreCase = true) }
            if (user == null || !BCrypt.checkpw(req.password, user.passwordHash)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Wrong email or password"))
                return@post
            }
            call.respond(user.toAuthResponse())
        }

        get("/users") {
            call.respond(users.values.map { it.toPublicUser() })
        }

        post("/media") {
            val req = call.receive<SaveMediaRequest>()
            val allowedSources = setOf("whatsapp","viber","instagram","vk","youtube","tiktok","other")
            val allowedTypes   = setOf("video","image","file","text")
            when {
                req.userId.isBlank() || req.sourceUrl.isBlank() || req.mediaType.isBlank() ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("userId, sourceUrl, mediaType required"))
                !users.containsKey(req.userId) ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                req.source !in allowedSources ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid source"))
                req.mediaType !in allowedTypes ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid mediaType"))
                else -> {
                    val item = MediaItem(
                        id = UUID.randomUUID().toString(),
                        userId = req.userId,
                        source = req.source,
                        sourceUrl = req.sourceUrl,
                        mediaType = req.mediaType,
                        format = req.format,
                        title = req.title,
                        localPath = req.localPath,
                        downloadedAt = Instant.now().toString()
                    )
                    mediaItems[item.id] = item
                    call.respond(HttpStatusCode.Created, item)
                }
            }
        }

        get("/media/{userId}") {
            val userId = call.parameters["userId"]
            if (userId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("userId required"))
                return@get
            }
            call.respond(mediaItems.values.filter { it.userId == userId }.sortedByDescending { it.downloadedAt })
        }

        post("/media/delete") {
            val req = call.receive<DeleteMediaRequest>()
            if (mediaItems.remove(req.itemId) != null)
                call.respond(mapOf("deleted" to req.itemId))
            else
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Item not found"))
        }

        get("/categories") {
            call.respond(listOf(
                Category("1", "music",  listOf("pop","rock","jazz","electronic")),
                Category("2", "art",    listOf("painting","sculpture","digital")),
                Category("3", "cook",   listOf("recipes","techniques","reviews")),
                Category("4", "design", listOf("ui","graphic","interior"))
            ))
        }

        post("/messages") {
            val req = call.receive<SendMessageRequest>()
            if (req.chatId.isBlank() || req.senderId.isBlank() || req.text.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId, senderId, text required"))
                return@post
            }
            val msg = EliMessage(UUID.randomUUID().toString(), req.chatId, req.senderId, req.text, Instant.now().toString())
            messages.getOrPut(req.chatId) { mutableListOf() }.add(msg)
            call.respond(HttpStatusCode.Created, msg)
        }

        get("/messages/{chatId}") {
            val chatId = call.parameters["chatId"]
            if (chatId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
                return@get
            }
            call.respond(messages[chatId].orEmpty().sortedBy { it.createdAt })
        }
    }
}

@Serializable data class RegisterRequest(val username: String, val email: String, val password: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class AuthResponse(val id: String, val username: String, val email: String, val token: String)
@Serializable data class PublicUser(val id: String, val username: String, val email: String, val createdAt: String)

data class EliUser(val id: String, val username: String, val email: String,
                   val passwordHash: String, val createdAt: String) {
    fun toAuthResponse() = AuthResponse(id, username, email, token = id)
    fun toPublicUser()   = PublicUser(id, username, email, createdAt)
}

@Serializable data class SaveMediaRequest(
    val userId: String, val source: String, val sourceUrl: String,
    val mediaType: String, val format: String = "", val title: String = "", val localPath: String = ""
)
@Serializable data class MediaItem(
    val id: String, val userId: String, val source: String, val sourceUrl: String,
    val mediaType: String, val format: String, val title: String,
    val localPath: String, val downloadedAt: String
)
@Serializable data class DeleteMediaRequest(val itemId: String)
@Serializable data class Category(val id: String, val name: String, val subcategories: List<String>)
@Serializable data class SendMessageRequest(val chatId: String, val senderId: String, val text: String)
@Serializable data class EliMessage(val id: String, val chatId: String, val senderId: String, val text: String, val createdAt: String)
@Serializable data class ErrorResponse(val error: String)
