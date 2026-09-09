package com.gato.relay.listener

import com.gato.relay.GatoRelaySession
import com.gato.relay.util.AuthUtils
import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload
import org.cloudburstmc.protocol.bedrock.packet.*
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils
import org.cloudburstmc.protocol.bedrock.util.JsonUtils
import org.jose4j.json.JsonUtil
import org.jose4j.json.internal.json_simple.JSONObject
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwx.HeaderParameterNames
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi


@Suppress("MemberVisibilityCanBePrivate")
class OnlineLoginPacketListener(
    val gatoRelaySession: GatoRelaySession,
    val fullBedrockSession: StepFullBedrockSession.FullBedrockSession
) : GatoRelayPacketListener {

    private var skinData: JSONObject? = null

    override fun beforeClientBound(packet: BedrockPacket): Boolean {
        if (packet is LoginPacket) {
            if (fullBedrockSession.isExpired) {
                gatoRelaySession.server.disconnect("Your session was expired, you need to delete account then login again in the Gato Client Mobile")
                return true
            }

            println("Handle online login data")

            val jws = JsonWebSignature()
            jws.compactSerialization = packet.clientJwt

            skinData = JSONObject(JsonUtil.parseJson(jws.unverifiedPayload))
            connectServer()
            return true
        }
        return false
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun beforeServerBound(packet: BedrockPacket): Boolean {
        if (packet is NetworkSettingsPacket) {
            val threshold = packet.compressionThreshold
            if (threshold > 0) {
                gatoRelaySession.client!!.setCompression(packet.compressionAlgorithm)
                println("Compression threshold set to $threshold")
            } else {
                gatoRelaySession.client!!.setCompression(PacketCompressionAlgorithm.NONE)
                println("Compression threshold set to 0")
            }

            try {
                val chain = AuthUtils.fetchOnlineChain(fullBedrockSession)
                val skinData =
                    AuthUtils.fetchOnlineSkinData(
                        fullBedrockSession,
                        skinData!!,
                        gatoRelaySession.gatoRelay.remoteAddress!!
                    )

                val loginPacket = LoginPacket()
                loginPacket.protocolVersion = gatoRelaySession.server.codec.protocolVersion
                loginPacket.authPayload = CertificateChainPayload(chain, AuthType.FULL)
                loginPacket.clientJwt = skinData
                gatoRelaySession.serverBoundImmediately(loginPacket)

                println("Login success")
            } catch (e: Throwable) {
                gatoRelaySession.clientBound(DisconnectPacket().apply {
                    setKickMessage(e.toString())
                })
                println("Login failed: $e")
            }

            return true
        }
        if (packet is ServerToClientHandshakePacket) {
            val jws = JsonWebSignature().apply {
                compactSerialization = packet.jwt
            }

            val saltJwt = JSONObject(JsonUtil.parseJson(jws.unverifiedPayload))
            val x5u = jws.getHeader(HeaderParameterNames.X509_URL)
            val serverKey = EncryptionUtils.parseKey(x5u)
            val key = EncryptionUtils.getSecretKey(
                fullBedrockSession.mcChain.privateKey, serverKey,
                Base64.decode(JsonUtils.childAsType(saltJwt, "salt", String::class.java))
            )
            gatoRelaySession.client!!.enableEncryption(key)
            println("Encryption enabled")

            gatoRelaySession.serverBoundImmediately(ClientToServerHandshakePacket())
            return true
        }
        return false
    }

    private fun connectServer() {
        gatoRelaySession.gatoRelay.connectToServer {
            println("Connected to server")

            val packet = RequestNetworkSettingsPacket()
            packet.protocolVersion = gatoRelaySession.server.codec.protocolVersion
            gatoRelaySession.serverBoundImmediately(packet)
        }
    }

}