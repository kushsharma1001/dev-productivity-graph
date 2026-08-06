package com.devgraph;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point.
 *
 * <p>Bean wiring (the graph store, seed data, analytics service) lives in
 * {@link com.devgraph.config.GraphConfig}; the REST surface lives in the
 * {@code com.devgraph.api} controllers. Component scanning starts from this
 * package, so everything under {@code com.devgraph} is picked up automatically.
 */
@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
