package com.gato.client.game.module.misc

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.ListItem
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import com.gato.client.game.inventory.PlayerInventory
import com.gato.client.util.RelayLog
import com.gato.relay.definition.Definitions
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket

/**
 * Port of the GatoClient (PC) Offhand module (the AutoTotem) with a
 * relay-safe Slot mode.
 *
 * PC reference: Client/Managers/ModuleManager/Modules/Category/Player/Offhand.cpp
 *
 * Two swap modes:
 * - **Identifier** (PC-faithful): identifies the totem/shield by definition
 *   name — requires the item registry to resolve.
 * - **Slot** (relay-safe, default): swaps whatever sits in the chosen hotbar
 *   slot into the offhand WITHOUT identifying it — the server knows what's in
 *   each slot. Drop the totem in that slot and it moves on its own.
 *
 * Smart mode adds health hysteresis (swap to shield when hurt, back to totem
 * when healed). The swap is PlayerInventory.moveItem into SLOT_OFFHAND:
 * ItemStackRequest place/swap for server-auth inventories, InventoryTransaction
 * for legacy, with client UI sync.
 */
class OffhandModule : Module("Offhand", ModuleCategory.Misc) {

    private class Mode(override val name: String, val idx: Int) : ListItem

    private val itemModes = listOf(Mode("Totem", 0), Mode("Shield", 1))
    private val swapModes = listOf(Mode("Identifier", 0), Mode("Slot", 1))

    // --- Settings ---
    private var itemItem by listValue("Item", itemModes[0], itemModes.toSet())
    private var swapModeItem by listValue("Swap Mode", swapModes[1], swapModes.toSet())
    private var sourceSlotItem by intValue("Source Slot", 8, 0..8)
    private var delay by intValue("Delay", 0, 0..20)
    private var smart by boolValue("Smart", false)
    private var swapToHealth by floatValue("Swap Totem", 0f, 0f..20f)
    private var swapBack by floatValue("Swap Shield", 0f, 0f..20f)
    private var surroundOnly by boolValue("SurroundOnly", false)
    private var debug by boolValue("Debug", false)
    private var legacySwap by boolValue("Legacy Swap", true)

    // accessors over the named selectors
    private val itemModeIdx get() = (itemItem as Mode).idx
    private val swapModeIdx get() = (swapModeItem as Mode).idx

    init {
        getValue("Swap Totem")?.visibleIf = { smart }
        getValue("Swap Shield")?.visibleIf = { smart }
        getValue("SurroundOnly")?.visibleIf = { smart }
        getValue("Debug")?.visibleIf = { smart }
        getValue("Item")?.visibleIf = { (swapModeItem as Mode).idx == 0 }
        getValue("Source Slot")?.visibleIf = { (swapModeItem as Mode).idx == 1 }
    }

    private val TOTEM = "minecraft:totem_of_undying"
    private val SHIELD = "minecraft:shield"

    private var shouldWeSwap = false
    private var swapDelay = 0
    private var health = 20f
    private var horizontalCollision = false

    // === captured manual-swap template (the relay-safe swap mechanism) ===
    private class CapturedSwap(val isPlace: Boolean, val srcType: ContainerSlotType, val srcSlot: Int)
    private var captured: CapturedSwap? = null
    private var replayPending = false
    private var replayDelayTicks = 0

    private fun itemName(item: ItemData): String? =
    item.definition?.identifier ?: itemPalette[item.runtimeId]?.identifier

    /**
     * Replays the user's own manual offhand move: an ItemStackRequest with the
     * exact captured structure (source container type + slot, destination
     * OFFHAND) but with the CURRENT netIds from the inventory mirror. The
     * server accepted the original — the replay is the same request with
     * refreshed references.
     */
    private fun replayCapturedSwap() {
        val template = captured ?: run {
            RelayLog.log("[Offhand] replay: no captured swap template yet — move the totem to the offhand manually once")
            return
        }
        val inventory = session.localPlayer.inventory

        val totemSlot = inventory.searchForItem(0 until 36) {
            itemName(it) == TOTEM
        } ?: run {
            RelayLog.log("[Offhand] replay: no totem in inventory")
            return
        }

        val srcStack = inventory.content[totemSlot]
        val dstStack = inventory.offhand
        val isPlace = dstStack.netId == 0

        val srcType = if (totemSlot < 9) ContainerSlotType.HOTBAR else ContainerSlotType.INVENTORY
        val srcSlot = if (totemSlot < 9) totemSlot else totemSlot - 9

        val request = ItemStackRequest(inventory.getRequestId(), arrayOf(
            if (isPlace)
                PlaceAction(srcStack.count,
                    ItemStackRequestSlotData(srcType, srcSlot, srcStack.netId,
                        FullContainerName(srcType, if (totemSlot < 9) 0 else ContainerId.INVENTORY)),
                    ItemStackRequestSlotData(ContainerSlotType.OFFHAND, 0, dstStack.netId,
                        FullContainerName(ContainerSlotType.OFFHAND, ContainerId.OFFHAND)))
            else
                SwapAction(
                    ItemStackRequestSlotData(srcType, srcSlot, srcStack.netId,
                        FullContainerName(srcType, if (totemSlot < 9) 0 else ContainerId.INVENTORY)),
                    ItemStackRequestSlotData(ContainerSlotType.OFFHAND, 0, dstStack.netId,
                        FullContainerName(ContainerSlotType.OFFHAND, ContainerId.OFFHAND)))
        ), arrayOf(), null)

        session.localPlayer.inventory.itemStackRequest(request, session)
        RelayLog.log("[Offhand] replayed captured swap: totem slot $totemSlot -> offhand (isPlace=$isPlace, netId=${srcStack.netId})")

        // optimistic client sync
        inventory.content[totemSlot] = dstStack
        inventory.content[40] = srcStack
        session.clientBound(InventorySlotPacket().apply {
            containerId = 0
            slot = totemSlot
            item = dstStack
        })
        session.clientBound(InventoryContentPacket().apply {
            containerId = ContainerId.OFFHAND
            contents = listOf(srcStack)
        })
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        when (val packet = interceptablePacket.packet) {
            is UpdateAttributesPacket -> {
                if (isSessionCreated &&
                    packet.runtimeEntityId == session.localPlayer.runtimeEntityId
                ) {
                    packet.attributes
                        .find { it.name == "minecraft:health" }
                        ?.let { health = it.value }
                }
            }

            is EntityEventPacket -> {
                if (isSessionCreated && isEnabled &&
                    packet.type == EntityEventType.CONSUME_TOTEM &&
                    packet.runtimeEntityId == session.localPlayer.runtimeEntityId
                ) {
                    // totem popped → schedule the refill replay
                    replayPending = true
                    replayDelayTicks = 10
                }
            }

            is PlayerAuthInputPacket -> {
                // capture the user's manual offhand move as the swap template
                packet.itemStackRequest?.actions?.forEach { action ->
                    val dest = when (action) {
                        is PlaceAction -> action.destination
                        is SwapAction -> action.destination
                        else -> null
                    } ?: return@forEach
                    if (dest.container != ContainerSlotType.OFFHAND) return@forEach

                    captured = packet.itemStackRequest.actions.mapNotNull { a ->
                        when (a) {
                            is PlaceAction -> CapturedSwap(true, a.source.container, a.source.slot)
                            is SwapAction -> CapturedSwap(false, a.source.container, a.source.slot)
                            else -> null
                        }
                    }.firstOrNull()
                    RelayLog.log("[Offhand] CAPTURED manual swap template: isPlace=${captured?.isPlace} srcType=${captured?.srcType} srcSlot=${captured?.srcSlot}")
                }
                tick(packet)
            }
        }
    }

    private fun tick(packet: BedrockPacket) {
        if (!isSessionCreated) return
        packet as PlayerAuthInputPacket

        horizontalCollision = packet.inputData.contains(PlayerAuthInputData.HORIZONTAL_COLLISION)

        // replay the captured swap after a totem pop (the offhand emptied)
        if (replayPending) {
            if (replayDelayTicks > 0) {
                replayDelayTicks--
            } else {
                replayPending = false
                replayCapturedSwap()
                return
            }
        }

        if (!isEnabled) return

        if (debug) {
            session.displayClientMessage(health.toString())
        }

        val inventory = session.localPlayer.inventory
        val offhand = inventory.offhand

        // empty check: netId 0 OR explicit air — deserialized air slots can
        // carry a nonzero netId and must not block the swap
        val offhandEmpty = offhand.netId == 0 || itemName(offhand) == "minecraft:air"

        var wantIdentifier: String? = null
        var wantSlot = -1

        if (!smart) {
            if (swapModeIdx == 1) {
                // Slot mode: swap whatever is in the chosen hotbar slot
                if (!offhandEmpty) return
                wantSlot = sourceSlotItem
                if (inventory.content[wantSlot].netId == 0) return
            } else {
                val id = if (itemModeIdx == 0) TOTEM else SHIELD
                if (itemName(offhand) == id) return
                wantIdentifier = id
            }
        } else {
            if (health >= swapBack) shouldWeSwap = true
            if (health <= swapToHealth) shouldWeSwap = false

            val hasShield = inventory.searchForItem { itemName(it) == SHIELD } != null
            val hasTotem = inventory.searchForItem { itemName(it) == TOTEM } != null

            val isSurrounded = if (surroundOnly && shouldWeSwap) horizontalCollision else true

            wantIdentifier = if (shouldWeSwap && isSurrounded && hasShield) SHIELD else TOTEM
            if (itemName(offhand) == wantIdentifier) return
        }

        if (swapDelay < delay) {
            swapDelay++
            return
        }
        swapDelay = 0

        if (wantSlot != -1) {
            val heldContent = inventory.content[wantSlot]
            RelayLog.log("[Offhand] CHECKPOINT swap: offhand(def=${offhand.definition?.identifier}, netId=${offhand.netId}) | " +
                "slot $wantSlot(def=${heldContent.definition?.identifier}, netId=${heldContent.netId}, count=${heldContent.count}) | " +
                "legacy=$legacySwap")
            RelayLog.log("[Offhand] slot-mode: moving slot $wantSlot -> offhand")
            if (legacySwap) {
                // legacy NORMAL transaction: no netIds needed — pure slot + item data
                val totemStack = inventory.content[wantSlot]
                val offhandStack = inventory.offhand
                session.serverBound(InventoryTransactionPacket().apply {
                    transactionType = InventoryTransactionType.NORMAL
                    actions.add(InventoryActionData(
                        InventorySource.fromContainerWindowId(ContainerId.INVENTORY), wantSlot,
                        totemStack, offhandStack))
                    actions.add(InventoryActionData(
                        InventorySource.fromContainerWindowId(ContainerId.OFFHAND), 0,
                        offhandStack, totemStack))
                })
                // optimistic sync: client UI reflects the swap immediately
                inventory.content[wantSlot] = offhandStack
                inventory.content[40] = totemStack
                session.clientBound(InventorySlotPacket().apply {
                    containerId = 0
                    slot = wantSlot
                    item = offhandStack
                })
                session.clientBound(InventoryContentPacket().apply {
                    containerId = ContainerId.OFFHAND
                    contents = listOf(totemStack)
                })
            } else {
                inventory.moveItem(wantSlot, PlayerInventory.SLOT_OFFHAND, inventory, session)
            }
            return
        }

        val bestSlot = inventory.searchForItem(0 until 36) { itemName(it) == wantIdentifier }
        if (bestSlot == null) {
            RelayLog.log("[Offhand] swap requested ($wantIdentifier) but not found in inventory")
            return
        }
        RelayLog.log("[Offhand] swapping slot $bestSlot -> offhand ($wantIdentifier)")
        inventory.moveItem(bestSlot, PlayerInventory.SLOT_OFFHAND, inventory, session)
    }
}
