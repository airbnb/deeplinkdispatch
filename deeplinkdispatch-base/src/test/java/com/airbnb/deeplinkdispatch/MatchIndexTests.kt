package com.airbnb.deeplinkdispatch

import org.assertj.core.api.Assertions
import org.junit.Test

@kotlin.ExperimentalUnsignedTypes
class MatchIndexTests {

    @Test
    fun testGetAllEntries() {
        val deepLinkEntries = listOf(
            DeepLinkEntry.MethodDeeplinkEntry( "http://www.example.com/somePath1/differentPath2", MatchIndexTests::class.java.name, "someMethod1"),
            DeepLinkEntry.MethodDeeplinkEntry( "https://www.example.com/path1/path2", MatchIndexTests::class.java.name, "someMethod2"),
            DeepLinkEntry.MethodDeeplinkEntry( "dld://dldPath1/dldPath2", MatchIndexTests::class.java.name, "someMethod3"),
            DeepLinkEntry.MethodDeeplinkEntry( "http://example.de/", MatchIndexTests::class.java.name, "someMethod4"),
            DeepLinkEntry.MethodDeeplinkEntry( "http://example.com/path1", MatchIndexTests::class.java.name, "someMethod7"),
            DeepLinkEntry.MethodDeeplinkEntry( "http://example.com/somethingElse", MatchIndexTests::class.java.name, "someMethod9"),
            DeepLinkEntry.MethodDeeplinkEntry( "http://example.com/path1/pathElement2/path3", MatchIndexTests::class.java.name, "someMethod5"),
            DeepLinkEntry.MethodDeeplinkEntry( "http://example.com/path1/someOtherPathElement2", MatchIndexTests::class.java.name, "someMethod6"),
            DeepLinkEntry.MethodDeeplinkEntry( "http://example.com/", MatchIndexTests::class.java.name, "someMethod8"),
        ).sortedBy { it.uriTemplate }
        val root = Root()
        deepLinkEntries.forEach{
            root.addToTrie(it)
        }
        val testRegistry = TestRegistry(root.toUByteArray().toByteArray())
        val allEntries = testRegistry.getAllEntries().sortedBy { it.uriTemplate }
        Assertions.assertThat(allEntries).isEqualTo(deepLinkEntries)
    }

    class TestRegistry(val matchArray: ByteArray) : BaseRegistry(matchArray, emptyArray())

}