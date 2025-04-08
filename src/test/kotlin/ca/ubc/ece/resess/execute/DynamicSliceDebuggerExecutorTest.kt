package ca.ubc.ece.resess.execute

import junit.framework.TestCase

class DynamicSliceDebuggerExecutorTest : TestCase() {
    private val executor = DynamicSliceDebuggerExecutor()

    fun testGetStartActionTextBasic() {
        val expected = "Debug 'Basic' with Slicer4D using Slicer4J (default)"
        val actual = executor.getStartActionText("Basic")
        assertEquals(expected, actual)
    }

    fun testGetStartActionTextEmpty() {
        val expected = "Debug '' with Slicer4D using Slicer4J (default)"
        val actual = executor.getStartActionText("")
        assertEquals(expected, actual)
    }
}