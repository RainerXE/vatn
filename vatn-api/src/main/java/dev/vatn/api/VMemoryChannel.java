package dev.vatn.api;

/**
 * Interface for the VATN Memory Channel architecture.
 * Designed to leverage Project Panama (Foreign Function & Memory API) for 0-copy data passing.
 */
@VatnApi(since = "2.0")
public interface VMemoryChannel extends VService {
    
    /**
     * Allocates a segment of managed memory.
     * @param bytes Number of bytes to allocate.
     * @return Address handle for the memory region.
     */
    long allocate(long bytes);
    
    /**
     * Frees a previously allocated segment.
     */
    void free(long address);
    
    /**
     * Reads a byte array from the specified memory region.
     */
    byte[] read(long address, int length);
    
    /**
     * Writes a byte array to the specified memory region.
     */
    void write(long address, byte[] data);
}
