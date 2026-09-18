package com.example.dsim

import org.junit.Assert.*
import org.junit.Test

class CloudTopicsTest {
    @Test fun publishAndSubscribeLayout() {
        assertEquals("dsim/home/dev-A", CloudTopics.publishTopic("dsim/home", "dev-A"))
        assertEquals("dsim/home/+", CloudTopics.subscriptionFilter("dsim/home"))
    }

    @Test fun baseIsNormalized() {
        assertEquals("dsim/home/dev-A", CloudTopics.publishTopic(" dsim/home/ ", "dev-A"))
        assertEquals("dsim/home/+", CloudTopics.subscriptionFilter("dsim/home//"))
    }

    @Test fun senderIsParsedFromTopic() {
        assertEquals("dev-B", CloudTopics.senderOf("dsim/home", "dsim/home/dev-B"))
        assertNull(CloudTopics.senderOf("dsim/home", "dsim/home"))
        assertNull(CloudTopics.senderOf("dsim/home", "dsim/home/"))
        assertNull(CloudTopics.senderOf("dsim/home", "dsim/home/dev-B/extra"))
        assertNull(CloudTopics.senderOf("dsim/home", "dsim/other/dev-B"))
        assertNull(CloudTopics.senderOf("dsim/home", null))
    }

    @Test fun ownEchoDetectedByStringOnly() {
        assertTrue(CloudTopics.isOwnEcho("dsim/home", "dsim/home/dev-A", "dev-A"))
        assertFalse(CloudTopics.isOwnEcho("dsim/home", "dsim/home/dev-B", "dev-A"))
        assertFalse("legacy base-topic message is never treated as own echo",
            CloudTopics.isOwnEcho("dsim/home", "dsim/home", "dev-A"))
    }
}
