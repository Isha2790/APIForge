package dev.apiforge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the real Lua scripts against a real Redis (skipped automatically when Docker is unavailable). */
@Testcontainers(disabledWithoutDocker = true)
class RedisScriptsTest {
    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static StringRedisTemplate template;

    @BeforeAll
    static void connect() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> listScript(String file) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(file));
        script.setResultType(List.class);
        return script;
    }

    @Test
    @SuppressWarnings("rawtypes")
    void concurrentCallersNeverExceedTokenBucketCapacity() throws Exception {
        DefaultRedisScript<List> script = listScript("rate_limit.lua");
        AtomicInteger allowed = new AtomicInteger();
        List<Callable<Void>> calls = IntStream.range(0, 500).<Callable<Void>>mapToObj(i -> () -> {
            List<?> result = template.execute(script, List.of("rate:test"), "100");
            if (((Number) result.get(0)).intValue() == 1) allowed.incrementAndGet();
            return null;
        }).toList();

        var pool = Executors.newFixedThreadPool(32);
        pool.invokeAll(calls);
        pool.shutdown();

        // capacity is 100; allow a few tokens of continuous refill while the burst runs
        assertThat(allowed.get()).isBetween(100, 106);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void quotaIsEnforcedAtomicallyAndRefusalsDoNotConsume() {
        DefaultRedisScript<List> script = listScript("quota.lua");
        List<String> keys = List.of("q:d:test", "q:m:test");
        int ok = 0;
        for (int i = 0; i < 5; i++) {
            List<?> r = template.execute(script, keys, "3", "0", "86400", "86400");
            if (((Number) r.get(0)).intValue() == 0) ok++;
        }
        List<?> refused = template.execute(script, keys, "3", "0", "86400", "86400");
        assertThat(ok).isEqualTo(3);
        assertThat(((Number) refused.get(0)).intValue()).isEqualTo(1);   // daily exceeded
        assertThat(template.opsForValue().get("q:d:test")).isEqualTo("3");   // refusals did not increment

        List<?> monthly = template.execute(script, List.of("q:d:m", "q:m:m"), "0", "1", "86400", "86400");
        List<?> monthlyAgain = template.execute(script, List.of("q:d:m", "q:m:m"), "0", "1", "86400", "86400");
        assertThat(((Number) monthly.get(0)).intValue()).isEqualTo(0);
        assertThat(((Number) monthlyAgain.get(0)).intValue()).isEqualTo(2);   // monthly exceeded
    }

    @Test
    void usageStreamIsCappedAndFieldsArePreserved() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("usage_xadd.lua"));
        script.setResultType(Long.class);
        for (int i = 0; i < 300; i++) {
            template.execute(script, List.of("usage:test"), "100", "org", "o", "api", "a", "status", Integer.toString(i));
        }
        Long length = template.opsForStream().size("usage:test");
        assertThat(length).isNotNull().isLessThan(300);   // approximate trimming keeps it bounded
        var entries = template.opsForStream().range("usage:test", org.springframework.data.domain.Range.unbounded());
        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0).getValue()).containsEntry("org", "o");
    }
}
