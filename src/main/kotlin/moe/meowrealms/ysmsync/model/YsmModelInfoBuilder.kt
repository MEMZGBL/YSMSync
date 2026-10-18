package moe.meowrealms.ysmsync.model

import com.elfmcys.yesstevemodel.client.gui.custom.AbstractConfig
import com.elfmcys.yesstevemodel.client.gui.custom.ExtraAnimationButtons
import com.elfmcys.yesstevemodel.client.gui.custom.configs.CheckboxConfig
import com.elfmcys.yesstevemodel.client.gui.custom.configs.RadioConfig
import com.elfmcys.yesstevemodel.client.gui.custom.configs.RangeConfig
import com.elfmcys.yesstevemodel.resource.models.MainModelInfo
import com.elfmcys.yesstevemodel.model.format.ServerModelInfo
import com.elfmcys.yesstevemodel.resource.models.AuthorInfo
import com.elfmcys.yesstevemodel.resource.models.Metadata
import com.elfmcys.yesstevemodel.resource.models.ModelProperties
import com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel
import com.elfmcys.yesstevemodel.util.data.OrderedStringMap
import com.elfmcys.yesstevemodel.util.data.StringMapPair
import com.elfmcys.yesstevemodel.util.data.StringPair
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap

/**
 * 从 [RawYsmModel] 构建 [ServerModelInfo]。
 *
 * 原实现位于 YSM 模组的 `YSMClientMapper.buildModelInfo`，但那个类同时持有大量
 * 仅客户端可用的渲染/GUI 引用。这里只复刻纯数据映射部分，因此可以在 Dedicated
 * Server 上安全加载 —— 逻辑与原实现逐行等价。
 */
object YsmModelInfoBuilder {

    @JvmStatic
    fun buildModelInfo(raw: RawYsmModel): ServerModelInfo {
        val rawMetadata = raw.metadata

        val authors = ArrayList<AuthorInfo>()
        for (author in rawMetadata.authors) {
            authors.add(
                AuthorInfo(
                    author.name,
                    author.role,
                    OrderedStringMap(Object2ObjectArrayMap(author.contacts)),
                    author.comment
                )
            )
        }

        val extraInfo = Metadata(
            rawMetadata.name,
            rawMetadata.tips,
            StringPair(rawMetadata.licenseType, rawMetadata.licenseDescription),
            authors.toTypedArray(),
            OrderedStringMap(Object2ObjectArrayMap(rawMetadata.links))
        )

        val rawProperties = raw.properties

        val classifyList = ArrayList<StringMapPair>()
        for (classify in rawProperties.extraAnimationClassifies) {
            classifyList.add(
                StringMapPair(
                    classify.id,
                    OrderedStringMap(Object2ObjectArrayMap(classify.extras))
                )
            )
        }

        val buttonsList = ArrayList<ExtraAnimationButtons>()
        for (rawButton in rawProperties.extraAnimationButtons) {
            val formList = ArrayList<AbstractConfig>()
            for (form in rawButton.forms) {
                when (form.type) {
                    "checkbox" -> formList.add(
                        CheckboxConfig(form.title, form.description, form.defaultValue)
                    )

                    "radio" -> formList.add(
                        RadioConfig(
                            form.title,
                            form.description,
                            form.defaultValue,
                            OrderedStringMap(Object2ObjectArrayMap(form.labels))
                        )
                    )

                    "range" -> formList.add(
                        RangeConfig(
                            form.title,
                            form.description,
                            form.defaultValue,
                            form.step.toDouble(),
                            form.min.toDouble(),
                            form.max.toDouble()
                        )
                    )
                }
            }
            buttonsList.add(
                ExtraAnimationButtons(
                    rawButton.id,
                    rawButton.name,
                    rawButton.description,
                    formList.toTypedArray()
                )
            )
        }

        val modelProperties = ModelProperties(
            rawProperties.heightScale,
            rawProperties.widthScale,
            rawProperties.defaultTexture,
            rawProperties.previewAnimation,
            OrderedStringMap(Object2ObjectArrayMap(rawProperties.extraAnimations)),
            buttonsList.toTypedArray(),
            classifyList.toTypedArray(),
            rawProperties.isFree,
            rawProperties.renderLayersFirst,
            rawProperties.disablePreviewRotation
        )

        var bones = 0
        var cubes = 0
        var faces = 0

        val mainModel = raw.mainEntity.mainModel
        if (mainModel != null) {
            bones = mainModel.bones.size
            for (bone in mainModel.bones) {
                cubes += bone.cubes.size
                for (cube in bone.cubes) {
                    faces += cube.faces.size
                }
            }
        }

        val mainModelInfo = MainModelInfo(bones, cubes, faces)
        val footer = raw.footer

        return ServerModelInfo(
            extraInfo,
            modelProperties,
            mainModelInfo,
            footer.version,
            rawProperties.sha256 ?: "",
            footer.extra,
            footer.time,
            footer.rand
        )
    }
}
