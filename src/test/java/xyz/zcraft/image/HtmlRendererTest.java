package xyz.zcraft.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlRendererTest {
    @Test
    void rejectsWrongThread() {
        try (var renderer = new HtmlRenderer(1, 1, 1000, c -> {
        })) {
            assertThrows(IllegalStateException.class, () -> renderer.screenshot("<body>test</body>"));
        }
    }

    @Test
    void rendersAndRecoversAfterFailure() throws Exception {
        try (var renderer = new HtmlRenderer(1, 4, 5000, c -> {
        })) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                throw new IllegalArgumentException();
            }, renderer.executor()).get());
            byte[] png = renderer.render("<body style='width:100px;height:40px'>Hello</body>").get();
            assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71}, java.util.Arrays.copyOf(png, 4));
            assertTrue(renderer.render("<body>Again</body>").get().length > 8);
        }
    }
}
