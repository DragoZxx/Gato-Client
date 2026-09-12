package com.gato.client.game.module.misc

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

/**
 * Port of the GatoClient (PC) NoSwing — disables the arm swing animation.
 *
 * PC reference: Player/NoSwing.cpp (empty body on PC: the memory hook
 * suppresses the swing at the engine level). The relay equivalent intercepts
 * the outgoing AnimatePacket(SWING_ARM) from the real client (and modules
 * that call swing()) so the swing animation/arm packet never reaches the
 * server or the client's view.
 */
class NoSwingModule : Module("NoSwing", ModuleCategory.Misc) {

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val packet = interceptablePacket.packet
        if (packet is AnimatePacket &&
            packet.action == AnimatePacket.Action.SWING_ARM &&
            packet.runtimeEntityId == session.localPlayer.runtimeEntityId
        ) {
            interceptablePacket.intercept()
        }
    }
}
