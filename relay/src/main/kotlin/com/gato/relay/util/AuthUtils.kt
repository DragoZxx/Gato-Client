package com.gato.relay.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.gato.relay.address.GatoAddress
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager
import org.jose4j.json.internal.json_simple.JSONObject
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.NumericDate
import org.jose4j.jwx.HeaderParameterNames
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Suppress("SpellCheckingInspection")
object AuthUtils {

    private const val MOJANG_PUBLIC_KEY =
        "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAECRXueJeTDqNRRgJi/vlRufByu/2G0i2Ebt6YMar5QX/R0DIIyrJMcUpruK4QveTfJSTp3Shlq4Gk34cD/4GUWwkv0DVuzeuB+tXija7HBxii03NHDbPAD0AKnLr2wdAp"

    private var mojangPublicKey = fetchMojangPublicKey()

    val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    @OptIn(ExperimentalEncodingApi::class)
    fun fetchOfflineChain(keyPair: KeyPair, extraData: JSONObject, chain: List<String>): List<String> {
        val publicKeyBase64: String = Base64.encode(keyPair.public.encoded)

        val timestamp = System.currentTimeMillis()
        val nbf = Date(timestamp - TimeUnit.SECONDS.toMillis(1))
        val exp = Date(timestamp + TimeUnit.DAYS.toMillis(1))

        val claimsSet = JwtClaims()
        claimsSet.notBefore = NumericDate.fromMilliseconds(nbf.time)
        claimsSet.expirationTime = NumericDate.fromMilliseconds(exp.time)
        claimsSet.issuedAt = NumericDate.fromMilliseconds(exp.time)
        claimsSet.issuer = "self"
        claimsSet.setClaim("certificateAuthority", true)
        claimsSet.setClaim("extraData", extraData)
        claimsSet.setClaim("identityPublicKey", publicKeyBase64)

        val jws = JsonWebSignature()
        jws.payload = claimsSet.toJson()
        jws.key = keyPair.private
        jws.algorithmHeaderValue = "ES384"
        jws.setHeader(HeaderParameterNames.X509_URL, publicKeyBase64)

        return buildList {
            addAll(chain.dropLast(1))
            add(jws.compactSerialization)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun fetchOfflineSkinData(keyPair: KeyPair, skinData: JSONObject): String {
        val publicKeyBase64: String = Base64.encode(keyPair.public.encoded)

        val jws = JsonWebSignature()
        jws.algorithmHeaderValue = "ES384"
        jws.setHeader(HeaderParameterNames.X509_URL, publicKeyBase64)
        jws.payload = skinData.toJSONString()
        jws.key = keyPair.private

        return jws.compactSerialization
    }

    /**
     * The online login token for the new Bedrock token authentication: a
     * single Mojang-signed JWT bound to the account's session key pair
     * (sent as a TokenPayload with AuthType.FULL).
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun fetchOnlineToken(authManager: BedrockAuthManager): String {
        val certificateChain = authManager.minecraftCertificateChain.upToDate
        // touch the XBL/XSTS chain too so everything is refreshed up front
        authManager.bedrockXstsToken.upToDate
        return certificateChain.mojangJwt
    }

    @OptIn(ExperimentalEncodingApi::class, ExperimentalUuidApi::class)
    fun fetchOnlineSkinData(
        authManager: BedrockAuthManager,
        skinData: JSONObject,
        remoteAddress: GatoAddress
    ): String {
        val publicKeyBase64 = Base64.encode(authManager.sessionKeyPair.public.encoded)

        val overridedData = HashMap<String, Any>()
        overridedData["PlayFabId"] = authManager.playFabToken.upToDate.entityId.lowercase(Locale.ROOT)
        overridedData["DeviceId"] = Uuid.random().toString()
        overridedData["DeviceOS"] = 1
        overridedData["ThirdPartyName"] =
            authManager.minecraftCertificateChain.upToDate.identityDisplayName
        overridedData["ServerAddress"] = "${remoteAddress.hostName}:${remoteAddress.port}"

        skinData.putAll(overridedData)

        val jws = JsonWebSignature()
        jws.algorithmHeaderValue = "ES384"
        jws.setHeader(HeaderParameterNames.X509_URL, publicKeyBase64)
        jws.payload = skinData.toJSONString()
        jws.key = authManager.sessionKeyPair.private

        return jws.compactSerialization
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun fetchMojangPublicKey(): ECPublicKey {
        return KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(MOJANG_PUBLIC_KEY))) as ECPublicKey
    }

}
