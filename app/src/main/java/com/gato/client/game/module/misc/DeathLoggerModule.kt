package com.gato.client.game.module.misc

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.floor

/**
 * Port of the GatoClient (PC) DeathLogger — logs the position where you died
 * so you can recover your items.
 *
 * PC reference: Player/DeathLogger.h — tracks the last feet position per tick
 * and prints it when getDeathTime() becomes 1.
 *
 * Relay equivalent: the death signal is the client-bound
 * EntityEventPacket(DEATH) for the local player; the logged position is the
 * last one reported by the player's own auth input.
 */
class DeathLoggerModule : Module("DeathLogger", ModuleCategory.Misc) {

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isSessionCreated) return

        val packet = interceptablePacket.packet
        when (packet) {
            is PlayerAuthInputPacket -> {
                lastX = packet.position.x
                lastY = packet.position.y
                lastZ = packet.position.z
            }

            is EntityEventPacket -> {
                if (!isEnabled) return
                if (packet.type != EntityEventType.DEATH) return
                if (packet.runtimeEntityId != session.localPlayer.runtimeEntityId) return

                val fx = floor(lastX)
                val fy = floor(lastY)
                val fz = floor(lastZ)
                session.displayClientMessage(
                    "§7DeathLogger: §fposición guardada §7(§f$fx§7, §f$fy§7, §f$fz§7)."
                )
            }
        }
    }
}
