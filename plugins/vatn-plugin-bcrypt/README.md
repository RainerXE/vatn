# vatn-plugin-bcrypt
![Stability](https://img.shields.io/badge/stability-preview-yellow)

Provides BCrypt password hashing and verification as a VATN service.

## How it works

Wraps the jBCrypt library (at.favre.lib:bcrypt). The cost factor controls the work factor exponentially; the default of 12 produces a hash in roughly 250 ms on modern hardware, which is appropriate for interactive login flows. Each `hash` call internally generates a fresh random salt, so two calls with the same plaintext always produce different 60-character output strings. The BCrypt string encodes the cost and salt alongside the hash, so `verify` needs no extra state.

## Setup

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-bcrypt</artifactId>
    <version>1.0-alpha.14</version>
</dependency>
```

```java
// Default cost factor (12)
VNodeRunner.create()
    .addPlugin(new BcryptPlugin())
    .run();

// Custom cost factor
VNodeRunner.create()
    .addPlugin(new BcryptPlugin(10))
    .run();
```

## API

```java
public interface BcryptService {
    String  hash(String plaintext);
    boolean verify(String plaintext, String hash);
}
```

```java
BcryptService bcrypt = ctx.service(BcryptService.class);

// Hash a password at registration time
String stored = bcrypt.hash(rawPassword);
// → "$2a$12$<22-char salt><31-char hash>"

// Verify at login time
boolean ok = bcrypt.verify(rawPassword, stored);
```

## Configuration

| Option       | Default | Meaning                                                          |
|--------------|---------|------------------------------------------------------------------|
| `costFactor` | `12`    | BCrypt work factor (4–31); each increment roughly doubles hash time |

## Notes

- Cost 12 ≈ 250 ms; cost 10 ≈ 60 ms. Choose based on your latency budget.
- `hash` is intentionally slow — do not call it on a hot request path without offloading to a virtual thread.
- The 60-character output is the complete, self-contained BCrypt string; store it as-is.
- `verify` is timing-safe; it always runs the full BCrypt computation regardless of early mismatches.
