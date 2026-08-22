package com.cerocoder.meshtest.emulator

import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo

/**
 * Описание эмулируемого меша: данные без поведения.
 *
 * Порядок кадров повторяет порядок настоящей прошивки: MyNodeInfo идёт первым,
 * потому что только после него известен номер локальной ноды.
 */
class MeshScenario private constructor(
    val id: String,
    val displayName: String,
    /** Ноды сценария — для отображения и проверок. */
    val nodes: List<NodeInfo>,
    private val configStage: List<FromRadio>,
    private val nodeStage: List<FromRadio>,
) {

    /** Кадры ответа на want_config_id стадии 1, с подтверждением в конце. */
    fun configStageFrames(nonce: Int): List<FromRadio> = configStage + FromRadio(config_complete_id = nonce)

    /** Кадры ответа на want_config_id стадии 2, с подтверждением в конце. */
    fun nodeStageFrames(nonce: Int): List<FromRadio> = nodeStage + FromRadio(config_complete_id = nonce)

    companion object {

        /**
         * Сценарий из структурированных данных.
         *
         * Порядок кадров повторяет порядок настоящей прошивки: MyNodeInfo идёт
         * первым, потому что только после него известен номер локальной ноды.
         */
        fun of(
            id: String,
            displayName: String,
            myInfo: MyNodeInfo,
            metadata: DeviceMetadata,
            config: List<Config>,
            moduleConfig: List<ModuleConfig>,
            channels: List<Channel>,
            nodes: List<NodeInfo>,
        ): MeshScenario = MeshScenario(
            id = id,
            displayName = displayName,
            nodes = nodes,
            configStage = buildList {
                add(FromRadio(my_info = myInfo))
                add(FromRadio(metadata = metadata))
                config.forEach { add(FromRadio(config = it)) }
                moduleConfig.forEach { add(FromRadio(moduleConfig = it)) }
                channels.forEach { add(FromRadio(channel = it)) }
            },
            nodeStage = nodes.map { FromRadio(node_info = it) },
        )

        /**
         * Сценарий из готовых кадров — например, из дампа трафика реальной ноды.
         *
         * Завершающие кадры `config_complete_id` добавляются автоматически, поэтому
         * из дампа их нужно исключить, иначе приложение получит подтверждение дважды.
         */
        fun fromFrames(
            id: String,
            displayName: String,
            configStage: List<FromRadio>,
            nodeStage: List<FromRadio>,
        ): MeshScenario = MeshScenario(
            id = id,
            displayName = displayName,
            nodes = nodeStage.mapNotNull { it.node_info },
            configStage = configStage,
            nodeStage = nodeStage,
        )
    }
}
