package dev.rebelonion

import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours

class MusicControlServer {
    private val sessions = ConcurrentHashMap<String, AppSession>()
    private val usedKeys = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        isLenient = true
        encodeDefaults = true
    }

    data class AppSession(
        val key: String,
        var connection: DefaultWebSocketSession? = null,
        val controllers: MutableSet<DefaultWebSocketSession> = mutableSetOf(),
        var lastActive: Instant = Instant.now(),
        val expiresAt: Instant = Instant.now().plus(365, ChronoUnit.DAYS),
        var state: Message.PlayerState = Message.PlayerState()
    )

    private fun generateKey(): String {
        val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val length = 6
        var key: String
        do {
            key = buildString {
                repeat(length) {
                    append(chars[Random.nextInt(chars.length)])
                }
            }
        } while (!usedKeys.add(key))
        return key
    }

    private suspend fun broadcastToControllers(key: String, message: Message) {
        val session = sessions[key] ?: return
        val messageJson = json.encodeToString(message)

        session.controllers.forEach { controller ->
            try {
                controller.send(Frame.Text(messageJson))
            } catch (e: Exception) {
                println("Failed to send to controller: ${e.message}")
            }
        }
    }

    private suspend fun sendToApp(key: String, message: Message): Boolean {
        val session = sessions[key] ?: return false
        val app = session.connection ?: return false

        try {
            app.send(Frame.Text(json.encodeToString(message)))
            return true
        } catch (e: Exception) {
            println("Failed to send to app: ${e.message}")
            return false
        }
    }

    suspend fun handleAppConnection(
        existingKey: String?,
        socket: DefaultWebSocketSession
    ): Message.AppConnectResponse {
        // If existing key is provided and valid, use it
        if (existingKey != null) {
            val session = sessions[existingKey]
            if (session != null) {
                // If the key exists but another app is connected
                if (session.connection != null) {
                    val newKey = generateKey()
                    sessions[newKey] = AppSession(key = newKey, connection = socket)
                    return Message.AppConnectResponse(newKey, true)
                }
                // Key exists and no app connected, reuse it
                session.connection = socket
                return Message.AppConnectResponse(existingKey, true)
            } else {
                val newSession = AppSession(key = existingKey, connection = socket)
                sessions[existingKey] = newSession
                return Message.AppConnectResponse(existingKey, true)
            }
        }

        val newKey = generateKey()
        sessions[newKey] = AppSession(key = newKey, connection = socket)
        return Message.AppConnectResponse(newKey, true)
    }

    suspend fun handleControllerConnection(
        key: String,
        socket: DefaultWebSocketSession
    ): Boolean {
        val session = sessions[key] ?: return false
        if (session.connection == null) return false

        session.controllers.add(socket)

        try {
            socket.send(Frame.Text(json.encodeToString<Message>(
                Message.PlaybackStateUpdate(
                    isPlaying = session.state.isPlaying,
                    currentPosition = session.state.currentPosition,
                    track = session.state.currentTrack ?: return true
                )
            )))

            socket.send(Frame.Text(json.encodeToString<Message>(
                Message.PlaylistUpdate(
                    tracks = session.state.playlist,
                    currentIndex = session.state.currentIndex
                )
            )))

            socket.send(Frame.Text(json.encodeToString<Message>(
                Message.PlaybackModeUpdate(
                    shuffle = session.state.shuffle,
                    repeatMode = session.state.repeatMode
                )
            )))
        } catch (e: Exception) {
            println("Failed to send initial state to controller: ${e.message}")
        }

        return true
    }

    suspend fun handleAppMessage(key: String, message: Message) {
        val session = sessions[key] ?: return

        when (message) {
            is Message.PlaybackStateUpdate -> {
                session.state = session.state.copy(
                    isPlaying = message.isPlaying,
                    currentPosition = message.currentPosition,
                    currentTrack = message.track
                )
                broadcastToControllers(key, message)
            }

            is Message.PlaylistUpdate -> {
                session.state = session.state.copy(
                    playlist = message.tracks,
                    currentIndex = message.currentIndex
                )
                broadcastToControllers(key, message)
            }

            is Message.PlaybackModeUpdate -> {
                session.state = session.state.copy(
                    shuffle = message.shuffle,
                    repeatMode = message.repeatMode
                )
                broadcastToControllers(key, message)
            }

            is Message.PositionUpdate -> {
                session.state = session.state.copy(
                    currentPosition = message.position
                )
                broadcastToControllers(key, message)
            }

            is Message.VolumeUpdate -> {
                session.state = session.state.copy(
                    volume = message.volume
                )
                broadcastToControllers(key, message)
            }

            else -> {} // Ignore other message types from app
        }
    }

    suspend fun handleControllerMessage(key: String, message: Message) {
        val session = sessions[key] ?: return

        when (message) {
            is Message.PlaybackCommand -> {
                sendToApp(key, message)
            }

            is Message.SeekCommand -> {
                sendToApp(key, message)
            }

            is Message.PlaylistMoveCommand -> {
                sendToApp(key, message)
            }

            is Message.PlaylistRemoveCommand -> {
                sendToApp(key, message)
            }

            is Message.ShuffleCommand -> {
                sendToApp(key, message)
            }

            is Message.RepeatCommand -> {
                sendToApp(key, message)
            }

            is Message.VolumeCommand -> {
                sendToApp(key, message)
            }

            is Message.RequestCurrentState -> {
                handleControllerConnection(key, session.controllers.find { it.isActive } ?: return)
            }

            else -> {} // Ignore other message types from controller
        }
    }

    fun removeConnection(socket: DefaultWebSocketSession) {
        sessions.forEach { (key, session) ->
            if (session.connection == socket) {
                session.connection = null
            }
            session.controllers.remove(socket)
        }
    }

    // Cleanup job for expired sessions
    init {
        scope.launch {
            while (true) {
                delay(24.hours)
                val now = Instant.now()
                sessions.entries.removeIf { (key, session) ->
                    val expired = session.expiresAt.isBefore(now)
                    if (expired) {
                        usedKeys.remove(key)
                    }
                    expired
                }
            }
        }
    }
}