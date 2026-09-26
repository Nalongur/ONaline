package space.privatecanvas.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

data class PairingInvite(
    val inviterDeviceId: String,
    val inviterDisplayName: String,
    val identityPublicKey: String,
    val spaceId: String,
    val expiresAtEpochMs: Long,
    val nonce: String,
    val serverUrl: String,
    val spaceKeyBase64: String,
)

data class PairingInviteDisplay(
    val token: String,
    val shortCode: String,
    val expiresAtEpochMs: Long,
)

sealed interface PairingInviteResult {
    data class Valid(val invite: PairingInvite) : PairingInviteResult
    data object Expired : PairingInviteResult
    data object Invalid : PairingInviteResult
}

/** A non-exportable signing identity for this installation. */
class DeviceIdentityManager(context: Context) {
    private val preferences = context.getSharedPreferences("private_canvas_identity", Context.MODE_PRIVATE)

    val deviceId: String = preferences.getString(DEVICE_ID, null) ?: UUID.randomUUID().toString().also { id ->
        preferences.edit { putString(DEVICE_ID, id) }
    }

    val publicKeyBase64: String
        get() = Base64.encodeToString(keyStore().getCertificate(KEY_ALIAS).publicKey.encoded, Base64.NO_WRAP)

    init {
        ensureKeyPair()
    }

    fun createInvite(displayName: String, serverUrl: String, now: Long = System.currentTimeMillis()): PairingInviteDisplay {
        val spaceKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val invite = PairingInvite(
            inviterDeviceId = deviceId,
            inviterDisplayName = normalizeDisplayName(displayName),
            identityPublicKey = publicKeyBase64,
            spaceId = UUID.randomUUID().toString(),
            expiresAtEpochMs = now + INVITE_LIFETIME_MS,
            nonce = UUID.randomUUID().toString(),
            serverUrl = serverUrl.trim(),
            spaceKeyBase64 = Base64.encodeToString(spaceKey, Base64.NO_WRAP),
        )
        val payload = PairingInviteCodec.canonicalPayload(invite)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keyStore().getKey(KEY_ALIAS, null) as java.security.PrivateKey)
            update(payload.toByteArray(Charsets.UTF_8))
            sign()
        }
        val envelope = JSONObject().apply {
            put("payload", payload)
            put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
        }
        val token = Base64.encodeToString(
            envelope.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return PairingInviteDisplay(
            token = "PC1.$token",
            shortCode = PairingInviteCodec.shortCode(token),
            expiresAtEpochMs = invite.expiresAtEpochMs,
        )
    }

    private fun ensureKeyPair() {
        if (keyStore().containsAlias(KEY_ALIAS)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
            initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKeyPair()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val DEVICE_ID = "device_id"
        const val KEY_ALIAS = "private_canvas_identity_signing_v1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val INVITE_LIFETIME_MS = 15 * 60 * 1000L
    }
}

object PairingInviteCodec {
    fun decodeAndVerify(token: String, now: Long = System.currentTimeMillis()): PairingInviteResult = runCatching {
        val encoded = token.trim().removePrefix("PC1.")
        val envelopeBytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))
        val payload = envelope.getString("payload")
        val payloadJson = JSONObject(payload)
        if (payloadJson.optInt("version") != 1) return PairingInviteResult.Invalid
        val invite = PairingInvite(
            inviterDeviceId = payloadJson.getString("deviceId"),
            inviterDisplayName = normalizeDisplayName(payloadJson.getString("displayName")),
            identityPublicKey = payloadJson.getString("publicKey"),
            spaceId = payloadJson.getString("spaceId"),
            expiresAtEpochMs = payloadJson.getLong("expiresAt"),
            nonce = payloadJson.getString("nonce"),
            serverUrl = payloadJson.getString("serverUrl"),
            spaceKeyBase64 = payloadJson.getString("spaceKey"),
        )
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(invite.identityPublicKey, Base64.NO_WRAP)),
        )
        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(payload.toByteArray(Charsets.UTF_8))
            verify(Base64.decode(envelope.getString("signature"), Base64.NO_WRAP))
        }
        when {
            !verified -> PairingInviteResult.Invalid
            invite.expiresAtEpochMs < now -> PairingInviteResult.Expired
            else -> PairingInviteResult.Valid(invite)
        }
    }.getOrElse { PairingInviteResult.Invalid }

    fun canonicalPayload(invite: PairingInvite): String = JSONObject().apply {
        put("version", 1)
        put("deviceId", invite.inviterDeviceId)
        put("displayName", invite.inviterDisplayName)
        put("publicKey", invite.identityPublicKey)
        put("spaceId", invite.spaceId)
        put("expiresAt", invite.expiresAtEpochMs)
        put("nonce", invite.nonce)
        put("serverUrl", invite.serverUrl)
        put("spaceKey", invite.spaceKeyBase64)
    }.toString()

    fun shortCode(tokenBody: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(tokenBody.toByteArray(Charsets.UTF_8))
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        var value = digest.take(8).fold(0UL) { acc, byte -> (acc shl 8) or byte.toUByte().toULong() }
        val body = buildString {
            repeat(12) {
                append(alphabet[(value % alphabet.length.toUInt()).toInt()])
                value /= alphabet.length.toUInt()
            }
        }
        return "${body.take(4)}-${body.drop(4).take(4)}-${body.drop(8)}"
    }

    fun safetyCode(localPublicKey: String, remotePublicKey: String, spaceId: String): String {
        val ordered = listOf(localPublicKey, remotePublicKey).sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest("$spaceId|$ordered".toByteArray(Charsets.UTF_8))
        val digits = digest.take(6).joinToString("") { byte -> "%03d".format(byte.toUByte().toInt()) }.take(12)
        return digits.chunked(4).joinToString(" ")
    }
}
