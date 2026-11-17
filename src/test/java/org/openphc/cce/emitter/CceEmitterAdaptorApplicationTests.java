package org.openphc.cce.emitter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Beckn is on by default on demo-beckn and needs its ids; this test is about the base context.
@SpringBootTest(properties = "beckn.enabled=false")
class CceEmitterAdaptorApplicationTests {

    @Test
    void contextLoads() {
    }
}
