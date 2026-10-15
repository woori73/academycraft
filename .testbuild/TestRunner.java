import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal JUnit Platform runner (junit-platform-launcher is not in the offline
 * dependency cache, so the engine is driven directly).
 * Usage: java TestRunner com.foo.BarTest [com.foo.BazTest ...]
 */
public class TestRunner {

    static class Params implements ConfigurationParameters {
        public Optional<String> get(String key) { return Optional.empty(); }
        public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }
        public int size() { return 0; }
        public Set<String> keySet() { return Set.of(); }
    }

    static class Discovery implements EngineDiscoveryRequest {
        private final List<DiscoverySelector> selectors;
        private final ConfigurationParameters params = new Params();
        Discovery(List<DiscoverySelector> selectors) { this.selectors = selectors; }
        @SuppressWarnings("unchecked")
        public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> selectorType) {
            List<T> out = new ArrayList<>();
            for (DiscoverySelector s : selectors) {
                if (selectorType.isInstance(s)) out.add((T) s);
            }
            return out;
        }
        public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> filterType) {
            return List.of();
        }
        public ConfigurationParameters getConfigurationParameters() { return params; }
    }

    public static void main(String[] args) throws Exception {
        List<DiscoverySelector> selectors = new ArrayList<>();
        for (String name : args) {
            selectors.add(DiscoverySelectors.selectClass(Class.forName(name)));
        }
        JupiterTestEngine engine = new JupiterTestEngine();
        Discovery request = new Discovery(selectors);
        TestDescriptor root = engine.discover(request, UniqueId.forEngine(engine.getId()));

        AtomicInteger started = new AtomicInteger();
        AtomicInteger passed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        List<String> failures = new ArrayList<>();

        EngineExecutionListener listener = new EngineExecutionListener() {
            public void executionStarted(TestDescriptor d) {
                if (d.isTest()) started.incrementAndGet();
            }
            public void executionSkipped(TestDescriptor d, String reason) {
                if (d.isTest()) {
                    skipped.incrementAndGet();
                    System.out.println("[SKIP] " + d.getDisplayName() + " - " + reason);
                }
            }
            public void executionFinished(TestDescriptor d, TestExecutionResult r) {
                if (!d.isTest()) return;
                String name = d.getDisplayName();
                switch (r.getStatus()) {
                    case SUCCESSFUL -> {
                        passed.incrementAndGet();
                        System.out.println("[PASS] " + name);
                    }
                    case FAILED -> {
                        failed.incrementAndGet();
                        String msg = r.getThrowable().map(t -> t.toString()).orElse("unknown");
                        failures.add(name + " -> " + msg);
                        System.out.println("[FAIL] " + name + " -> " + msg);
                        r.getThrowable().ifPresent(Throwable::printStackTrace);
                    }
                    case ABORTED -> {
                        skipped.incrementAndGet();
                        System.out.println("[ABORT] " + name);
                    }
                }
            }
        };

        engine.execute(new ExecutionRequest(root, listener, request.getConfigurationParameters()));

        System.out.println("==== SUMMARY: started=" + started + " passed=" + passed
                + " failed=" + failed + " skipped=" + skipped + " ====");
        if (!failures.isEmpty()) {
            System.out.println("FAILURES:");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }
}
