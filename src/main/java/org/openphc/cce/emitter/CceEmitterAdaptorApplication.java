package org.openphc.cce.emitter;

import org.openphc.cce.emitter.config.CollectorProperties;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.MediatorProperties;
import org.openphc.cce.emitter.config.OpenHimProperties;
import org.openphc.cce.emitter.filter.FacilityFilterProperties;
import org.openphc.cce.emitter.redaction.ClinicalDataRedactionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({OpenHimProperties.class, CollectorProperties.class, EmitterProperties.class, MediatorProperties.class, FacilityFilterProperties.class, ClinicalDataRedactionProperties.class})
public class CceEmitterAdaptorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CceEmitterAdaptorApplication.class, args);
    }
}
