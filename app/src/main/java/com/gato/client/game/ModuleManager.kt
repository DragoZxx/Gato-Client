package com.gato.client.game


import android.content.Context
import android.net.Uri
import com.gato.client.application.AppContext
import com.gato.client.game.module.combat.AntiCrystalModule
import com.gato.client.game.module.combat.GatoAuraModule
import com.gato.client.game.module.combat.GatoAuraXModule
import com.gato.client.game.module.combat.Plus999AuraModule
import com.gato.client.game.module.combat.AntiKnockbackModule
import com.gato.client.game.module.combat.CrystalSmashModule
import com.gato.client.game.module.combat.HitAndRunModule
import com.gato.client.game.module.combat.HitboxModule
import com.gato.client.game.module.combat.TriggerBotModule
import com.gato.client.game.module.effect.EffectsModule
import com.gato.client.game.module.effect.PoseidonModule
import com.gato.client.game.module.particle.ParticlesModule
import com.gato.client.game.module.misc.BaritoneModule
import com.gato.client.game.module.misc.CommandHandlerModule
import com.gato.client.game.module.misc.OffhandModule
import com.gato.client.game.module.misc.PopCounterModule
import com.gato.client.game.module.misc.DesyncModule
import com.gato.client.game.module.misc.TimerModule
import com.gato.client.game.module.misc.FakeDeathModule
import com.gato.client.game.module.misc.FakeXPModule
import com.gato.client.game.module.misc.NoChatModule
import com.gato.client.game.module.motion.NoClipModule
import com.gato.client.game.module.misc.PositionLoggerModule
import com.gato.client.game.module.misc.ReplayModule
import com.gato.client.game.module.visual.TimeShiftModule
import com.gato.client.game.module.visual.WeatherControllerModule
import com.gato.client.game.module.motion.AirJumpModule
import com.gato.client.game.module.motion.AntiAFKModule
import com.gato.client.game.module.motion.AutoWalkModule
import com.gato.client.game.module.motion.BhopModule
import com.gato.client.game.module.motion.BypassFlyModule
import com.gato.client.game.module.motion.FlyModule
import com.gato.client.game.module.motion.HighJumpModule
import com.gato.client.game.module.motion.JetPackModule
import com.gato.client.game.module.motion.SpeedModule
import com.gato.client.game.module.motion.SprintModule
import com.gato.client.game.module.visual.CustomFovModule
import com.gato.client.game.module.visual.ESPModule
import com.gato.client.game.module.visual.FreeCameraModule
import com.gato.client.game.module.visual.NetworkInfoModule
import com.gato.client.game.module.visual.NoHurtCameraModule
import com.gato.client.game.module.visual.PositionDisplayModule
import com.gato.client.game.module.visual.SpeedDisplayModule
import com.gato.client.game.module.visual.WorldStateModule
import com.gato.client.game.module.visual.ZoomModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

object ModuleManager {

    private val _modules: MutableList<Module> = ArrayList()

    val modules: List<Module> = _modules

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        with(_modules) {
            add(FlyModule())
            add(BypassFlyModule())
            add(ESPModule())
            add(ZoomModule())
            add(CustomFovModule())
            add(AirJumpModule())
            add(NoClipModule())
            add(SpeedModule())
            add(JetPackModule())
            add(HighJumpModule())
            add(PoseidonModule())
            add(EffectsModule())
            add(ParticlesModule())
            add(AntiKnockbackModule())
            add(BhopModule())
            add(SprintModule())
            add(NoHurtCameraModule())
            add(AutoWalkModule())
            add(AntiAFKModule())
            add(DesyncModule())
            add(PositionLoggerModule())
            add(PopCounterModule())
            add(OffhandModule())
            add(TimerModule())
            add(FreeCameraModule())
            add(GatoAuraModule())
            add(Plus999AuraModule())
            add(GatoAuraXModule())
            add(AntiCrystalModule())
            add(TimeShiftModule())
            add(WeatherControllerModule())
            add(FakeDeathModule())
            add(FakeXPModule())
            add(HitAndRunModule())
            add(HitboxModule())
            add(CrystalSmashModule())
            add(TriggerBotModule())
            add(NoChatModule())
            add(SpeedDisplayModule())
            add(PositionDisplayModule())
            add(CommandHandlerModule())
            add(NetworkInfoModule())
            add(WorldStateModule())
            add(ReplayModule())
            add(BaritoneModule())
        }
    }

    fun saveConfig() {
        val configsDir = AppContext.instance.filesDir.resolve("configs")
        configsDir.mkdirs()

        val config = configsDir.resolve("UserConfig.json")
        val jsonObject = buildJsonObject {
            put("modules", buildJsonObject {
                _modules.forEach {
                    if (it.private) {
                        return@forEach
                    }
                    put(it.name, it.toJson())
                }
            })
        }

        config.writeText(json.encodeToString(jsonObject))
    }

    fun loadConfig() {
        val configsDir = AppContext.instance.filesDir.resolve("configs")
        configsDir.mkdirs()

        val config = configsDir.resolve("UserConfig.json")
        if (!config.exists()) {
            return
        }

        val jsonString = config.readText()
        if (jsonString.isEmpty()) {
            return
        }

        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        val modules = jsonObject["modules"]!!.jsonObject
        _modules.forEach { module ->
            (modules[module.name] as? JsonObject)?.let {
                module.fromJson(it)
            }
        }
    }

    fun exportConfig(): String {
        val jsonObject = buildJsonObject {
            put("modules", buildJsonObject {
                _modules.forEach {
                    if (it.private) {
                        return@forEach
                    }
                    put(it.name, it.toJson())
                }
            })
        }
        return json.encodeToString(jsonObject)
    }

    fun importConfig(configStr: String) {
        try {
            val jsonObject = json.parseToJsonElement(configStr).jsonObject
            val modules = jsonObject["modules"]?.jsonObject ?: return

            _modules.forEach { module ->
                modules[module.name]?.let {
                    if (it is JsonObject) {
                        module.fromJson(it)
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid config format")
        }
    }

    fun exportConfigToFile(context: Context, fileName: String): Boolean {
        return try {
            val configsDir = context.getExternalFilesDir("configs")
            configsDir?.mkdirs()

            val configFile = File(configsDir, "$fileName.json")
            configFile.writeText(exportConfig())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importConfigFromFile(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val configStr = input.bufferedReader().readText()
                importConfig(configStr)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

}