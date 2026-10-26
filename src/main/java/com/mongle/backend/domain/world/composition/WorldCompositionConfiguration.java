package com.mongle.backend.domain.world.composition;
import com.mongle.backend.domain.world.bridge.BridgeService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(WorldCompositionProperties.class)
public class WorldCompositionConfiguration {
    @Bean WorldCompositionWorker worldCompositionWorker(WorldCompositionTransactions transactions,BridgeService bridges,WorldCompositionProperties properties) {
        return new WorldCompositionWorker(transactions,bridges,properties);
    }
}
