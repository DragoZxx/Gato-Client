package com.gato.client.game.module.combat

import com.gato.client.game.InterceptablePacket
import com.gato.client.game.ListItem
import com.gato.client.game.Module
import com.gato.client.game.ModuleCategory
import com.gato.client.game.entity.Entity
import com.gato.client.game.entity.EntityUnknown
import com.gato.client.game.entity.Item
import com.gato.client.game.entity.Player
import com.gato.client.game.inventory.PlayerInventory
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * AutoCrystal — automated End Crystal place + break (adapted from a
 * CrystalSmash.kt contributed for the WClient framework to this relay's API).
 *
 * Relay mapping (documented deviations):
 * - NO voxel access: the base-block obsidian/bedrock check and the air-above
 *   check are impossible — candidate positions are scanned by geometry around
 *   the target and placement packets are sent blind; the server validates each
 *   one (the same mapping as AutoTrapModule).
 * - Explosion damage is approximated: raw = (1 - dist/12) * 42 from the
 *   crystal spawn point to the entity center (armor/exposure unknown).
 * - PacketMine dependency removed (doesn't exist here).
 * - Render settings removed (no renderer integration on this build).
 * - ID predict: after placing, attacks are sent to predicted entity IDs
 *   (highest crystal id + 1..N); the PC's outgoing-attack rewrite is not
 *   needed since we generate the predicted attacks ourselves.
 */
class AutoCrystalModule : Module("AutoCrystal", ModuleCategory.Combat) {

    // --- named selectors ---
    private class Mode(override val name: String, val idx: Int) : ListItem

    private val targetModes = listOf(Mode("Distance", 0), Mode("Health", 1), Mode("Fov", 2))
    private val mathModes = listOf(Mode("0-0", 0), Mode("0-5", 1), Mode("Test", 2))

    // --- Settings: Target ---
    private var targetRange by floatValue("Target Range", 10f, 5f..20f)
    private var targetModeItem by listValue("Target Mode", targetModes[0], targetModes.toSet())
    private var includeMobs by boolValue("Include Mobs", false)

    // --- Settings: Place ---
    private var autoPlace by boolValue("Auto Place", true)
    private var placeRange by floatValue("Place Range", 5f, 3f..10f)
    private var maxPlaceSelfDamage by floatValue("Max Self Damage", 10f, 1f..20f)
    private var minPlaceTargetDamage by floatValue("Min Target Damage", 5f, 1f..20f)
    private var placeDelay by intValue("Place Delay", 3, 0..20)
    private var multiPlace by intValue("Multi Place", 1, 1..5)
    private var placeRotate by boolValue("Place Rotate", true)
    private var mathModeItem by listValue("Math Mode", mathModes[0], mathModes.toSet())

    // --- Settings: Break ---
    private var autoBreak by boolValue("Auto Break", true)
    private var breakRange by floatValue("Break Range", 5f, 3f..10f)
    private var maxBreakSelfDamage by floatValue("Max Break Self", 10f, 1f..20f)
    private var minBreakTargetDamage by floatValue("Min Break Target", 5f, 1f..20f)
    private var breakDelay by intValue("Break Delay", 3, 0..20)
    private var breakRotate by boolValue("Break Rotate", true)

    // --- Settings: ID Prediction ---
    private var idPredict by boolValue("ID Predict", false)
    private var predictPackets by intValue("Predict Packets", 5, 1..30)
    private var sendDelay by intValue("Send Delay", 5, 0..20)

    private val targetMode get() = (targetModeItem as Mode).idx
    private val mathMode get() = (mathModeItem as Mode).idx

    // --- state ---
    private var placeDelayTick = 0
    private var breakDelayTick = 0
    private var sendDelayTick = 0
    private var highestId = 0L

    private var rotAnglePlace: Vector2f? = null
    private var rotAngleBreak: Vector2f? = null

    private data class Placement(val base: Vector3i, val spawn: Vector3f, val selfDamage: Float, val targetDamage: Float)
    private data class Breaker(val crystal: Entity, val selfDamage: Float, val targetDamage: Float)

    // ===== helpers =====

    private fun dims(e: Entity): Pair<Float, Float> {
        val w = runCatching { e.metadata[EntityDataTypes.WIDTH] as? Float }.getOrNull() ?: 0.6f
        val h = runCatching { e.metadata[EntityDataTypes.HEIGHT] as? Float }.getOrNull() ?: 1.8f
        return w to h
    }

    private fun entityHealth(e: Entity): Float =
        runCatching { e.attributes["minecraft:health"]?.value ?: 20f }.getOrDefault(20f)

    /** Approximate crystal explosion damage at the entity's position. */
    private fun explosionDamage(spawn: Vector3f, e: Entity): Float {
        val dx = e.posX - spawn.x
        val dy = (e.posY + 0.5f) - spawn.y
        val dz = e.posZ - spawn.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        return max(0f, (1f - dist / 12f) * 42f)
    }

    private fun entityIntersectsSpawn(e: Entity, base: Vector3i): Boolean {
        val (w, h) = dims(e)
        val minX = e.posX - w / 2f
        val maxX = e.posX + w / 2f
        val minZ = e.posZ - w / 2f
        val maxZ = e.posZ + w / 2f
        // crystal spawn AABB: base.x..base.x+1, base.y+1..base.y+2, base.z..base.z+1
        val overlapX = maxX > base.x && minX < base.x + 1f
        val overlapY = (e.posY + h) > base.y + 1f && e.posY < base.y + 2f
        val overlapZ = maxZ > base.z && minZ < base.z + 1f
        return overlapX && overlapY && overlapZ
    }

    private fun isEndCrystal(e: Entity): Boolean =
        e is EntityUnknown && e.identifier == "minecraft:end_crystal"

    private fun calcAngle(from: Vector3f, to: Vector3f): Vector2f {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val horiz = sqrt(dx * dx + dz * dz)
        val pitch = -(kotlin.math.atan2(dy, horiz) * 57.295776f)
        var yaw = -atan2(dx, dz) * 57.295776f
        while (yaw > 180f) yaw -= 360f
        while (yaw < -180f) yaw += 360f
        return Vector2f.from(yaw, pitch)
    }

    // ===== main =====

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return
        val packet = interceptablePacket.packet

        when (packet) {
            is PlayerAuthInputPacket -> {
                onTick(packet, packet.rotation.y)
                // rotation spoof while placing/breaking
                val rot = rotAnglePlace ?: rotAngleBreak
                if (rot != null) {
                    packet.rotation = Vector3f.from(rot.y, rot.x, rot.x)
                }
            }

            is InventoryTransactionPacket -> {
                // our own outgoing attack: track crystal ids for prediction
                if (packet.transactionType == InventoryTransactionType.ITEM_USE_ON_ENTITY) {
                    if (packet.runtimeEntityId > highestId) highestId = packet.runtimeEntityId
                }
            }
        }
    }

    private fun onTick(packet: PlayerAuthInputPacket, playerYaw: Float) {
        val localPlayer = session.localPlayer

        val targetList = ArrayList<Entity>()
        val crystalList = ArrayList<Entity>()

        for (entity in session.level.entityMap.values) {
            if (entity === localPlayer) continue
            if (isEndCrystal(entity)) {
                crystalList.add(entity)
                if (entity.runtimeEntityId > highestId) highestId = entity.runtimeEntityId
                continue
            }
            val isPlayer = entity is Player
            val isMob = entity is EntityUnknown && entity !is Item &&
                entity.identifier != "minecraft:item" && !isPlayer
            if (!isPlayer && !(includeMobs && isMob)) continue
            if (entity.distance(localPlayer.vec3Position) > targetRange) continue
            targetList.add(entity)
        }

        if (targetList.isEmpty()) return

        // sort by target mode
        when (targetMode) {
            1 -> targetList.sortBy { entityHealth(it) } // Health: weakest first
            2 -> targetList.sortBy { // Fov: angle between the view direction and the entity
                val dx = it.posX - localPlayer.posX
                val dz = it.posZ - localPlayer.posZ
                val dist = sqrt(dx * dx + dz * dz)
                val entityYaw = -atan2(dx, dz) * 57.295776f
                var diff = entityYaw - playerYaw
                while (diff > 180f) diff -= 360f
                while (diff < -180f) diff += 360f
                abs(diff)
            }
            else -> targetList.sortBy { it.distance(localPlayer.vec3Position) }
        }

        val target = targetList.first()

        val placeList = ArrayList<Placement>()
        val breakList = ArrayList<Breaker>()

        if (autoPlace) generatePlacements(target, localPlayer, crystalList, placeList)
        if (autoBreak) getBreakableCrystals(target, localPlayer, crystalList, breakList)

        val holdingCrystal = inventory.hand.definition?.identifier == "minecraft:end_crystal"
        if (!holdingCrystal) return

        if (placeList.isNotEmpty()) executePlace(localPlayer, placeList, packet)
        if (breakList.isNotEmpty()) executeBreak(breakList)
        if (idPredict && placeList.isNotEmpty()) executeIdPredict(placeList, localPlayer)
    }

    private val inventory: PlayerInventory get() = session.localPlayer.inventory

    private fun generatePlacements(
        target: Entity,
        localPlayer: com.gato.client.game.entity.LocalPlayer,
        crystals: List<Entity>,
        out: ArrayList<Placement>
    ) {
        val radius = 4 // reduced from the PC's 7: blind placement beyond this is noise
        val targetPos = Vector3i.from(
            floor(target.posX).toInt(),
            floor(target.posY).toInt(),
            floor(target.posZ).toInt()
        )

        val candidates = ArrayList<Placement>()

        for (x in -radius..radius) {
            for (y in -4..4) {
                for (z in -radius..radius) {
                    val base = Vector3i.from(targetPos.x + x, targetPos.y + y, targetPos.z + z)
                    val spawn = Vector3f.from(base.x + 0.5f, base.y + 1f, base.z + 0.5f)

                    // placement reach from the player
                    if (localPlayer.distance(spawn) > placeRange) continue

                    // skip spawns colliding with any entity (players/mobs — crystals excluded)
                    var collides = false
                    for (e in session.level.entityMap.values) {
                        if (isEndCrystal(e)) continue
                        if (entityIntersectsSpawn(e, base)) { collides = true; break }
                    }
                    if (collides) continue

                    val selfDamage = explosionDamage(spawn, localPlayer)
                    val targetDamage = explosionDamage(spawn, target)

                    if (selfDamage >= maxPlaceSelfDamage) continue
                    if (targetDamage < minPlaceTargetDamage) continue

                    candidates.add(Placement(base, spawn, selfDamage, targetDamage))
                }
            }
        }

        candidates.sortByDescending { it.targetDamage }
        out.addAll(candidates.take(multiPlace))
    }

    private fun getBreakableCrystals(
        target: Entity,
        localPlayer: com.gato.client.game.entity.LocalPlayer,
        crystals: List<Entity>,
        out: ArrayList<Breaker>
    ) {
        for (crystal in crystals) {
            val dist = crystal.distance(localPlayer.vec3Position)
            if (dist > breakRange) continue

            val selfDamage = explosionDamage(crystal.vec3Position, localPlayer)
            val targetDamage = explosionDamage(crystal.vec3Position, target)

            if (selfDamage >= maxBreakSelfDamage) continue
            if (targetDamage < minBreakTargetDamage) continue

            out.add(Breaker(crystal, selfDamage, targetDamage))
        }
        out.sortByDescending { it.targetDamage }
    }

    private fun executePlace(localPlayer: com.gato.client.game.entity.LocalPlayer, placeList: List<Placement>, packet: PlayerAuthInputPacket) {
        if (placeDelayTick < placeDelay) {
            placeDelayTick++
            return
        }

        for (placement in placeList.take(multiPlace)) {
            if (placeRotate) {
                rotAnglePlace = calcAngle(localPlayer.vec3Position, placement.spawn)
            }

            session.localPlayer.swing()

            session.serverBound(InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                actionType = 0 // place/build
                runtimeEntityId = localPlayer.runtimeEntityId
                blockPosition = placement.base
                blockFace = when (mathMode) {
                    1 -> (0..5).random()
                    else -> 1 // up
                }
                hotbarSlot = inventory.heldItemSlot
                itemInHand = inventory.hand
                playerPosition = localPlayer.vec3Position
                clickPosition = Vector3f.from(0.5f, 1f, 0.5f)
            })
        }

        placeDelayTick = 0
        if (placeRotate) rotAnglePlace = null
    }

    private fun executeBreak(breakList: List<Breaker>) {
        if (breakDelayTick < breakDelay) {
            breakDelayTick++
            return
        }

        val target = breakList.first()
        target.crystal.runtimeEntityId.let { if (it > highestId) highestId = it }

        session.localPlayer.attack(target.crystal)
        breakDelayTick = 0
        rotAngleBreak = null
    }

    private fun executeIdPredict(placeList: List<Placement>, localPlayer: com.gato.client.game.entity.LocalPlayer) {
        if (sendDelayTick < sendDelay) {
            sendDelayTick++
            return
        }

        val placement = placeList.first()
        repeat(predictPackets) { i ->
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
                actionType = 1 // attack
                runtimeEntityId = highestId + 1 + i // predicted id of the crystal about to spawn
                hotbarSlot = inventory.heldItemSlot
                itemInHand = inventory.hand
                playerPosition = localPlayer.vec3Position
                clickPosition = Vector3f.from(0f, 0.4f, 0f)
            })
        }

        sendDelayTick = 0
    }
}
