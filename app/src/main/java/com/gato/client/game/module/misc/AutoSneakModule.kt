package com.gato.client.game.module.misc

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Port of the GatoClient (PC) AutoSneak — persistent sneak while multitasking.
 *
 * PC reference: Player/AutoSneak.cpp — forces the MoveInputComponent sneaking
 * flag and the Actor Sneaking status flag in memory. The relay equivalent
 * mutates the server-bound PlayerAuthInputPacket: every input set gets the
 * SNEAKING flag (and START_SNEAKING on the transition), so the server sees a
 * permanently sneaking player.
 *
 * Note: the client's own edge-guard physics (not walking off blocks) is driven
 * by its local flag, which the relay cannot set — the server-side sneak state
 * (name opacity, other players' view) is what this module provides.
 */
class AutoSneakModule : Module("AutoSneak", ModuleCategory.Motion) {

    private var wasSneaking = false

    override fun onDisabled() {
        super.onDisabled()
        wasSneaking = false
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val packet = interceptablePacket.packet as? PlayerAuthInputPacket ?: return

        val input = packet.inputData
        val alreadySneaking = input.contains(PlayerAuthInputData.SNEAKING)

        // keep the server-side sneak state sticky
        if (!alreadySneaking) {
            input.add(PlayerAuthInputData.SNEAKING)
        }
        if (!wasSneaking) {
            input.add(PlayerAuthInputData.START_SNEAKING)
            input.remove(PlayerAuthInputData.STOP_SNEAKING)
        }
        wasSneaking = true
    }
}
