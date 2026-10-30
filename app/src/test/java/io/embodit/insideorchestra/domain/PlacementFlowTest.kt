package io.embodit.insideorchestra.domain

import io.embodit.insideorchestra.domain.model.Vec3
import io.embodit.insideorchestra.domain.usecase.OrchPose
import io.embodit.insideorchestra.domain.usecase.PlaceInput
import io.embodit.insideorchestra.domain.usecase.PlaceMode
import io.embodit.insideorchestra.domain.usecase.PlaceState
import io.embodit.insideorchestra.domain.usecase.PlacementFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.2 (profile D1 "放好就锁住" / "放置模式必须有终点"): the walk start -> place -> confirm -> play -> move ->
 * confirm again, and that nothing spatial moves a locked orchestra.
 */
class PlacementFlowTest {
    private val pose = OrchPose(Vec3(0f, 0f, -2f), 0.3f, 1f)
    private val other = OrchPose(Vec3(1f, 0f, -3f), -0.5f, 1f)

    private fun run(s: PlaceState, vararg inputs: PlaceInput): PlaceState = inputs.fold(s) { acc, i -> PlacementFlow.reduce(acc, i) }

    @Test
    fun startsPlacingWithoutMarker() {
        val s = PlaceState()
        assertTrue(s.placing)
        assertNull(s.marker)
        assertFalse(s.canConfirm)
        // confirm without a marker does nothing (no dead end, no jump)
        assertEquals(s, PlacementFlow.reduce(s, PlaceInput.Confirm(pose)))
    }

    @Test
    fun floorTapThenConfirmLocks() {
        val s = run(PlaceState(), PlaceInput.FloorTap(Vec3(0f, 0f, -2f)))
        assertTrue(s.placing)
        assertTrue(s.canConfirm)
        val locked = PlacementFlow.reduce(s, PlaceInput.Confirm(pose))
        assertFalse("placing ends at Confirm", locked.placing)
        assertEquals(PlaceMode.LOCKED, locked.mode)
        assertEquals(pose, locked.pose)
        assertNull(locked.marker)
    }

    @Test
    fun confirmWithoutPlaceablePoseStays() {
        val s = run(PlaceState(), PlaceInput.FloorTap(Vec3(0f, 0f, -0.2f)))
        // too close: the scene hands no pose; still placing, marker kept, the user taps farther away
        val after = PlacementFlow.reduce(s, PlaceInput.Confirm(null))
        assertTrue(after.placing)
        assertTrue(after.canConfirm)
    }

    @Test
    fun lockedIgnoresEverySpatialInput() {
        val locked = run(PlaceState(), PlaceInput.FloorTap(Vec3.ZERO), PlaceInput.Confirm(pose))
        assertFalse(PlacementFlow.canMove(locked))
        val inputs =
            listOf(
                PlaceInput.FloorTap(Vec3(3f, 0f, 3f)),
                PlaceInput.Stick(1f, 0f),
                PlaceInput.Stick(0f, -1f),
                PlaceInput.Drag(0.5f, 0.5f),
                PlaceInput.Confirm(other),
                PlaceInput.Replace,
                PlaceInput.Cancel,
            )
        for (i in inputs) {
            val after = PlacementFlow.reduce(locked, i)
            assertEquals("$i must not change a locked orchestra", locked, after)
            assertEquals(pose, after.pose)
        }
    }

    @Test
    fun moveUnlocksAndConfirmLocksAgain() {
        val locked = run(PlaceState(), PlaceInput.FloorTap(Vec3.ZERO), PlaceInput.Confirm(pose))
        val moving = PlacementFlow.reduce(locked, PlaceInput.Move)
        assertTrue("[Move orchestra] opens placing", moving.placing)
        assertTrue(moving.moving)
        assertTrue(PlacementFlow.canMove(moving))
        // the ghost starts where the orchestra stood: a marker exists, Confirm is available at once
        assertEquals(pose.anchor, moving.marker)
        assertTrue(moving.canConfirm)
        val relocked = run(moving, PlaceInput.FloorTap(Vec3(1f, 0f, -3f)), PlaceInput.Confirm(other))
        assertTrue(relocked.locked)
        assertEquals(other, relocked.pose)
        assertFalse(relocked.moving)
    }

    @Test
    fun cancelGoesBackUnchanged() {
        val locked = run(PlaceState(), PlaceInput.FloorTap(Vec3.ZERO), PlaceInput.Confirm(pose))
        val back = run(locked, PlaceInput.Move, PlaceInput.FloorTap(Vec3(4f, 0f, 4f)), PlaceInput.Cancel)
        assertTrue(back.locked)
        assertEquals(pose, back.pose)
    }

    @Test
    fun replaceDropsTheMarkerButStaysPlacing() {
        val s = run(PlaceState(), PlaceInput.FloorTap(Vec3.ZERO), PlaceInput.Replace)
        assertTrue(s.placing)
        assertNull(s.marker)
        assertFalse(s.canConfirm)
        // and a new tap brings Confirm back: no state without a way out
        assertTrue(run(s, PlaceInput.FloorTap(Vec3(0f, 0f, -2f))).canConfirm)
    }

    @Test
    fun everyStateHasAnExit() {
        // placing without marker -> tap; placing with marker -> confirm; locked -> move; moving -> cancel or confirm
        val start = PlaceState()
        assertTrue(PlacementFlow.reduce(start, PlaceInput.FloorTap(Vec3.ZERO)) != start)
        val marked = PlacementFlow.reduce(start, PlaceInput.FloorTap(Vec3.ZERO))
        assertTrue(PlacementFlow.reduce(marked, PlaceInput.Confirm(pose)).locked)
        val locked = PlacementFlow.reduce(marked, PlaceInput.Confirm(pose))
        assertTrue(PlacementFlow.reduce(locked, PlaceInput.Move).placing)
        val moving = PlacementFlow.reduce(locked, PlaceInput.Move)
        assertTrue(PlacementFlow.reduce(moving, PlaceInput.Cancel).locked)
        assertTrue(PlacementFlow.reduce(moving, PlaceInput.Confirm(other)).locked)
    }
}
