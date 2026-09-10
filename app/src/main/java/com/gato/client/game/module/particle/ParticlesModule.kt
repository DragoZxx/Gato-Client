package com.gato.client.game.module.particle

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.ListItem
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Consolidation of the 7 individual particle modules (cosmetic, client-side
 * only — same mechanism, only the particle event changed): pick the particle
 * from a list and it spawns around the player on the configured interval.
 */
class ParticlesModule : Module("particles", ModuleCategory.Particle) {

    private class ParticleChoice(
        override val name: String,
        val levelEvent: LevelEvent? = null,
        val entityEvent: EntityEventType? = null
    ) : ListItem

    private val choices: List<ParticleChoice> = listOf(
        ParticleChoice("Breeze Wind Explosion", levelEvent = LevelEvent.PARTICLE_BREEZE_WIND_EXPLOSION),
        ParticleChoice("Bubbles", levelEvent = LevelEvent.PARTICLE_BUBBLES),
        ParticleChoice("Dust", entityEvent = EntityEventType.DUST_PARTICLES),
        ParticleChoice("Explosion", levelEvent = LevelEvent.PARTICLE_EXPLOSION),
        ParticleChoice("Ender Eye Death", levelEvent = LevelEvent.PARTICLE_EYE_OF_ENDER_DEATH),
        ParticleChoice("Fizz", levelEvent = LevelEvent.PARTICLE_FIZZ_EFFECT),
        ParticleChoice("Hearts", entityEvent = EntityEventType.LOVE_PARTICLES)
    )

    private var particleItem by listValue("particle", choices[6], choices.toSet())
    private val intervalValue by intValue("interval", 500, 100..2000)
    private val particleCount by intValue("count", 1, 1..10)
    private val offsetY by floatValue("height_offset", 1.0f, -2.0f..5.0f)
    private val particleSize by floatValue("size", 1.0f, 0.1f..5.0f)
    private val randomOffset by boolValue("random_offset", false)
    private val offsetRadius by floatValue("offset_radius", 1.0f, 0.1f..5.0f)

    private var lastParticleTime = 0L

    private val particle: ParticleChoice get() = particleItem as ParticleChoice

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) {
            return
        }

        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket) {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastParticleTime >= intervalValue) {
                lastParticleTime = currentTime

                repeat(particleCount) {
                    val offsetX = if (randomOffset) (Math.random() * 2 - 1) * offsetRadius else 0.0
                    val offsetZ = if (randomOffset) (Math.random() * 2 - 1) * offsetRadius else 0.0

                    val levelEvent = particle.levelEvent
                    val entityEvent = particle.entityEvent

                    if (levelEvent != null) {
                        session.clientBound(LevelEventPacket().apply {
                            type = levelEvent
                            position = Vector3f.from(
                                packet.position.x + offsetX.toFloat(),
                                packet.position.y + offsetY,
                                packet.position.z + offsetZ.toFloat()
                            )
                            data = (particleSize * 1000).toInt()
                        })
                    } else if (entityEvent != null) {
                        session.clientBound(EntityEventPacket().apply {
                            runtimeEntityId = session.localPlayer.runtimeEntityId
                            type = entityEvent
                            data = 0
                        })
                    }
                }
            }
        }
    }
}
