package com.gato.client.game.module.combat

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import com.gato.client.game.entity.Entity
import com.gato.client.game.entity.Item
import com.gato.client.game.entity.Player
import com.gato.client.game.inventory.PlayerInventory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.floor

/**
 * Port of the GatoClient (PC) AutoTrap — traps players with obsidian.
 *
 * PC reference: Combat/AutoTrap.cpp — computes the trap geometry around each
 * target's AABB (walls x3 high, floor below, ceiling with head space, skipping
 * corners), sorts by distance and places up to BPT blocks per tick.
 *
 * Relay mapping (documented deviations — the relay has NO voxel access):
 * - The trap geometry is computed purely from the target's entity position
 *   (which the relay sees) — same layout as the PC: 4 walls, floor, ceiling.
 * - No occupancy checks: placement packets are sent blind at the geometry
 *   positions; the server accepts the ones where placement is legal and
 *   ignores the rest (a real placement needs a support face — we click the
 *   block below the target position, face up).
 * - No render/fade (PC onLevelRender).
 * - Switch modes map: None / Silent (MobEquipmentPacket + restore) —
 *   "Normal"/"Spoof" collapse into Silent since the relay cannot move the
 *   real client's hotbar.
 */
class AutoTrapModule : Module("AutoTrap", ModuleCategory.Combat) {

    // --- Settings: same names/defaults/ranges as the PC ctor ---
    private var range by intValue("Range", 6, 1..12)
    private var bpt by intValue("BPT", 1, 1..50)
    private var maxTarget by intValue("Targets", 1, 1..10)
    private var headSpace by intValue("Head Space", 0, 0..3)
    private var switchMode by intValue("Switch Mode", 0, 0..3) // None, Normal, Silent, Spoof
    private var useNetherite by boolValue("Netherite", false)

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val packet = interceptablePacket.packet as? PlayerAuthInputPacket ?: return
        val localPlayer = session.localPlayer

        // ---- targets ----
        val targetList = ArrayList<Entity>()
        for (entity in session.level.entityMap.values) {
            if (entity is Item) continue
            if (entity === localPlayer) continue
            val dist = entity.distance(localPlayer.vec3Position)
            if (dist > range) continue
            if (entity is com.gato.client.game.entity.EntityUnknown && entity.identifier == "minecraft:end_crystal") continue
            targetList.add(entity)
        }
        if (targetList.isEmpty()) return

        // ---- block slot ----
        val inventory = localPlayer.inventory
        val blockIdentifier =
            if (useNetherite) "minecraft:polished_blackstone_bricks" else "minecraft:obsidian"
        val blockSlot =
            inventory.searchForItem(0..8) { it.definition?.identifier == blockIdentifier } ?: return

        // ---- place geometry per target ----
        var targets = 0
        for (target in targetList) {
            if (targets >= maxTarget) break
            targets++

            // feet block of the target (PC: center.xz, lower.y + 0.5, floored)
            val ax = floor(target.posX).toInt()
            val ay = floor(target.posY + 0.5f).toInt()
            val az = floor(target.posZ).toInt()

            val placements = ArrayList<Vector3i>()

            // walls: 3+headSpace high, skipping corners (PC loop)
            for (x in -1..1) {
                for (z in -1..1) {
                    val isCorner = (x == -1 || x == 1) && (z == -1 || z == 1)
                    val isCenter = x == 0 && z == 0
                    if (isCorner || isCenter) continue

                    for (i in 0 until 3 + headSpace) {
                        placements.add(Vector3i.from(ax + x, ay + i, az + z))
                    }
                }
            }

            // floor below + ceiling above (interior cells)
            placements.add(Vector3i.from(ax, ay - 1, az))
            placements.add(Vector3i.from(ax, ay + 2 + headSpace, az))

            // ---- switch to block ----
            val curSlot = inventory.heldItemSlot
            var didSwitch = false
            if (switchMode != 0 && blockSlot != curSlot) {
                session.serverBound(MobEquipmentPacket().apply {
                    runtimeEntityId = localPlayer.runtimeEntityId
                    item = inventory.content[blockSlot]
                    inventorySlot = blockSlot
                    hotbarSlot = blockSlot
                })
                didSwitch = true
            }

            // ---- place up to BPT (blind: the server validates each) ----
            val canPlaceNow = (switchMode != 0) || (curSlot == blockSlot)
            if (canPlaceNow) {
                var placed = 0
                for (pos in placements) {
                    // click support: the block below the target position, face up
                    session.serverBound(InventoryTransactionPacket().apply {
                        transactionType = InventoryTransactionType.ITEM_USE
                        actionType = 0 // place/build
                        runtimeEntityId = localPlayer.runtimeEntityId
                        blockPosition = Vector3i.from(pos.x, pos.y - 1, pos.z)
                        blockFace = 1 // up
                        hotbarSlot = if (switchMode != 0) blockSlot else curSlot
                        itemInHand = inventory.content[if (switchMode != 0) blockSlot else curSlot]
                        playerPosition = localPlayer.vec3Position
                        clickPosition = Vector3f.from(0.5f, 1f, 0.5f)
                    })
                    placed++
                    if (placed >= bpt) break
                }
            }

            if (didSwitch && switchMode == 2) {
                // Silent-style restore
                session.serverBound(MobEquipmentPacket().apply {
                    runtimeEntityId = localPlayer.runtimeEntityId
                    item = inventory.content[curSlot]
                    inventorySlot = curSlot
                    hotbarSlot = curSlot
                })
            }
        }
    }
}
