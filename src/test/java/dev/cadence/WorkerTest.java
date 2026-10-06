package dev.cadence;

import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;

class WorkerTest {
  @Test
  void reconciliationUsesDescendingPriorityAndStableJobIds() {
    JobStore store = mock(JobStore.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ZSetOperations<String, String> queue = mock(ZSetOperations.class);
    when(redis.opsForZSet()).thenReturn(queue);
    UUID id = UUID.randomUUID();
    when(store.due()).thenReturn(List.of(Map.of("id", id, "priority", 9)));
    Worker worker = new Worker(store, redis);
    try {
      worker.dispatch();
      worker.dispatch();
      verify(store, times(2)).recover();
      verify(queue, times(2)).add("cadence:ready", id.toString(), -9.0);
    } finally {
      worker.close();
    }
  }

  @Test
  void staleQueueEntryDoesNotExecuteJob() {
    JobStore store = mock(JobStore.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ZSetOperations<String, String> queue = mock(ZSetOperations.class);
    when(redis.opsForZSet()).thenReturn(queue);
    UUID id = UUID.randomUUID();
    when(queue.popMin("cadence:ready"))
        .thenReturn(new DefaultTypedTuple<>(id.toString(), -5.0))
        .thenReturn(null);
    Worker worker = new Worker(store, redis);
    try {
      worker.poll();
      verify(store).claim(eq(id), any());
      verify(store, never()).get(any());
    } finally {
      worker.close();
    }
  }
}
