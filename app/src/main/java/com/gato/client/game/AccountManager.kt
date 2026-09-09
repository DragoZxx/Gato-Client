package com.gato.client.game

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonParser
import com.gato.client.application.AppContext
import com.gato.relay.util.AuthUtils
import com.gato.relay.util.MINECRAFT_GAME_VERSION
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.lenni0451.commons.httpclient.HttpClient
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager
import java.io.File

object AccountManager {

    private val coroutineScope =
        CoroutineScope(Dispatchers.IO + CoroutineName("AccountManagerCoroutine"))

    private val _accounts: MutableList<BedrockAuthManager> = mutableStateListOf()

    val accounts: List<BedrockAuthManager>
        get() = _accounts

    var selectedAccount: BedrockAuthManager? by mutableStateOf(null)
        private set

    init {
        val fetchedAccounts = fetchAccounts()

        _accounts.addAll(fetchedAccounts)
        selectedAccount = fetchSelectedAccount()
    }

    fun displayNameOf(authManager: BedrockAuthManager): String =
        runCatching {
            authManager.minecraftCertificateChain.upToDate.identityDisplayName
        }.getOrDefault("Account")

    fun addAccount(authManager: BedrockAuthManager) {
        _accounts.add(authManager)

        coroutineScope.launch {
            val file = File(AppContext.instance.cacheDir, "accounts")
            file.mkdirs()

            val json = BedrockAuthManager.toJson(authManager)
            file.resolve("${displayNameOf(authManager)}.json")
                .writeText(AuthUtils.gson.toJson(json))
        }
    }

    fun containsAccount(authManager: BedrockAuthManager): Boolean {
        val name = displayNameOf(authManager)
        return _accounts.find { displayNameOf(it) == name } != null
    }

    fun removeAccount(authManager: BedrockAuthManager) {
        _accounts.remove(authManager)

        coroutineScope.launch {
            val file = File(AppContext.instance.cacheDir, "accounts")
            file.mkdirs()

            file.resolve("${displayNameOf(authManager)}.json")
                .delete()
        }
    }

    fun selectAccount(authManager: BedrockAuthManager?) {
        this.selectedAccount = authManager

        coroutineScope.launch {
            val file = File(AppContext.instance.cacheDir, "accounts")
            file.mkdirs()

            runCatching {
                val selectedAccount = file.resolve("selectedAccount")
                if (authManager != null) {
                    selectedAccount.writeText(displayNameOf(authManager))
                } else {
                    selectedAccount.delete()
                }
            }
        }
    }

    private fun newHttpClient(): HttpClient = MinecraftAuth.createHttpClient()

    private fun fetchAccounts(): List<BedrockAuthManager> {
        val file = File(AppContext.instance.cacheDir, "accounts")
        file.mkdirs()

        val accounts = ArrayList<BedrockAuthManager>()
        val listFiles = file.listFiles() ?: emptyArray()
        for (child in listFiles) {
            runCatching {
                if (child.isFile && child.extension == "json") {
                    val account = BedrockAuthManager.fromJson(
                        newHttpClient(),
                        MINECRAFT_GAME_VERSION,
                        JsonParser.parseString(child.readText()).asJsonObject
                    )
                    accounts.add(account)
                }
            }
        }

        return accounts
    }

    private fun fetchSelectedAccount(): BedrockAuthManager? {
        val file = File(AppContext.instance.cacheDir, "accounts")
        file.mkdirs()

        val selectedAccount = file.resolve("selectedAccount")
        if (!selectedAccount.exists() || selectedAccount.isDirectory) {
            return null
        }

        val displayName = selectedAccount.readText()
        return accounts.find { runCatching { displayNameOf(it) }.getOrDefault("") == displayName }
    }

}
