package space.privatecanvas.app

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class RelaySpace(val spaceId: String, val keyBase64: String)

sealed interface RelayIncoming {
    data class Item(val spaceId: String, val id: String, val kind: String, val plaintext: String) : RelayIncoming
    data class Ack(val id: String) : RelayIncoming
    data class Presence(val spaceId: String, val count: Int) : RelayIncoming
    data class Failure(val message: String) : RelayIncoming
    data object Connected : RelayIncoming
    data object Disconnected : RelayIncoming
}

class RelayClient(private val onIncoming: (RelayIncoming) -> Unit) {
    private data class Publication(val space: RelaySpace, val id: String, val kind: String, val plaintext: String)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var currentUrl: String = ""
    @Volatile private var currentDeviceId: String = ""
    @Volatile private var spaces: Map<String, RelaySpace> = emptyMap()
    private val queuedPublications = ConcurrentHashMap<String, Publication>()

    fun connect(url: String, deviceId: String, newSpaces: List<RelaySpace>) {
        val normalized = url.trim()
        val nextSpaces = newSpaces.associateBy { it.spaceId }
        val spacesChanged = nextSpaces.keys != spaces.keys
        spaces = nextSpaces
        queuedPublications.forEach { (id, publication) ->
            if (publication.space.spaceId !in nextSpaces) queuedPublications.remove(id, publication)
        }
        currentDeviceId = deviceId
        if (normalized.isBlank()) {
            close()
            onIncoming(RelayIncoming.Failure("SERVER ADDRESS REQUIRED"))
            return
        }
        if (socket != null && currentUrl == normalized && !spacesChanged) {
            joinAll()
            return
        }
        close()
        currentUrl = normalized
        val request = runCatching { Request.Builder().url(normalized).build() }.getOrElse {
            onIncoming(RelayIncoming.Failure("INVALID SERVER ADDRESS"))
            return
        }
        socket = client.newWebSocket(request, Listener())
    }

    fun publish(space: RelaySpace, id: String, kind: String, plaintext: String): Boolean {
        queuedPublications[id] = Publication(space, id, kind, plaintext)
        return sendPublication(queuedPublications.getValue(id))
    }

    private fun sendPublication(publication: Publication): Boolean {
        val (space, id, kind, plaintext) = publication
        val encrypted = RelayCrypto.encrypt(space.keyBase64, space.spaceId, plaintext)
        return socket?.send(JSONObject().apply {
            put("type", "publish")
            put("spaceId", space.spaceId)
            put("id", id)
            put("kind", kind)
            put("iv", encrypted.first)
            put("ciphertext", encrypted.second)
        }.toString()) == true
    }

    fun close() {
        socket?.close(1000, "client refresh")
        socket = null
    }

    private fun joinAll() {
        spaces.values.forEach { space ->
            socket?.send(JSONObject().apply {
                put("type", "join")
                put("spaceId", space.spaceId)
                put("deviceId", currentDeviceId)
                put("accessProof", RelayCrypto.accessProof(space.keyBase64, space.spaceId))
            }.toString())
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            onIncoming(RelayIncoming.Connected)
            joinAll()
            queuedPublications.values.forEach(::sendPublication)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val root = JSONObject(text)
                when (root.optString("type")) {
                    "item" -> {
                        val spaceId = root.getString("spaceId")
                        val space = spaces[spaceId] ?: return
                        val plaintext = RelayCrypto.decrypt(
                            space.keyBase64,
                            spaceId,
                            root.getString("iv"),
                            root.getString("ciphertext"),
                        )
                        onIncoming(RelayIncoming.Item(spaceId, root.getString("id"), root.getString("kind"), plaintext))
                    }
                    "ack" -> {
                        val id = root.getString("id")
                        queuedPublications.remove(id)
                        onIncoming(RelayIncoming.Ack(id))
                    }
                    "presence" -> onIncoming(RelayIncoming.Presence(root.getString("spaceId"), root.optInt("count")))
                    "error" -> onIncoming(RelayIncoming.Failure(root.optString("message", "RELAY ERROR")))
                }
            }.onFailure { onIncoming(RelayIncoming.Failure("SECURE MESSAGE REJECTED")) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) {
                socket = null
                onIncoming(RelayIncoming.Disconnected)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) {
                socket = null
                onIncoming(RelayIncoming.Failure("CAN'T REACH PRIVATE SERVER"))
            }
        }
    }
}

object RelayCrypto {
    fun accessProof(keyBase64: String, spaceId: String): String {
        val key = Base64.decode(keyBase64, Base64.NO_WRAP)
        val digest = MessageDigest.getInstance("SHA-256").digest(key + spaceId.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    fun encrypt(keyBase64: String, spaceId: String, plaintext: String): Pair<String, String> {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.decode(keyBase64, Base64.NO_WRAP), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(spaceId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) to Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decrypt(keyBase64: String, spaceId: String, ivBase64: String, ciphertextBase64: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(Base64.decode(keyBase64, Base64.NO_WRAP), "AES"),
            GCMParameterSpec(128, Base64.decode(ivBase64, Base64.NO_WRAP)),
        )
        cipher.updateAAD(spaceId.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.decode(ciphertextBase64, Base64.NO_WRAP)), Charsets.UTF_8)
    }
}
