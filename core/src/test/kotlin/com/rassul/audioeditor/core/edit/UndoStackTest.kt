package com.rassul.audioeditor.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-16: отмена/повтор не менее 100 шагов. */
class UndoStackTest {
    private fun list(n: Int) = EditList((0 until n).map { EditOp.Volume(null, it / 10.0) })

    @Test fun basics() {
        val st = UndoStack()
        assertFalse(st.canUndo); assertFalse(st.canRedo)
        assertNull(st.undo()); assertNull(st.redo())
        val a = list(1); val b = list(2)
        st.push(a); st.push(b)
        assertSame(a, st.undo())
        assertTrue(st.canRedo)
        assertSame(b, st.redo())
        st.undo()
        st.push(list(3))           // новое действие очищает повтор
        assertFalse(st.canRedo)
        assertEquals(list(3), st.current)
    }

    @Test fun hundredStepsAndLimit() {
        val st = UndoStack()
        assertTrue(st.limit >= 100)
        for (i in 1..150) st.push(list(i))
        repeat(150) { st.undo() }
        assertEquals(EditList(), st.current)
        repeat(150) { st.redo() }
        assertEquals(list(150), st.current)
        val small = UndoStack(limit = 100)
        for (i in 1..130) small.push(list(i))
        assertEquals(100, small.undoDepth)
        repeat(100) { small.undo() }
        assertFalse(small.canUndo)
        assertEquals(list(30), small.current)
    }
}
