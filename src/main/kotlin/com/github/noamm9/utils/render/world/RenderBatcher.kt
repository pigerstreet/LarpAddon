package com.github.noamm9.utils.render.world

import com.github.noamm9.utils.render.world.batches.FilledBatch
import com.github.noamm9.utils.render.world.batches.LineBatch
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType
import org.joml.Vector3f

object RenderBatcher {
    /// fork: the batches are double-buffered instead of being thrown away after every flush.
    ///
    /// `submitCustomGeometry` only stores the lambda; it runs in `LevelRenderer.render` ->
    /// `FeatureRenderDispatcher.prepareFrame` -> `CustomFeatureRenderer.buildGroup`, after `flush` has
    /// returned, so a batch's list must not be touched until that has happened. It always has by the time
    /// the next flush starts (collect and prepare are consecutive, synchronous steps of one render call),
    /// so the set a flush submits is emptied at the NEXT flush, when it is swapped back in, never at the
    /// flush that handed it to the lambdas. Each list then settles at its high-water mark instead of
    /// regrowing from capacity 10 every frame, one batch per pipeline per frame.
    private class Buffers {
        val filled = mutableMapOf<RenderType, FilledBatch>()
        val lines = mutableMapOf<RenderType, LineBatch>()
    }

    private val buffers = arrayOf(Buffers(), Buffers())
    private var active = 0
    private val poseStack = PoseStack()

    val tmpVec = Vector3f()
    val tmpDir = Vector3f()

    fun filledBatch(phase: Boolean): FilledBatch {
        val type = if (phase) NoammRenderTypes.DEBUG_FILLED else NoammRenderTypes.FILLED
        return buffers[active].filled.getOrPut(type) { FilledBatch(type) }
    }

    fun circleBatch(phase: Boolean): FilledBatch {
        val type = if (phase) NoammRenderTypes.DEBUG_CIRCLE_FILLED else NoammRenderTypes.CIRCLE_FILLED
        return buffers[active].filled.getOrPut(type) { FilledBatch(type) }
    }

    fun lineBatch(phase: Boolean): LineBatch {
        val type = if (phase) NoammRenderTypes.DEBUG_LINES else NoammRenderTypes.LINES
        return buffers[active].lines.getOrPut(type) { LineBatch(type) }
    }

    fun flush(collector: SubmitNodeCollector) {
        val current = buffers[active]
        var any = false

        for (batchData in current.filled.values) {
            if (batchData.data.isEmpty()) continue
            any = true
            collector.submitCustomGeometry(poseStack, batchData.type) { _, consumer ->
                for (state in batchData.data) {
                    consumer.addVertex(state.x, state.y, state.z)
                    consumer.setColor(state.r, state.g, state.b, state.a)
                }
            }
        }

        for (batchData in current.lines.values) {
            if (batchData.data.isEmpty()) continue
            any = true
            collector.submitCustomGeometry(poseStack, batchData.type) { _, consumer ->
                for (state in batchData.data) {
                    consumer.addVertex(state.x, state.y, state.z)
                    consumer.setColor(state.r, state.g, state.b, state.a)
                    consumer.setNormal(state.nx, state.ny, state.nz)
                    consumer.setLineWidth(state.lineWidth)
                }
            }
        }

        if (!any) return

        // The other set was submitted by the previous flush and its lambdas have run; it is free again.
        active = 1 - active
        val next = buffers[active]
        for (batchData in next.filled.values) batchData.data.clear()
        for (batchData in next.lines.values) batchData.data.clear()
    }
}
