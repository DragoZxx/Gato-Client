package com.gato.relay.definition

import com.google.gson.JsonParser
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition
import org.cloudburstmc.protocol.common.DefinitionRegistry
import org.cloudburstmc.protocol.common.NamedDefinition
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry
import java.io.InputStream


@Suppress("MemberVisibilityCanBePrivate")
object Definitions {

    /**
     * Item registry: vanilla baseline loaded from the embedded palette
     * (PMMP BedrockData — modern StartGamePackets no longer carry the full
     * vanilla palette), plus server additions (custom items) captured from
     * StartGamePacket/ItemComponentPacket. Unknown runtime ids fall back to a
     * synthesized "unknown:<id>" definition so inventory items ALWAYS
     * deserialize readably.
     */
    var itemDefinitions: DefinitionRegistry<ItemDefinition> =
        MappedItemDefinitionRegistry.withVanillaPalette()

    var blockDefinitions: DefinitionRegistry<BlockDefinition> =
        SimpleDefinitionRegistry.builder<BlockDefinition>()
            .build()

    var cameraPresetDefinitions: DefinitionRegistry<NamedDefinition> =
        SimpleDefinitionRegistry.builder<NamedDefinition>()
            .build()

    var blockDefinitionsHashed: DefinitionRegistry<BlockDefinition> =
        SimpleDefinitionRegistry.builder<BlockDefinition>()
            .build()

    fun loadBlockPalette() {
        Definitions::class.java.classLoader.getResourceAsStream("nbt/block_palette.nbt")?.let {
            val tag = loadGzipNBT(it)
            if (tag is NbtMap) {
                blockDefinitions = NbtBlockDefinitionRegistry(tag.getList("blocks", NbtType.COMPOUND), false)
                blockDefinitionsHashed = NbtBlockDefinitionRegistry(tag.getList("blocks", NbtType.COMPOUND), true)
            }
        }
    }

    fun registerItemDefinitions(defs: List<ItemDefinition>) {
        (itemDefinitions as? MappedItemDefinitionRegistry)?.registerAll(defs)
        println("[Definitions] server item definitions registered: ${defs.size} entries")
    }

    fun lookupItemRuntimeId(identifier: String): Int? =
        (itemDefinitions as? MappedItemDefinitionRegistry)?.lookupRuntimeId(identifier)

    private fun loadGzipNBT(stream: InputStream): Any {
        NbtUtils.createGZIPReader(stream).use { nbtInputStream ->
            return nbtInputStream.readTag()
        }
    }

}

/**
 * Mutable item registry: the codec helpers hold this instance, so in-place
 * updates (server item components, vanilla palette) propagate to both relay
 * sessions without touching the codec helpers again.
 */
class MappedItemDefinitionRegistry private constructor(
    private val defs: HashMap<Int, ItemDefinition>
) : DefinitionRegistry<ItemDefinition> {

    @Synchronized
    fun registerAll(items: Collection<ItemDefinition>) {
        // guard: never overwrite a good palette entry with a null/blank
        // identifier entry (server-sent definitions can deserialize weirdly)
        items.forEach {
            if (it.identifier != null && it.identifier.isNotBlank()) {
                defs[it.runtimeId] = it
            }
        }
    }

    @Synchronized
    fun lookupRuntimeId(identifier: String): Int? =
        defs.values.firstOrNull { it.identifier == identifier }?.runtimeId

    private val loggedUnknown = HashSet<Int>()

    @Synchronized
    override fun getDefinition(runtimeId: Int): ItemDefinition {
        val known = defs[runtimeId]
        if (known != null) return known
        // log each unknown wire runtime id once — reveals the server's real item ids
        if (loggedUnknown.add(runtimeId)) {
            println("[Definitions] unknown wire runtime id: $runtimeId")
        }
        return SimpleItemDefinition("unknown:$runtimeId", runtimeId, false)
    }

    @Synchronized
    override fun isRegistered(definition: ItemDefinition): Boolean =
        defs[definition.runtimeId] === definition

    companion object {
        /** Vanilla baseline from the embedded palette, empty if it fails to parse. */
        fun withVanillaPalette(): MappedItemDefinitionRegistry {
            val registry = MappedItemDefinitionRegistry(HashMap())
            runCatching {
                val json = JsonParser.parseString(ItemPaletteData.decompressed()).asJsonObject
                var loaded = 0
                for ((name, node) in json.entrySet()) {
                    val obj = node.asJsonObject
                    registry.registerAll(
                        listOf(SimpleItemDefinition(name, obj.get("runtime_id").asInt, obj.get("component_based").asBoolean))
                    )
                    loaded++
                }
                println("[Definitions] vanilla item palette loaded: $loaded items")
            }.onFailure {
                println("[Definitions] failed to load item palette: ${it.stackTraceToString()}")
            }
            return registry
        }
    }
}
