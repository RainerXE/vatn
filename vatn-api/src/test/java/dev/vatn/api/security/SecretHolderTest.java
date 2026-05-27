package dev.vatn.api.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SecretHolderTest {

    @Test
    void testInitializationAndReveal() {
        try (SecretHolder holder = new SecretHolder("my-token")) {
            assertEquals("my-token", holder.reveal());
            assertFalse(holder.isZeroized());
            
            holder.zeroize();
            assertTrue(holder.isZeroized());
            Exception e = assertThrows(IllegalStateException.class, holder::reveal);
            assertNotNull(e);
        }
    }

    @Test
    void testUseFunction() {
        try (SecretHolder holder = new SecretHolder("xyz")) {
            int length = holder.use(chars -> chars.length);
            assertEquals(3, length);
            
            String internal = holder.use(String::new);
            assertEquals("xyz", internal);
        }
    }

    @Test
    void testZeroizeActuallyWipesMemory() {
        char[] capturedBytes;
        try (SecretHolder holder = new SecretHolder("sensitive-data")) {
            // We capture the reference to the internal char array
            capturedBytes = holder.use(chars -> chars);
            
            // Verify it has data
            assertNotEquals((char) 0, capturedBytes[0]);
            
            holder.zeroize();
            
            // Verify it is now all zeros
            for (char c : capturedBytes) {
                assertEquals((char) 0, c);
            }
        }
    }

    @Test
    void testAutoCloseable() {
        try (SecretHolder holder = new SecretHolder("ephemeral")) {
            assertEquals("ephemeral", holder.reveal());
        }
        // Should be automatically zeroized here
        // We can't easily check as the object is out of scope, but we can check if we keep a ref
    }
    
    @Test
    void testEmptyInitialization() {
        try (SecretHolder holder = new SecretHolder((String) null)) {
            assertTrue(holder.reveal().isEmpty());
        }
    }
}
