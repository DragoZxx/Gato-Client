package com.gato.client.game.module.effect

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.ListItem
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import com.gato.client.game.data.Effect
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Consolidation of the 28 individual effect modules (identical mechanism,
 * only the effect id changed): pick the effect from a list, same
 * MobEffectPacket(ADD) every second and MobEffectPacket(REMOVE) on disable.
 * Effects applied this way are client-side only (the server does not grant
 * them) — useful for the visual ones (Night Vision, etc.).
 */
class EffectsModule : Module("effects", ModuleCategory.Effect) {

    private class EffectChoice(override val name: String, val id: Int) : ListItem

    private val choices: List<EffectChoice> = listOf(
        EffectChoice("Absorption", Effect.ABSORPTION),
        EffectChoice("Bad Omen", Effect.BAD_OMEN),
        EffectChoice("Blindness", Effect.BLINDNESS),
        EffectChoice("Conduit Power", Effect.CONDUIT_POWER),
        EffectChoice("Darkness", Effect.DARKNESS),
        EffectChoice("Fatal Poison", Effect.FATAL_POISON),
        EffectChoice("Fire Resistance", Effect.FIRE_RESISTANCE),
        EffectChoice("Haste", Effect.HASTE),
        EffectChoice("Health Boost", Effect.HEALTH_BOOST),
        EffectChoice("Hunger", Effect.HUNGER),
        EffectChoice("Instant Damage", Effect.INSTANT_DAMAGE),
        EffectChoice("Instant Health", Effect.INSTANT_HEALTH),
        EffectChoice("Invisibility", Effect.INVISIBILITY),
        EffectChoice("Jump Boost", Effect.JUMP_BOOST),
        EffectChoice("Levitation", Effect.LEVITATION),
        EffectChoice("Mining Fatigue", Effect.MINING_FATIGUE),
        EffectChoice("Nausea", Effect.NAUSEA),
        EffectChoice("Night Vision", Effect.NIGHT_VISION),
        EffectChoice("Poison", Effect.POISON),
        EffectChoice("Regeneration", Effect.REGENERATION),
        EffectChoice("Resistance", Effect.RESISTANCE),
        EffectChoice("Saturation", Effect.SATURATION),
        EffectChoice("Slow Falling", Effect.SLOW_FALLING),
        EffectChoice("Strength", Effect.STRENGTH),
        EffectChoice("Swiftness", Effect.SWIFTNESS),
        EffectChoice("Village Hero", Effect.VILLAGE_HERO),
        EffectChoice("Weakness", Effect.WEAKNESS),
        EffectChoice("Wither", Effect.WITHER)
    )

    private var effectItem by listValue("effect", choices[17], choices.toSet()) // Night Vision default
    private val amplifierValue by floatValue("amplifier", 1f, 1f..5f)

    private val effectId: Int get() = (effectItem as EffectChoice).id

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated) {
            session.clientBound(MobEffectPacket().apply {
                runtimeEntityId = session.localPlayer.runtimeEntityId
                event = MobEffectPacket.Event.REMOVE
                effectId = this@EffectsModule.effectId
            })
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) {
            return
        }

        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket) {
            if (session.localPlayer.tickExists % 20 == 0L) {
                session.clientBound(MobEffectPacket().apply {
                    runtimeEntityId = session.localPlayer.runtimeEntityId
                    event = MobEffectPacket.Event.ADD
                    effectId = this@EffectsModule.effectId
                    amplifier = amplifierValue.toInt() - 1
                    isParticles = false
                    duration = 360000
                })
            }
        }
    }
}
