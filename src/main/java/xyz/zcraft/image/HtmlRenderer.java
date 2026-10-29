package xyz.zcraft.image;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Thread-confined browser workers. Call screenshot only from executor(), or use render(). */
public final class HtmlRenderer implements AutoCloseable {
    private final ThreadLocal<Boolean> owned = new ThreadLocal<>();
    private final ThreadLocal<Playwright> playwright = new ThreadLocal<>();
    private final ThreadLocal<BrowserContext> contexts = new ThreadLocal<>();
    private final ThreadPoolExecutor executor;
    private final Consumer<BrowserContext> setup;
    private final double timeoutMillis;

    public HtmlRenderer(int workers, int queueCapacity, double timeoutMillis, Consumer<BrowserContext> setup) {
        if (workers < 1 || queueCapacity < 1 || !Double.isFinite(timeoutMillis) || timeoutMillis <= 0) {
            throw new IllegalArgumentException("Positive workers, queue capacity and timeout required");
        }
        this.setup = java.util.Objects.requireNonNull(setup);
        this.timeoutMillis = timeoutMillis;
        executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity), task -> Thread.ofPlatform().name("image-renderer").unstarted(() -> {
                try {
                    owned.set(true);
                    task.run();
                } finally {
                    Playwright instance = playwright.get();
                    if (instance != null) {
                        instance.close();
                    }
                    playwright.remove();
                    contexts.remove();
                    owned.remove();
                }
            }), new ThreadPoolExecutor.AbortPolicy());
    }

    public ExecutorService executor() {
        return executor;
    }

    public CompletableFuture<byte[]> render(String html) {
        return CompletableFuture.supplyAsync(() -> screenshot(html), executor);
    }

    public byte[] screenshot(String html) {
        // All browser operations, including initialization and shutdown, stay on their worker.
        if (!Boolean.TRUE.equals(owned.get())) {
            throw new IllegalStateException("Use the renderer executor");
        }
        BrowserContext context = contexts.get();
        if (context == null) {
            Playwright instance = Playwright.create();
            try {
                context = instance.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)).newContext();
                context.setDefaultTimeout(timeoutMillis);
                setup.accept(context);
                playwright.set(instance);
                contexts.set(context);
            } catch (RuntimeException e) {
                instance.close();
                throw e;
            }
        }
        try (Page page = context.newPage()) {
            page.setContent(html);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForFunction("() => document.fonts.status === 'loaded' && Array.from(document.images).every(i => i.complete)");
            return page.locator("body").screenshot();
        }
    }

    @Override
    public void close() {
        executor.close();
    }
}
