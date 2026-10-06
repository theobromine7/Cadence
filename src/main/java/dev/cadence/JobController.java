package dev.cadence;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
  private final JobStore store;

  public JobController(JobStore store) {
    this.store = store;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateJob request) {
    UUID id = store.create(request);
    return ResponseEntity.created(java.net.URI.create("/api/jobs/" + id)).body(store.get(id));
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return store.list();
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable UUID id) {
    try {
      return store.get(id);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    }
  }

  @PostMapping("/{id}/cancel")
  public Map<String, Object> cancel(@PathVariable UUID id) {
    get(id);
    if (!store.cancel(id))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending jobs can be cancelled");
    return store.get(id);
  }
}
