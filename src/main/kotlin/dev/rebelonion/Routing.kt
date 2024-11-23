package dev.rebelonion

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

fun Application.configureRouting(server: MusicControlServer) {
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        isLenient = true
        encodeDefaults = true
    }

    routing {
        webSocket("/ws") {
            var sessionKey: String? = null
            var isApp = false

            try {
                withTimeout(30.seconds) {
                    handleInitialConnection(json, server) { key, appStatus ->
                        sessionKey = key
                        isApp = appStatus
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is TimeoutCancellationException -> {
                        application.log.warn("Connection timed out waiting for initial message")
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Connection timeout"))
                    }

                    else -> {
                        application.log.error("Error during initial connection: ${e.message}")
                        close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Invalid initial message"))
                    }
                }
                return@webSocket
            }

            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()

                            // Handle message parsing errors
                            val message = try {
                                json.decodeFromString<Message>(text)
                            } catch (e: SerializationException) {
                                application.log.error("Failed to parse message: ${e.message}")
                                sendErrorMessage("Invalid message format")
                                continue
                            } catch (e: Exception) {
                                application.log.error("Unexpected error parsing message: ${e.message}")
                                continue
                            }

                            if (!isValidMessageForConnectionType(message, isApp)) {
                                application.log.warn("Invalid message type for connection: ${message::class.simpleName}")
                                sendErrorMessage("Invalid message type for this connection")
                                continue
                            }

                            try {
                                sessionKey?.let { key ->
                                    when {
                                        isApp -> server.handleAppMessage(key, message)
                                        else -> server.handleControllerMessage(key, message)
                                    }
                                }
                            } catch (e: Exception) {
                                application.log.error("Error processing message: ${e.message}")
                                sendErrorMessage("Failed to process message")
                            }
                        }

                        is Frame.Close -> {
                            application.log.info("Received close frame")
                            break
                        }

                        is Frame.Ping -> {
                            send(Frame.Pong(frame.data))
                        }

                        else -> {
                            application.log.warn("Received unexpected frame type: ${frame::class.simpleName}")
                        }
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                application.log.info("WebSocket closed normally")
            } catch (e: Exception) {
                application.log.error("Error handling connection: ${e.message}")
                try {
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Internal server error"))
                } catch (closeError: Exception) {
                    application.log.error("Error during connection closure: ${closeError.message}")
                }
            } finally {
                try {
                    server.removeConnection(this)
                } catch (e: Exception) {
                    application.log.error("Error removing connection: ${e.message}")
                }
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleInitialConnection(
    json: Json,
    server: MusicControlServer,
    onSuccess: (String, Boolean) -> Unit
): Boolean {
    val frame = incoming.receive()
    if (frame !is Frame.Text) {
        throw IllegalStateException("Expected text frame for initial connection")
    }

    val text = frame.readText()
    return when (val message = json.decodeFromString<Message>(text)) {
        is Message.AppConnect -> {
            val response = server.handleAppConnection(message.existingKey, this)
            outgoing.send(Frame.Text(json.encodeToString(Message.serializer(), response)))
            if (response.success) {
                onSuccess(response.key, true)
                true
            } else {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Connection rejected"))
                false
            }
        }

        is Message.ControllerConnect -> {
            val success = server.handleControllerConnection(message.key, this)
            if (success) {
                onSuccess(message.key, false)
                true
            } else {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid key"))
                false
            }
        }

        else -> {
            close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Invalid initial message"))
            false
        }
    }
}

private fun isValidMessageForConnectionType(message: Message, isApp: Boolean): Boolean {
    return when {
        isApp -> message !is Message.ControllerConnect &&
                message !is Message.PlaybackCommand &&
                message !is Message.SeekCommand &&
                message !is Message.PlaylistMoveCommand &&
                message !is Message.PlaylistRemoveCommand &&
                message !is Message.ShuffleCommand &&
                message !is Message.RepeatCommand &&
                message !is Message.VolumeCommand &&
                message !is Message.RequestCurrentState

        else -> message !is Message.AppConnect &&
                message !is Message.PlaybackStateUpdate &&
                message !is Message.PlaylistUpdate &&
                message !is Message.PlaybackModeUpdate &&
                message !is Message.PositionUpdate &&
                message !is Message.VolumeUpdate
    }
}

private suspend fun DefaultWebSocketServerSession.sendErrorMessage(message: String) {
    try {
        send(
            Frame.Text(
                Json.encodeToString(
                    Message.serializer(), Message.ErrorMessage(
                        code = Message.ErrorMessage.ErrorCode.INVALID_COMMAND,
                        message = message
                    )
                )
            )
        )
    } catch (e: Exception) {
        // Ignore send errors for error messages
    }
}