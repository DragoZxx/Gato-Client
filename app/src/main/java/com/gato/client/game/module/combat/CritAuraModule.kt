package com.gato.client.game.module.combat

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.ListItem
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import com.gato.client.game.entity.Entity
import com.gato.client.game.entity.EntityUnknown
import com.gato.client.game.entity.Item
import com.gato.client.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Port of the GatoClient (PC) CritAura — KillAura variant with packet crits:
 * the attack transaction carries a Y-offset (playerPos.y + critOffset) and the
 * rotation spoof always aims the PAIP at the first target.
 *
 * PC reference: Combat/CritAura.cpp (KillAura4 merged).
 *
 * Relay mapping: same as GatoAuraModule (packet attacks via
 * ITEM_USE_ON_ENTITY, rotation spoof on the outgoing PAIP, weapon table
 * instead of engine damage, EatStop via the START_USING_ITEM input flag,
 * wall range inert without voxel access, no render).
 */
class CritAuraModule : Module("CritAura", ModuleCategory.Combat) {

    // --- named selectors ---
    private class Mode(override val name: String, val idx: Int) : ListItem

    private val rotModes = listOf(Mode("None", 0), Mode("Normal", 1), Mode("Silent", 2), Mode("Adaptive", 3))
    private val hitTypes = listOf(Mode("Single", 0), Mode("Multi", 1))
    private val weaponModes = listOf(Mode("Off", 0), Mode("Best Damage", 1), Mode("Always", 2))

    // --- Settings: same names/defaults/ranges as the PC ctor ---
    private var delay by intValue("Delay", 0, 0..20)
    private var critOffset by floatValue("JollyGoodVrizzyStuff", 0f, 0f..2f)
    private var range by floatValue("Range", 5f, 1f..10f)
    private var wallRange by floatValue("Wall Range", 3f, 1f..10f) // inerte sin vóxeles
    private var interval by intValue("Interval", 1, 1..20)
    private var rotModeItem by listValue("Rot Mode", rotModes[1], rotModes.toSet())
    private var randomize by boolValue("Randomize", true)
    private var hitTypeItem by listValue("Hit Type", hitTypes[0], hitTypes.toSet())
    private var hitAttempts by intValue("Hit Attempts", 1, 1..5)
    private var hitChance by intValue("Hit Chance", 100, 1..100)
    private var weaponItem by listValue("Auto Weapon", weaponModes[0], weaponModes.toSet())
    private var eatStop by boolValue("Eat Stop", false)
    private var includeMobs by boolValue("Include Mobs", false)
    private var hurtTimeCheck by boolValue("Hurt Check", false)
    private var packetAttack by boolValue("Packet Attack", false)

    private val rotMode get() = (rotModeItem as Mode).idx
    private val hitType get() = (hitTypeItem as Mode).idx
    private val autoWeaponMode get() = (weaponItem as Mode).idx

    private val targetList = ArrayList<Entity>()
    private var tickCounter = 0
    private var oTick = Int.MAX_VALUE

    private var usingItemTicks = 0

    override fun onEnabled() {
        super.onEnabled()
        targetList.clear()
        tickCounter = 0
        oTick = Int.MAX_VALUE
    }

    override fun onDisabled() {
        super.onDisabled()
        targetList.clear()
        tickCounter = 0
    }

    private fun weaponDamage(identifier: String?): Float = when (identifier) {
        "minecraft:wooden_sword" -> 4f
        "minecraft:stone_sword" -> 5f
        "minecraft:iron_sword" -> 6f
        "minecraft:diamond_sword" -> 7f
        "minecraft:netherite_sword" -> 8f
        "minecraft:golden_sword" -> 4f
        "minecraft:wooden_axe" -> 7f
        "minecraft:stone_axe" -> 9f
        "minecraft:iron_axe" -> 9f
        "minecraft:diamond_axe" -> 9f
        "minecraft:netherite_axe" -> 10f
        "minecraft:trident" -> 9f
        else -> 1f
    }

    private fun getBestWeaponSlot(): Int {
        val inventory = session.localPlayer.inventory
        var bestSlot = inventory.heldItemSlot
        var best = weaponDamage(inventory.content[bestSlot].definition?.identifier)
        for (i in 0..8) {
            val dmg = weaponDamage(inventory.content[i].definition?.identifier)
            if (dmg > best) { best = dmg; bestSlot = i }
        }
        return bestSlot
    }

    private fun attackPacket(target: Entity) {
        val inventory = session.localPlayer.inventory
        session.serverBound(InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
            actionType = 1 // attack
            runtimeEntityId = target.runtimeEntityId
            hotbarSlot = inventory.heldItemSlot
            itemInHand = inventory.hand
            // crit Y-offset on the claimed player position (PC behaviour)
            playerPosition = Vector3f.from(
                session.localPlayer.posX,
                session.localPlayer.posY + critOffset,
                session.localPlayer.posZ
            )
            clickPosition = Vector3f.from(0f, 0.4f, 0f)
        })
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isSessionCreated) return
        val packet = interceptablePacket.packet

        when (packet) {
            is PlayerAuthInputPacket -> {
                if (isEnabled) tick(packet)
                spoofRotation(packet)
            }

            // PC onSendPacket: Y-offset on own outbound movement packets
            is MovePlayerPacket -> applyCritOffset(packet)
            is MoveEntityAbsolutePacket -> applyCritOffset(packet)
        }
    }

    private fun applyCritOffset(packet: BedrockPacket) {
        if (!isEnabled || critOffset == 0f) return
        when (packet) {
            is MovePlayerPacket -> {
                if (packet.runtimeEntityId == session.localPlayer.runtimeEntityId) {
                    packet.position = Vector3f.from(
                        packet.position.x, packet.position.y + critOffset, packet.position.z
                    )
                }
            }
            is MoveEntityAbsolutePacket -> {
                if (packet.runtimeEntityId == session.localPlayer.runtimeEntityId) {
                    packet.position = Vector3f.from(
                        packet.position.x, packet.position.y + critOffset, packet.position.z
                    )
                }
            }
            else -> {}
        }
    }

    private fun tick(packet: PlayerAuthInputPacket) {
        val localPlayer = session.localPlayer
        targetList.clear()

        if (eatStop) {
            if (packet.inputData.contains(PlayerAuthInputData.START_USING_ITEM)) usingItemTicks = 20
            if (usingItemTicks > 0) { usingItemTicks--; return }
        }

        for (entity in session.level.entityMap.values) {
            if (entity === localPlayer) continue
            val isPlayer = entity is Player
            val isMob = entity is EntityUnknown && entity !is Item &&
                entity.identifier != "minecraft:item" && !isPlayer
            if (!isPlayer && !(includeMobs && isMob)) continue
            if (entity.distance(localPlayer.vec3Position) > range) continue
            if (hurtTimeCheck && ((entity.metadata[EntityDataTypes.HURT_TICKS] as? Int) ?: 0) > 0) continue
            targetList.add(entity)
        }
        targetList.sortBy { it.distance(localPlayer.vec3Position) }

        val inventory = session.localPlayer.inventory
        if (targetList.isNotEmpty() && autoWeaponMode != 0) {
            val slot = getBestWeaponSlot()
            if (inventory.heldItemSlot != slot) {
                session.serverBound(MobEquipment(slot))
            }
        }

        tickCounter++
        if (tickCounter < delay) return
        tickCounter = 0

        oTick++
        if (oTick < interval) return
        oTick = 0

        if (hitChance < 100 && (Math.random() * 100).toInt() >= hitChance) return

        var attacksDone = 0
        for (target in targetList) {
            if (attacksDone >= hitAttempts) break

            repeat(if (randomize) 1 else 1) {
                attackPacket(target)
            }
            localPlayer.swing()
            attacksDone++

            if (hitType == 0) break
        }
    }

    private fun MobEquipment(slot: Int) = MobEquipmentPacket().apply {
        runtimeEntityId = session.localPlayer.runtimeEntityId
        item = session.localPlayer.inventory.content[slot]
        inventorySlot = slot
        hotbarSlot = slot
    }

    private fun spoofRotation(packet: PlayerAuthInputPacket) {
        // PC: the PAIP always aims at the first target
        val first = targetList.firstOrNull() ?: return

        val eyeY = session.localPlayer.posY + 1.62f
        val dx = first.posX - session.localPlayer.posX
        val dy = (first.posY + 1.62f) - eyeY
        val dz = first.posZ - session.localPlayer.posZ
        val horiz = sqrt(dx * dx + dz * dz)

        val pitch = -(atan2(dy, horiz) * 57.295776f)
        var yaw = -atan2(dx, dz) * 57.295776f
        while (yaw > 180f) yaw -= 360f
        while (yaw < -180f) yaw += 360f

        packet.rotation = Vector3f.from(pitch, yaw, yaw)
    }
}
