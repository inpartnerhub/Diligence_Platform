package com.inpartnerhub.diligence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

@Modulithic(sharedModules = "shared")
@SpringBootApplication
@ConfigurationPropertiesScan
public class DiligenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiligenceApplication.class, args);
    }
}
