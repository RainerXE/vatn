package dev.vatn.api;

/**
 * Service for distributed resource locking across VATN nodes.
 */
@VatnApi(since = "1.0")
public interface VResourceLockService extends VService {
    
    /**
     * Tries to acquire a global lock on a resource.
     * @param resourceId The unique identifier of the resource.
     * @param timeoutSeconds Duration after which the lock expires automatically.
     * @return true if acquired, false otherwise.
     */
    boolean tryLock(String resourceId, long timeoutSeconds);

    /**
     * Releases a lock previously acquired by this service instance.
     */
    void unlock(String resourceId);
}
