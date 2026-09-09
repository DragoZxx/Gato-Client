package com.gato.relay.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.gato.relay.GatoRelay
import com.gato.relay.GatoRelaySession
import com.gato.relay.address.GatoAddress
import net.lenni0451.commons.httpclient.HttpClient
import net.lenni0451.commons.httpclient.retry.RetryConfig
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService
import org.cloudburstmc.protocol.bedrock.BedrockPong
import java.io.File
import java.nio.file.Paths
import java.util.function.Consumer

/**
 * Game version reported to the Minecraft session service during login.
 * Kept in sync with the relay's default codec (the target Bedrock version).
 */
val MINECRAFT_GAME_VERSION: String = GatoRelay.DefaultCodec.minecraftVersion

fun captureGamePacket(
    advertisement: BedrockPong = GatoRelay.DefaultAdvertisement,
    localAddress: GatoAddress = GatoAddress("0.0.0.0", 19132),
    remoteAddress: GatoAddress,
    onSessionCreated: GatoRelaySession.() -> Unit
): GatoRelay {
    return GatoRelay(
        localAddress = localAddress,
        advertisement = advertisement
    ).capture(
        remoteAddress = remoteAddress,
        onSessionCreated = onSessionCreated
    )
}

private fun createAuthHttpClient(): HttpClient {
    val httpClient = MinecraftAuth.createHttpClient()
    httpClient.connectTimeout = 30000
    httpClient.readTimeout = 30000
    httpClient.setRetryHandler(RetryConfig(3, 100))
    return httpClient
}

/**
 * Device-code login against the new Bedrock token authentication
 * (MinecraftAuth v5 BedrockAuthManager). The callback receives the
 * MsaDeviceCode so the UI can open the verification URL.
 */
fun authorize(
    cache: Boolean = true,
    file: File? = Paths.get(".").resolve("bedrockSession.json").toFile(),
    msaDeviceCodeCallback: Consumer<MsaDeviceCode> = Consumer {
        println("Go to ${it.directVerificationUri}")
    }
): BedrockAuthManager {
    val httpClient = createAuthHttpClient()

    if (cache && file != null && file.exists()) {
        val json = JsonParser.parseString(file.readText()).asJsonObject
        return BedrockAuthManager.fromJson(httpClient, MINECRAFT_GAME_VERSION, json)
    }

    val authManager = BedrockAuthManager.create(httpClient, MINECRAFT_GAME_VERSION)
        .login(::DeviceCodeMsaAuthService, msaDeviceCodeCallback)

    if (cache && file != null && !file.isDirectory) {
        val json = BedrockAuthManager.toJson(authManager)
        file.writeText(AuthUtils.gson.toJson(json))
    }

    return authManager
}

/**
 * Lazily refreshes the underlying tokens (MSA first; the XBL/session chain
 * holders refresh themselves when their values are requested).
 */
fun BedrockAuthManager.refresh(): BedrockAuthManager {
    this.msaToken.refreshIfExpired()
    return this
}

val BedrockAuthManager.isExpired: Boolean
    get() = !this.msaToken.hasValue() || this.msaToken.isExpired
