package com.gato.client.game.module.misc

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.ListItem
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket
import com.gato.client.game.inventory.PlayerInventory

/**
 * Port of the GatoClient (PC) AutoTool — switches to the most effective tool
 * when performing an action.
 *
 * PC reference: Player/AutoTool.cpp — reads the block under the crosshair
 * (memory hit result), scores each hotbar item with the engine's
 * getDestroySpeed(block) and switches selectedSlot; restores on release.
 *
 * Relay mapping (documented deviation): the relay sees the block being mined
 * only as a POSITION (PlayerActionPacket START_BREAK/ABORT/STOP) — the block
 * TYPE is not visible without voxel access, so tool selection works by the
 * user-chosen tool class (highest tier of that class in the hotbar). The swap
 * is a real inventory transaction into the held slot (server + client UI stay
 * in sync), and the original item is swapped back when the break ends.
 */
class AutoToolModule : Module("AutoTool", ModuleCategory.Misc) {

    private class ToolClass(override val name: String) : ListItem

    private val toolClasses = listOf(
        ToolClass("Pickaxe"), ToolClass("Axe"), ToolClass("Shovel")
    )

    private var modeItem by listValue("Tool", toolClasses[0], toolClasses.toSet())

    private val tiers = listOf("netherite_", "diamond_", "iron_", "golden_", "stone_", "wooden_")

    private var restoreSlot = -1
    private var restoreIdentifier: String? = null

    private val mode get() = (modeItem as ToolClass).name
    private val suffix: String get() = "_" + mode.lowercase()

    private fun toolScore(identifier: String?): Float {
        if (identifier == null || !identifier.endsWith(suffix)) return 0f
        tiers.forEachIndexed { index, tier ->
            if (identifier.startsWith(tier)) return (tiers.size - index).toFloat()
        }
        return 0f
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val packet = interceptablePacket.packet
        if (packet !is PlayerActionPacket) return
        if (packet.runtimeEntityId != session.localPlayer.runtimeEntityId) return

        val inventory = session.localPlayer.inventory

        when (packet.action) {
            PlayerActionType.START_BREAK -> {
                if (restoreSlot != -1) return // already swapped for this break

                val held = inventory.heldItemSlot
                restoreIdentifier = inventory.content[held].definition?.identifier

                var bestSlot = held
                var bestScore = toolScore(inventory.content[held].definition?.identifier)
                for (i in 0..8) {
                    val score = toolScore(inventory.content[i].definition?.identifier)
                    if (score > bestScore) {
                        bestScore = score
                        bestSlot = i
                    }
                }

                if (bestSlot != held) {
                    restoreSlot = held
                    inventory.moveItem(bestSlot, held, inventory, session)
                }
            }

            PlayerActionType.ABORT_BREAK, PlayerActionType.STOP_BREAK -> {
                restoreOriginal(inventory)
            }

            else -> {}
        }
    }

    private fun restoreOriginal(inventory: PlayerInventory) {
        if (restoreSlot == -1) return

        val held = inventory.heldItemSlot
        if (inventory.content[held].definition?.identifier != restoreIdentifier) {
            val original = inventory.searchForItem(0 until 9) {
                it.definition?.identifier == restoreIdentifier
            }
            if (original != null && original != held) {
                inventory.moveItem(original, held, inventory, session)
            }
        }
        restoreSlot = -1
        restoreIdentifier = null
    }

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated) {
            restoreOriginal(session.localPlayer.inventory)
        }
        restoreSlot = -1
        restoreIdentifier = null
    }
}
