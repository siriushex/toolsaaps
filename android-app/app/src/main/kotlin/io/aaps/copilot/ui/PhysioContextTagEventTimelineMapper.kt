package io.aaps.copilot.ui

import io.aaps.copilot.data.local.entity.PhysioContextTagEntity
import io.aaps.copilot.domain.isfcr.PhysioContextTag

internal fun PhysioContextTag.toEventTimelineEntity() = PhysioContextTagEntity(
    id = id,
    tsStart = tsStart,
    tsEnd = tsEnd,
    tagType = tagType,
    severity = severity,
    source = source,
    note = note,
    subtype = subtype,
    title = title,
    attributesJson = attributesJson,
    revision = revision,
    status = status
)
