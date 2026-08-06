package com.devgraph.api;

import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates domain exceptions into HTTP responses, preserving the semantics the
 * original hand-rolled server had: an unknown node/incident id (surfaced by the
 * analytics layer as {@link IllegalArgumentException}) becomes a 404 with a JSON
 * body, rather than a 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> notFound(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * When the Neo4j database is unreachable at request time (driver cannot get a
     * connection), the driver throws {@link ServiceUnavailableException}. Map it to
     * a clean 503 with a JSON body instead of leaking an unhandled 500, so callers
     * can distinguish "the graph store is down" from a genuine server bug.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String, String>> databaseUnavailable(ServiceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Graph database is unavailable", "detail", ex.getMessage()));
    }
}
