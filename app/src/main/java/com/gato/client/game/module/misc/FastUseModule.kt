package com.gato.client.game.module.misc

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Port of the GatoClient (PC) FastUse module — spams "use item" on the held
 * throwable so items like XP bottles throw at very high speed.
 *
 * PC reference: Player/FastUse.cpp (baseUseItem on right-click hold per tick).
 *
 * Relay mapping: the relay cannot hold a right click, but it can emit the
 * ITEM_USE inventory transactions the client would emit — with the item
 * currently in hand (from the tracked inventory). The server processes each
 * throw and syncs the inventory back, so counts stay consistent.
 * Only throws when the held item is a known throwable.
 */
class FastUseModule : Module("FastUse", ModuleCategory.Misc) {

    // --- Settings: same names/defaults/ranges as the PC ctor ---
    private var delay by intValue("Delay", 1, 0..20)
    private var usePerTick by intValue("UsePerTick", 1, 1..64)
    private var onlyXP by boolValue("OnlyXP", false)

    private val throwables = setOf(
        "minecraft:experience_bottle",
        "minecraft:snowball",
        "minecraft:ender_pearl",
        "minecraft:egg",
        "minecraft:splash_potion",
        "minecraft:lingering_potion"
    )

    private var tickDelay = 0

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val packet = interceptablePacket.packet as? PlayerAuthInputPacket ?: return

        val inventory = session.localPlayer.inventory
        val hand = inventory.hand
        val identifier = hand.definition?.identifier ?: return
        if (onlyXP && identifier != "minecraft:experience_bottle") return
        if (identifier !in throwables) return

        if (tickDelay >= delay) {
            repeat(usePerTick) {
                session.serverBound(InventoryTransactionPacket().apply {
                    transactionType = InventoryTransactionType.ITEM_USE
                    actionType = USE_ITEM_ACTION
                    runtimeEntityId = session.localPlayer.runtimeEntityId
                    hotbarSlot = inventory.heldItemSlot
                    itemInHand = hand
                    playerPosition = session.localPlayer.vec3Position
                    clickPosition = Vector3f.ZERO
                    blockPosition = org.cloudburstmc.math.vector.Vector3i.from(0, 0, 0)
                    blockFace = -1
                })
            }
            tickDelay = 0
        } else {
            tickDelay++
        }
    }

    private companion object {
        // ItemUseInventoryTransaction action type: 2 = "use item" (throw/release)
        const val USE_ITEM_ACTION = 2
    }
}
